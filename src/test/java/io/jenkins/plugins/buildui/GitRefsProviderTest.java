package io.jenkins.plugins.buildui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.Item;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import jenkins.model.Jenkins;
import org.htmlunit.Page;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class GitRefsProviderTest {

    @TempDir
    static Path repo;

    @BeforeAll
    static void allowLocalCheckout() throws Exception {
        // The Git plugin refuses file: URLs unless told otherwise (SECURITY-2478); only this test needs one.
        System.setProperty("hudson.plugins.git.GitSCM.ALLOW_LOCAL_CHECKOUT", "true");
        git("init", "-q", "-b", "main");
        Files.writeString(repo.resolve("README"), "x");
        git("add", "README");
        git("-c", "user.name=t", "-c", "user.email=t@example.invalid", "commit", "-q", "-m", "first");
        git("branch", "feature/login");
        git("tag", "v1.0");
        git("tag", "-a", "-m", "release", "v1.1");
    }

    private static void git(String... args) throws Exception {
        List<String> cmd = new java.util.ArrayList<>(List.of("git", "-C", repo.toString()));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).inheritIO().start();
        assertEquals(0, p.waitFor(), String.join(" ", cmd));
    }

    @Test
    void listsBranchesAndTagsOfTheRepositoryTheJobUses(JenkinsRule j) throws Exception {
        WorkflowJob job = j.createProject(WorkflowJob.class, "app");
        job.setDefinition(new CpsFlowDefinition("node { git url: '" + repo.toUri() + "', branch: 'main' }", true));
        j.buildAndAssertSuccess(job);

        String body = query(j.createWebClient(), "{\"job\":\"app\"}").getWebResponse().getContentAsString();
        assertTrue(body.contains("{\"name\":\"feature/login\""), body);
        assertTrue(body.contains("{\"name\":\"main\""), body);
        assertTrue(body.contains("{\"name\":\"v1.0\""), body);
        assertTrue(body.contains("{\"name\":\"v1.1\""), body);
        assertTrue(!body.contains("^{}"), "peeled tag refs leak into the list: " + body);
    }

    @Test
    void refusesUsersWithoutBuildPermissionBecauseEachCallContactsTheRemote(JenkinsRule j) throws Exception {
        WorkflowJob job = j.createProject(WorkflowJob.class, "app");
        job.setDefinition(new CpsFlowDefinition("node { git url: '" + repo.toUri() + "', branch: 'main' }", true));
        j.buildAndAssertSuccess(job);
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.READ, Item.READ).everywhere().to("reader")
                .grant(Jenkins.READ, Item.READ, Item.BUILD).everywhere().to("builder"));

        JenkinsRule.WebClient reader = j.createWebClient().withThrowExceptionOnFailingStatusCode(false).login("reader");
        Page denied = reader.goTo("build-ui/query/gitRefs?args=" + URLEncoder.encode("{\"job\":\"app\"}", StandardCharsets.UTF_8), null);
        assertEquals(403, denied.getWebResponse().getStatusCode());
        JenkinsRule.WebClient builder = j.createWebClient().login("builder");
        assertTrue(query(builder, "{\"job\":\"app\"}").getWebResponse().getContentAsString().contains("feature/login"));
    }

    @Test
    void explainsWhenTheJobHasNotCheckedOutAnythingYet(JenkinsRule j) throws Exception {
        j.createProject(WorkflowJob.class, "fresh");
        Page page = query(j.createWebClient().withThrowExceptionOnFailingStatusCode(false), "{\"job\":\"fresh\"}");
        assertEquals(400, page.getWebResponse().getStatusCode());
        assertTrue(page.getWebResponse().getContentAsString().contains("uses no Git repository yet"));
    }

    private static Page query(JenkinsRule.WebClient wc, String args) throws Exception {
        return wc.goTo("build-ui/query/gitRefs?args=" + URLEncoder.encode(args, StandardCharsets.UTF_8), "application/json");
    }
}
