package io.jenkins.plugins.buildui;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.Extension;
import hudson.PluginWrapper;
import hudson.model.UnprotectedRootAction;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.StaplerResponse2;

/**
 * {@code /build-ui-sandbox/} serves what a sandboxed form's iframe loads. The
 * frame has an opaque origin, so the browser fetches its module scripts in CORS
 * mode and without cookies; this action therefore needs no permission and sends
 * {@code Access-Control-Allow-Origin: *}. It serves only:
 *
 * <ul>
 *   <li>the plugin's boot script and runtime bundles, from a fixed list; no
 *       part of the request path reaches the file system;
 *   <li>{@code module/<token>.mjs}: the module a {@link SandboxTokens} token
 *       was minted for, once.
 * </ul>
 */
@Extension
public class SandboxAction implements UnprotectedRootAction {

    public static final String URL_NAME = "build-ui-sandbox";

    /** Request path to the plugin resource it serves. */
    private static final Map<String, String> FILES = files();

    private static Map<String, String> files() {
        Map<String, String> files = new HashMap<>();
        files.put("boot.js", "sandbox/boot.js");
        for (String id : Runtimes.ALL) {
            if (!id.equals(Runtimes.NONE)) {
                files.put("runtimes/" + id + ".js", "runtimes/" + id + ".js");
            }
        }
        return Map.copyOf(files);
    }

    /** True when code is sandboxed: the form asks for it or an admin sandboxes all forms, and there is code to sandbox. */
    static boolean applies(boolean requested, @CheckForNull String module, @CheckForNull String html, @CheckForNull String css) {
        return (requested || BuildUiConfiguration.get().isSandboxAll()) && (module != null || html != null || css != null);
    }

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
        String path = req.getRestOfPath();
        path = path.startsWith("/") ? path.substring(1) : path;
        byte[] body = null;
        if (path.startsWith("module/") && path.endsWith(".mjs")) {
            String token = path.substring("module/".length(), path.length() - ".mjs".length());
            String key = SandboxTokens.TOKEN.matcher(token).matches() ? SandboxTokens.redeem(token, System.currentTimeMillis()) : null;
            ContentStore.Content content = key == null ? null : ContentStore.get(key);
            body = content == null ? null : content.bytes();
            rsp.setHeader("Cache-Control", "no-store");
        } else if (FILES.containsKey(path)) {
            body = resource(FILES.get(path));
            rsp.setHeader("Cache-Control", "no-cache");
        }
        if (body == null) {
            rsp.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        rsp.setHeader("Access-Control-Allow-Origin", "*");
        rsp.setContentType("text/javascript;charset=UTF-8");
        rsp.getOutputStream().write(body);
    }

    @CheckForNull
    private static byte[] resource(String name) throws IOException {
        PluginWrapper plugin = Jenkins.get().getPluginManager().getPlugin("build-ui");
        if (plugin == null) {
            return null;
        }
        try (InputStream in = new URL(plugin.baseResourceURL, name).openStream()) {
            return in.readAllBytes();
        }
    }
}
