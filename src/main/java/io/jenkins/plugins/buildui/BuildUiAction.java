package io.jenkins.plugins.buildui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import hudson.Extension;
import hudson.model.RootAction;
import io.jenkins.plugins.buildui.json.Json;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.StaplerResponse2;

/**
 * {@code /build-ui/<sha256>.<ext>} serves form content that a parameter
 * definition registered in {@link ContentStore}; {@code /build-ui/query/<id>}
 * runs a {@link DataProvider} as the calling user. Both need Overall/Read,
 * which Jenkins checks for every {@link RootAction}.
 */
@Extension
public class BuildUiAction implements RootAction {

    public static final String URL_NAME = "build-ui";
    private static final Pattern CONTENT_KEY = Pattern.compile("[0-9a-f]{64}\\.(mjs|css|html\\.txt)");

    @Override
    public String getIconFileName() {
        return null;
    }

    @Override
    public String getDisplayName() {
        return null;
    }

    @Override
    public String getUrlName() {
        return URL_NAME;
    }

    public void doDynamic(StaplerRequest2 req, StaplerResponse2 rsp) throws IOException {
        Jenkins.get().checkPermission(Jenkins.READ);
        String path = req.getRestOfPath();
        if (path.startsWith("/query/")) {
            query(path.substring("/query/".length()), req, rsp);
            return;
        }
        String key = path.startsWith("/") ? path.substring(1) : path;
        ContentStore.Content entry = CONTENT_KEY.matcher(key).matches() ? ContentStore.get(key) : null;
        if (entry == null) {
            rsp.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        rsp.setContentType(entry.contentType());
        rsp.setHeader("Cache-Control", "private, max-age=31536000, immutable");
        rsp.getOutputStream().write(entry.bytes());
    }

    private static void query(String id, StaplerRequest2 req, StaplerResponse2 rsp) throws IOException {
        DataProvider provider = DataProvider.find(id);
        JsonNode result;
        int status = HttpServletResponse.SC_OK;
        if (provider == null) {
            status = HttpServletResponse.SC_NOT_FOUND;
            result = error("unknown data provider '" + id + "'");
        } else {
            try {
                String args = req.getParameter("args");
                result = provider.query(args == null ? JsonNodeFactory.instance.objectNode() : Json.parse(args, "args"));
            } catch (IllegalArgumentException e) {
                status = HttpServletResponse.SC_BAD_REQUEST;
                result = error(e.getMessage());
            }
        }
        rsp.setStatus(status);
        rsp.setContentType("application/json;charset=UTF-8");
        rsp.setHeader("Cache-Control", "no-store");
        rsp.getOutputStream().write(Json.write(result).getBytes(StandardCharsets.UTF_8));
    }

    private static JsonNode error(String message) {
        return JsonNodeFactory.instance.objectNode().put("error", message);
    }
}
