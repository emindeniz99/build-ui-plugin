package io.jenkins.plugins.buildui;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.ExtensionList;
import hudson.model.Job;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jenkinsci.plugins.scriptsecurity.scripts.ApprovalContext;
import org.jenkinsci.plugins.scriptsecurity.scripts.Language;
import org.jenkinsci.plugins.scriptsecurity.scripts.ScriptApproval;

/**
 * Optional approval of form code, off by default. When an admin turns on
 * {@link BuildUiConfiguration#isRequireApproval()}, a form or panel with a
 * module, HTML or CSS is drawn only after its exact code is approved under
 * Manage Jenkins » In-process Script Approval. Until then the page shows the
 * JSON textarea, which the controller still validates.
 */
public final class FormApproval {

    private FormApproval() {}

    /**
     * Whether this code may run in the browser. Unapproved code is added to the
     * pending list, one entry per form: a newer version of a form replaces its
     * older pending entry.
     */
    static boolean allowed(String runtime, @CheckForNull String module, @CheckForNull String html,
            @CheckForNull String css, @CheckForNull Job<?, ?> job, String form) {
        if (!BuildUiConfiguration.get().isRequireApproval() || (module == null && html == null && css == null)) {
            return true;
        }
        String text = text(runtime, module, html, css);
        ScriptApproval approval = ScriptApproval.get();
        Language language = ExtensionList.lookupSingleton(FormLanguage.class);
        if (approval.isScriptApproved(text, language)) {
            return true;
        }
        if (approval.isForceSandboxForCurrentUser()) {
            // configuring() would list nothing for this viewer and drop the form's
            // pending entry, and remembering the hash would stop an admin's view
            // from listing it later.
            return false;
        }
        String key = "build-ui:" + (job == null ? "" : job.getFullName()) + "#" + form;
        String hash = ContentStore.sha256(text.getBytes(StandardCharsets.UTF_8));
        // configuring() saves to disk on every call, so a page view submits a
        // form's code once. Denied code is not offered again until it changes.
        synchronized (SUBMITTED) {
            if (hash.equals(SUBMITTED.get(key))) {
                return false;
            }
            SUBMITTED.put(key, hash);
        }
        ApprovalContext context = ApprovalContext.create().withKey(key);
        if (job != null) {
            context = context.withItem(job);
        }
        // Returns approved without asking when Jenkins security is off, as for Groovy scripts.
        approval.configuring(text, language, context, false);
        return approval.isScriptApproved(text, language);
    }

    /** Form key to the hash of the code last submitted for it. */
    private static final Map<String, String> SUBMITTED = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > ContentStore.CAPACITY;
        }
    };

    /** Forgets what was submitted, as a controller restart does. For tests. */
    static void forget() {
        synchronized (SUBMITTED) {
            SUBMITTED.clear();
        }
    }

    /** The text an admin reviews: everything the browser would load for this form. */
    static String text(String runtime, @CheckForNull String module, @CheckForNull String html, @CheckForNull String css) {
        StringBuilder b = new StringBuilder("// build-ui form, runtime ").append(runtime).append('\n');
        if (module != null) {
            b.append("// ---- module ----\n").append(module).append('\n');
        }
        if (html != null) {
            b.append("// ---- html ----\n").append(html).append('\n');
        }
        if (css != null) {
            b.append("// ---- css ----\n").append(css).append('\n');
        }
        return b.toString();
    }

    @Extension
    public static final class FormLanguage extends Language {
        @NonNull
        @Override
        public String getName() {
            return "build-ui";
        }

        @NonNull
        @Override
        public String getDisplayName() {
            return "Build UI form";
        }
    }
}
