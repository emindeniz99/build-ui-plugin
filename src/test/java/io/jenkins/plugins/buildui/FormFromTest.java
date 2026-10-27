package io.jenkins.plugins.buildui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.ExtensionList;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.ParametersAction;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.Queue;
import hudson.model.Result;
import hudson.model.StringParameterValue;
import hudson.model.queue.QueueTaskFuture;
import hudson.plugins.git.BranchSpec;
import hudson.plugins.git.GitSCM;
import hudson.scm.SCM;
import hudson.scm.SCMDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.xml.transform.Source;
import javax.xml.transform.stream.StreamSource;
import jenkins.branch.BranchSource;
import jenkins.model.Jenkins;
import jenkins.plugins.git.GitSCMSource;
import jenkins.plugins.git.traits.BranchDiscoveryTrait;
import jenkins.scm.api.SCMFile;
import jenkins.scm.api.SCMFileSystem;
import jenkins.scm.api.SCMHead;
import jenkins.scm.api.SCMRevision;
import jenkins.scm.api.SCMSource;
import jenkins.scm.api.SCMSourceDescriptor;
import jenkins.scm.api.mixin.ChangeRequestCheckoutStrategy;
import jenkins.scm.impl.mock.MockChangeRequestFlags;
import jenkins.scm.impl.mock.MockChangeRequestSCMHead;
import jenkins.scm.impl.mock.MockChangeRequestSCMRevision;
import jenkins.scm.impl.mock.MockRepositoryFlags;
import jenkins.scm.impl.mock.MockSCMController;
import jenkins.scm.impl.mock.MockSCMDiscoverBranches;
import jenkins.scm.impl.mock.MockSCMDiscoverChangeRequests;
import jenkins.scm.impl.mock.MockSCMRevision;
import jenkins.scm.impl.mock.MockSCMSource;
import org.apache.commons.io.FileUtils;
import org.htmlunit.HttpMethod;
import org.htmlunit.Page;
import org.htmlunit.WebRequest;
import org.jenkinsci.plugins.scriptsecurity.scripts.ScriptApproval;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.cps.CpsScmFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * The owner's problem: a pushed change to a form did not show up until a
 * build ran the new Jenkinsfile, because Jenkins keeps parameter definitions
 * in the job's configuration. formFrom reads the form from the branch head
 * instead, so these tests push and look again without running a build.
 */
@WithJenkins
class FormFromTest {

    static final String JENKINSFILE = """
            properties([parameters([uiParameter(name: 'CFG', formFrom: 'forms/cfg')])])
            echo "CFG=${params.CFG}"
            """;

    @TempDir
    Path repo;

    private JenkinsRule j;

    @BeforeAll
    static void allowLocalRepositories() {
        // The Git plugin refuses file: URLs unless told otherwise (SECURITY-2478).
        System.setProperty("hudson.plugins.git.GitSCM.ALLOW_LOCAL_CHECKOUT", "true");
    }

    @BeforeEach
    void setUp(JenkinsRule j) throws Exception {
        this.j = j;
        FormFrom.clearCache();
        ContentStore.clear();
        FormApproval.forget();
        git("init", "-q", "-b", "main");
        write("Jenkinsfile", JENKINSFILE);
    }

