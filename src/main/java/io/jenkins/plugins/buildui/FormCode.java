package io.jenkins.plugins.buildui;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.Util;
import io.jenkins.plugins.buildui.json.Json;
import io.jenkins.plugins.buildui.json.Schema;

/**
 * What one form sends to the browser and validates with, after a single-file
 * HTML is split ({@link SingleFileHtml}). Approval, the sandbox, the eval gate
 * and the content store see these parts, so a module written inside the HTML
 * counts exactly as one written in {@code module}.
 */
record FormCode(@CheckForNull String module, @CheckForNull String html, @CheckForNull String css,
        @CheckForNull String schemaText, @CheckForNull Schema schema, @CheckForNull String defaultJson) {

    /**
     * Splits and checks the parts; throws {@link IllegalArgumentException} with
     * the field or file name. {@code files} picks the names errors use: the
     * Jenkinsfile fields, or the file names of a {@code formFrom} folder.
     */
    static FormCode of(@CheckForNull String module, @CheckForNull String html, @CheckForNull String css,
            @CheckForNull String schema, @CheckForNull String defaultJson, boolean files) {
        String moduleName = files ? "form.mjs" : "module";
        String htmlName = files ? "form.html" : "html";
        String m = Util.fixEmptyAndTrim(module) == null ? null : module;
        SingleFileHtml.Parts parts = SingleFileHtml.split(Util.fixEmptyAndTrim(html) == null ? null : html, htmlName);
        if (m != null && parts.module() != null) {
            throw new IllegalArgumentException(htmlName + " has a <script type=\"module\"> and the form has " + moduleName
                    + " too; keep one of them");
        }
        String c = Util.fixEmptyAndTrim(css) == null ? null : css;
        String s = Util.fixEmptyAndTrim(schema);
        Schema compiled = s == null ? null : Schema.compile(Json.parse(s, files ? "schema.json" : "schema"));
        String d = Util.fixEmptyAndTrim(defaultJson);
        if (d != null) {
            Json.parse(d, files ? "default.json" : "defaultJson");
        }
        return new FormCode(m != null ? m : parts.module(), parts.html(), SingleFileHtml.join(c, parts.css()), s, compiled, d);
    }
}
