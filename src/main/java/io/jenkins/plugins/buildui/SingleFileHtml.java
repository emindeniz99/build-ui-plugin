package io.jenkins.plugins.buildui;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.Util;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits a form's HTML so that one file can hold the whole form: the content of
 * one {@code <script type="module">} becomes the module, every {@code <style>}
 * becomes CSS, and the rest stays HTML. No browser loads HTML modules, and
 * Jenkins's CSP blocks inline scripts, so the controller does the split and the
 * page gets the parts as if they were separate fields.
 *
 * <p>HTML ends a script at the first {@code </script} and a style at the first
 * {@code </style}, whatever comes before, so this cuts at those end tags too. It
 * is not an HTML parser: it skips comments, finds those two elements and checks
 * start tags for inline event handlers, the same rule as the Jenkinsfile checker.
 * Every scan moves forward, so the time is linear in the length of the text.
 */
final class SingleFileHtml {

    /** The parts; each is null when the HTML has none of it. */
    record Parts(@CheckForNull String module, @CheckForNull String html, @CheckForNull String css) {}

    static final Parts NONE = new Parts(null, null, null);

    private static final Pattern HANDLER = Pattern.compile("\\s(on[a-z]+)\\s*=", Pattern.CASE_INSENSITIVE);
    private static final Pattern SRC = Pattern.compile("(?:^|[\\s/])src(?:\\s*=|[\\s/]|$)", Pattern.CASE_INSENSITIVE);
    private static final Pattern MODULE_TYPE = Pattern.compile(
            "(?:^|[\\s/])type\\s*=\\s*(?:\"\\s*module\\s*\"|'\\s*module\\s*'|module(?=[\\s/]|$))", Pattern.CASE_INSENSITIVE);

    private SingleFileHtml() {}

    /** Splits {@code html}; {@code what} names it in errors, such as {@code html} or {@code form.html}. */
    static Parts split(@CheckForNull String html, String what) {
        if (html == null) {
            return NONE;
        }
        StringBuilder rest = new StringBuilder();
        StringBuilder css = null;
        String module = null;
        int n = html.length();
        int i = 0;
        while (i < n) {
            int lt = html.indexOf('<', i);
            if (lt < 0) {
                rest.append(html, i, n);
                break;
            }
            rest.append(html, i, lt);
            if (html.startsWith("<!--", lt)) {
                int end = html.indexOf("-->", lt + 4);
                end = end < 0 ? n : end + 3;
                rest.append(html, lt, end);
                i = end;
                continue;
            }
            int gt = html.indexOf('>', lt);
            if (gt < 0) {
                rest.append(html, lt, n);
                break;
            }
            String startTag = html.substring(lt, gt + 1);
            if (lt + 1 < n && Character.isLetter(html.charAt(lt + 1))) {
                Matcher handler = HANDLER.matcher(startTag);
                if (handler.find()) {
                    throw new IllegalArgumentException(what + " uses the inline handler " + handler.group(1)
                            + "=; Jenkins's CSP blocks it, attach the handler in the module");
                }
            }
            boolean script = isTag(html, lt, "script");
            if (!script && !isTag(html, lt, "style")) {
                rest.append(startTag);
                i = gt + 1;
                continue;
            }
            String name = script ? "script" : "style";
            int close = indexOfIgnoreCase(html, "</" + name, gt + 1);
            if (close < 0) {
                throw new IllegalArgumentException(what + " has a <" + name + "> without </" + name + ">");
            }
            String content = html.substring(gt + 1, close);
            int closeEnd = html.indexOf('>', close);
            i = closeEnd < 0 ? n : closeEnd + 1;
            if (!script) {
                css = css == null ? new StringBuilder(content) : css.append('\n').append(content);
                continue;
            }
            String attributes = startTag.substring("<script".length(), startTag.length() - 1);
            if (SRC.matcher(attributes).find()) {
                throw new IllegalArgumentException(what + " has a <script src=...>; a form loads no other code, put the code in the module script");
            }
            if (!MODULE_TYPE.matcher(attributes).find()) {
                throw new IllegalArgumentException(what + " has a <script> without type=\"module\"; Jenkins's CSP blocks inline scripts,"
                        + " so write the form's code as one <script type=\"module\"> that exports default function mount");
            }
            if (module != null) {
                throw new IllegalArgumentException(what + " has more than one <script type=\"module\">; a form has one module");
            }
            module = content;
        }
        String markup = rest.toString();
        return new Parts(Util.fixEmptyAndTrim(module) == null ? null : module,
                Util.fixEmptyAndTrim(markup) == null ? null : markup,
                css == null || Util.fixEmptyAndTrim(css.toString()) == null ? null : css.toString());
    }

    /** Whether a {@code <name} start tag begins at {@code lt}: the name, then a space, {@code /}, {@code >} or the end. */
    private static boolean isTag(String html, int lt, String name) {
        int after = lt + 1 + name.length();
        if (!html.regionMatches(true, lt + 1, name, 0, name.length())) {
            return false;
        }
        if (after >= html.length()) {
            return true;
        }
        char c = html.charAt(after);
        return Character.isWhitespace(c) || c == '/' || c == '>';
    }

    private static int indexOfIgnoreCase(String text, String needle, int from) {
        for (int i = from; i + needle.length() <= text.length(); i++) {
            if (text.regionMatches(true, i, needle, 0, needle.length())) {
                return i;
            }
        }
        return -1;
    }

    /** {@code a} then {@code b} on a new line; either may be null. */
    @CheckForNull
    static String join(@CheckForNull String a, @CheckForNull String b) {
        if (a == null) {
            return b;
        }
        return b == null ? a : a + "\n" + b;
    }
}