    private void git(String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("git", "-C", repo.toString(), "-c", "user.name=t", "-c", "user.email=t@example.invalid"));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).inheritIO().start();
        assertEquals(0, p.waitFor(), String.join(" ", cmd));
    }

    private void write(String path, String text) throws IOException {
        Path file = repo.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }

    private void commit(String message) throws Exception {
        git("add", "-A");
        git("commit", "-q", "-m", message);
    }

    static String module(String text) {
        return "export default function mount({ root }) { root.textContent = '" + text + "' }";
    }

    private static String key(String text, String extension) {
        return ContentStore.sha256(text.getBytes(StandardCharsets.UTF_8)) + "." + extension;
    }

    private GitSCM scm() {
        return new GitSCM(GitSCM.createRepoList(repo.toUri().toString(), null), List.of(new BranchSpec("*/main")), null, null, List.of());
    }

    /** A Pipeline job defined by "Pipeline script from SCM", built once so its Jenkinsfile registers the parameter. */
    private WorkflowJob scmJob(String name) throws Exception {
        WorkflowJob job = j.createProject(WorkflowJob.class, name);
        job.setDefinition(new CpsScmFlowDefinition(scm(), "Jenkinsfile"));
        j.buildAndAssertSuccess(job);
        return job;
    }

    private String formPage(String path) throws Exception {
        return formPage(j.createWebClient(), path);
    }

    private static String formPage(JenkinsRule.WebClient wc, String path) throws Exception {
        wc.getOptions().setJavaScriptEnabled(false);
        wc.getOptions().setThrowExceptionOnFailingStatusCode(false);
        // The form page answers 405 to a GET on purpose; its body is still the form.
        return wc.goTo(path + "build?delay=0sec").getWebResponse().getContentAsString();
    }

    private Page buildWithParameters(String jobPath, String value) throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false).withRedirectEnabled(false);
        WebRequest req = new WebRequest(URI.create(j.getURL() + jobPath + "buildWithParameters?CFG="
                + URLEncoder.encode(value, StandardCharsets.UTF_8)).toURL(), HttpMethod.POST);
        return wc.getPage(wc.addCrumb(req));
    }

    // ---- (a) Pipeline from SCM ----------------------------------------------

    @Test
    void aPushToTheFormShowsUpOnBuildWithParametersWithoutABuild() throws Exception {
        write("forms/cfg/form.mjs", module("v1"));
        write("forms/cfg/form.html", "<p>v1</p>");
        commit("form v1");
        WorkflowJob job = scmJob("p");

        String page = formPage("job/p/");
        assertTrue(page.contains("data-module=\"" + key(module("v1"), "mjs") + "\""), page);
        assertTrue(page.contains("data-html=\"" + key("<p>v1</p>", "html.txt") + "\""));

        write("forms/cfg/form.mjs", module("v2"));
        write("forms/cfg/form.html", "<p>v2</p>");
        commit("form v2");
        // Within the cache window the page keeps the version it read.
        assertTrue(formPage("job/p/").contains(key(module("v1"), "mjs")), "the cache must spare the SCM a read per page view");

        FormFrom.clearCache(); // what the window's end does
        page = formPage("job/p/");
        assertTrue(page.contains("data-module=\"" + key(module("v2"), "mjs") + "\""), "the pushed module must show up without a build");
        assertTrue(page.contains("data-html=\"" + key("<p>v2</p>", "html.txt") + "\""));
        assertEquals(1, job.getLastBuild().getNumber(), "no build may have run");
        assertEquals(module("v2"), new String(ContentStore.get(key(module("v2"), "mjs")).bytes(), StandardCharsets.UTF_8));

        // Recorded in docs/design.md: what a read costs once the controller's Git cache exists.
        FormFrom.clearCache();
        long t0 = System.nanoTime();
        FormFrom.read(job, "forms/cfg");
        long t1 = System.nanoTime();
        FormFrom.read(job, "forms/cfg");
        long t2 = System.nanoTime();
        System.out.printf("formFrom read: %d ms from the repository, %d us from the cache%n", (t1 - t0) / 1_000_000, (t2 - t1) / 1_000);
    }

    // ---- (b) multibranch ----------------------------------------------------

    @Test
    void aMultibranchBranchJobReadsItsOwnBranchHead() throws Exception {
        write("forms/cfg/form.mjs", module("main-v1"));
        commit("form v1");
        WorkflowMultiBranchProject mp = j.createProject(WorkflowMultiBranchProject.class, "mb");
        GitSCMSource source = new GitSCMSource(repo.toUri().toString());
        source.setTraits(List.of(new BranchDiscoveryTrait()));
        mp.getSourcesList().add(new BranchSource(source));
        mp.scheduleBuild2(0).getFuture().get(60, TimeUnit.SECONDS);
        j.waitUntilNoActivity();
        WorkflowJob main = mp.getItem("main");
        assertNotNull(main, "branch indexing must create the main job");
        assertEquals(1, main.getLastBuild().getNumber());
        assertTrue(formPage("job/mb/job/main/").contains(key(module("main-v1"), "mjs")));

        write("forms/cfg/form.mjs", module("main-v2"));
        commit("form v2");
        FormFrom.clearCache();
        assertTrue(formPage("job/mb/job/main/").contains("data-module=\"" + key(module("main-v2"), "mjs") + "\""));
        assertEquals(1, main.getLastBuild().getNumber(), "no build may have run");
    }

    @Test
    void aChangeRequestFromAnUntrustedAuthorGetsTheFormOfTheTrustedRevisionLikeItsJenkinsfile() throws Exception {
        // A fork's change request must not put its own JavaScript in front of every viewer, nor pick
        // the defaults of the build its webhook starts. Pipeline loads that change request's
        // Jenkinsfile from the revision the source trusts, so the form must come from there too.
        try (MockSCMController c = MockSCMController.create()) {
            c.createRepository("repo", MockRepositoryFlags.TRUST_AWARE);
            c.addFile("repo", "master", "jenkinsfile", "Jenkinsfile", JENKINSFILE.getBytes(StandardCharsets.UTF_8));
            mockForm(c, "master", "trusted");
            Integer fork = c.openChangeRequest("repo", "master", MockChangeRequestFlags.UNTRUSTED);
            mockForm(c, "change-request/" + fork, "fork");
            Integer member = c.openChangeRequest("repo", "master");
            mockForm(c, "change-request/" + member, "member");

            WorkflowMultiBranchProject mp = j.createProject(WorkflowMultiBranchProject.class, "mb");
            mp.getSourcesList().add(new BranchSource(new MockSCMSource(c, "repo", new MockSCMDiscoverBranches(),
                    new MockSCMDiscoverChangeRequests(ChangeRequestCheckoutStrategy.HEAD))));
            mp.scheduleBuild2(0).getFuture().get(60, TimeUnit.SECONDS);
            j.waitUntilNoActivity();
            WorkflowJob master = mp.getItem("master");
            WorkflowJob untrusted = changeRequest(mp, fork);
            WorkflowJob trusted = changeRequest(mp, member);

            // No cache reset between the three: each job has its own entry and its own trust decision.
            assertTrue(formPage(untrusted.getUrl()).contains("data-module=\"" + key(module("trusted"), "mjs") + "\""),
                    "an untrusted change request must show the trusted revision's form.mjs");
            String trustedPage = formPage(trusted.getUrl());
            assertTrue(trustedPage.contains("data-module=\"" + key(module("member"), "mjs") + "\""),
                    "a trusted change request reads its own tip: " + trustedPage);
            assertTrue(formPage(master.getUrl()).contains("data-module=\"" + key(module("trusted"), "mjs") + "\""));
            assertNull(ContentStore.get(key(module("fork"), "mjs")), "the fork's module must never become servable");

            // The build the indexing started took default.json from the trusted revision as well.
            j.assertLogContains("CFG={\"who\":\"trusted\"}", untrusted.getLastBuild());
            j.assertLogContains("CFG={\"who\":\"member\"}", trusted.getLastBuild());
        }
    }

    private static void mockForm(MockSCMController c, String branch, String who) throws IOException {
        c.addFile("repo", branch, who + " form", "forms/cfg/form.mjs", module(who).getBytes(StandardCharsets.UTF_8));
        c.addFile("repo", branch, who + " default", "forms/cfg/default.json", ("{\"who\":\"" + who + "\"}").getBytes(StandardCharsets.UTF_8));
    }

    private static WorkflowJob changeRequest(WorkflowMultiBranchProject mp, Integer number) {
        for (WorkflowJob job : mp.getItems()) {
            if (SCMHead.HeadByItem.findHead(job) instanceof MockChangeRequestSCMHead head && head.getNumber().equals(number)) {
                return job;
            }
        }
        throw new AssertionError("no job for change request " + number + " among " + mp.getItems());
    }

    /**
     * scm-api's mock source has no file system of its own. This one checks the
     * requested revision out into a temporary folder, the way a lightweight
     * read of a real SCM reads exactly the revision it is given.
     */
    @TestExtension("aChangeRequestFromAnUntrustedAuthorGetsTheFormOfTheTrustedRevisionLikeItsJenkinsfile")
    public static final class MockFileSystems extends SCMFileSystem.Builder {
        @Override
        public boolean supports(SCM source) {
            return false;
        }

        @Override
        public boolean supports(SCMSource source) {
            return source instanceof MockSCMSource;
        }

        @Override
        protected boolean supportsDescriptor(SCMDescriptor descriptor) {
            return false;
        }

        @Override
        protected boolean supportsDescriptor(SCMSourceDescriptor descriptor) {
            return descriptor instanceof MockSCMSource.DescriptorImpl;
        }

        @Override
        public SCMFileSystem build(Item owner, SCM scm, SCMRevision rev) {
            return null;
        }

        @Override
        public SCMFileSystem build(SCMSource source, SCMHead head, SCMRevision rev) throws IOException {
            MockSCMSource mock = (MockSCMSource) source;
            String identifier;
            if (rev instanceof MockSCMRevision r) {
                identifier = r.getHash();
            } else if (rev instanceof MockChangeRequestSCMRevision r) {
                identifier = r.getHash();
            } else if (head instanceof MockChangeRequestSCMHead cr) {
                identifier = "change-request/" + cr.getNumber();
            } else {
                identifier = head.getName();
            }
            Path dir = Files.createTempDirectory("mock-scm");
            MockSCMController.lookup(mock.getControllerId()).checkout(dir.toFile(), mock.getRepository(), identifier);
            return new SCMFileSystem(rev) {
                @Override
                public long lastModified() {
                    return 0;
                }

                @Override
                public SCMFile getRoot() {
                    return new PathFile(dir);
                }

                @Override
                public void close() throws IOException {
                    FileUtils.deleteDirectory(dir.toFile());
                }
            };
        }
    }

    private static final class PathFile extends SCMFile {
        private final Path path;

        PathFile(Path root) {
            this.path = root;
        }

        private PathFile(PathFile parent, String name) {
            super(parent, name);
            this.path = parent.path.resolve(name);
        }

        @Override
        protected SCMFile newChild(String name, boolean assumeIsDirectory) {
            return new PathFile(this, name);
        }

        @Override
        public Iterable<SCMFile> children() throws IOException {
            try (Stream<Path> files = Files.list(path)) {
                return files.map(p -> (SCMFile) new PathFile(this, p.getFileName().toString())).toList();
            }
        }

        @Override
        public long lastModified() {
            return 0;
        }

        @Override
        protected Type type() {
            return Files.isDirectory(path) ? Type.DIRECTORY : Files.isRegularFile(path) ? Type.REGULAR_FILE : Type.NONEXISTENT;
        }

        @Override
        public InputStream content() throws IOException {
            return Files.newInputStream(path);
        }
    }

    // ---- (c) schema.json and default.json -----------------------------------

    @Test
    void valuesAreValidatedAgainstTheCommittedSchemaAndAPushedSchemaChangesThatWithoutABuild() throws Exception {
        write("forms/cfg/schema.json", "{\"type\":\"object\",\"properties\":{\"n\":{\"type\":\"integer\",\"maximum\":3}}}");
        write("forms/cfg/default.json", "{\"n\":2}");
        commit("schema");
        WorkflowJob job = scmJob("p");
        // The first build had no definition yet; this one takes default.json.
        j.assertLogContains("CFG={\"n\":2}", j.buildAndAssertSuccess(job));

        Page bad = buildWithParameters("job/p/", "{\"n\":5}");
        assertEquals(400, bad.getWebResponse().getStatusCode());
        assertTrue(bad.getWebResponse().getContentAsString().contains("/n: must be <= 3"), bad.getWebResponse().getContentAsString());
        String page = formPage("job/p/");
        assertTrue(page.contains("{&quot;n&quot;:2}") || page.contains("{\"n\":2}"), "the form must start from default.json");
        assertTrue(page.contains("&quot;maximum&quot;:3"), "ui.schema must be the committed schema");

        write("forms/cfg/schema.json", "{\"type\":\"object\",\"properties\":{\"n\":{\"type\":\"integer\",\"maximum\":10}}}");
        commit("allow up to 10");
        FormFrom.clearCache();
        assertEquals(2, job.getLastBuild().getNumber());
        assertEquals(201, buildWithParameters("job/p/", "{\"n\":5}").getWebResponse().getStatusCode());
        j.waitUntilNoActivity();
        j.assertLogContains("CFG={\"n\":5}", job.getLastBuild());

        // A value queued without a request is checked against the same schema before the build starts.
        QueueTaskFuture<WorkflowRun> tooBig = job.scheduleBuild2(0, new ParametersAction(new StringParameterValue("CFG", "{\"n\":50}")));
        assertNotNull(tooBig, "the queue-lock check cannot read the schema, so it must let RefGuard decide");
        assertThrows(CancellationException.class, () -> tooBig.get(60, TimeUnit.SECONDS));
        assertEquals(3, job.getLastBuild().getNumber());

        // A build step without the parameter gets default.json, read off the request path.
        WorkflowJob up = j.createProject(WorkflowJob.class, "up");
        up.setDefinition(new CpsFlowDefinition("build job: 'p'", true));
        j.buildAndAssertSuccess(up);
        j.assertLogContains("CFG={\"n\":2}", job.getLastBuild());
    }

    // ---- (d) unsupported job types ------------------------------------------

    @Test
    void anInlinePipelineOrAFreestyleJobShowsWhyAndRefusesValuesInsteadOfShowingStaleContent() throws Exception {
        WorkflowJob inline = j.createProject(WorkflowJob.class, "inline");
        inline.setDefinition(new CpsFlowDefinition(JENKINSFILE, true));
        // Reading params in the defining build asks the new parameter for its default, which needs the folder.
        j.assertLogContains("inline is neither", j.buildAndAssertStatus(Result.FAILURE, inline));
        String page = formPage("job/inline/");
        assertTrue(page.contains("This form could not be read"), page);
        assertTrue(page.contains("inline is neither"), page);
        assertFalse(page.contains("build-ui-root"), "no form may render without its files");
        Page refused = buildWithParameters("job/inline/", "{}");
        assertEquals(400, refused.getWebResponse().getStatusCode());
        assertTrue(refused.getWebResponse().getContentAsString().contains("'Pipeline script from SCM'"));

        FreeStyleProject free = j.createFreeStyleProject("free");
        UiParameterDefinition d = new UiParameterDefinition("CFG");
        d.setFormFrom("forms/cfg");
        free.addProperty(new ParametersDefinitionProperty(d));
        assertTrue(formPage("job/free/").contains("free is neither"));
        // A build that needs default.json must not start without it; whoever asked for it gets the reason.
        InvalidValueException e = assertThrows(InvalidValueException.class, () -> free.scheduleBuild2(0));
        assertTrue(e.getMessage().startsWith("No default for parameter 'CFG': formFrom works in"), e.getMessage());
        assertNull(free.getLastBuild());
    }

    @Test
    void theInputStepRefusesFormFromWithAReason() throws Exception {
        WorkflowJob job = j.createProject(WorkflowJob.class, "ask");
        job.setDefinition(new CpsFlowDefinition("input message: 'go', parameters: [uiParameter(name: 'CFG', formFrom: 'forms/cfg')]", true));
        WorkflowRun run = job.scheduleBuild2(0).waitForStart();
        j.waitForMessage("Input requested", run);
        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false);
        wc.getOptions().setJavaScriptEnabled(false);
        String page = wc.goTo("job/ask/1/input/").getWebResponse().getContentAsString();
        assertTrue(page.contains(FormFrom.UNSUPPORTED_INPUT), page);
        run.doStop();
        j.waitForCompletion(run);
    }

    // ---- (e) path validation ------------------------------------------------

    @Test
    void pathsThatLeaveTheRepositoryOrNeedQuotingAreRejectedWhenTheFormIsDefined() throws Exception {
        for (String bad : List.of("../secrets", "/etc", "forms/../../x", "forms/./x", "forms\\x", "forms//x", "forms/",
                "forms x", "forms/$x", "x".repeat(FormFrom.MAX_PATH_CHARS + 1))) {
            assertThrows(IllegalArgumentException.class, () -> FormFrom.checkPath(bad), bad);
        }
        FormFrom.checkPath("forms/deploy");
        FormFrom.checkPath(".jenkins/forms/a_b-1.v2");

        WorkflowJob job = j.createProject(WorkflowJob.class, "evil");
        job.setDefinition(new CpsFlowDefinition("properties([parameters([uiParameter(name: 'CFG', formFrom: '../secrets')])])", true));
        j.assertLogContains("must not contain '..'", j.buildAndAssertStatus(Result.FAILURE, job));
    }

    @Test
    void aBadPathFromConfigXmlIsShownAndRefusedRatherThanRead() throws Exception {
        // config.xml (REST, CLI) bypasses the setter, and readResolve must not throw.
        FreeStyleProject p = j.createFreeStyleProject("free");
        UiParameterDefinition d = new UiParameterDefinition("CFG");
        d.setFormFrom("forms/cfg");
        p.addProperty(new ParametersDefinitionProperty(d));
        String xml = p.getConfigFile().asString();
        p.updateByXml((Source) new StreamSource(new StringReader(xml.replace("<formFrom>forms/cfg</formFrom>", "<formFrom>../../secrets</formFrom>"))));
        assertTrue(formPage("job/free/").contains("must not contain '..'"));
        UiParameterDefinition loaded = (UiParameterDefinition) p.getProperty(ParametersDefinitionProperty.class).getParameterDefinition("CFG");
        assertEquals(List.of("formFrom '../../secrets' must not contain '..'"), loaded.errorsFor("{}"));
    }

    // ---- (f) inline fields and formFrom -------------------------------------

    @Test
    void inlineFieldsNextToFormFromAreRejectedWhicheverComesFirst() throws Exception {
        UiParameterDefinition d = new UiParameterDefinition("CFG");
        d.setModule(module("x"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> d.setFormFrom("forms/cfg"));
        assertTrue(e.getMessage().endsWith("must not set them too; remove module"), e.getMessage());
        assertNull(d.getFormFrom(), "a refused value must not stay set");

        UiParameterDefinition other = new UiParameterDefinition("CFG");
        other.setFormFrom("forms/cfg");
        assertThrows(IllegalArgumentException.class, () -> other.setSchema("{\"type\":\"object\"}"));
        assertThrows(IllegalArgumentException.class, () -> other.setDefaultJson("{}"));
        assertNull(other.getSchema());

        WorkflowJob job = j.createProject(WorkflowJob.class, "both");
        job.setDefinition(new CpsFlowDefinition(
                "properties([parameters([uiParameter(name: 'CFG', formFrom: 'forms/cfg', html: '<p>x</p>')])])", true));
        j.assertLogContains("remove html", j.buildAndAssertStatus(Result.FAILURE, job));
    }

    // ---- (g) approval -------------------------------------------------------

    @Test
    void withApprovalOnPushedCodeWaitsForAnAdminLikeInlineCode() throws Exception {
        BuildUiConfiguration.get().setRequireApproval(true);
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, Item.BUILD).everywhere().to("dev"));
        String v1 = "<p>x</p><script type=\"module\">" + module("v1") + "</script>";
        write("forms/cfg/form.html", v1);
        commit("single-file form v1");
        scmJob("p");

        JenkinsRule.WebClient dev = j.createWebClient().login("dev");
        assertTrue(formPage(dev, "job/p/").contains(FormApprovalTest.NOTICE));
        assertNull(ContentStore.get(key(module("v1"), "mjs")), "unapproved code must not be servable");
        List<ScriptApproval.PendingScript> waiting = pending();
        assertEquals(1, waiting.size());
        // The admin reviews the split parts, the same text inline fields would give.
        assertEquals(FormApproval.text(Runtimes.PREACT, module("v1"), "<p>x</p>", null, false), waiting.get(0).script);
        ScriptApproval.get().approveScript(waiting.get(0).getHash());
        assertTrue(formPage(dev, "job/p/").contains("data-module=\"" + key(module("v1"), "mjs") + "\""));

        write("forms/cfg/form.html", "<p>x</p><script type=\"module\">" + module("v2") + "</script>");
        commit("v2");
        FormFrom.clearCache();
        assertTrue(formPage(dev, "job/p/").contains(FormApprovalTest.NOTICE), "an approval must not carry over to pushed code");
        assertEquals(1, pending().size(), "one pending entry per form");
        assertTrue(pending().get(0).script.contains(module("v2")));
    }

    private static List<ScriptApproval.PendingScript> pending() {
        return ScriptApproval.get().getPendingScripts().stream()
                .filter(p -> p.getLanguage() instanceof FormApproval.FormLanguage)
                .toList();
    }

    // ---- (h) no SCM I/O under the queue lock --------------------------------

    @Test
    void aSlowRepositoryHoldsOnlyItsOwnBuildAndNotTheQueueOrTheUpstreamBuild() throws Exception {
        // Jenkins asks QueueDecisionHandlers and QueueTaskDispatchers with the lock for the whole
        // queue held. Reading schema.json there would let one slow server stop every job.
        ScheduledExecutorService closer = Executors.newSingleThreadScheduledExecutor();
        List<Socket> held = new CopyOnWriteArrayList<>();
        try (ServerSocket tarpit = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            Thread acceptor = new Thread(() -> {
                try {
                    while (true) {
                        Socket s = tarpit.accept();
                        held.add(s);
                        closer.schedule(() -> close(s), 15, TimeUnit.SECONDS);
                    }
                } catch (IOException e) {
                    // closed by the test
                }
            });
            acceptor.setDaemon(true);
            acceptor.start();

            WorkflowJob slow = j.createProject(WorkflowJob.class, "slow");
            slow.setDefinition(new CpsScmFlowDefinition(new GitSCM(GitSCM.createRepoList("http://127.0.0.1:" + tarpit.getLocalPort() + "/repo.git", null),
                    List.of(new BranchSpec("*/main")), null, null, List.of()), "Jenkinsfile"));
            UiParameterDefinition d = new UiParameterDefinition("CFG");
            d.setFormFrom("forms/cfg");
            slow.addProperty(new ParametersDefinitionProperty(d));
            FreeStyleProject other = j.createFreeStyleProject("other");
            WorkflowJob up = j.createProject(WorkflowJob.class, "up");
            up.setDefinition(new CpsFlowDefinition("build job: 'slow', wait: false, parameters: [string(name: 'CFG', value: '{}')]", true));

            long start = System.nanoTime();
            j.buildAndAssertSuccess(up);
            long millis = (System.nanoTime() - start) / 1_000_000;
            assertTrue(millis < 10_000, "the build step waited " + millis + " ms for the repository");
            j.buildAndAssertSuccess(other);
            assertNull(slow.getLastBuild(), "a build whose value is not checked yet must not start");
            assertEquals(1, Queue.getInstance().getItems(slow).size());

            held.forEach(FormFromTest::close);
            tarpit.close();
            // The schema cannot be read, so the build is cancelled and never starts.
            long deadline = System.currentTimeMillis() + 90_000;
            while (!Queue.getInstance().getItems(slow).isEmpty() && System.currentTimeMillis() < deadline) {
                Thread.sleep(200);
            }
            assertTrue(Queue.getInstance().getItems(slow).isEmpty(), "the item must leave the queue");
            assertNull(slow.getLastBuild());
        } finally {
            closer.shutdownNow();
            held.forEach(FormFromTest::close);
        }
    }

    private static void close(Socket s) {
        try {
            s.close();
        } catch (IOException e) {
            // already closed
        }
    }

    @Test
    void anScmErrorThatQuotesARemoteUrlReachesTheBrowserWithoutIt() throws Exception {
        // java.net.URI and other code the SCM calls put the whole URL, token included, in an
        // IllegalArgumentException; the page and the 400 answer go to anyone with Build permission.
        String url = "https://deploy:s3cr3t-token@git.example.invalid/app.git";
        ExtensionList<FormFrom.Reader> readers = ExtensionList.lookup(FormFrom.Reader.class);
        readers.removeAll(new ArrayList<>(readers));
        readers.add((job, path) -> {
            throw new IllegalArgumentException("Illegal character in authority at index 8: " + url);
        });
        FreeStyleProject p = j.createFreeStyleProject("p");
        UiParameterDefinition d = new UiParameterDefinition("CFG");
        d.setFormFrom("forms/cfg");
        p.addProperty(new ParametersDefinitionProperty(d));

        String page = formPage("job/p/");
        assertTrue(page.contains("formFrom: could not read 'forms/cfg' from the repository of p (IllegalArgumentException);"
                + " the controller log has the details"), page);
        assertFalse(page.contains("s3cr3t"), page);
        Page refused = buildWithParameters("job/p/", "{}");
        assertEquals(400, refused.getWebResponse().getStatusCode());
        assertFalse(refused.getWebResponse().getContentAsString().contains("s3cr3t"));
    }

    @Test
    void theCacheHoldsABoundedNumberOfFolders() {
        for (int i = 0; i < FormFrom.CACHE_ENTRIES * 2; i++) {
            FormFrom.remember("job-" + i + "\nforms/x");
        }
        assertEquals(FormFrom.CACHE_ENTRIES, FormFrom.cacheSize());
    }

    // ---- single-file HTML ---------------------------------------------------

    @Test
    void anInlineSingleFileHtmlIsServedAsSeparateModuleCssAndHtml() throws Exception {
        WorkflowJob job = j.createProject(WorkflowJob.class, "one");
        job.setDefinition(new CpsFlowDefinition("""
                properties([parameters([uiParameter(name: 'CFG', html: '''<style>p { color: red }</style>
                <p>hi</p>
                <script type="module">%s</script>''')])])
                """.formatted(module("one")), true));
        j.buildAndAssertSuccess(job);
        String page = formPage("job/one/");
        assertTrue(page.contains("data-module=\"" + key(module("one"), "mjs") + "\""), page);
        assertTrue(page.contains("data-css=\"" + key("p { color: red }", "css") + "\""));
        Matcher html = Pattern.compile("data-html=\"([0-9a-f]{64}\\.html\\.txt)\"").matcher(page);
        assertTrue(html.find());
        String markup = new String(ContentStore.get(html.group(1)).bytes(), StandardCharsets.UTF_8);
        assertEquals("\n<p>hi</p>\n", markup, "the script and style must not reach innerHTML");
    }

    @Test
    void aSingleFileFormHtmlInTheFolderIsAllAFormNeeds() throws Exception {
        write("forms/cfg/form.html", "<p>hi</p><script type=\"module\">" + module("file") + "</script>");
        commit("one file");
        scmJob("p");
        assertTrue(formPage("job/p/").contains("data-module=\"" + key(module("file"), "mjs") + "\""));
    }

    @Test
    void singleFileMistakesFailWhenTheFormIsDefinedOrRead() throws Exception {
        WorkflowJob job = j.createProject(WorkflowJob.class, "bad");
        job.setDefinition(new CpsFlowDefinition(
                "properties([parameters([uiParameter(name: 'CFG', html: '<button onclick=\"go()\">Go</button>')])])", true));
        j.assertLogContains("html uses the inline handler onclick=", j.buildAndAssertStatus(Result.FAILURE, job));
        job.setDefinition(new CpsFlowDefinition("properties([parameters([uiParameter(name: 'CFG', module: 'export default () => {}',"
                + " html: '<script type=\"module\">export default () => {}</script>')])])", true));
        j.assertLogContains("html has a <script type=\"module\"> and the form has module too", j.buildAndAssertStatus(Result.FAILURE, job));

        write("forms/cfg/form.html", "<script>classic()</script>");
        commit("classic script");
        WorkflowJob p = j.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsScmFlowDefinition(scm(), "Jenkinsfile"));
        // The defining build reads params, which asks for the default and so reads the folder: the author learns at once.
        j.assertLogContains("form.html has a <script> without type=\"module\"", j.buildAndAssertStatus(Result.FAILURE, p));
        String classic = formPage("job/p/");
        assertTrue(classic.contains("form.html has a &lt;script> without type=\"module\""), classic);

        write("forms/cfg/form.html", "<script type=\"module\">" + module("a") + "</script>");
        write("forms/cfg/form.mjs", module("b"));
        commit("two modules");
        FormFrom.clearCache();
        String page = formPage("job/p/");
        assertTrue(page.contains("and the form has form.mjs too"), page);
        assertFalse(page.contains("build-ui-root"));
    }
}
