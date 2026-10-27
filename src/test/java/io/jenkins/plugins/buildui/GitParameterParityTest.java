package io.jenkins.plugins.buildui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import hudson.model.ParametersDefinitionProperty;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;
import net.uaznia.lukanus.hudson.plugins.gitparameter.GitParameterDefinition;
import org.htmlunit.HttpMethod;
import org.htmlunit.Page;
import org.htmlunit.WebRequest;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * The original design request (the RFC in docs/design.md): "do not replace
 * [Git Parameter] until parity is demonstrated".
 * Each test sets up Git Parameter and gitRefs with equivalent settings on one
 * job and compares what each offers, entry by entry and in order. Where they
 * differ on purpose the test asserts the difference, so a change on either
 * side shows up here. Git Parameter's lists come from
 * {@code getAllValueItems()}, which calls the descriptor method its select box
 * calls on the form.
 */
@WithJenkins
class GitParameterParityTest {

    @TempDir
    static Path app;

    @TempDir
    static Path tools;

    @BeforeAll
    static void makeRepositories() throws Exception {
        System.setProperty("hudson.plugins.git.GitSCM.ALLOW_LOCAL_CHECKOUT", "true");
        commit(app);
        git(app, "branch", "feature/login");
        git(app, "branch", "release/2026.9");
        git(app, "branch", "release/2026.10");
        for (String tag : List.of("v1.0", "v1.9.0", "v1.10.0", "v1.10.0-rc1", "v2.0", "nightly", "old/v0.9")) {
            git(app, "tag", tag);
        }
        git(app, "tag", "-a", "-m", "release", "v1.1");
        commit(tools);
        git(tools, "branch", "hotfix/1");
        git(tools, "tag", "tools-2.0");
    }

    private static void commit(Path dir) throws Exception {
        git(dir, "init", "-q", "-b", "main");
        Files.writeString(dir.resolve("README"), dir.getFileName().toString());
        git(dir, "add", "README");
        git(dir, "-c", "user.name=t", "-c", "user.email=t@example.invalid", "commit", "-q", "-m", "first");
    }

