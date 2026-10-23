package io.jenkins.plugins.buildui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import com.sun.net.httpserver.HttpServer;
import hudson.util.Secret;
import java.net.InetSocketAddress;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.htmlunit.Page;
import org.htmlunit.html.DomElement;
import org.htmlunit.html.DomNode;
import org.htmlunit.html.HtmlForm;
import org.htmlunit.html.HtmlPage;
import org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class ArgoCdAppProviderTest {

    static final String WEB = """
            {"metadata":{"name":"web"},"spec":{"project":"shop","destination":{"namespace":"web-prod"}},
             "status":{"health":{"status":"Healthy"},"sync":{"status":"Synced","revision":"abc123"},
               "operationState":{"phase":"Succeeded"},"reconciledAt":"2026-10-23T09:00:00Z",
               "summary":{"images":["registry.example/web:1.4.2"]},"resources":[{"kind":"Deployment","name":"web"}]}}""";

    private HttpServer argo;
    private final CountDownLatch release = new CountDownLatch(1);
    private final List<String> authorizations = new CopyOnWriteArrayList<>();
    private final List<String> paths = new CopyOnWriteArrayList<>();
    private JenkinsRule j;

    @BeforeEach
    void setUp(JenkinsRule j) throws Exception {
        this.j = j;
        argo = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        argo.setExecutor(Executors.newCachedThreadPool());
        argo.createContext("/api/v1/applications/web-stalled", exchange -> {
            // Headers, the start of a body, then nothing: a hung server or a connection lost mid-answer.
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write("{\"metadata\":".getBytes(StandardCharsets.UTF_8));
            exchange.getResponseBody().flush();
            try {
                release.await(60, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        argo.createContext("/api/v1/applications/", exchange -> {
            authorizations.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
            paths.add(exchange.getRequestURI().getRawPath());
            String app = exchange.getRequestURI().getPath().substring("/api/v1/applications/".length());
            byte[] body = (app.equals("web") ? WEB : "{\"error\":\"not found\"}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(app.equals("web") ? 200 : 404, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        argo.start();
        SystemCredentialsProvider.getInstance().getCredentials().add(
                new StringCredentialsImpl(CredentialsScope.GLOBAL, "argo-token", "", Secret.fromString("t0ken")));
        ArgoCdServer server = new ArgoCdServer("default", "http://127.0.0.1:" + argo.getAddress().getPort() + "/", "argo-token");
        server.setApps("web*");
        BuildUiConfiguration.get().setArgoCdServers(List.of(server));
    }

    @AfterEach
    void tearDown() {
        release.countDown();
        argo.stop(0);
    }

    private Page query(String args) throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false);
        return wc.goTo("build-ui/query/argoCdApp?args=" + URLEncoder.encode(args, StandardCharsets.UTF_8), "application/json");
    }

    @Test
    void returnsASummaryOfTheApplicationAndSendsTheTokenOnlyToTheServer() throws Exception {
        Page page = query("{\"app\":\"web\"}");
        String body = page.getWebResponse().getContentAsString();
        assertEquals(200, page.getWebResponse().getStatusCode(), body);
        assertEquals("{\"name\":\"web\",\"project\":\"shop\",\"namespace\":\"web-prod\",\"health\":\"Healthy\",\"sync\":\"Synced\","
                + "\"revision\":\"abc123\",\"operation\":\"Succeeded\",\"reconciledAt\":\"2026-10-23T09:00:00Z\","
                + "\"images\":[\"registry.example/web:1.4.2\"]}", body);
        assertEquals(List.of("Bearer t0ken"), authorizations);
        assertFalse(body.contains("t0ken"));
        assertFalse(body.contains("resources"), "the full ArgoCD answer should not reach the browser");
    }

    @Test
    void cachesAnswersBrieflySoAFormDoesNotHammerArgo() throws Exception {
        query("{\"app\":\"web\"}");
        query("{\"app\":\"web\"}");
        assertEquals(1, paths.size());
    }

    @Test
    void refusesAppsOutsideTheAllowedGlobAndNamesThatCouldChangeThePath() throws Exception {
        Page outside = query("{\"app\":\"billing\"}");
        assertEquals(400, outside.getWebResponse().getStatusCode());
        assertTrue(outside.getWebResponse().getContentAsString().contains("does not allow forms to read application 'billing'"));
        for (String bad : List.of("../projects", "web/../../x", "web?x=1", "WEB", "")) {
            assertEquals(400, query("{\"app\":\"" + bad + "\"}").getWebResponse().getStatusCode(), bad);
        }
        assertTrue(paths.isEmpty(), "no request may reach ArgoCD for a refused name: " + paths);
    }

    @Test
    void reportsAMissingAppAndAnUnknownServerClearly() throws Exception {
        Page missing = query("{\"app\":\"web-old\"}");
        assertEquals(400, missing.getWebResponse().getStatusCode());
        assertTrue(missing.getWebResponse().getContentAsString().contains("ArgoCD has no application 'web-old'"));
        Page unknown = query("{\"app\":\"web\",\"server\":\"staging\"}");
        assertTrue(unknown.getWebResponse().getContentAsString().contains("no ArgoCD server 'staging' is configured"));
    }

    @Test
    void aServerThatStallsMidAnswerFailsTheQueryInsteadOfPinningARequestThread() throws Exception {
        // Only the headers had a timeout; a stalled body held the request thread until the server let go.
        ArgoCdAppProvider.ANSWER_TIMEOUT_MILLIS = 2_000;
        try {
            long start = System.nanoTime();
            Page page = query("{\"app\":\"web-stalled\"}");
            long millis = (System.nanoTime() - start) / 1_000_000;
            assertEquals(400, page.getWebResponse().getStatusCode());
            assertTrue(page.getWebResponse().getContentAsString().contains("did not answer within"), page.getWebResponse().getContentAsString());
            assertTrue(millis < 20_000, "took " + millis + " ms");
        } finally {
            ArgoCdAppProvider.ANSWER_TIMEOUT_MILLIS = 15_000;
        }
    }

    @Test
    void deletingTheLastServerOnTheSettingsPageRemovesIt() throws Exception {
        // A form with no servers sends no argoCdServers key at all, so the setter never ran and the
        // deleted server, with its token, kept being contacted.
        HtmlPage page = j.createWebClient().goTo("manage/configure");
        HtmlForm form = page.getFormByName("config");
        DomNode chunk = null;
        for (DomElement input : page.getElementsByName("_.url")) {
            if (input.getAttribute("value").startsWith("http://127.0.0.1:")) {
                chunk = input;
                while (!(chunk instanceof DomElement e && e.getAttribute("class").contains("repeated-chunk"))) {
                    chunk = chunk.getParentNode();
                }
            }
        }
        assertTrue(chunk != null, "the configured server is not on the page");
        chunk.remove();
        j.submit(form);
        assertEquals(List.of(), BuildUiConfiguration.get().getArgoCdServers());
        assertEquals(List.of(), new BuildUiConfiguration().getArgoCdServers(), "the deletion must reach disk");
    }

    @Test
    void theSettingsPageRoundTrips() throws Exception {
        j.configRoundtrip();
        ArgoCdServer server = BuildUiConfiguration.get().getArgoCdServers().get(0);
        assertEquals("default", server.getName());
        assertEquals("argo-token", server.getCredentialsId());
        assertEquals("web*", server.getApps());
        assertTrue(server.getUrl().startsWith("http://127.0.0.1:"));
        assertFalse(server.getUrl().endsWith("/"));
    }
}
