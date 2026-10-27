package io.jenkins.plugins.buildui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudbees.hudson.plugins.folder.Folder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import hudson.model.Items;
import hudson.plugins.git.BranchSpec;
import hudson.plugins.git.GitSCM;
import java.io.IOException;
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
import org.htmlunit.html.HtmlForm;
import org.htmlunit.html.HtmlPage;
import org.htmlunit.html.HtmlSelect;
import org.htmlunit.html.HtmlTextArea;
import org.jenkinsci.plugins.scriptsecurity.scripts.ScriptApproval;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.cps.CpsScmFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * From the original design request (the RFC in docs/design.md): build-ui has
 * to live next to the parameter plugins people already use, and keep working
 * when a job is renamed or moved.
 */
@WithJenkins
class IntegrationTest {

    @TempDir
    Path first;

    @TempDir
    Path second;

    private JenkinsRule j;

    @BeforeAll
    static void allowLocalRepositories() {
        System.setProperty("hudson.plugins.git.GitSCM.ALLOW_LOCAL_CHECKOUT", "true");
    }

    @BeforeEach
    void setUp(JenkinsRule j) {
        this.j = j;
        FormFrom.clearCache();
        ContentStore.clear();
        FormApproval.forget();
    }

    private static void git(Path dir, String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("git", "-C", dir.toString(), "-c", "user.name=t", "-c", "user.email=t@example.invalid"));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).inheritIO().start();
        assertEquals(0, p.waitFor(), String.join(" ", cmd));
    }

    private static void write(Path dir, String path, String text) throws IOException {
        Path file = dir.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }

    // ---- one form, three parameter plugins ----------------------------------

    static final String MIXED = """
            properties([parameters([
              uiParameter(name: 'CFG', runtime: 'none', defaultJson: '{"branch":"main"}',
                module: 'export default function mount({ root }) { root.textContent = "build-ui" }',
                schema: '{"type":"object","required":["branch"],"properties":{"branch":{"type":"string","x-git-ref":"branch"}}}'),
              gitParameter(name: 'TAG', type: 'PT_TAG', defaultValue: 'v1.0', sortMode: 'ASCENDING_SMART', selectedValue: 'DEFAULT'),
              activeChoice(name: 'COLOR', choiceType: 'PT_SINGLE_SELECT', filterable: false,
                script: groovyScript(script: [script: "return ['red', 'green:selected']", sandbox: true],
                                     fallbackScript: [script: "return ['fallback']", sandbox: true]))
            ])])
            node { git url: '%s', branch: 'main' }
            echo "CFG=${params.CFG} TAG=${params.TAG} COLOR=${params.COLOR}"
            """;

    private WorkflowJob mixedJob() throws Exception {
        git(first, "init", "-q", "-b", "main");
        write(first, "README", "x");
        git(first, "add", "-A");
        git(first, "commit", "-q", "-m", "first");
        git(first, "branch", "feature/x");
        git(first, "tag", "v1.0");
        git(first, "tag", "v1.1");
        WorkflowJob job = j.createProject(WorkflowJob.class, "mixed");
        // The Active Choices scripts run in the Groovy sandbox, so nothing needs an approval.
        job.setDefinition(new CpsFlowDefinition(MIXED.formatted(first.toUri()), true));
        j.buildAndAssertSuccess(job);
        return job;
    }

    @Test
    void aUiParameterNextToGitParameterAndActiveChoicesSubmitsAllThreeFromTheForm() throws Exception {
        WorkflowJob job = mixedJob();
        JenkinsRule.WebClient wc = j.createWebClient();
        // Jenkins's page scripts run, so the form submits through the same buildFormTree JSON a browser sends.
        // The test writes the uiParameter's JSON field, which is what that form submits; the browser suite
        // covers the module side. Script errors do not fail the page, because HtmlUnit is not that browser.
        wc.getOptions().setThrowExceptionOnScriptError(false);
        wc.getOptions().setThrowExceptionOnFailingStatusCode(false);
        HtmlPage page = wc.goTo("job/mixed/build?delay=0sec");
        wc.waitForBackgroundJavaScript(10_000);
        HtmlForm form = page.getFormByName("parameters");

        HtmlTextArea cfg = form.getFirstByXPath(".//div[@name='parameter' and input[@value='CFG']]//textarea[@name='value']");
        assertNotNull(cfg, "the uiParameter's JSON field");
        cfg.setText("{\"branch\":\"feature/x\"}");
        HtmlSelect tag = form.getFirstByXPath(".//div[@name='parameter' and input[@value='TAG']]//select");
        assertNotNull(tag, "Git Parameter's select");
        assertEquals(List.of("v1.0", "v1.1"), tag.getOptions().stream().map(o -> o.getValueAttribute()).filter(v -> !v.isEmpty()).toList(),
                "Git Parameter filled its select on the same page");
        tag.setSelectedAttribute("v1.1", true);
        HtmlSelect color = form.getFirstByXPath(".//div[@name='parameter' and input[@value='COLOR']]//select");
        assertNotNull(color, "Active Choices' select");
        color.setSelectedAttribute("red", true);

        j.submit(form);
        j.waitUntilNoActivity();
        assertEquals(2, job.getLastBuild().getNumber());
        j.assertLogContains("CFG={\"branch\":\"feature/x\"} TAG=v1.1 COLOR=red", job.getLastBuild());
    }

    @Test
    void theSameThreeValuesReachTheBuildThroughBuildWithParameters() throws Exception {
        WorkflowJob job = mixedJob();
        assertEquals(201, buildWithParameters("job/mixed/", "CFG=" + enc("{\"branch\":\"feature/x\"}") + "&TAG=v1.1&COLOR=red"));
        j.waitUntilNoActivity();
        j.assertLogContains("CFG={\"branch\":\"feature/x\"} TAG=v1.1 COLOR=red", job.getLastBuild());
        // The uiParameter and the Git Parameter still refuse their own bad values with the others present.
        assertEquals(400, buildWithParameters("job/mixed/", "CFG=" + enc("{\"branch\":\"nope\"}") + "&TAG=v1.1&COLOR=red"));
        assertEquals(400, buildWithParameters("job/mixed/", "CFG=" + enc("{\"branch\":\"main\"}") + "&TAG=v9.9&COLOR=red"));
        assertEquals(2, job.getLastBuild().getNumber());
    }

    private int buildWithParameters(String jobPath, String query) throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false).withRedirectEnabled(false);
        WebRequest req = new WebRequest(URI.create(j.getURL() + jobPath + "buildWithParameters?" + query).toURL(), HttpMethod.POST);
        return wc.getPage(wc.addCrumb(req)).getWebResponse().getStatusCode();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    // ---- renamed and moved jobs ---------------------------------------------

    static final String FROM_SCM = """
            properties([parameters([uiParameter(name: 'CFG', formFrom: 'forms/cfg'),
              uiParameter(name: 'REF', defaultJson: '{"branch":"main"}',
                schema: '{"type":"object","properties":{"branch":{"type":"string","x-git-ref":"branch"}}}')])])
            echo "CFG=${params.CFG} REF=${params.REF}"
            """;

    /** A repository with the Jenkinsfile above, a formFrom folder whose module says {@code text}, and one extra branch. */
    private static void repository(Path dir, String text, String branch) throws Exception {
        git(dir, "init", "-q", "-b", "main");
        write(dir, "Jenkinsfile", FROM_SCM);
        write(dir, "forms/cfg/form.mjs", module(text));
        git(dir, "add", "-A");
        git(dir, "commit", "-q", "-m", text);
        git(dir, "branch", branch);
    }

    static String module(String text) {
        return "export default function mount({ root }) { root.textContent = '" + text + "' }";
    }

    private static String moduleKey(String text) {
        return ContentStore.sha256(module(text).getBytes(StandardCharsets.UTF_8)) + ".mjs";
    }

    private WorkflowJob scmJob(String name, Path dir) throws Exception {
        WorkflowJob job = j.createProject(WorkflowJob.class, name);
        job.setDefinition(new CpsScmFlowDefinition(new GitSCM(GitSCM.createRepoList(dir.toUri().toString(), null),
                List.of(new BranchSpec("*/main")), null, null, List.of()), "Jenkinsfile"));
        j.buildAndAssertSuccess(job);
        return job;
    }

    private String formPage(String jobPath) throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient().login("admin");
        wc.getOptions().setJavaScriptEnabled(false);
        wc.getOptions().setThrowExceptionOnFailingStatusCode(false);
        return wc.goTo(jobPath + "build?delay=0sec").getWebResponse().getContentAsString();
    }

    private List<String> branches(String job) throws Exception {
        Page page = j.createWebClient().login("admin").goTo("build-ui/query/gitRefs?args="
                + enc("{\"job\":\"" + job + "\"}"), "application/json");
        JsonNode refs = JsonMapper.builder().build().readTree(page.getWebResponse().getContentAsString());
        return StreamSupport.stream(refs.get("branches").spliterator(), false).map(r -> r.get("name").asText()).toList();
    }

    private int content(String key) throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient().login("admin").withThrowExceptionOnFailingStatusCode(false);
        return wc.goTo("build-ui/" + key, null).getWebResponse().getStatusCode();
    }

    private void security() {
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy().grant(Jenkins.ADMINISTER).everywhere().to("admin"));
    }

    @Test
    void aRenamedJobKeepsItsFormAndANewJobUnderTheOldNameGetsItsOwn() throws Exception {
        security();
        repository(first, "first", "only-first");
        repository(second, "second", "only-second");
        WorkflowJob job = scmJob("app", first);
        // Built now, so that it can take the old name right after the rename, inside the cache windows.
        WorkflowJob other = scmJob("other", second);
        // Fill the gitRefs cache under the name "app", and warm the pages up: a cold first page took 7.6 s.
        assertTrue(branches("app").contains("only-first"));
        assertTrue(formPage("job/other/").contains("data-module=\"" + moduleKey("second") + "\""));
        // Fill the formFrom cache under "app" with the read the page makes, then reuse the name at once.
        long filled = System.nanoTime();
        assertEquals(module("first"), FormFrom.read(job, "forms/cfg").module());
        job.renameTo("renamed");
        other.renameTo("app");
        String reused = formPage("job/app/");
        long millis = (System.nanoTime() - filled) / 1_000_000;
        assertTrue(millis < FormFrom.CACHE_MILLIS, "took " + millis + " ms, past the formFrom cache window, so this proves nothing");
        // Failed before FormFrom.Renames: the cache is keyed by full name, so app showed renamed's form.
        assertTrue(reused.contains("data-module=\"" + moduleKey("second") + "\""), "the job now called app must show its own form, not the renamed one's");
        assertFalse(reused.contains(moduleKey("first")));
        assertEquals(List.of("main", "only-second"), branches("app"));
        assertEquals(400, buildWithParametersAs("job/app/", "REF=" + enc("{\"branch\":\"only-first\"}")),
                "x-git-ref must check the new job's repository");

        String renamed = formPage("job/renamed/");
        assertTrue(renamed.contains("data-module=\"" + moduleKey("first") + "\""), "the renamed job still shows its form");
        assertTrue(renamed.contains("data-job=\"renamed\""), "ui.job is the new name");
        assertEquals(200, content(moduleKey("first")), "content keys are hashes of the code, not of the job name");
        assertTrue(branches("renamed").contains("only-first"));
    }

    @Test
    void aJobMovedIntoAFolderKeepsItsFormRefsAndApproval() throws Exception {
        security();
        BuildUiConfiguration.get().setRequireApproval(true);
        repository(first, "first", "only-first");
        WorkflowJob job = scmJob("app", first);
        // Opening the form puts its code on the pending list; it waits there while the job moves.
        assertFalse(formPage("job/app/").contains("data-module="));
        List<ScriptApproval.PendingScript> waiting = ScriptApproval.get().getPendingScripts().stream()
                .filter(p -> p.getLanguage() instanceof FormApproval.FormLanguage).toList();
        assertEquals(1, waiting.size());
        assertTrue(branches("app").contains("only-first"));

        Folder folder = j.jenkins.createProject(Folder.class, "team");
        Items.move(job, folder);
        assertEquals("team/app", job.getFullName());
        assertFalse(formPage("job/team/job/app/").contains("data-module="), "moving a job approves nothing");

        ScriptApproval.get().approveScript(waiting.get(0).getHash());
        String page = formPage("job/team/job/app/");
        assertTrue(page.contains("data-module=\"" + moduleKey("first") + "\""), "the approval made before the move counts after it");
        assertTrue(page.contains("data-job=\"team/app\""));
        assertTrue(branches("team/app").contains("only-first"));
        assertEquals(201, buildWithParametersAs("job/team/job/app/", "REF=" + enc("{\"branch\":\"only-first\"}")));
        j.waitUntilNoActivity();
        j.assertLogContains("REF={\"branch\":\"only-first\"}", job.getLastBuild());

        // Nothing is left that answers under the old name.
        JenkinsRule.WebClient wc = j.createWebClient().login("admin").withThrowExceptionOnFailingStatusCode(false);
        assertEquals(400, wc.goTo("build-ui/query/gitRefs?args=" + enc("{\"job\":\"app\"}"), null).getWebResponse().getStatusCode());
    }

    private int buildWithParametersAs(String jobPath, String query) throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient().login("admin").withThrowExceptionOnFailingStatusCode(false).withRedirectEnabled(false);
        WebRequest req = new WebRequest(URI.create(j.getURL() + jobPath + "buildWithParameters?" + query).toURL(), HttpMethod.POST);
        return wc.getPage(wc.addCrumb(req)).getWebResponse().getStatusCode();
    }
}
