package io.jenkins.plugins.buildui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.ParametersAction;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.PasswordParameterDefinition;
import hudson.model.StringParameterValue;
import hudson.util.Secret;
import java.io.StringReader;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.transform.Source;
import javax.xml.transform.stream.StreamSource;
import org.htmlunit.Page;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.JenkinsSessionExtension;

/** The parameter outside the declarative-pipeline happy path: freestyle jobs, restarts, history. */
class SurfacesTest {

    @RegisterExtension
    private final JenkinsSessionExtension sessions = new JenkinsSessionExtension();

    private static UiParameterDefinition definition() {
        UiParameterDefinition d = new UiParameterDefinition("CFG");
        d.setModule("export default ({ root }) => { root.textContent = 'hi' }");
        d.setSchema("{\"type\":\"object\",\"required\":[\"n\"],\"properties\":{\"n\":{\"type\":\"integer\",\"maximum\":3}}}");
        d.setDefaultJson("{\"n\":1}");
        return d;
    }

    @Test
    void worksInAFreestyleJobAndItsConfigurationPageRoundTrips() throws Throwable {
        sessions.then(j -> {
            FreeStyleProject p = j.createFreeStyleProject("free");
            p.addProperty(new ParametersDefinitionProperty(definition()));
            // Saving the configuration page unchanged must keep every field.
            j.configRoundtrip(p);
            UiParameterDefinition after = (UiParameterDefinition) p.getProperty(ParametersDefinitionProperty.class).getParameterDefinition("CFG");
            j.assertEqualDataBoundBeans(definition(), after);

            FreeStyleBuild b = j.assertBuildStatusSuccess(p.scheduleBuild2(0, new ParametersAction(new StringParameterValue("CFG", "{\"n\":2}"))));
            assertEquals("{\"n\":2}", b.getAction(ParametersAction.class).getParameter("CFG").getValue());
            assertEquals("{\"n\":2}", b.getEnvironment(j.createTaskListener()).get("CFG"));
        });
    }

    @Test
    void definitionSurvivesARestartAndTheEmptiedStoreRefillsWhenTheFormRenders() throws Throwable {
        sessions.then(j -> {
            FreeStyleProject p = j.createFreeStyleProject("free");
            p.addProperty(new ParametersDefinitionProperty(definition()));
        });
        sessions.then(j -> {
            FreeStyleProject p = j.jenkins.getItemByFullName("free", FreeStyleProject.class);
            UiParameterDefinition d = (UiParameterDefinition) p.getProperty(ParametersDefinitionProperty.class).getParameterDefinition("CFG");
            // The compiled schema is transient; it must come back after loading from disk.
            assertEquals(1, d.errorsFor("{\"n\":9}").size());

            // The store lives in memory, so a restart empties it; rendering the form fills it again.
            ContentStore.clear();
            JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false);
            wc.getOptions().setJavaScriptEnabled(false);
            String html = wc.goTo("job/free/build?delay=0sec").getWebResponse().getContentAsString();
            Matcher m = Pattern.compile("data-module=\"([0-9a-f]{64}\\.mjs)\"").matcher(html);
            assertTrue(m.find(), "no module key on the form");
            assertEquals(200, wc.goTo("build-ui/" + m.group(1), null).getWebResponse().getStatusCode());
        });
    }

    @Test
    void aRuntimeFromConfigXmlIsCheckedLikeOneFromTheForm() throws Throwable {
        sessions.then(j -> {
            // config.xml (REST, CLI) bypasses the setter. host.js imports runtimes/<id>.js, so a path
            // there would load any same-origin script, and a form with no code skips approval.
            BuildUiConfiguration.get().setRequireApproval(true);
            FreeStyleProject p = j.createFreeStyleProject("free");
            UiParameterDefinition d = new UiParameterDefinition("CFG");
            d.setSchema("{\"type\":\"object\"}");
            p.addProperty(new ParametersDefinitionProperty(d));
            String xml = p.getConfigFile().asString();
            assertTrue(xml.contains("<runtime>preact11-htm3</runtime>"), xml);
            p.updateByXml((Source) new StreamSource(new StringReader(
                    xml.replace("<runtime>preact11-htm3</runtime>", "<runtime>../../../../job/free/ws/evil</runtime>"))));

            UiParameterDefinition loaded = (UiParameterDefinition) p.getProperty(ParametersDefinitionProperty.class).getParameterDefinition("CFG");
            assertEquals(Runtimes.PREACT, loaded.getRuntime());
            JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false);
            wc.getOptions().setJavaScriptEnabled(false);
            String html = wc.goTo("job/free/build?delay=0sec").getWebResponse().getContentAsString();
            assertFalse(html.contains("evil"), "the stored path reached the page");
            assertTrue(html.contains("data-runtime=\"preact11-htm3\""), "form missing");
        });
    }

    @Test
    void anInvalidDefaultKeepsTheRestApiWorkingAndStillStartsNoBuild() throws Throwable {
        sessions.then(j -> {
            FreeStyleProject p = j.createFreeStyleProject("free");
            UiParameterDefinition d = definition();
            // Valid JSON, but past the schema's maximum, as after a schema change.
            d.setDefaultJson("{\"n\":9}");
            p.addProperty(new ParametersDefinitionProperty(d));

            // The default is exported; one stale default must not turn the job's whole API into a 500.
            Page api = j.createWebClient().goTo("job/free/api/json?depth=1", "application/json");
            assertTrue(api.getWebResponse().getContentAsString().contains("\"name\":\"CFG\""), api.getWebResponse().getContentAsString());
            // A build that would use the default is still refused.
            assertNull(p.scheduleBuild2(0));
            assertNull(p.getLastBuild());
        });
    }

    @Test
    void jenkinsRunsReturnsPastFormValuesButNeverOtherParameters() throws Throwable {
        sessions.then(j -> {
            WorkflowJob job = j.createProject(WorkflowJob.class, "hist");
            job.setDefinition(new CpsFlowDefinition("echo 'ok'", true));
            job.addProperty(new ParametersDefinitionProperty(definition(),
                    new PasswordParameterDefinition("TOKEN", Secret.fromString("s3cret"), "")));
            j.buildAndAssertSuccess(job);

            JenkinsRule.WebClient wc = j.createWebClient();
            String withConfig = query(wc, "{\"job\":\"hist\",\"parameter\":\"CFG\"}");
            assertTrue(withConfig.contains("\"config\":{\"n\":1}"), withConfig);
            // Asking for a password parameter by name gives null, not the secret.
            String password = query(wc, "{\"job\":\"hist\",\"parameter\":\"TOKEN\"}");
            assertTrue(password.contains("\"config\":null"), password);
            assertTrue(!password.contains("s3cret"), password);
            assertTrue(!query(wc, "{\"job\":\"hist\"}").contains("config"), "config without the parameter argument");
        });
    }

    private static String query(JenkinsRule.WebClient wc, String args) throws Exception {
        Page page = wc.goTo("build-ui/query/jenkinsRuns?args=" + URLEncoder.encode(args, StandardCharsets.UTF_8), "application/json");
        return page.getWebResponse().getContentAsString();
    }
}
