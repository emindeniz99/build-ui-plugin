package io.jenkins.plugins.buildui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import hudson.Extension;
import hudson.Util;
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
 * runs a {@link DataProvider} as the calling user; {@code /build-ui/frame}
 * renders the document that {@code host.js} puts in a sandboxed form's
 * iframe as {@code srcdoc}. All need Overall/Read,
 * which Jenkins checks for every {@link RootAction}.
 */
@Extension
public class BuildUiAction implements RootAction {

    public static final String URL_NAME = "build-ui";
    private static final Pattern CONTENT_KEY = Pattern.compile("[0-9a-f]{64}\\.(mjs|css|html\\.txt)");
    private static final Pattern MODULE_KEY = Pattern.compile("[0-9a-f]{64}\\.mjs");

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
        if (path.equals("/frame")) {
            frame(req, rsp);
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

    /**
     * The iframe document of a sandboxed form: no code of its own, only the
     * boot script and, in an attribute, a one-time URL of the form's module.
     * {@code host.js} fetches it with the viewer's session, so this is where
     * the permission check happens, and sets it as the frame's {@code srcdoc}.
     * The frame's own requests come from an opaque origin.
     */
    private static void frame(StaplerRequest2 req, StaplerResponse2 rsp) throws IOException {
        String runtime = req.getParameter("runtime");
        String module = req.getParameter("module");
        // The view never offers an eval runtime an admin has not allowed; a hand-made URL must not either.
        if (runtime == null || !Runtimes.ALL.contains(runtime) || !Runtimes.allowed(runtime)
                || (module != null && (!MODULE_KEY.matcher(module).matches() || ContentStore.get(module) == null))) {
            rsp.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        String sandbox = req.getContextPath() + "/" + SandboxAction.URL_NAME + "/";
        String moduleAttr = module == null ? ""
                : " data-module=\"" + Util.escape(sandbox + "module/" + SandboxTokens.mint(module, System.currentTimeMillis()) + ".mjs") + "\"";
        String html = "<!DOCTYPE html>\n<html lang=\"en\" data-runtime=\"" + runtime + "\"" + moduleAttr + ">"
                + "<head><meta charset=\"utf-8\"><title>build-ui form</title>"
                + "<script type=\"module\" src=\"" + Util.escape(sandbox + "boot.js") + "\"></script></head><body></body></html>\n";
        rsp.setContentType("text/html;charset=UTF-8");
        // The token is single use; a cached copy of this page would hand out a spent one.
        rsp.setHeader("Cache-Control", "no-store");
        rsp.getOutputStream().write(html.getBytes(StandardCharsets.UTF_8));
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
