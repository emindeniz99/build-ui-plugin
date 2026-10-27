package io.jenkins.plugins.buildui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.ParametersAction;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.Result;
import hudson.model.StringParameterValue;
import hudson.model.queue.QueueTaskFuture;
import hudson.plugins.git.GitSCM;
import java.io.IOException;
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
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.StreamSupport;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.Page;
import org.htmlunit.WebRequest;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
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

    /** A second repository for jobs that check out two. */
    @TempDir
    static Path tools;

    @BeforeAll
    static void makeRepository() throws Exception {
        // The Git plugin refuses file: URLs unless told otherwise (SECURITY-2478); only this test needs one.
        System.setProperty("hudson.plugins.git.GitSCM.ALLOW_LOCAL_CHECKOUT", "true");
        git("init", "-q", "-b", "main");
        Files.writeString(repo.resolve("README"), "x");
        git("add", "README");
        git("-c", "user.name=t", "-c", "user.email=t@example.invalid", "commit", "-q", "-m", "first");
        git("branch", "feature/login");
        git("branch", "release/2026.10");
        for (String tag : List.of("v1.0", "v1.9.0", "v1.10.0", "v1.10.0-rc1", "nightly")) {
            git("tag", tag);
        }
        git("tag", "-a", "-m", "release", "v1.1");

        gitIn(tools, "init", "-q", "-b", "main");
        Files.writeString(tools.resolve("README"), "tools");
        gitIn(tools, "add", "README");
        gitIn(tools, "-c", "user.name=t", "-c", "user.email=t@example.invalid", "commit", "-q", "-m", "first");
        gitIn(tools, "branch", "hotfix/1");
        gitIn(tools, "tag", "tools-2.0");
    }

    private static void git(String... args) throws Exception {
        gitIn(repo, args);
    }

    private static void gitIn(Path dir, String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("git", "-C", dir.toString()));
        cmd.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(cmd).inheritIO();
        // CI runners have no global git identity; commits and annotated tags need one.
        pb.environment().putAll(Map.of("GIT_AUTHOR_NAME", "test", "GIT_AUTHOR_EMAIL", "test@example.invalid",
                "GIT_COMMITTER_NAME", "test", "GIT_COMMITTER_EMAIL", "test@example.invalid"));
        Process p = pb.start();
        assertEquals(0, p.waitFor(), String.join(" ", cmd));
    }

    private static WorkflowJob job(JenkinsRule j, String name, String parameters) throws Exception {
        WorkflowJob job = j.createProject(WorkflowJob.class, name);
        job.setDefinition(new CpsFlowDefinition(parameters
                + "node { git url: '" + repo.toUri() + "', branch: 'main' }\n"
                + "echo \"CFG=${params.CFG}\"", true));
        j.buildAndAssertSuccess(job);
        return job;
    }

    private static List<String> names(JsonNode refs) {
        return StreamSupport.stream(refs.spliterator(), false).map(r -> r.get("name").asText()).toList();
    }

    private static JsonNode query(JenkinsRule.WebClient wc, String args) throws Exception {
        Page page = wc.goTo("build-ui/query/gitRefs?args=" + URLEncoder.encode(args, StandardCharsets.UTF_8), "application/json");
        return JsonMapper.builder().build().readTree(page.getWebResponse().getContentAsString());
    }

    @Test
    void listsBranchesAndTagsWithoutPeeledEntries(JenkinsRule j) throws Exception {
        job(j, "app", "");
        JsonNode refs = query(j.createWebClient(), "{\"job\":\"app\"}");
        assertEquals(List.of("feature/login", "main", "release/2026.10"), names(refs.get("branches")));
        assertEquals(List.of("nightly", "v1.0", "v1.1", "v1.10.0", "v1.10.0-rc1", "v1.9.0"), names(refs.get("tags")));
    }

    @Test
    void filtersWithGlobsAndSortsNewestVersionFirstLikeGitParameter(JenkinsRule j) throws Exception {
        job(j, "app", "");
        JsonNode refs = query(j.createWebClient(),
                "{\"job\":\"app\",\"branches\":\"release/*\",\"tags\":\"v1.*\",\"sort\":\"version-desc\",\"limit\":3}");
        assertEquals(List.of("release/2026.10"), names(refs.get("branches")));
        // A release sorts above its release candidate, and 1.10 above 1.9.
        assertEquals(List.of("v1.10.0", "v1.10.0-rc1", "v1.9.0"), names(refs.get("tags")));
    }

    @Test
    void comparesVersionsTheWayPeopleReadThem() {
        assertTrue(GitRefsProvider.compareVersions("v1.10.0", "v1.9.0") > 0);
        assertTrue(GitRefsProvider.compareVersions("1.0.0", "1.0.0-rc1") > 0);
        assertTrue(GitRefsProvider.compareVersions("1.0.1", "1.0.0") > 0);
        assertTrue(GitRefsProvider.compareVersions("1.0.nightly-rc", "1.0.9") > 0);
        assertEquals(0, GitRefsProvider.compareVersions("v01.2", "v1.2"));
    }

    @Test
    void versionOrderIsConsistentSoSortingMixedTagsCannotThrow() {
        // TimSort throws "Comparison method violates its general contract" on an inconsistent order.
        List<String> tags = List.of("v1.0.0", "v1.0.0-rc1", "v1.0.0+build1", "v1.0.0.1", "v1.0.0 x", "v1.0.0_a",
                "v1.0.0-rc1+b", "v1.0", "v1", "v1.0.0-", "v1.0.0+", "1.0.0", "v01.0.0", "va", "v");
        for (String a : tags) {
            for (String b : tags) {
                assertEquals(Integer.signum(GitRefsProvider.compareVersions(a, b)),
                        -Integer.signum(GitRefsProvider.compareVersions(b, a)), a + " vs " + b);
                for (String c : tags) {
                    if (GitRefsProvider.compareVersions(a, b) < 0 && GitRefsProvider.compareVersions(b, c) < 0) {
                        assertTrue(GitRefsProvider.compareVersions(a, c) < 0, a + " < " + b + " < " + c);
                    }
                }
            }
        }
        assertTrue(GitRefsProvider.compareVersions("v1.0.0-rc1", "v1.0.0") < 0);
        assertTrue(GitRefsProvider.compareVersions("v1.0.0", "v1.0.0+build1") < 0);
    }

    @Test
    void theRefCacheHoldsABoundedNumberOfEntriesSoDeletedJobsDoNotLeakMemory() {
        // Every job, branch job and pull request job that was ever queried used to stay in memory.
        for (int i = 0; i < GitRefsProvider.CACHE_ENTRIES * 2; i++) {
            GitRefsProvider.remember("job-" + i + "\nurl\n", java.util.Map.of("refs/heads/main", "0".repeat(40)));
        }
        assertEquals(GitRefsProvider.CACHE_ENTRIES, GitRefsProvider.cacheSize());
    }

    @Test
    void xGitRefUnderAdditionalPropertiesIsEnforcedToo(JenkinsRule j) throws Exception {
        // Schema.check accepts x-git-ref there, so leaving it unchecked would be a rule the author believes in but nobody enforces.
        WorkflowJob job = job(j, "app", "");
        UiParameterDefinition d = new UiParameterDefinition("CFG");
        d.setSchema("{\"type\":\"object\",\"additionalProperties\":{\"type\":\"string\",\"x-git-ref\":\"tag\"}}");
        assertEquals(List.of("/svc: 'v9.9.9' is not a tag of app's repository"), d.errorsFor("{\"svc\":\"v9.9.9\"}", job));
        assertEquals(List.of(), d.errorsFor("{\"svc\":\"v1.1\"}", job));
    }

    @Test
    void globFiltersAreQuotedSoRegexCharactersMatchLiterally() {
        assertTrue(Globs.compile("release/2026.10", "x").matches("release/2026.10"));
        assertTrue(!Globs.compile("release/2026.10", "x").matches("release/2026x10"));
        assertTrue(Globs.compile("v?.*", "x").matches("v1.10.0"));
        assertTrue(Globs.compile("*", "x").matches(""));
        assertTrue(Globs.compile("a*b*c", "x").matches("abbbc"));
        assertTrue(!Globs.compile("a*b*c", "x").matches("abbbcd"));
        assertTrue(Globs.compile("?", "x").matches("😀"), "? is one character, also outside the BMP");
        assertTrue(!Globs.compile("v1.*", "x").matches("old/v1.5"), "a glob matches the whole name");
        assertThrows(IllegalArgumentException.class, () -> Globs.compile("*".repeat(101), "x"));
    }

    @Test
    void aGlobCannotMakeTheControllerBacktrack() {
        // Anyone with Build permission writes the filter, anyone with push access the ref names. Compiled
        // to a regex, '*a*a*a*a*b' took 20 s against 200 'a's on Java 21, and six stars ran past 100 s.
        Globs.Glob glob = Globs.compile("*a".repeat(49) + "*b", "x");
        long start = System.nanoTime();
        assertTrue(!glob.matches("a".repeat(10_000)));
        long millis = (System.nanoTime() - start) / 1_000_000;
        assertTrue(millis < 2_000, "took " + millis + " ms");
    }

    @Test
    void refusesUsersWithoutBuildPermissionBecauseEachCallContactsTheRemote(JenkinsRule j) throws Exception {
        job(j, "app", "");
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.READ, Item.READ).everywhere().to("reader")
                .grant(Jenkins.READ, Item.READ, Item.BUILD).everywhere().to("builder"));

        JenkinsRule.WebClient reader = j.createWebClient().withThrowExceptionOnFailingStatusCode(false).login("reader");
        Page denied = reader.goTo("build-ui/query/gitRefs?args=" + URLEncoder.encode("{\"job\":\"app\"}", StandardCharsets.UTF_8), null);
        assertEquals(403, denied.getWebResponse().getStatusCode());
        assertTrue(names(query(j.createWebClient().login("builder"), "{\"job\":\"app\"}").get("branches")).contains("main"));
    }

    @Test
    void explainsWhenTheJobHasNotCheckedOutAnythingYet(JenkinsRule j) throws Exception {
        j.createProject(WorkflowJob.class, "fresh");
        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false);
        Page page = wc.goTo("build-ui/query/gitRefs?args=" + URLEncoder.encode("{\"job\":\"fresh\"}", StandardCharsets.UTF_8), "application/json");
        assertEquals(400, page.getWebResponse().getStatusCode());
        assertTrue(page.getWebResponse().getContentAsString().contains("uses no Git repository yet"));
    }

    static final String TAG_PARAMETER = "properties([parameters([uiParameter(name: 'CFG', module: 'export default () => {}', "
            + "defaultJson: '{\"tag\":\"v1.0\"}', "
            + "schema: '{\"type\":\"object\",\"properties\":{\"tag\":{\"type\":\"string\",\"x-git-ref\":\"tag\"}}}')])])\n";

    @Test
    void xGitRefAcceptsARealTagAndRejectsAMissingOneOnSubmit(JenkinsRule j) throws Exception {
        WorkflowJob job = job(j, "release", TAG_PARAMETER);
        assertEquals(201, buildWithParameters(j, "{\"tag\":\"v1.10.0\"}").getWebResponse().getStatusCode());
        j.waitUntilNoActivity();
        j.assertLogContains("CFG={\"tag\":\"v1.10.0\"}", job.getLastBuild());

        Page bad = buildWithParameters(j, "{\"tag\":\"v9.9.9\"}");
        assertEquals(400, bad.getWebResponse().getStatusCode());
        assertTrue(bad.getWebResponse().getContentAsString().contains("/tag: 'v9.9.9' is not a tag of release's repository"),
                bad.getWebResponse().getContentAsString());
        // A branch name is not a tag.
        assertEquals(400, buildWithParameters(j, "{\"tag\":\"main\"}").getWebResponse().getStatusCode());
    }

    @Test
    void xGitRefIsCheckedForBuildsThatDoNotComeFromAForm(JenkinsRule j) throws Exception {
        WorkflowJob job = job(j, "release", TAG_PARAMETER);
        // Queued, held while the refs are listed outside the queue lock, then cancelled before it starts.
        QueueTaskFuture<WorkflowRun> bad = job.scheduleBuild2(0, new ParametersAction(new StringParameterValue("CFG", "{\"tag\":\"v9.9.9\"}")));
        assertNotNull(bad);
        assertThrows(CancellationException.class, () -> bad.get(60, TimeUnit.SECONDS));
        WorkflowRun good = job.scheduleBuild2(0, new ParametersAction(new StringParameterValue("CFG", "{\"tag\":\"v1.1\"}"))).get(60, TimeUnit.SECONDS);
        assertEquals(2, good.getNumber(), "the refused build must not have started");
        j.assertLogContains("CFG={\"tag\":\"v1.1\"}", j.waitForCompletion(good));

        WorkflowJob up = j.createProject(WorkflowJob.class, "up");
        up.setDefinition(new CpsFlowDefinition("build job: 'release', parameters: [string(name: 'CFG', value: '{\"tag\":\"v9.9.9\"}')]", true));
        j.buildAndAssertStatus(Result.FAILURE, up);
    }

    @Test
    void aSlowGitServerHoldsOnlyItsOwnBuildAndNotTheWholeQueue(JenkinsRule j) throws Exception {
        // Jenkins asks QueueDecisionHandlers while it holds the lock for the whole queue. Listing refs
        // there let one slow or unreachable Git server stop every job on the controller from scheduling.
        ScheduledExecutorService closer = Executors.newSingleThreadScheduledExecutor();
        List<Socket> held = new CopyOnWriteArrayList<>();
        try (ServerSocket tarpit = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            Thread acceptor = new Thread(() -> {
                try {
                    while (true) {
                        Socket s = tarpit.accept();
                        held.add(s);
                        // Never answers; lets go after 15 s so the old behaviour fails instead of hanging.
                        closer.schedule(() -> close(s), 15, TimeUnit.SECONDS);
                    }
                } catch (IOException e) {
                    // closed by the test
                }
            });
            acceptor.setDaemon(true);
            acceptor.start();

            FreeStyleProject slow = j.createFreeStyleProject("slow");
            slow.setScm(new GitSCM("http://127.0.0.1:" + tarpit.getLocalPort() + "/repo.git"));
            UiParameterDefinition d = new UiParameterDefinition("CFG");
            d.setSchema("{\"type\":\"object\",\"properties\":{\"tag\":{\"type\":\"string\",\"x-git-ref\":\"tag\"}}}");
            slow.addProperty(new ParametersDefinitionProperty(d));
            FreeStyleProject other = j.createFreeStyleProject("other");

            long start = System.nanoTime();
            QueueTaskFuture<FreeStyleBuild> queued = slow.scheduleBuild2(0, new ParametersAction(new StringParameterValue("CFG", "{\"tag\":\"v1.0\"}")));
            long millis = (System.nanoTime() - start) / 1_000_000;
            assertTrue(millis < 5_000, "scheduling waited " + millis + " ms for the Git server");
            assertNotNull(queued);
            j.buildAndAssertSuccess(other);
            assertNull(slow.getLastBuild(), "a build whose refs are not checked yet must not start");

            held.forEach(GitRefsProviderTest::close);
            tarpit.close();
            // The refs cannot be listed, so the build is cancelled and never starts.
            assertThrows(CancellationException.class, () -> queued.get(60, TimeUnit.SECONDS));
            assertNull(slow.getLastBuild());
        } finally {
            closer.shutdownNow();
            held.forEach(GitRefsProviderTest::close);
        }
    }

    private static void close(Socket s) {
        try {
            s.close();
        } catch (IOException e) {
            // already closed
        }
    }

    private static Page rawQuery(JenkinsRule j, String args) throws Exception {
        return j.createWebClient().withThrowExceptionOnFailingStatusCode(false)
                .goTo("build-ui/query/gitRefs?args=" + URLEncoder.encode(args, StandardCharsets.UTF_8), null);
    }

    /** A job that checks out {@link #repo} and then {@link #tools}, as a job with an app and a tools repository does. */
    private static WorkflowJob twoRepositoryJob(JenkinsRule j, String name, String parameters) throws Exception {
        WorkflowJob job = j.createProject(WorkflowJob.class, name);
        job.setDefinition(new CpsFlowDefinition(parameters
                + "node { dir('app') { git url: '" + repo.toUri() + "', branch: 'main' }\n"
                + "  dir('tools') { git url: '" + tools.toUri() + "', branch: 'main' } }\n"
                + "echo \"CFG=${params.CFG}\"", true));
        j.buildAndAssertSuccess(job);
        return job;
    }

    @Test
    void pagesWithOffsetAndCountsWhatIsLeftSoAFormCanAskForMore(JenkinsRule j) throws Exception {
        // Repositories with thousands of tags must not reach the browser in one answer, and a form
        // that shows the first page has to know whether a second one exists.
        job(j, "app", "");
        JenkinsRule.WebClient wc = j.createWebClient();
        JsonNode page = query(wc, "{\"job\":\"app\",\"offset\":2,\"limit\":2}");
        assertEquals(List.of("v1.1", "v1.10.0"), names(page.get("tags")));
        assertEquals(List.of("release/2026.10"), names(page.get("branches")));
        assertEquals(6, page.at("/total/tags").asInt());
        assertEquals(3, page.at("/total/branches").asInt());

        JsonNode past = query(wc, "{\"job\":\"app\",\"offset\":50}");
        assertEquals(List.of(), names(past.get("tags")));
        assertEquals(6, past.at("/total/tags").asInt());
        // A negative offset is the first page, as a too small limit is one entry.
        assertEquals(List.of("nightly"), names(query(wc, "{\"job\":\"app\",\"offset\":-5,\"limit\":0}").get("tags")));
    }

    @Test
    void searchIsACaseInsensitiveSubstringAppliedAfterTheGlobs(JenkinsRule j) throws Exception {
        job(j, "app", "");
        JenkinsRule.WebClient wc = j.createWebClient();
        JsonNode rc = query(wc, "{\"job\":\"app\",\"search\":\"RC\"}");
        assertEquals(List.of("v1.10.0-rc1"), names(rc.get("tags")));
        assertEquals(1, rc.at("/total/tags").asInt());
        assertEquals(List.of("feature/login"), names(query(wc, "{\"job\":\"app\",\"search\":\"log\"}").get("branches")));
        // The search is literal: a '*' in it is a character to find, not a wildcard.
        JsonNode star = query(wc, "{\"job\":\"app\",\"search\":\"v*\"}");
        assertEquals(0, star.at("/total/tags").asInt());
        assertEquals(List.of("v1.10.0"), names(query(wc, "{\"job\":\"app\",\"tags\":\"v1.*\",\"search\":\"10.0\",\"sort\":\"version-desc\",\"limit\":1}").get("tags")));

        Page tooLong = rawQuery(j, "{\"job\":\"app\",\"search\":\"" + "x".repeat(101) + "\"}");
        assertEquals(400, tooLong.getWebResponse().getStatusCode());
    }

    @Test
    void repositoryPicksOneOfTheJobsOwnRemotesAndNeverAnyOtherUrl(JenkinsRule j) throws Exception {
        twoRepositoryJob(j, "app", "");
        JenkinsRule.WebClient wc = j.createWebClient();
        // Without it, every repository the job checked out.
        assertEquals(List.of("feature/login", "hotfix/1", "main", "main", "release/2026.10"),
                names(query(wc, "{\"job\":\"app\"}").get("branches")));

        String toolsUrl = tools.toUri().toString();
        JsonNode exact = query(wc, "{\"job\":\"app\",\"repository\":\"" + toolsUrl + "\"}");
        assertEquals(List.of("hotfix/1", "main"), names(exact.get("branches")));
        assertEquals(List.of("tools-2.0"), names(exact.get("tags")));
        assertTrue(StreamSupport.stream(exact.get("branches").spliterator(), false)
                .allMatch(r -> r.get("repository").asText().equals(toolsUrl)));
        JsonNode glob = query(wc, "{\"job\":\"app\",\"repository\":\"*/" + repo.getFileName() + "/\"}");
        assertEquals(List.of("feature/login", "main", "release/2026.10"), names(glob.get("branches")));

        // A URL the job does not use selects nothing; it is never contacted.
        Page other = rawQuery(j, "{\"job\":\"app\",\"repository\":\"https://git.example.invalid/x.git\"}");
        assertEquals(400, other.getWebResponse().getStatusCode());
        assertTrue(other.getWebResponse().getContentAsString().contains("no Git repository of app matches 'https://git.example.invalid/x.git'"),
                other.getWebResponse().getContentAsString());
        assertEquals(400, rawQuery(j, "{\"job\":\"app\",\"repository\":\"" + "*".repeat(101) + "\"}").getWebResponse().getStatusCode());
    }

    static final String TOKEN_URL = "https://deploy:s3cr3t-token@git.example.invalid/app.git";

    @Test
    void aTokenInARemoteUrlNeverReachesTheBrowser(JenkinsRule j) throws Exception {
        // Anyone with Build permission may query; the token in a URL like this belongs to the job alone.
        FreeStyleProject job = j.createFreeStyleProject("app");
        job.setScm(new GitSCM(TOKEN_URL));
        GitRefsProvider.remember("app\n" + TOKEN_URL + "\nnull", java.util.Map.of("refs/heads/main", "0".repeat(40), "refs/tags/v1.0", "1".repeat(40)));
        JenkinsRule.WebClient wc = j.createWebClient();
        Page answer = wc.goTo("build-ui/query/gitRefs?args=" + URLEncoder.encode("{\"job\":\"app\"}", StandardCharsets.UTF_8), "application/json");
        String text = answer.getWebResponse().getContentAsString();
        assertTrue(text.contains("\"repository\":\"https://git.example.invalid/app.git\""), text);
        assertFalse(text.contains("s3cr3t") || text.contains("deploy"), text);

        // repository and x-git-repository match the URL as the browser sees it, so a glob cannot probe the token.
        assertEquals(List.of("main"), names(query(wc, "{\"job\":\"app\",\"repository\":\"https://git.example.invalid/*\"}").get("branches")));
        assertEquals(400, rawQuery(j, "{\"job\":\"app\",\"repository\":\"*s3cr3t*\"}").getWebResponse().getStatusCode());
        assertTrue(new GitRefsProvider().hasRef(job, "v1.0", "tag", "https://git.example.invalid/app.git"));

        // A remote that cannot be listed names its URL in the error, without the token.
        FreeStyleProject down = j.createFreeStyleProject("down");
        down.setScm(new GitSCM("http://deploy:s3cr3t-token@127.0.0.1:1/app.git"));
        Page error = rawQuery(j, "{\"job\":\"down\"}");
        assertEquals(400, error.getWebResponse().getStatusCode());
        String message = error.getWebResponse().getContentAsString();
        assertTrue(message.contains("could not list refs of http://127.0.0.1:1/app.git"), message);
        assertFalse(message.contains("s3cr3t"), message);
    }

    @Test
    void userInfoIsTakenOutOfUrlsWithASchemeOnly() {
        assertEquals("https://git.example.invalid/app.git", GitRefsProvider.withoutUserInfo(TOKEN_URL));
        assertEquals("https://git.example.invalid/app.git", GitRefsProvider.withoutUserInfo("https://ghp_token@git.example.invalid/app.git"));
        // An unencoded '@' in the password: the host starts after the last one.
        assertEquals("https://git.example.invalid:8443/a@b.git", GitRefsProvider.withoutUserInfo("https://u:p@ss@git.example.invalid:8443/a@b.git"));
        assertEquals("ssh://git.example.invalid/app.git", GitRefsProvider.withoutUserInfo("ssh://git@git.example.invalid/app.git"));
        // scp-like syntax has a user name and no password, and file paths have neither: both stay as they are.
        assertEquals("git@git.example.invalid:org/app.git", GitRefsProvider.withoutUserInfo("git@git.example.invalid:org/app.git"));
        assertEquals("file:///srv/git/app.git", GitRefsProvider.withoutUserInfo("file:///srv/git/app.git"));
        assertEquals("https://git.example.invalid/app.git?a=b@c", GitRefsProvider.withoutUserInfo("https://git.example.invalid/app.git?a=b@c"));
    }

    @Test
    void xGitRepositoryLimitsTheRefCheckToOneRepositoryOnEveryPath(JenkinsRule j) throws Exception {
        // v1.0 is a tag of the app repository only, tools-2.0 of the tools one. A directory's file: URI ends in '/'.
        String toolsGlob = "*/" + tools.getFileName() + "/";
        WorkflowJob job = twoRepositoryJob(j, "two", "properties([parameters([uiParameter(name: 'CFG', module: 'export default () => {}', "
                + "schema: '{\"type\":\"object\",\"properties\":{\"tag\":{\"type\":\"string\",\"x-git-ref\":\"tag\",\"x-git-repository\":\""
                + toolsGlob + "\"}}}')])])\n");

        // Form and buildWithParameters: a tag of the other repository is refused.
        assertEquals(201, buildWithParameters(j, "two", "{\"tag\":\"tools-2.0\"}").getWebResponse().getStatusCode());
        j.waitUntilNoActivity();
        j.assertLogContains("CFG={\"tag\":\"tools-2.0\"}", job.getLastBuild());
        Page bad = buildWithParameters(j, "two", "{\"tag\":\"v1.0\"}");
        assertEquals(400, bad.getWebResponse().getStatusCode());
        assertTrue(bad.getWebResponse().getContentAsString().contains("/tag: 'v1.0' is not a tag of two's repository matching '" + toolsGlob + "'"),
                bad.getWebResponse().getContentAsString());

        // Queued without a request: RefGuard applies the same repository.
        int before = job.getLastBuild().getNumber();
        QueueTaskFuture<WorkflowRun> queued = job.scheduleBuild2(0, new ParametersAction(new StringParameterValue("CFG", "{\"tag\":\"v1.0\"}")));
        assertNotNull(queued);
        assertThrows(CancellationException.class, () -> queued.get(60, TimeUnit.SECONDS));
        assertEquals(before, job.getLastBuild().getNumber());
    }

    @Test
    void xGitRepositoryMustBeAShortGlobNextToXGitRef() {
        UiParameterDefinition d = new UiParameterDefinition("CFG");
        IllegalArgumentException alone = assertThrows(IllegalArgumentException.class,
                () -> d.setSchema("{\"type\":\"string\",\"x-git-repository\":\"*tools*\"}"));
        assertTrue(alone.getMessage().contains("x-git-repository needs x-git-ref"), alone.getMessage());
        assertThrows(IllegalArgumentException.class, () -> d.setSchema("{\"type\":\"string\",\"x-git-ref\":\"tag\",\"x-git-repository\":1}"));
        assertThrows(IllegalArgumentException.class,
                () -> d.setSchema("{\"type\":\"string\",\"x-git-ref\":\"tag\",\"x-git-repository\":\"" + "*".repeat(101) + "\"}"));
        d.setSchema("{\"type\":\"string\",\"x-git-ref\":\"tag\",\"x-git-repository\":\"" + "*".repeat(100) + "\"}");
    }

    @Test
    void anUnknownXGitRefKindFailsWhenTheJobIsConfigured() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> new UiParameterDefinition("CFG")
                .setSchema("{\"type\":\"object\",\"properties\":{\"tag\":{\"type\":\"string\",\"x-git-ref\":\"tga\"}}}"));
        assertTrue(e.getMessage().contains("x-git-ref must be true"), e.getMessage());
    }

    private static Page buildWithParameters(JenkinsRule j, String value) throws Exception {
        return buildWithParameters(j, "release", value);
    }

    private static Page buildWithParameters(JenkinsRule j, String job, String value) throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false).withRedirectEnabled(false);
        WebRequest req = new WebRequest(URI.create(j.getURL() + "job/" + job + "/buildWithParameters?CFG="
                + URLEncoder.encode(value, StandardCharsets.UTF_8)).toURL(), HttpMethod.POST);
        return wc.getPage(wc.addCrumb(req));
    }
}
