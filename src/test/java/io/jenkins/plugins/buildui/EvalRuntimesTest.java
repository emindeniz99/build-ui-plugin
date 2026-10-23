package io.jenkins.plugins.buildui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.Item;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.jenkinsci.plugins.scriptsecurity.scripts.ScriptApproval;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Runtimes that need eval (alpine3, vue3) work only while Jenkins sends CSP in
 * report-only mode, so they would break on the day an admin enforces CSP. An
 * admin has to allow them; until then their code never reaches a browser and
 * builds keep working through the JSON field. alpine3-csp needs no eval and so
 * no setting.
 */
@WithJenkins
class EvalRuntimesTest {

    static final String NOTICE = "needs eval, which an administrator has not allowed";

    private JenkinsRule j;
    private WorkflowJob job;

    @BeforeEach
    void setUp(JenkinsRule j) throws Exception {
        this.j = j;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, Item.BUILD).everywhere().to("dev"));
        job = j.createProject(WorkflowJob.class, "p");
        ContentStore.clear();
        FormApproval.forget();
    }

    private static final String MODULE = "export default function mount({ root }) { root.textContent = 'hi'; }";

    private void defineForm(String runtime) throws Exception {
        job.setDefinition(new CpsFlowDefinition("""
                properties([parameters([uiParameter(name: 'CFG', runtime: '%s', module: "%s")])])
                echo "cfg=${params.CFG}"
                """.formatted(runtime, MODULE), true));
        j.buildAndAssertSuccess(job);
    }

    private String page(String user, String path) throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient().login(user);
        wc.getOptions().setJavaScriptEnabled(false);
        wc.getOptions().setThrowExceptionOnFailingStatusCode(false);
        return wc.goTo(path).getWebResponse().getContentAsString();
    }

    private String formPage() throws Exception {
        // The form page answers 405 to a GET on purpose; its body is still the form.
        return page("dev", "job/p/build?delay=0sec");
    }

    private static String moduleKey() {
        return ContentStore.sha256(MODULE.getBytes(StandardCharsets.UTF_8)) + ".mjs";
    }

    @Test
    void theContractMarksExactlyTheRuntimesThatCompileTemplatesWithEval() {
        assertEquals(List.of("alpine3", "vue3"), Contract.EVAL_RUNTIMES.stream().sorted().toList());
        assertTrue(Contract.RUNTIMES.containsAll(Contract.EVAL_RUNTIMES), "an eval runtime must also be a runtime");
        assertFalse(Runtimes.needsEval(Runtimes.ALPINE_CSP), "the CSP build of Alpine is the eval-free one");
    }

    @Test
    void everyRuntimeShipsTheBundleTheHostLoads() {
        for (String id : Contract.RUNTIMES) {
            if (!id.equals(Runtimes.NONE)) {
                assertTrue(Files.isRegularFile(Path.of("src/main/webapp/runtimes", id + ".js")), id + " has no bundle");
            }
        }
    }

    @Test
    void evalRuntimesAreOffByDefaultAndTheirCodeNeverReachesABrowser() throws Exception {
        assertFalse(BuildUiConfiguration.get().isAllowEvalRuntimes(), "must be off unless an admin allows it");
        defineForm(Runtimes.VUE);

        String page = formPage();
        assertTrue(page.contains(NOTICE), "notice missing");
        assertFalse(page.contains("build-ui-root"), "nothing may mount a blocked runtime");
        assertFalse(page.contains("host.js"), "the host would load the runtime file");
        assertNull(ContentStore.get(moduleKey()), "blocked code must not be servable");

        // A build still works through the JSON field.
        JenkinsRule.WebClient wc = j.createWebClient().login("dev");
        WebRequest req = new WebRequest(URI.create(j.getURL()
                + "job/p/buildWithParameters?CFG=" + URLEncoder.encode("{\"a\":1}", StandardCharsets.UTF_8)).toURL(), HttpMethod.POST);
        assertEquals(201, wc.getPage(wc.addCrumb(req)).getWebResponse().getStatusCode());
        j.waitUntilNoActivity();
        j.assertLogContains("cfg={\"a\":1}", job.getLastBuild());
    }

    @Test
    void theCspBuildOfAlpineNeedsNoSetting() throws Exception {
        defineForm(Runtimes.ALPINE_CSP);
        String page = formPage();
        assertTrue(page.contains("data-runtime=\"alpine3-csp\""), "alpine3-csp must render");
        assertTrue(page.contains("data-eval=\"false\""), "the host must not treat it as an eval runtime");
        assertFalse(page.contains(NOTICE));
    }

    @Test
    void anAllowedEvalRuntimeRendersAndTellsTheHostItNeedsEval() throws Exception {
        BuildUiConfiguration.get().setAllowEvalRuntimes(true);
        defineForm(Runtimes.ALPINE);
        String page = formPage();
        assertFalse(page.contains(NOTICE));
        assertTrue(page.contains("data-runtime=\"alpine3\""), "allowed runtime must render");
        // host.js checks for eval before it loads the runtime, so enforced CSP gives a clear error.
        assertTrue(page.contains("data-eval=\"true\""), "the host must know to check for eval");
        assertTrue(page.contains("host.js"));
        assertNotNull(ContentStore.get(moduleKey()));
    }

    @Test
    void approvalStillHoldsBackAnAllowedEvalRuntime() throws Exception {
        BuildUiConfiguration.get().setRequireApproval(true);
        defineForm(Runtimes.VUE);
        assertTrue(formPage().contains(NOTICE));
        assertTrue(pending().isEmpty(), "code that cannot run is not offered for approval");

        BuildUiConfiguration.get().setAllowEvalRuntimes(true);
        String page = formPage();
        assertTrue(page.contains(FormApprovalTest.NOTICE), "allowing eval must not skip approval");
        assertFalse(page.contains("data-module="));
        List<ScriptApproval.PendingScript> waiting = pending();
        assertEquals(1, waiting.size());
        assertTrue(waiting.get(0).script.contains("runtime vue3"), "the admin must see which runtime runs the code");

        ScriptApproval.get().approveScript(waiting.get(0).getHash());
        page = formPage();
        assertTrue(page.contains("data-module=\"" + moduleKey() + "\""), "approved form must render");
        assertTrue(page.contains("data-eval=\"true\""));
    }

    @Test
    void panelsFollowTheSameSetting() throws Exception {
        job.setDefinition(new CpsFlowDefinition(
                "uiPanel title: 'Summary', runtime: 'vue3', data: '{\"env\":\"dev\"}', module: \"" + MODULE + "\"", true));
        WorkflowRun run = j.buildAndAssertSuccess(job);
        String url = run.getUrl();

        String page = page("dev", url);
        assertTrue(page.contains("needs eval"), "notice missing");
        assertTrue(page.contains("{&quot;env&quot;:&quot;dev&quot;}") || page.contains("{\"env\":\"dev\"}"), "data missing");
        assertFalse(page.contains("data-module="));
        assertFalse(page.contains("host.js"));

        BuildUiConfiguration.get().setAllowEvalRuntimes(true);
        page = page("dev", url);
        assertTrue(page.contains("data-module=\"" + moduleKey() + "\""), "allowed panel must render");
        assertTrue(page.contains("data-eval=\"true\""));
    }

    @Test
    void theSettingWarnsThatEnforcedCspBreaksTheseRuntimes() throws Exception {
        String page = page("admin", "manage/configure");
        assertTrue(page.contains("Allow runtimes that need eval"), "setting missing");
        assertTrue(page.contains("stop working the day Jenkins enforces"), "an admin must learn the cost before turning it on");
    }

    private static List<ScriptApproval.PendingScript> pending() {
        return ScriptApproval.get().getPendingScripts().stream()
                .filter(p -> p.getLanguage() instanceof FormApproval.FormLanguage)
                .toList();
    }
}
