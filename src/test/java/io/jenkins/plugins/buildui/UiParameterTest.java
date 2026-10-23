package io.jenkins.plugins.buildui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.Item;
import hudson.model.ParametersAction;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.Result;
import hudson.model.StringParameterValue;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.Page;
import org.htmlunit.WebRequest;
import org.htmlunit.util.NameValuePair;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class UiParameterTest {

    static final String MODULE = "export default function mount({ root }) { root.textContent = 'hi'; }";
    static final String SCHEMA = "{\"type\":\"object\",\"required\":[\"branch\"],\"properties\":{\"branch\":{\"type\":\"string\",\"minLength\":1}}}";

    private JenkinsRule j;
    private WorkflowJob job;

    @BeforeEach
    void setUp(JenkinsRule j) throws Exception {
        this.j = j;
        job = j.createProject(WorkflowJob.class, "p");
        job.setDefinition(new CpsFlowDefinition("""
                pipeline {
                  agent none
                  parameters {
                    uiParameter(name: 'BUILD_CONFIG', module: '%s', schema: '%s', defaultJson: '{"branch":"main"}')
                  }
                  stages { stage('s') { steps {
                    echo "config=${params.BUILD_CONFIG}"
                    echo "env-missing=${env.BUILD_CONFIG == null}"
                  } } }
                }""".formatted(MODULE.replace("'", "\\'"), SCHEMA), true));
        // The first run registers the parameter, as declarative pipelines do.
        j.buildAndAssertSuccess(job);
        assertNotNull(job.getProperty(ParametersDefinitionProperty.class).getParameterDefinition("BUILD_CONFIG"));
    }

    private WorkflowRun lastBuildAfterQueue() throws Exception {
        j.waitUntilNoActivity();
        return job.getLastBuild();
    }

    @Test
    void defaultValueReachesParamsAndEnvironment() throws Exception {
        WorkflowRun run = j.buildAndAssertSuccess(job);
        j.assertLogContains("config={\"branch\":\"main\"}", run);
        j.assertLogContains("env-missing=false", run);
    }

    @Test
    void restBuildWithParametersAcceptsAValidValueAndNormalizesIt() throws Exception {
        Page page = post("job/p/buildWithParameters?BUILD_CONFIG=" + enc("{ \"branch\" : \"feature/x\" }"), List.of());
        assertEquals(201, page.getWebResponse().getStatusCode());
        WorkflowRun run = lastBuildAfterQueue();
        assertEquals(2, run.getNumber());
        j.assertLogContains("config={\"branch\":\"feature/x\"}", run);
    }

    @Test
    void restBuildWithParametersRejectsAnInvalidValueWithTheReasonAndQueuesNothing() throws Exception {
        Page page = post("job/p/buildWithParameters?BUILD_CONFIG=" + enc("{\"branch\":\"\"}"), List.of());
        assertEquals(400, page.getWebResponse().getStatusCode());
        assertTrue(page.getWebResponse().getContentAsString().contains("/branch: must have at least 1 characters"),
                page.getWebResponse().getContentAsString());
        assertEquals(1, lastBuildAfterQueue().getNumber());
    }

    @Test
    void formSubmissionGoesThroughTheSameValidation() throws Exception {
        String ok = "{\"parameter\":[{\"name\":\"BUILD_CONFIG\",\"value\":" + quote("{\"branch\":\"from-form\"}") + "}]}";
        assertTrue(post("job/p/build?delay=0sec", List.of(new NameValuePair("json", ok))).getWebResponse().getStatusCode() < 400);
        j.assertLogContains("config={\"branch\":\"from-form\"}", lastBuildAfterQueue());

        String bad = "{\"parameter\":[{\"name\":\"BUILD_CONFIG\",\"value\":" + quote("{}") + "}]}";
        Page page = post("job/p/build?delay=0sec", List.of(new NameValuePair("json", bad)));
        assertEquals(400, page.getWebResponse().getStatusCode());
        assertEquals(2, lastBuildAfterQueue().getNumber());
    }

    @Test
    void downstreamBuildStepConvertsAndValidatesTheStringValue() throws Exception {
        WorkflowJob up = j.createProject(WorkflowJob.class, "up");
        up.setDefinition(new CpsFlowDefinition(
                "build job: 'p', parameters: [string(name: 'BUILD_CONFIG', value: '{\"branch\":\"from-upstream\"}')]", true));
        j.buildAndAssertSuccess(up);
        j.assertLogContains("config={\"branch\":\"from-upstream\"}", job.getLastBuild());

        up.setDefinition(new CpsFlowDefinition(
                "build job: 'p', parameters: [string(name: 'BUILD_CONFIG', value: '{\"branch\":\"\"}')]", true));
        WorkflowRun failed = j.buildAndAssertStatus(Result.FAILURE, up);
        j.assertLogContains("/branch: must have at least 1 characters", failed);
    }

    @Test
    void queueGuardRefusesAHandBuiltInvalidValue() throws Exception {
        assertNull(job.scheduleBuild2(0, new ParametersAction(new StringParameterValue("BUILD_CONFIG", "{\"branch\":\"\"}"))));
        assertNotNull(job.scheduleBuild2(0, new ParametersAction(new StringParameterValue("BUILD_CONFIG", "{\"branch\":\"ok\"}"))));
        j.assertLogContains("config={\"branch\":\"ok\"}", lastBuildAfterQueue());
    }

    @Test
    void largeValuesStayInParamsButAreNotExportedToBuildProcesses() throws Exception {
        // Sent in the POST body: a value this size in the URL exceeds Jetty's 8 KB header limit.
        String pad = "x".repeat(UiParameterValue.MAX_ENV_CHARS);
        Page page = post("job/p/buildWithParameters",
                List.of(new NameValuePair("BUILD_CONFIG", "{\"branch\":\"main\",\"pad\":\"" + pad + "\"}")));
        assertEquals(201, page.getWebResponse().getStatusCode());
        WorkflowRun run = lastBuildAfterQueue();
        j.assertLogContains("\"pad\":\"xxxx", run);
        j.assertLogContains("env-missing=true", run);
    }

    @Test
    void servesTheRegisteredModuleAsJavaScriptAndNothingElse() throws Exception {
        String key = moduleKeyFromBuildPage();
        Page module = j.createWebClient().goTo("build-ui/" + key, null);
        assertEquals("text/javascript", module.getWebResponse().getContentType());
        assertEquals(MODULE, module.getWebResponse().getContentAsString());
        assertTrue(module.getWebResponse().getResponseHeaderValue("Cache-Control").contains("immutable"));

        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false);
        String unknown = "0".repeat(64) + ".mjs";
        assertEquals(404, wc.goTo("build-ui/" + unknown, null).getWebResponse().getStatusCode());
        assertEquals(404, wc.goTo("build-ui/..%2F..%2Fconfig.xml", null).getWebResponse().getStatusCode());
    }

    @Test
    void contentAndQueriesNeedOverallReadAndQueriesSeeOnlyReadableJobs() throws Exception {
        String key = moduleKeyFromBuildPage();
        j.createProject(WorkflowJob.class, "secret");
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.READ).everywhere().to("alice")
                .grant(Item.READ).onItems(job).to("alice"));

        // A plain client: HtmlUnit would follow Jenkins's login redirect and report 200.
        HttpResponse<String> anonymous = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(j.getURL() + "build-ui/" + key)).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(403, anonymous.statusCode());
        assertTrue(!anonymous.body().contains("mount"), "anonymous user got the module");

        JenkinsRule.WebClient alice = j.createWebClient().withThrowExceptionOnFailingStatusCode(false).login("alice");
        assertEquals(200, alice.goTo("build-ui/" + key, null).getWebResponse().getStatusCode());
        Page runs = alice.goTo("build-ui/query/jenkinsRuns?args=" + enc("{\"job\":\"p\",\"limit\":5}"), "application/json");
        assertTrue(runs.getWebResponse().getContentAsString().contains("\"number\":1"), runs.getWebResponse().getContentAsString());
        Page hidden = alice.goTo("build-ui/query/jenkinsRuns?args=" + enc("{\"job\":\"secret\"}"), "application/json");
        assertEquals(400, hidden.getWebResponse().getStatusCode());
        assertTrue(hidden.getWebResponse().getContentAsString().contains("no job 'secret' that you can read"));
    }

    @Test
    void formChecksNeedConfigureBecauseTheCallerSuppliesBothSchemaAndValue() throws Exception {
        // A slow pattern and a large value from the same caller cost controller CPU, so readers may not run checks.
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.READ, Item.READ).everywhere().to("reader")
                .grant(Jenkins.READ, Item.READ, Item.CONFIGURE).everywhere().to("author"));
        String descriptor = "descriptorByName/" + UiParameterDefinition.class.getName() + "/";
        List<NameValuePair> args = List.of(new NameValuePair("value", "{}"), new NameValuePair("schema", SCHEMA));

        assertEquals(403, postAs("reader", "job/p/" + descriptor + "checkDefaultJson", args).getWebResponse().getStatusCode());
        assertEquals(403, postAs("reader", "job/p/" + descriptor + "checkSchema", args).getWebResponse().getStatusCode());
        // Outside a job only an administrator may check.
        assertEquals(403, postAs("author", descriptor + "checkDefaultJson", args).getWebResponse().getStatusCode());
        Page checked = postAs("author", "job/p/" + descriptor + "checkDefaultJson", args);
        assertEquals(200, checked.getWebResponse().getStatusCode());
        assertTrue(checked.getWebResponse().getContentAsString().contains("missing required property"),
                checked.getWebResponse().getContentAsString());
    }

    private Page postAs(String user, String path, List<NameValuePair> params) throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false).login(user);
        WebRequest req = new WebRequest(URI.create(j.getURL() + path).toURL(), HttpMethod.POST);
        req.setRequestParameters(new java.util.ArrayList<>(params));
        return wc.getPage(wc.addCrumb(req));
    }

    private String moduleKeyFromBuildPage() throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false);
        wc.getOptions().setJavaScriptEnabled(false);
        String html = wc.goTo("job/p/build?delay=0sec").getWebResponse().getContentAsString();
        Matcher m = Pattern.compile("data-module=\"([0-9a-f]{64}\\.mjs)\"").matcher(html);
        assertTrue(m.find(), "no data-module in the build page");
        assertTrue(html.contains("/plugin/build-ui/host.js"), "the build page does not load host.js");
        return m.group(1);
    }

    private Page post(String path, List<NameValuePair> params) throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false).withRedirectEnabled(false);
        WebRequest req = new WebRequest(URI.create(j.getURL() + path).toURL(), HttpMethod.POST);
        req.setRequestParameters(new java.util.ArrayList<>(params));
        return wc.getPage(wc.addCrumb(req));
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
