package io.jenkins.plugins.buildui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.Result;
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
    void rejectsDataThatIsNotJsonSoTheBuildFailsWhereTheMistakeIs(JenkinsRule j) throws Exception {
        WorkflowJob job = j.createProject(WorkflowJob.class, "bad");
        job.setDefinition(new CpsFlowDefinition("uiPanel title: 'x', data: '{oops'", true));
        j.assertLogContains("data is not valid JSON", j.buildAndAssertStatus(Result.FAILURE, job));
    }
}