    private static void git(Path dir, String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("git", "-C", dir.toString()));
        cmd.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(cmd).inheritIO();
        // CI runners have no global git identity; commits and annotated tags need one.
        pb.environment().putAll(Map.of("GIT_AUTHOR_NAME", "test", "GIT_AUTHOR_EMAIL", "test@example.invalid",
                "GIT_COMMITTER_NAME", "test", "GIT_COMMITTER_EMAIL", "test@example.invalid"));
        Process p = pb.start();
        assertEquals(0, p.waitFor(), String.join(" ", cmd));
    }

    /** The tools repository's file: URI ends in its directory name and '/'. */
    private static String toolsGlob() {
        return "*/" + tools.getFileName() + "/";
    }

    /**
     * One job that checks out both repositories, app first, with a Git
     * Parameter for each comparison and a uiParameter whose tag must exist in
     * the tools repository.
     */
    private static WorkflowJob job(JenkinsRule j) throws Exception {
        WorkflowJob job = j.createProject(WorkflowJob.class, "app");
        job.setDefinition(new CpsFlowDefinition("properties([parameters([\n"
                + "  gitParameter(name: 'TAG', type: 'PT_TAG', tagFilter: 'v*', sortMode: 'DESCENDING_SMART', listSize: '2'),\n"
                + "  gitParameter(name: 'ALLTAGS', type: 'PT_TAG', sortMode: 'ASCENDING'),\n"
                + "  gitParameter(name: 'REL', type: 'PT_BRANCH', branchFilter: 'origin/(release/.*)', sortMode: 'ASCENDING'),\n"
                + "  gitParameter(name: 'BR', type: 'PT_BRANCH', sortMode: 'ASCENDING'),\n"
                + "  gitParameter(name: 'TOOLS', type: 'PT_TAG', useRepository: '" + tools.getFileName() + "', sortMode: 'ASCENDING'),\n"
                + "  uiParameter(name: 'CFG', module: 'export default () => {}', defaultJson: '{\"tag\":\"tools-2.0\"}',\n"
                + "    schema: '{\"type\":\"object\",\"properties\":{\"tag\":{\"type\":\"string\",\"x-git-ref\":\"tag\",\"x-git-repository\":\""
                + toolsGlob() + "\"}}}')\n"
                + "])])\n"
                + "node { dir('app') { git url: '" + app.toUri() + "', branch: 'main' }\n"
                + "  dir('tools') { git url: '" + tools.toUri() + "', branch: 'main' } }\n"
                + "echo \"TAG=${params.TAG} TOOLS=${params.TOOLS} CFG=${params.CFG}\"", true));
        j.buildAndAssertSuccess(job);
        return job;
    }

    /** What Git Parameter offers for one of its parameters on this job's form. */
    private static List<String> offered(WorkflowJob job, String name) {
        GitParameterDefinition d = (GitParameterDefinition) job.getProperty(ParametersDefinitionProperty.class).getParameterDefinition(name);
        return d.getAllValueItems().values.stream().map(o -> o.value).toList();
    }

    private static JsonNode gitRefs(JenkinsRule j, String args) throws Exception {
        Page page = j.createWebClient().goTo("build-ui/query/gitRefs?args=" + URLEncoder.encode(args, StandardCharsets.UTF_8), "application/json");
        return JsonMapper.builder().build().readTree(page.getWebResponse().getContentAsString());
    }

    private static List<String> names(JsonNode refs) {
        return StreamSupport.stream(refs.spliterator(), false).map(r -> r.get("name").asText()).toList();
    }

    @Test
    void tagFilterAndDescendingSmartMatchTagsAndVersionDescWithTwoDeliberateDifferences(JenkinsRule j) throws Exception {
        WorkflowJob job = job(j);
        List<String> gitParameter = offered(job, "TAG");
        List<String> ours = names(gitRefs(j, "{\"job\":\"app\",\"tags\":\"v*\",\"sort\":\"version-desc\"}").get("tags"));

        // 1. Git Parameter hands tagFilter to 'git ls-remote', which matches the end of the ref path, so
        //    'v*' also takes old/v0.9. A gitRefs glob matches the whole tag name.
        // 2. DESCENDING_SMART reverses an order in which a longer name sorts after its prefix, so a release
        //    candidate comes before its release. version-desc puts the release first.
        assertEquals(List.of("v2.0", "v1.10.0-rc1", "v1.10.0", "v1.9.0", "v1.1", "v1.0", "old/v0.9"), gitParameter);
        assertEquals(List.of("v2.0", "v1.10.0", "v1.10.0-rc1", "v1.9.0", "v1.1", "v1.0"), ours);

        // Apart from those two, the lists are the same: same tags, same order, no peeled ^{} entries.
        List<String> aligned = new ArrayList<>(gitParameter);
        aligned.remove("old/v0.9");
        aligned.set(1, "v1.10.0");
        aligned.set(2, "v1.10.0-rc1");
        assertEquals(aligned, ours);
        // A pattern that names the folder brings the nested tag in on our side too.
        assertEquals(List.of("old/v0.9"), names(gitRefs(j, "{\"job\":\"app\",\"tags\":\"*/v*\"}").get("tags")));
    }

    @Test
    void listSizeIsOnlyTheHeightOfTheSelectBoxWhileLimitBoundsTheAnswer(JenkinsRule j) throws Exception {
        WorkflowJob job = job(j);
        // listSize: '2' still returns every tag; it sets how many rows the select shows.
        assertEquals(7, offered(job, "TAG").size());
        JsonNode page = gitRefs(j, "{\"job\":\"app\",\"tags\":\"v*\",\"sort\":\"version-desc\",\"limit\":2}");
        assertEquals(List.of("v2.0", "v1.10.0"), names(page.get("tags")));
        assertEquals(6, page.at("/total/tags").asInt());
        JsonNode next = gitRefs(j, "{\"job\":\"app\",\"tags\":\"v*\",\"sort\":\"version-desc\",\"limit\":2,\"offset\":2}");
        assertEquals(List.of("v1.10.0-rc1", "v1.9.0"), names(next.get("tags")));
    }

    @Test
    void branchFilterWithAGroupMatchesTheBranchesGlob(JenkinsRule j) throws Exception {
        WorkflowJob job = job(j);
        // Equivalent settings give the same list in the same order (ASCENDING and 'name' are plain string order).
        assertEquals(List.of("release/2026.10", "release/2026.9"), offered(job, "REL"));
        assertEquals(offered(job, "REL"), names(gitRefs(j, "{\"job\":\"app\",\"branches\":\"release/*\"}").get("branches")));

        // Deliberate differences: Git Parameter prefixes branches with the remote name unless the filter has
        // a group, and without useRepository it lists only the job's first repository.
        assertEquals(List.of("origin/feature/login", "origin/main", "origin/release/2026.10", "origin/release/2026.9"), offered(job, "BR"));
        assertEquals(List.of("feature/login", "hotfix/1", "main", "main", "release/2026.10", "release/2026.9"),
                names(gitRefs(j, "{\"job\":\"app\"}").get("branches")));
    }

    @Test
    void useRepositoryMatchesTheRepositoryArgument(JenkinsRule j) throws Exception {
        WorkflowJob job = job(j);
        // useRepository is a regex found in the remote URL; repository is a glob of the whole URL.
        assertEquals(List.of("tools-2.0"), offered(job, "TOOLS"));
        assertEquals(offered(job, "TOOLS"), names(gitRefs(j, "{\"job\":\"app\",\"repository\":\"" + toolsGlob() + "\"}").get("tags")));
        // Without it Git Parameter reads the first repository only; gitRefs reads all of them.
        assertTrue(!offered(job, "ALLTAGS").contains("tools-2.0"));
        assertTrue(names(gitRefs(j, "{\"job\":\"app\"}").get("tags")).contains("tools-2.0"));
    }

    @Test
    void bothRefuseARefTheRepositoryDoesNotHaveWhenTheBuildIsSubmitted(JenkinsRule j) throws Exception {
        WorkflowJob job = job(j);
        // TOOLS (Git Parameter, useRepository) and CFG.tag (x-git-ref with x-git-repository) guard the same thing.
        assertEquals(201, submit(j, "TOOLS=tools-2.0&CFG=" + cfg("tools-2.0")));
        j.waitUntilNoActivity();
        j.assertLogContains("TOOLS=tools-2.0 CFG={\"tag\":\"tools-2.0\"}", job.getLastBuild());

        // v1.0 exists, but in the app repository, not in tools: both refuse it.
        assertEquals(400, submit(j, "TOOLS=v1.0&CFG=" + cfg("tools-2.0")));
        assertEquals(400, submit(j, "TOOLS=tools-2.0&CFG=" + cfg("v1.0")));
        // A ref that exists nowhere.
        assertEquals(400, submit(j, "TAG=v9.9.9&CFG=" + cfg("tools-2.0")));
        assertEquals(400, submit(j, "TOOLS=tools-2.0&CFG=" + cfg("v9.9.9")));
        assertEquals(2, job.getLastBuild().getNumber(), "no refused submission may start a build");
    }

    private static String cfg(String tag) {
        return URLEncoder.encode("{\"tag\":\"" + tag + "\"}", StandardCharsets.UTF_8);
    }

    private static int submit(JenkinsRule j, String query) throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false).withRedirectEnabled(false);
        WebRequest req = new WebRequest(URI.create(j.getURL() + "job/app/buildWithParameters?" + query).toURL(), HttpMethod.POST);
        return wc.getPage(wc.addCrumb(req)).getWebResponse().getStatusCode();
    }
}
