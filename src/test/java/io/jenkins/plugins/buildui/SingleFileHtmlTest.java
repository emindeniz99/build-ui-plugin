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
        long start = System.nanoTime();
        assertEquals(unclosed, SingleFileHtml.split(unclosed, "html").html());
        assertEquals(20_000 * 4 - 1, SingleFileHtml.split(styles, "html").css().length());
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
