package io.jenkins.plugins.buildui;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.Util;
import java.util.Arrays;
import java.util.List;
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
 * is not an HTML parser, but it reads comments and tags as the HTML tokenizer
 * does: where a comment ends, what starts a tag, quoted attribute values and
 * attributes separated by {@code /}. It checks the HTML that is left for inline
 * event handlers, the same rule as the Jenkinsfile checker. Every scan moves
 * forward, so the time is linear in the length of the text.
 */
final class SingleFileHtml {

    /** The parts; each is null when the HTML has none of it. */
    record Parts(@CheckForNull String module, @CheckForNull String html, @CheckForNull String css) {}

    static final Parts NONE = new Parts(null, null, null);

    private static final Pattern HANDLER = Pattern.compile("on[a-z]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern SRC = Pattern.compile("(?:^|[\\s/])src(?:\\s*=|[\\s/]|$)", Pattern.CASE_INSENSITIVE);
    private static final Pattern MODULE_TYPE = Pattern.compile(
            "(?:^|[\\s/])type\\s*=\\s*(?:\"\\s*module\\s*\"|'\\s*module\\s*'|module(?=[\\s/]|$))", Pattern.CASE_INSENSITIVE);

    /**
     * Elements whose content HTML reads as text up to their end tag, while inside
     * SVG or MathML the same content is markup. The handler check reads it as
     * markup and refuses a tag or comment that hides the end tag, so both
     * readings see the same tags.
     */
    private static final List<String> TEXT_ELEMENTS = List.of("textarea", "title", "noscript", "iframe", "noembed", "noframes", "xmp");

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
            Construct tag = Construct.at(html, lt);
            if (tag.end() < 0) {
                // The text ends inside a tag; the browser drops it.
                rest.append(html, lt, n);
                break;
            }
            boolean script = "script".equals(tag.startTag());
            if (!script && !"style".equals(tag.startTag())) {
                rest.append(html, lt, tag.end());
                i = tag.end();
                continue;
            }
            String name = tag.startTag();
            if (rest.length() > 0 && rest.charAt(rest.length() - 1) == '<') {
                throw new IllegalArgumentException(what + " has a '<' right before <" + name + ">; taking the element out would join it"
                        + " to the text after the element, so write it as &lt;");
            }
            int gt = tag.end() - 1;
            String startTag = html.substring(lt, gt + 1);
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
        String handler = firstHandler(markup, what);
        if (handler != null) {
            throw new IllegalArgumentException(what + " uses the inline handler " + handler
                    + "=; Jenkins's CSP blocks it, attach the handler in the module");
        }
        return new Parts(Util.fixEmptyAndTrim(module) == null ? null : module,
                Util.fixEmptyAndTrim(markup) == null ? null : markup,
                css == null || Util.fixEmptyAndTrim(css.toString()) == null ? null : css.toString());
    }

    /** The first {@code on*=} attribute of a start tag in the markup the browser gets, or null. */
    @CheckForNull
    private static String firstHandler(String html, String what) {
        TextEnds ends = new TextEnds(html);
        int n = html.length();
        int i = 0;
        while (i < n) {
            int lt = html.indexOf('<', i);
            if (lt < 0) {
                break;
            }
            Construct c = Construct.at(html, lt);
            int end = c.end() < 0 ? n : c.end();
            String hidden = ends.crossedBy(lt, end);
            if (hidden != null) {
                throw new IllegalArgumentException(what + " has </" + hidden + " inside a tag or comment; a browser ends the <" + hidden
                        + "> there and reads what follows as markup, so move it out of the tag or comment");
            }
            if (c.handler() != null) {
                return c.handler();
            }
            if (c.startTag() != null && TEXT_ELEMENTS.contains(c.startTag())) {
                ends.open(c.startTag(), end);
            }
            i = end;
        }
        return null;
    }

    /**
     * The markup that starts at a {@code <}, read as the HTML tokenizer reads it.
     * {@code end} is where it stops (exclusive), -1 when the text ends inside a
     * tag, and the {@code <} plus one when the {@code <} is text. A start tag
     * has its lowercase name and its first {@code on*=} attribute.
     */
    private record Construct(int end, @CheckForNull String startTag, @CheckForNull String handler) {

        static Construct at(String html, int lt) {
            int n = html.length();
            char next = lt + 1 < n ? html.charAt(lt + 1) : 0;
            if (isAsciiLetter(next)) {
                return tag(html, lt + 1, true);
            }
            if (html.startsWith("<!--", lt)) {
                // "<!-->" and "<!--->" are whole comments, and "--!>" ends one too.
                int dashes = html.indexOf("-->", lt + 2);
                int bang = html.indexOf("--!>", lt + 4);
                int end = dashes >= 0 && (bang < 0 || dashes < bang) ? dashes + 3 : bang >= 0 ? bang + 4 : n;
                return new Construct(end, null, null);
            }
            if (next == '/' && lt + 2 < n && isAsciiLetter(html.charAt(lt + 2))) {
                return tag(html, lt + 2, false);
            }
            if (next == '!' || next == '?' || next == '/') {
                // A doctype or a bogus comment, "</>" included: it ends at the first '>'.
                int gt = html.indexOf('>', lt + 2);
                return new Construct(gt < 0 ? n : gt + 1, null, null);
            }
            return new Construct(lt + 1, null, null);
        }

