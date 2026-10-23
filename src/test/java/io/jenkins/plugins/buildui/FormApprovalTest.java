package io.jenkins.plugins.buildui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.Item;
import java.io.File;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.FileTime;
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
 * Approval is opt-in. Off, forms behave as before; on, code a Jenkinsfile
 * author wrote never reaches a browser until an admin has read it, while
 * builds keep working through the JSON field.
 */
@WithJenkins
class FormApprovalTest {

    static final String NOTICE = "waits for approval by an administrator";

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

    private static String module(String text) {
        return "export default function mount({ root }) { root.textContent = '" + text + "'; }";
    }

    /** Defines the job's form, then runs it once so the parameter is registered. */
    private void defineForm(String module) throws Exception {
        job.setDefinition(new CpsFlowDefinition("""
                properties([parameters([uiParameter(name: 'CFG', module: "%s")])])
                echo "cfg=${params.CFG}"
                """.formatted(module), true));
        j.buildAndAssertSuccess(job);
    }

    private String formPageAs(String user) throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient().login(user);
        wc.getOptions().setJavaScriptEnabled(false);
        wc.getOptions().setThrowExceptionOnFailingStatusCode(false);
        // The form page answers 405 to a GET on purpose; its body is still the form.
        return wc.goTo("job/p/build?delay=0sec").getWebResponse().getContentAsString();
    }

    private static List<ScriptApproval.PendingScript> pending() {
        return ScriptApproval.get().getPendingScripts().stream()
                .filter(p -> p.getLanguage() instanceof FormApproval.FormLanguage)
                .toList();
    }

    private static String moduleKey(String module) {
        return ContentStore.sha256(module.getBytes(StandardCharsets.UTF_8)) + ".mjs";
    }

    @Test
    void offByDefaultSoFormsRunAsTheyAlwaysDid() throws Exception {
        defineForm(module("v1"));
        String page = formPageAs("dev");
        assertTrue(page.contains("data-module=\"" + moduleKey(module("v1")) + "\""), "form module missing");
        assertFalse(page.contains(NOTICE));
        assertTrue(pending().isEmpty());
    }

    @Test
    void requiredApprovalHoldsBackTheCodeUntilAnAdminApprovesIt() throws Exception {
        BuildUiConfiguration.get().setRequireApproval(true);
        defineForm(module("v1"));

        String page = formPageAs("dev");
        assertTrue(page.contains(NOTICE), "notice missing");
        assertFalse(page.contains("data-module="), "unapproved code must not be referenced");
        assertFalse(page.contains("host.js"), "nothing should mount an unapproved form");
        assertNull(ContentStore.get(moduleKey(module("v1"))), "unapproved code must not be servable");

        List<ScriptApproval.PendingScript> waiting = pending();
        assertEquals(1, waiting.size());
        assertTrue(waiting.get(0).script.contains(module("v1")), "the admin must see the exact code");

        // A build still works: the JSON field is submitted and validated as before.
        JenkinsRule.WebClient wc = j.createWebClient().login("dev");
        WebRequest req = new WebRequest(URI.create(j.getURL()
                + "job/p/buildWithParameters?CFG=" + URLEncoder.encode("{\"a\":1}", StandardCharsets.UTF_8)).toURL(), HttpMethod.POST);
        assertEquals(201, wc.getPage(wc.addCrumb(req)).getWebResponse().getStatusCode());
        j.waitUntilNoActivity();
        j.assertLogContains("cfg={\"a\":1}", job.getLastBuild());

        ScriptApproval.get().approveScript(waiting.get(0).getHash());
        page = formPageAs("dev");
        assertTrue(page.contains("data-module=\"" + moduleKey(module("v1")) + "\""), "approved form must render");
        assertFalse(page.contains(NOTICE));
        assertNotNull(ContentStore.get(moduleKey(module("v1"))));
    }

    @Test
    void aChangedModuleNeedsAFreshApprovalAndReplacesItsOldPendingEntry() throws Exception {
        BuildUiConfiguration.get().setRequireApproval(true);
        defineForm(module("v1"));
        formPageAs("dev");
        ScriptApproval.get().approveScript(pending().get(0).getHash());

        defineForm(module("v2"));
        assertTrue(formPageAs("dev").contains(NOTICE), "an approval must not carry over to new code");
        defineForm(module("v3"));
        formPageAs("dev");
        List<ScriptApproval.PendingScript> waiting = pending();
        assertEquals(1, waiting.size(), "one pending entry per form, not one per push");
        assertTrue(waiting.get(0).script.contains(module("v3")));
    }

    @Test
    void pageViewsDoNotRewriteTheApprovalFile() throws Exception {
        BuildUiConfiguration.get().setRequireApproval(true);
        defineForm(module("v1"));
        formPageAs("dev");
        File file = new File(j.jenkins.getRootDir(), "scriptApproval.xml");
        FileTime old = FileTime.fromMillis(0);
        Files.setLastModifiedTime(file.toPath(), old);
        formPageAs("dev");
        formPageAs("admin");
        assertEquals(old, Files.getLastModifiedTime(file.toPath()));
    }

    @Test
    void aFormWithoutCodeNeedsNoApproval() throws Exception {
        BuildUiConfiguration.get().setRequireApproval(true);
        job.setDefinition(new CpsFlowDefinition("""
                properties([parameters([uiParameter(name: 'CFG', runtime: 'schema-form',
                  schema: '{"type":"object","properties":{"env":{"type":"string"}}}')])])
                """, true));
        j.buildAndAssertSuccess(job);
        String page = formPageAs("dev");
        assertTrue(page.contains("data-runtime=\"schema-form\""), "schema-form must render");
        assertFalse(page.contains(NOTICE));
        assertTrue(pending().isEmpty());
    }

    @Test
    void panelCodeWaitsTooWhileItsDataStaysReadable() throws Exception {
        BuildUiConfiguration.get().setRequireApproval(true);
        job.setDefinition(new CpsFlowDefinition(
                "uiPanel title: 'Summary', data: '{\"env\":\"dev\"}', module: \"" + module("panel") + "\"", true));
        WorkflowRun run = j.buildAndAssertSuccess(job);
        JenkinsRule.WebClient wc = j.createWebClient().login("dev");
        wc.getOptions().setJavaScriptEnabled(false);

        String page = wc.getPage(run).getWebResponse().getContentAsString();
        assertTrue(page.contains("waits for approval"), "notice missing");
        assertTrue(page.contains("{&quot;env&quot;:&quot;dev&quot;}") || page.contains("{\"env\":\"dev\"}"), "data missing");
        assertFalse(page.contains("data-module="));

        ScriptApproval.get().approveScript(pending().get(0).getHash());
        page = wc.getPage(run).getWebResponse().getContentAsString();
        assertTrue(page.contains("data-module=\"" + moduleKey(module("panel")) + "\""), "approved panel must render");
    }
}
