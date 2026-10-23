package io.jenkins.plugins.buildui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.ParametersDefinitionProperty;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import jenkins.model.Jenkins;
import org.htmlunit.Page;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * A sandboxed form runs in an iframe with an opaque origin, which sends no
 * cookies. The protected frame endpoint is therefore the only place the
 * viewer's permission is checked, and the unprotected endpoint must hand out
 * nothing but the plugin's own scripts and a module someone with Overall/Read
 * asked for a moment ago.
 */
@WithJenkins
class SandboxTest {

    static final String MODULE = "export default function mount({ root }) { root.textContent = 'boxed'; }";
    static final Pattern MODULE_URL = Pattern.compile("data-module=\"([^\"]+/build-ui-sandbox/module/([0-9a-f]{32})\\.mjs)\"");

    private JenkinsRule j;
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void setUp(JenkinsRule j) throws Exception {
        this.j = j;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        // Anonymous gets nothing, so a 200 for anonymous proves an endpoint is unprotected on purpose.
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ).everywhere().to("alice"));
        ContentStore.clear();
    }

    private WorkflowJob job(String name, String parameter) throws Exception {
        WorkflowJob job = j.createProject(WorkflowJob.class, name);
        job.setDefinition(new CpsFlowDefinition("properties([parameters([" + parameter + "])])", true));
        j.buildAndAssertSuccess(job);
        return job;
    }

    private static String sandboxed(String module) {
        return "uiParameter(name: 'CFG', sandbox: true, module: \"" + module + "\")";
    }

    private String page(String path) throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false).login("alice");
        wc.getOptions().setJavaScriptEnabled(false);
        return wc.goTo(path, null).getWebResponse().getContentAsString();
    }

    private Page aliceGet(String path) throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false).login("alice");
        return wc.goTo(path, null);
    }

    /** A request without a session, as the sandboxed frame makes it. */
    private HttpResponse<String> anonymous(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(j.getURL() + path)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String key(String text) {
        return ContentStore.sha256(text.getBytes(StandardCharsets.UTF_8)) + ".mjs";
    }

    /** The one-time module path in a frame document, relative to the Jenkins root. */
    private String tokenPath(String frameHtml) {
        Matcher m = MODULE_URL.matcher(frameHtml);
        assertTrue(m.find(), frameHtml);
        return "build-ui-sandbox/module/" + m.group(2) + ".mjs";
    }

    @Test
    void onlyAFormThatAsksForItIsMarkedSandboxed() throws Exception {
        job("boxed", sandboxed(MODULE));
        job("plain", "uiParameter(name: 'CFG', module: \"" + MODULE + "\")");
        assertTrue(page("job/boxed/build?delay=0sec").contains("data-sandbox=\"true\""));
        assertTrue(page("job/plain/build?delay=0sec").contains("data-sandbox=\"false\""), "sandboxing is opt-in");
    }

    @Test
    void theAdminSettingSandboxesEveryFormWithCodeButNotACodelessSchemaForm() throws Exception {
        assertFalse(BuildUiConfiguration.get().isSandboxAll(), "off unless an admin turns it on");
        job("plain", "uiParameter(name: 'CFG', module: \"" + MODULE + "\")");
        job("schema", "uiParameter(name: 'CFG', runtime: 'schema-form', schema: '{\"type\":\"object\"}')");
        BuildUiConfiguration.get().setSandboxAll(true);
        assertTrue(page("job/plain/build?delay=0sec").contains("data-sandbox=\"true\""));
        // schema-form without html or css runs only the plugin's own code; an iframe would add nothing.
        assertTrue(page("job/schema/build?delay=0sec").contains("data-sandbox=\"false\""));
    }

    @Test
    void aPanelCanBeSandboxed() throws Exception {
        WorkflowJob job = j.createProject(WorkflowJob.class, "panel");
        job.setDefinition(new CpsFlowDefinition("uiPanel title: 'Summary', sandbox: true, module: \"" + MODULE + "\"", true));
        WorkflowRun run = j.buildAndAssertSuccess(job);
        assertTrue(page(run.getUrl()).contains("data-sandbox=\"true\""));
    }

    @Test
    void theSandboxFieldSurvivesTheConfigurationPage() throws Exception {
        UiParameterDefinition d = new UiParameterDefinition("CFG");
        d.setModule(MODULE);
        d.setSandbox(true);
        FreeStyleProject p = j.createFreeStyleProject("free");
        p.addProperty(new ParametersDefinitionProperty(d));
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy().grant(Jenkins.ADMINISTER).everywhere().toEveryone());
        j.configRoundtrip(p);
        UiParameterDefinition after = (UiParameterDefinition) p.getProperty(ParametersDefinitionProperty.class).getParameterDefinition("CFG");
        assertTrue(after.isSandbox(), "saving the page unchanged must keep the form sandboxed");
        j.assertEqualDataBoundBeans(d, after);
    }

    @Test
    void theFrameNeedsOverallReadAndRefersToItsModuleOnlyByAOneTimeUrl() throws Exception {
        job("boxed", sandboxed(MODULE));
        page("job/boxed/build?delay=0sec");
        String frame = "build-ui/frame?runtime=preact11-htm3&module=" + key(MODULE);

        assertEquals(403, anonymous(frame).statusCode(), "the frame request carries cookies, so it is where access is checked");

        Page res = aliceGet(frame);
        assertEquals(200, res.getWebResponse().getStatusCode());
        assertEquals("text/html", res.getWebResponse().getContentType());
        assertEquals("no-store", res.getWebResponse().getResponseHeaderValue("Cache-Control"), "a cached frame would hold a spent token");
        String html = res.getWebResponse().getContentAsString();
        assertTrue(html.contains("src=\"/jenkins/build-ui-sandbox/boot.js\""), html);
        assertTrue(html.contains("data-runtime=\"preact11-htm3\""), html);
        assertFalse(html.contains(key(MODULE)), "the frame must not learn the content key, which works for anyone with Overall/Read");
        assertFalse(html.contains("mount"), "the frame document holds no form code");
        tokenPath(html);
    }

    @Test
    void theFrameRefusesContentNobodyRegisteredAndUnknownRuntimes() throws Exception {
        job("boxed", sandboxed(MODULE));
        page("job/boxed/build?delay=0sec");
        assertEquals(404, aliceGet("build-ui/frame?runtime=preact11-htm3&module=" + "0".repeat(64) + ".mjs").getWebResponse().getStatusCode());
        assertEquals(404, aliceGet("build-ui/frame?runtime=vue3&module=" + key(MODULE)).getWebResponse().getStatusCode());
        assertEquals(404, aliceGet("build-ui/frame?module=" + key(MODULE)).getWebResponse().getStatusCode());
        // A key that is not a module (CSS, HTML) cannot become a script.
        String css = ContentStore.put("p { color: red }", "css", "text/css;charset=UTF-8");
        assertEquals(404, aliceGet("build-ui/frame?runtime=none&module=" + css).getWebResponse().getStatusCode());
        // A frame without a module is fine: schema-form with CSS has no module.
        String noModule = aliceGet("build-ui/frame?runtime=schema-form").getWebResponse().getContentAsString();
        assertFalse(noModule.contains("data-module"), noModule);
    }

    @Test
    void aTokenServesItsModuleOnceToARequestWithoutASession() throws Exception {
        job("boxed", sandboxed(MODULE));
        page("job/boxed/build?delay=0sec");
        String path = tokenPath(page("build-ui/frame?runtime=preact11-htm3&module=" + key(MODULE)));

        HttpResponse<String> first = anonymous(path);
        assertEquals(200, first.statusCode());
        assertEquals(MODULE, first.body());
        assertEquals("text/javascript;charset=UTF-8", first.headers().firstValue("Content-Type").orElse(""));
        assertEquals("*", first.headers().firstValue("Access-Control-Allow-Origin").orElse(""),
                "an opaque-origin frame loads module scripts in CORS mode");
        assertEquals("no-store", first.headers().firstValue("Cache-Control").orElse(""));

        assertEquals(404, anonymous(path).statusCode(), "a token works once, so a leaked URL is worth nothing later");
    }

    @Test
    void aTokenServesOnlyTheModuleItWasMintedFor() throws Exception {
        String other = "export default function mount({ root }) { root.textContent = 'other'; }";
        job("a", sandboxed(MODULE));
        job("b", sandboxed(other));
        page("job/a/build?delay=0sec");
        page("job/b/build?delay=0sec");
        String forA = tokenPath(page("build-ui/frame?runtime=preact11-htm3&module=" + key(MODULE)));
        String forB = tokenPath(page("build-ui/frame?runtime=preact11-htm3&module=" + key(other)));
        String againA = tokenPath(page("build-ui/frame?runtime=preact11-htm3&module=" + key(MODULE)));
        assertNotEquals(forA, againA, "every frame gets a fresh token");
        assertEquals(other, anonymous(forB).body());
        assertEquals(MODULE, anonymous(forA).body());
        assertEquals(MODULE, anonymous(againA).body());
    }

    @Test
    void tokensExpireAfterAMinuteAndTheStoreStaysBounded() {
        long now = 1_000_000;
        String token = SandboxTokens.mint("k.mjs", now);
        assertTrue(SandboxTokens.TOKEN.matcher(token).matches(), "128 random bits, hex");
        assertNull(SandboxTokens.redeem(token, now + SandboxTokens.TTL_MILLIS + 1), "an expired token serves nothing");
        assertNull(SandboxTokens.redeem(token, now), "and is gone afterwards");

        String fresh = SandboxTokens.mint("k.mjs", now);
        assertEquals("k.mjs", SandboxTokens.redeem(fresh, now + SandboxTokens.TTL_MILLIS));

        // Frames that never load their module must not grow memory without limit: the oldest grant goes first.
        String oldest = SandboxTokens.mint("old.mjs", now);
        for (int i = 0; i < SandboxTokens.CAPACITY; i++) {
            SandboxTokens.mint("k.mjs", now);
        }
        assertNull(SandboxTokens.redeem(oldest, now));
    }

    @Test
    void theUnprotectedEndpointServesItsScriptsToAnyoneWithCors() throws Exception {
        for (String file : new String[] {"boot.js", "runtimes/preact11-htm3.js", "runtimes/schema-form.js"}) {
            HttpResponse<String> res = anonymous("build-ui-sandbox/" + file);
            assertEquals(200, res.statusCode(), file);
            assertEquals("*", res.headers().firstValue("Access-Control-Allow-Origin").orElse(""), file);
            assertEquals("text/javascript;charset=UTF-8", res.headers().firstValue("Content-Type").orElse(""), file);
        }
        assertTrue(anonymous("build-ui-sandbox/boot.js").body().contains("build-ui:init"), "the boot script itself");
    }

    @Test
    void theUnprotectedEndpointRefusesEverythingOutsideItsAllowlist() throws Exception {
        job("boxed", sandboxed(MODULE));
        page("job/boxed/build?delay=0sec");
        String[] refused = {
            "build-ui-sandbox/",
            "build-ui-sandbox/host.js", // a real plugin file, but not on the list
            "build-ui-sandbox/sandbox/boot.js",
            "build-ui-sandbox/runtimes/none.js",
            "build-ui-sandbox/runtimes/preact11-htm3.mjs",
            "build-ui-sandbox/" + key(MODULE), // the protected store is not reachable by key
            "build-ui-sandbox/module/" + key(MODULE),
            "build-ui-sandbox/module/" + "0".repeat(32) + ".mjs",
            "build-ui-sandbox/WEB-INF/web.xml",
        };
        for (String path : refused) {
            assertEquals(404, anonymous(path).statusCode(), path);
        }
        String[] traversals = {
            "build-ui-sandbox/runtimes/../host.js",
            "build-ui-sandbox/runtimes/..%2Fhost.js",
            "build-ui-sandbox/runtimes/%2e%2e%2fhost.js",
            "build-ui-sandbox/%2e%2e/build-ui/" + key(MODULE),
            "build-ui-sandbox/module/../" + key(MODULE),
            "build-ui-sandbox/..%2F..%2Fconfig.xml",
        };
        for (String path : traversals) {
            HttpResponse<String> res = anonymous(path);
            // The action answers 404; Jetty (400) or Stapler (500 for a decoded "..") may refuse
            // the path before the action sees it. Any refusal will do; content must not leak.
            assertNotEquals(200, res.statusCode(), path);
            assertFalse(res.body().contains("export default") || res.body().contains("build-ui host") || res.body().contains("<hudson>"),
                    path + " leaked content");
        }
    }
}