        /** A tag from its name at {@code at} to its '>': a quoted value may hold '>', and '/' separates attributes. */
        private static Construct tag(String html, int at, boolean start) {
            int n = html.length();
            int i = at;
            while (i < n && !isSpace(html.charAt(i)) && html.charAt(i) != '/' && html.charAt(i) != '>') {
                i++;
            }
            String name = asciiLowerCase(html.substring(at, i));
            String handler = null;
            while (i < n) {
                char c = html.charAt(i);
                if (c == '>') {
                    return start ? new Construct(i + 1, name, handler) : new Construct(i + 1, null, null);
                }
                if (isSpace(c) || c == '/') {
                    i++;
                    continue;
                }
                // An attribute name runs to a space, '/', '>' or '='; a '=' in first place belongs to it.
                int nameStart = i++;
                while (i < n && !isSpace(html.charAt(i)) && "/>=".indexOf(html.charAt(i)) < 0) {
                    i++;
                }
                String attribute = html.substring(nameStart, i);
                while (i < n && isSpace(html.charAt(i))) {
                    i++;
                }
                if (i >= n || html.charAt(i) != '=') {
                    continue;
                }
                if (handler == null && HANDLER.matcher(attribute).matches()) {
                    handler = attribute;
                }
                i++;
                while (i < n && isSpace(html.charAt(i))) {
                    i++;
                }
                if (i < n && (html.charAt(i) == '"' || html.charAt(i) == '\'')) {
                    int quote = html.indexOf(html.charAt(i), i + 1);
                    if (quote < 0) {
                        return new Construct(-1, null, null);
                    }
                    // The next attribute may follow the quote without a space.
                    i = quote + 1;
                } else {
                    while (i < n && !isSpace(html.charAt(i)) && html.charAt(i) != '>') {
                        i++;
                    }
                }
            }
            return new Construct(-1, null, null);
        }
    }

    /**
     * Where HTML ends each open text element: at the first {@code </name}
     * followed by a space, '/' or '>'. Each name's search only moves forward.
     */
    private static final class TextEnds {
        private final String html;
        private final int[] end = new int[TEXT_ELEMENTS.size()];
        private final int[] searched = new int[TEXT_ELEMENTS.size()];
        private final boolean[] open = new boolean[TEXT_ELEMENTS.size()];

        TextEnds(String html) {
            this.html = html;
            Arrays.fill(end, -1);
        }

        void open(String name, int from) {
            int k = TEXT_ELEMENTS.indexOf(name);
            if (end[k] < from && searched[k] < html.length()) {
                end[k] = -1;
                int j = Math.max(from, searched[k]);
                while ((j = html.indexOf("</", j)) >= 0) {
                    int after = j + 2 + name.length();
                    if (after < html.length() && regionIsAsciiLowerCase(html, j + 2, name)
                            && (isSpace(html.charAt(after)) || html.charAt(after) == '/' || html.charAt(after) == '>')) {
                        end[k] = j;
                        break;
                    }
                    j += 2;
                }
                searched[k] = end[k] < 0 ? html.length() : end[k];
            }
            open[k] = end[k] >= 0;
        }

        /** The name of an open text element whose end lies inside [start, stop), past start; closes those that end at or before start. */
        @CheckForNull
        String crossedBy(int start, int stop) {
            for (int k = 0; k < open.length; k++) {
                if (!open[k]) {
                    continue;
                }
                if (end[k] <= start) {
                    open[k] = false;
                } else if (end[k] < stop) {
                    return TEXT_ELEMENTS.get(k);
                }
            }
            return null;
        }
    }

    private static boolean isSpace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\f' || c == '\r';
    }

    private static boolean isAsciiLetter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    private static String asciiLowerCase(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            b.append(c >= 'A' && c <= 'Z' ? (char) (c + 32) : c);
        }
        return b.toString();
    }

    /** Whether {@code lower} stands at {@code at}, ignoring ASCII case only, as HTML compares tag names. */
    private static boolean regionIsAsciiLowerCase(String text, int at, String lower) {
        if (at + lower.length() > text.length()) {
            return false;
        }
        for (int i = 0; i < lower.length(); i++) {
            char c = text.charAt(at + i);
            if ((c >= 'A' && c <= 'Z' ? (char) (c + 32) : c) != lower.charAt(i)) {
                return false;
            }
        }
        return true;
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
