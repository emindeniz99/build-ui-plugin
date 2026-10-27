package io.jenkins.plugins.buildui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.Result;
import hudson.model.Run;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class UiPanelStepTest {

    @Test
    void addsAPanelToTheBuildPageWithItsDataAndModule(JenkinsRule j) throws Exception {
        WorkflowJob job = j.createProject(WorkflowJob.class, "panel");
        job.setDefinition(new CpsFlowDefinition("""
                uiPanel title: 'Deploy summary', data: '{ "env": "dev", "services": ["api"] }',
                  module: 'export default function mount({ root, ui }) { root.textContent = ui.value.env }'
                """, true));
        WorkflowRun run = j.buildAndAssertSuccess(job);
        UiPanelAction panel = run.getAction(UiPanelAction.class);
        assertEquals("{\"env\":\"dev\",\"services\":[\"api\"]}", panel.getData());

        JenkinsRule.WebClient wc = j.createWebClient();
        wc.getOptions().setJavaScriptEnabled(false);
        String page = wc.getPage(run).getWebResponse().getContentAsString();
        assertTrue(page.contains("Deploy summary"), "panel title missing");
        assertTrue(page.contains("data-module=\"" + panel.getModuleKey() + "\""), "panel module missing");
    }

    @Test
    void aRuntimeReadFromBuildXmlIsCheckedLikeOneFromTheStep(JenkinsRule j) {
        // host.js imports runtimes/<id>.js, so a path stored in build.xml would load any same-origin script.
        String xml = Run.XSTREAM2.toXML(new UiPanelAction("t", Runtimes.NONE, null, null, null, "{}", false));
        assertTrue(xml.contains("<runtime>none</runtime>"), xml);
        UiPanelAction loaded = (UiPanelAction) Run.XSTREAM2.fromXML(xml.replace("<runtime>none</runtime>", "<runtime>../../ws/evil</runtime>"));
        assertEquals(Runtimes.PREACT, loaded.getRuntime());
        assertEquals("{}", loaded.getData());
    }

    @Test
    void rejectsDataThatIsNotJsonSoTheBuildFailsWhereTheMistakeIs(JenkinsRule j) throws Exception {
        WorkflowJob job = j.createProject(WorkflowJob.class, "bad");
        job.setDefinition(new CpsFlowDefinition("uiPanel title: 'x', data: '{oops'", true));
        j.assertLogContains("data is not valid JSON", j.buildAndAssertStatus(Result.FAILURE, job));
    }
}
