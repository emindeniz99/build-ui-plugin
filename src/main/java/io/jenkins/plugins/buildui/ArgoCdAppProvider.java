package io.jenkins.plugins.buildui;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import hudson.Extension;
import hudson.ProxyConfiguration;
import hudson.security.ACL;
import io.jenkins.plugins.buildui.json.Json;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;

/**
 * {@code ui.query('argoCdApp', { app: 'web', server: 'default' })}: health,
 * sync state, revision and images of one ArgoCD application. Only servers an
 * admin configured in {@link BuildUiConfiguration} are contacted (no SSRF),
 * only application names matching that server's {@code apps} glob, and the
 * token never leaves the controller.
 */
@Extension
public class ArgoCdAppProvider extends DataProvider {

    /** Kubernetes object names: lowercase letters, digits, '-' and '.'. */
    private static final Pattern APP_NAME = Pattern.compile("[a-z0-9]([-a-z0-9.]{0,251}[a-z0-9])?");
    static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
    static final long CACHE_MILLIS = 10_000;
    private static final Map<String, Cached> CACHE = new ConcurrentHashMap<>();

    private record Cached(long at, JsonNode value) {}

    @Override
    public String getId() {
        return "argoCdApp";
    }

    @Override
    public JsonNode query(JsonNode args) {
        String app = args.path("app").asText("");
        if (!APP_NAME.matcher(app).matches()) {
            throw new IllegalArgumentException("argoCdApp needs an 'app' that is a Kubernetes object name");
        }
        String serverName = args.path("server").asText("default");
        ArgoCdServer server = BuildUiConfiguration.get().server(serverName);
        if (server == null || server.getUrl().isEmpty()) {
            throw new IllegalArgumentException("no ArgoCD server '" + serverName + "' is configured under Manage Jenkins » System » Build UI");
        }
        if (!server.allows(app)) {
            throw new IllegalArgumentException("ArgoCD server '" + serverName + "' does not allow forms to read application '" + app + "'");
        }
        // The URL is part of the key, so changing a server in the settings never serves its old answers.
        String key = server.getUrl() + "\n" + server.getCredentialsId() + "\n" + app;
        Cached cached = CACHE.get(key);
        if (cached != null && System.currentTimeMillis() - cached.at() < CACHE_MILLIS) {
            return cached.value();
        }
        JsonNode summary = summarize(fetch(server, app));
        CACHE.put(key, new Cached(System.currentTimeMillis(), summary));
        return summary;
    }

    private static JsonNode fetch(ArgoCdServer server, String app) {
        String token = token(server);
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(server.getUrl() + "/api/v1/applications/"
                        + URLEncoder.encode(app, StandardCharsets.UTF_8)))
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json");
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        HttpClient client = ProxyConfiguration.newHttpClientBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        try {
            HttpResponse<InputStream> response = client.send(request.GET().build(), HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                byte[] bytes = body.readNBytes(MAX_RESPONSE_BYTES + 1);
                switch (response.statusCode()) {
                    case 200 -> { }
                    case 401, 403 -> throw new IllegalArgumentException("ArgoCD server '" + server.getName() + "' refused the token");
                    case 404 -> throw new IllegalArgumentException("ArgoCD has no application '" + app + "'");
                    default -> throw new IllegalArgumentException("ArgoCD answered HTTP " + response.statusCode());
                }
                if (bytes.length > MAX_RESPONSE_BYTES) {
                    throw new IllegalArgumentException("ArgoCD's answer is larger than " + MAX_RESPONSE_BYTES + " bytes");
                }
                return Json.parseServerAnswer(bytes);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalArgumentException("interrupted while asking ArgoCD", e);
        } catch (IOException e) {
            // The message goes to the browser: name the server, never the token.
            throw new IllegalArgumentException("could not reach ArgoCD server '" + server.getName() + "' (" + e.getClass().getSimpleName() + ")", e);
        }
    }

    private static String token(ArgoCdServer server) {
        if (server.getCredentialsId() == null) {
            return null;
        }
        StringCredentials credentials = CredentialsMatchers.firstOrNull(
                CredentialsProvider.lookupCredentialsInItemGroup(StringCredentials.class, Jenkins.get(), ACL.SYSTEM2, List.of()),
                CredentialsMatchers.withId(server.getCredentialsId()));
        if (credentials == null) {
            throw new IllegalArgumentException("the token credential of ArgoCD server '" + server.getName() + "' is missing");
        }
        return credentials.getSecret().getPlainText();
    }

    /** Keeps what a form needs and drops the rest of ArgoCD's large answer. */
    static JsonNode summarize(JsonNode app) {
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        out.put("name", app.path("metadata").path("name").asText(null));
        out.put("project", app.path("spec").path("project").asText(null));
        out.put("namespace", app.path("spec").path("destination").path("namespace").asText(null));
        out.put("health", app.path("status").path("health").path("status").asText(null));
        out.put("sync", app.path("status").path("sync").path("status").asText(null));
        out.put("revision", app.path("status").path("sync").path("revision").asText(null));
        out.put("operation", app.path("status").path("operationState").path("phase").asText(null));
        out.put("reconciledAt", app.path("status").path("reconciledAt").asText(null));
        ArrayNode images = out.putArray("images");
        app.path("status").path("summary").path("images").forEach(i -> images.add(i.asText()));
        return out;
    }
}
