package io.jenkins.plugins.buildui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import hudson.model.Item;
import hudson.model.ParametersAction;
import hudson.model.Result;
import hudson.model.StringParameterValue;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.StreamSupport;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.Page;
import org.htmlunit.WebRequest;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class GitRefsProviderTest {

    @TempDir
    static Path repo;

    @BeforeAll
    static void makeRepository() throws Exception {
        // The Git plugin refuses file: URLs unless told otherwise (SECURITY-2478); only this test needs one.
        System.setProperty("hudson.plugins.git.GitSCM.ALLOW_LOCAL_CHECKOUT", "true");
        git("init", "-q", "-b", "main");
        Files.writeString(repo.resolve("README"), "x");
        git("add", "README");
        git("-c", "user.name=t", "-c", "user.email=t@example.invalid", "commit", "-q", "-m", "first");
        git("branch", "feature/login");
        git("branch", "release/2026.10");
        for (String tag : List.of("v1.0", "v1.9.0", "v1.10.0", "v1.10.0-rc1", "nightly")) {
            git("tag", tag);
        }
        git("tag", "-a", "-m", "release", "v1.1");
    }

    private static void git(String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("git", "-C", repo.toString()));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).inheritIO().start();
        assertEquals(0, p.waitFor(), String.join(" ", cmd));
    }

    private static WorkflowJob job(JenkinsRule j, String name, String parameters) throws Exception {
        WorkflowJob job = j.createProject(WorkflowJob.class, name);
        job.setDefinition(new CpsFlowDefinition(parameters
                + "node { git url: '" + repo.toUri() + "', branch: 'main' }\n"
                + "echo \"CFG=${params.CFG}\"", true));
        j.buildAndAssertSuccess(job);
        return job;
    }

    private static List<String> names(JsonNode refs) {
        return StreamSupport.stream(refs.spliterator(), false).map(r -> r.get("name").asText()).toList();
    }

    private static JsonNode query(JenkinsRule.WebClient wc, String args) throws Exception {
        Page page = wc.goTo("build-ui/query/gitRefs?args=" + URLEncoder.encode(args, StandardCharsets.UTF_8), "application/json");
        return JsonMapper.builder().build().readTree(page.getWebResponse().getContentAsString());
    }

    @Test
    void listsBranchesAndTagsWithoutPeeledEntries(JenkinsRule j) throws Exception {
        job(j, "app", "");
        JsonNode refs = query(j.createWebClient(), "{\"job\":\"app\"}");
        assertEquals(List.of("feature/login", "main", "release/2026.10"), names(refs.get("branches")));
        assertEquals(List.of("nightly", "v1.0", "v1.1", "v1.10.0", "v1.10.0-rc1", "v1.9.0"), names(refs.get("tags")));
    }

    @Test
    void filtersWithGlobsAndSortsNewestVersionFirstLikeGitParameter(JenkinsRule j) throws Exception {
        job(j, "app", "");
        JsonNode refs = query(j.createWebClient(),
                "{\"job\":\"app\",\"branches\":\"release/*\",\"tags\":\"v1.*\",\"sort\":\"version-desc\",\"limit\":3}");
        assertEquals(List.of("release/2026.10"), names(refs.get("branches")));
        // A release sorts above its release candidate, and 1.10 above 1.9.
        assertEquals(List.of("v1.10.0", "v1.10.0-rc1", "v1.9.0"), names(refs.get("tags")));
    }

    @Test
    void comparesVersionsTheWayPeopleReadThem() {
        assertTrue(GitRefsProvider.compareVersions("v1.10.0", "v1.9.0") > 0);
        assertTrue(GitRefsProvider.compareVersions("1.0.0", "1.0.0-rc1") > 0);
        assertTrue(GitRefsProvider.compareVersions("1.0.1", "1.0.0") > 0);
        assertTrue(GitRefsProvider.compareVersions("1.0.nightly-rc", "1.0.9") > 0);
        assertEquals(0, GitRefsProvider.compareVersions("v01.2", "v1.2"));
    }

    @Test
    void globFiltersAreQuotedSoRegexCharactersMatchLiterally() {
        assertTrue(GitRefsProvider.glob("release/2026.10", "x").matcher("release/2026.10").matches());
        assertTrue(!GitRefsProvider.glob("release/2026.10", "x").matcher("release/2026x10").matches());
        assertTrue(GitRefsProvider.glob("v?.*", "x").matcher("v1.10.0").matches());
        assertThrows(IllegalArgumentException.class, () -> GitRefsProvider.glob("*".repeat(101), "x"));
    }

    @Test
    void refusesUsersWithoutBuildPermissionBecauseEachCallContactsTheRemote(JenkinsRule j) throws Exception {
        job(j, "app", "");
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.READ, Item.READ).everywhere().to("reader")
                .grant(Jenkins.READ, Item.READ, Item.BUILD).everywhere().to("builder"));

        JenkinsRule.WebClient reader = j.createWebClient().withThrowExceptionOnFailingStatusCode(false).login("reader");
        Page denied = reader.goTo("build-ui/query/gitRefs?args=" + URLEncoder.encode("{\"job\":\"app\"}", StandardCharsets.UTF_8), null);
        assertEquals(403, denied.getWebResponse().getStatusCode());
        assertTrue(names(query(j.createWebClient().login("builder"), "{\"job\":\"app\"}").get("branches")).contains("main"));
    }

    @Test
    void explainsWhenTheJobHasNotCheckedOutAnythingYet(JenkinsRule j) throws Exception {
        j.createProject(WorkflowJob.class, "fresh");
        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false);
        Page page = wc.goTo("build-ui/query/gitRefs?args=" + URLEncoder.encode("{\"job\":\"fresh\"}", StandardCharsets.UTF_8), "application/json");
        assertEquals(400, page.getWebResponse().getStatusCode());
        assertTrue(page.getWebResponse().getContentAsString().contains("uses no Git repository yet"));
    }

    static final String TAG_PARAMETER = "properties([parameters([uiParameter(name: 'CFG', module: 'export default () => {}', "
            + "defaultJson: '{\"tag\":\"v1.0\"}', "
            + "schema: '{\"type\":\"object\",\"properties\":{\"tag\":{\"type\":\"string\",\"x-git-ref\":\"tag\"}}}')])])\n";

    @Test
    void xGitRefAcceptsARealTagAndRejectsAMissingOneOnSubmit(JenkinsRule j) throws Exception {
        WorkflowJob job = job(j, "release", TAG_PARAMETER);
        assertEquals(201, buildWithParameters(j, "{\"tag\":\"v1.10.0\"}").getWebResponse().getStatusCode());
        j.waitUntilNoActivity();
        j.assertLogContains("CFG={\"tag\":\"v1.10.0\"}", job.getLastBuild());

        Page bad = buildWithParameters(j, "{\"tag\":\"v9.9.9\"}");
        assertEquals(400, bad.getWebResponse().getStatusCode());
        assertTrue(bad.getWebResponse().getContentAsString().contains("/tag: 'v9.9.9' is not a tag of release's repository"),
                bad.getWebResponse().getContentAsString());
        // A branch name is not a tag.
        assertEquals(400, buildWithParameters(j, "{\"tag\":\"main\"}").getWebResponse().getStatusCode());
    }

    @Test
    void xGitRefIsCheckedForBuildsThatDoNotComeFromAForm(JenkinsRule j) throws Exception {
        WorkflowJob job = job(j, "release", TAG_PARAMETER);
        assertNull(job.scheduleBuild2(0, new ParametersAction(new StringParameterValue("CFG", "{\"tag\":\"v9.9.9\"}"))));
        assertNotNull(job.scheduleBuild2(0, new ParametersAction(new StringParameterValue("CFG", "{\"tag\":\"v1.1\"}"))));

        WorkflowJob up = j.createProject(WorkflowJob.class, "up");
        up.setDefinition(new CpsFlowDefinition("build job: 'release', parameters: [string(name: 'CFG', value: '{\"tag\":\"v9.9.9\"}')]", true));
        j.buildAndAssertStatus(Result.FAILURE, up);
    }

    @Test
    void anUnknownXGitRefKindFailsWhenTheJobIsConfigured() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> new UiParameterDefinition("CFG")
                .setSchema("{\"type\":\"object\",\"properties\":{\"tag\":{\"type\":\"string\",\"x-git-ref\":\"tga\"}}}"));
        assertTrue(e.getMessage().contains("x-git-ref must be true"), e.getMessage());
    }

    private static Page buildWithParameters(JenkinsRule j, String value) throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false).withRedirectEnabled(false);
        WebRequest req = new WebRequest(URI.create(j.getURL() + "job/release/buildWithParameters?CFG="
                + URLEncoder.encode(value, StandardCharsets.UTF_8)).toURL(), HttpMethod.POST);
        return wc.getPage(wc.addCrumb(req));
    }
}
