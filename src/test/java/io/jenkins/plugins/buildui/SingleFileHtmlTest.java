package io.jenkins.plugins.buildui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * One file can hold a whole form, but the page can only run a module from its
 * own URL (Jenkins's CSP blocks inline scripts), so the controller splits the
 * file. Anything that would silently not run in the browser must fail here,
 * when the form is defined or read.
 */
class SingleFileHtmlTest {

    static final String FORM = """
            <style>.a { color: red }</style>
            <p class="a">Hello</p>
            <script type="module">
            export default function mount({ root }) { root.querySelector('p').textContent = 'hi' }
            </script>
            <STYLE media="screen">.b { color: blue }</STYLE>
            """;

    @Test
    void splitsTheModuleTheStylesAndTheMarkup() {
        SingleFileHtml.Parts p = SingleFileHtml.split(FORM, "html");
        assertEquals("\nexport default function mount({ root }) { root.querySelector('p').textContent = 'hi' }\n", p.module());
        assertEquals(".a { color: red }\n.b { color: blue }", p.css());
        assertEquals("\n<p class=\"a\">Hello</p>\n\n\n", p.html());
    }

    @Test
    void tagNamesAndTheModuleTypeAreCaseInsensitiveAsInHtml() {
        SingleFileHtml.Parts p = SingleFileHtml.split("<SCRIPT Type='MODULE'>export default () => {}</SCRIPT >", "html");
        assertEquals("export default () => {}", p.module());
        assertNull(p.html(), "nothing but the script: no markup");
        assertNull(p.css());
    }

    @Test
    void htmlWithoutScriptOrStyleStaysAsItIs() {
        String markup = "<custom-script>x</custom-script><p title='a'>b</p>";
        assertEquals(new SingleFileHtml.Parts(null, markup, null), SingleFileHtml.split(markup, "html"));
    }

    @Test
    void aScriptInsideAnHtmlCommentIsNotTheModule() {
        // The browser never runs it, so taking it as the module would run code the author commented out.
        SingleFileHtml.Parts p = SingleFileHtml.split("<!-- <script type=\"module\">old()</script> --><p>x</p>", "html");
        assertNull(p.module());
        assertTrue(p.html().contains("old()"));
    }

    @Test
    void aClassicScriptIsAnErrorBecauseJenkinsCspNeverRunsIt() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> SingleFileHtml.split("<p>x</p><script>go()</script>", "form.html"));
        assertTrue(e.getMessage().startsWith("form.html has a <script> without type=\"module\""), e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> SingleFileHtml.split("<script type=\"text/javascript\">go()</script>", "html"));
    }

    @Test
    void aScriptWithSrcIsAnErrorEvenAsAModuleBecauseItLoadsOtherCode() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> SingleFileHtml.split("<script type=\"module\" src=\"/userContent/x.mjs\"></script>", "html"));
        assertTrue(e.getMessage().contains("<script src=...>"), e.getMessage());
    }

    @Test
    void twoModuleScriptsAreAnErrorBecauseAFormHasOneModule() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> SingleFileHtml.split(
                "<script type=module>export default () => {}</script><script type=module>x()</script>", "html"));
        assertTrue(e.getMessage().contains("more than one <script type=\"module\">"), e.getMessage());
    }

    @Test
    void inlineHandlersAreAnErrorBecauseCspDropsThemSilently() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> SingleFileHtml.split("<button type=button onClick=\"go()\">Go</button>", "html"));
        assertEquals("html uses the inline handler onClick=; Jenkins's CSP blocks it, attach the handler in the module", e.getMessage());
        // Text and code may mention handlers; only attributes of elements count.
        SingleFileHtml.split("<p>use onclick= in the module</p><script type=module>el.onclick = go</script>", "html");
    }

    /** An {@code onerror} the browser runs, which the check must find however the markup around it is written. */
    static final String RUNS = "<img src=x onerror=alert(1)>";

    private static void assertHandlerRefused(String html) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> SingleFileHtml.split(html, "html"), html);
        assertEquals("html uses the inline handler onerror=; Jenkins's CSP blocks it, attach the handler in the module", e.getMessage(), html);
    }

    @Test
    void aCommentEndsWhereTheBrowserEndsItSoNoHandlerHidesBehindIt() {
        // Browsers end a comment at "<!-->", "<!--->" and "--!>", not only at "-->".
        assertHandlerRefused("<!-->" + RUNS + "-->");
        assertHandlerRefused("<!--->" + RUNS + "-->");
        assertHandlerRefused("<!-- old --!>" + RUNS + "-->");
        // A handler inside a real comment never runs.
        assertEquals("<!-- " + RUNS + " --><!----><p>x</p>", SingleFileHtml.split("<!-- " + RUNS + " --><!----><p>x</p>", "html").html());
    }

    @Test
    void attributesAreSeparatedAndQuotedAsTheBrowserReadsThem() {
        // A '/' separates attributes like a space, and so does the end of a quoted value.
        assertHandlerRefused("<img/onerror=alert(1) src=x>");
        assertHandlerRefused("<img src=\"x\"onerror=alert(1)>");
        // A '>' inside a quoted value does not end the tag.
        assertHandlerRefused("<img alt=\">\" src=x onerror=alert(1)>");
        assertHandlerRefused("<p title='>'></p>" + RUNS);
        // A '<' that starts no tag is text, so the tag after it counts.
        assertHandlerRefused("<3 " + RUNS);
        // A vertical tab is no space in HTML: this is one tag named a\u000btitle="x, and the img after it is a tag.
        assertHandlerRefused("<a\u000btitle=\"x>" + RUNS + "\">");
        // A handler's name inside a value is not an attribute.
        SingleFileHtml.split("<p title=\"onclick=go()\" data-x='a onclick=b'>x</p>", "html");
    }

    @Test
    void textElementsCannotHideTheirEndTagFromTheCheck() {
        // HTML reads <textarea> content as text up to </textarea>; inside SVG the same tags are markup.
        // Here HTML ends the textarea inside the title, and the img after it runs.
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> SingleFileHtml.split(
                "<textarea><p title=\"</textarea>" + RUNS + "\"></textarea>", "html"));
        assertTrue(e.getMessage().startsWith("html has </textarea inside a tag or comment"), e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> SingleFileHtml.split("<noscript><!-- </noscript>" + RUNS + " --></noscript>", "html"));
        // Taking a style out must not leave such an end tag inside a tag either.
        assertThrows(IllegalArgumentException.class, () -> SingleFileHtml.split(
                "<textarea><style></textarea></style><p title=\"</textarea>" + RUNS + "\">", "html"));
        // Inside SVG a <title> holds markup, and an img there leaves the SVG and runs.
        assertHandlerRefused("<svg><title>" + RUNS + "</title></svg>");
        SingleFileHtml.split("<svg><title>Icon</title></svg><textarea>a < b</textarea>", "html");
    }

    @Test
    void takingAScriptOutCannotJoinTextIntoANewTag() {
        // Removing the script would turn "<" + "img ..." into a tag the browser runs.
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> SingleFileHtml.split(
                "<<script type=module>export default () => {}</script>img src=x onerror=alert(1)>", "html"));
        assertTrue(e.getMessage().startsWith("html has a '<' right before <script>"), e.getMessage());
    }

    @Test
    void anUnclosedScriptIsAnError() {
        assertThrows(IllegalArgumentException.class, () -> SingleFileHtml.split("<script type=module>export default () => {}", "html"));
        assertThrows(IllegalArgumentException.class, () -> SingleFileHtml.split("<style>p {}", "html"));
    }

    @Test
    void timeStaysLinearOnTextMadeToMakeAScannerBacktrack() {
        // The Jenkinsfile author controls this text and the controller splits it on every definition.
        // A regex such as <[a-z][^>]*?\son[a-z]+= rescans to the end from every '<' when no '>' follows.
        String unclosed = "<a  ".repeat(130_000);
        String styles = "<style>p{}</style><b x=1>".repeat(20_000);
        // Every textarea looks for its end tag; the search must not start over for each one.
        String textareas = "<textarea></textareax>".repeat(20_000);
        long start = System.nanoTime();
        assertEquals(unclosed, SingleFileHtml.split(unclosed, "html").html());
        assertEquals(20_000 * 4 - 1, SingleFileHtml.split(styles, "html").css().length());
        assertEquals(textareas, SingleFileHtml.split(textareas, "html").html());
        long millis = (System.nanoTime() - start) / 1_000_000;
        assertTrue(millis < 2_000, millis + " ms");
    }

    @Test
    void formCodeAppendsSplitStylesAfterTheCssFieldAndRefusesTwoModules() {
        FormCode c = FormCode.of(null, FORM, ".z {}", null, null, false);
        assertEquals(".z {}\n.a { color: red }\n.b { color: blue }", c.css());
        assertTrue(c.module().contains("export default function mount"));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> FormCode.of("export default () => {}", FORM, null, null, null, true));
        assertEquals("form.html has a <script type=\"module\"> and the form has form.mjs too; keep one of them", e.getMessage());
    }
}
