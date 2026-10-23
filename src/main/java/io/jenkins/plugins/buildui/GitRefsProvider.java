package io.jenkins.plugins.buildui;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardCredentials;
import com.cloudbees.plugins.credentials.domains.URIRequirementBuilder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import hudson.EnvVars;
import hudson.Extension;
import hudson.model.Item;
import hudson.model.Job;
import hudson.model.TaskListener;
import hudson.plugins.git.GitSCM;
import hudson.plugins.git.UserRemoteConfig;
import hudson.scm.SCM;
import hudson.security.ACL;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import jenkins.model.Jenkins;
import jenkins.triggers.SCMTriggerItem;
import org.eclipse.jgit.lib.ObjectId;
import org.jenkinsci.plugins.gitclient.Git;
import org.jenkinsci.plugins.gitclient.GitClient;

/**
 * {@code ui.query('gitRefs', { job, branches, tags, sort, limit })}: branches
 * and tags of the Git repositories that job already uses. The browser never
 * names a URL, so the controller only contacts servers a job is configured for
 * (no SSRF), and the job's credentials stay on the controller. Filters are
 * globs ({@code *}, {@code ?}), not regular expressions, so a caller cannot
 * make the controller backtrack. Needs Build permission on the job, because
 * every uncached call contacts the remote. Loads only with the Git plugin.
 */
@Extension(optional = true)
public class GitRefsProvider extends DataProvider implements RefLookup {

    static final long CACHE_MILLIS = 30_000;
    static final int DEFAULT_LIMIT = 200;
    static final int MAX_LIMIT = 1000;
    static final int MAX_GLOB_LENGTH = 100;
    private static final Map<String, Cached> CACHE = new ConcurrentHashMap<>();

    private record Cached(long at, Map<String, String> refs) {}

    /** One branch or tag. */
    record Ref(String kind, String name, String sha, String repository) {}

    @Override
    public String getId() {
        return "gitRefs";
    }

    @Override
    public JsonNode query(JsonNode args) {
        Job<?, ?> job = job(args.path("job").asText(""));
        job.checkPermission(Item.BUILD);
        Pattern branchGlob = glob(args.path("branches").asText("*"), "branches");
        Pattern tagGlob = glob(args.path("tags").asText("*"), "tags");
        String sort = args.path("sort").asText("name");
        if (!sort.equals("name") && !sort.equals("version-desc")) {
            throw new IllegalArgumentException("sort must be 'name' or 'version-desc'");
        }
        int limit = Math.min(Math.max(args.path("limit").asInt(DEFAULT_LIMIT), 1), MAX_LIMIT);

        List<Ref> branches = new ArrayList<>();
        List<Ref> tags = new ArrayList<>();
        for (Ref ref : refs(job)) {
            if (ref.kind().equals("branch") && branchGlob.matcher(ref.name()).matches()) {
                branches.add(ref);
            } else if (ref.kind().equals("tag") && tagGlob.matcher(ref.name()).matches()) {
                tags.add(ref);
            }
        }
        Comparator<Ref> order = sort.equals("version-desc")
                ? Comparator.comparing(Ref::name, GitRefsProvider::compareVersions).reversed()
                : Comparator.comparing(Ref::name);
        branches.sort(order);
        tags.sort(order);

        ObjectNode result = JsonNodeFactory.instance.objectNode();
        write(result.putArray("branches"), branches, limit);
        write(result.putArray("tags"), tags, limit);
        return result;
    }

    @Override
    public boolean hasRef(Job<?, ?> job, String name, String kind) {
        for (Ref ref : refs(job)) {
            if (ref.name().equals(name) && (kind.equals("any") || ref.kind().equals(kind))) {
                return true;
            }
        }
        return false;
    }

    private static void write(ArrayNode out, List<Ref> refs, int limit) {
        for (Ref ref : refs.subList(0, Math.min(limit, refs.size()))) {
            out.addObject().put("name", ref.name()).put("sha", ref.sha()).put("repository", ref.repository());
        }
    }

    private static Job<?, ?> job(String name) {
        if (name.isEmpty()) {
            throw new IllegalArgumentException("gitRefs needs a 'job' argument");
        }
        Job<?, ?> job = Jenkins.get().getItemByFullName(name, Job.class);
        if (job == null) {
            throw new IllegalArgumentException("no job '" + name + "' that you can read");
        }
        return job;
    }

    /** All branches and tags of every Git remote the job uses, without peeled {@code ^{}} entries. */
    static List<Ref> refs(Job<?, ?> job) {
        SCMTriggerItem scmItem = SCMTriggerItem.SCMTriggerItems.asSCMTriggerItem(job);
        List<UserRemoteConfig> remotes = new ArrayList<>();
        if (scmItem != null) {
            for (SCM scm : scmItem.getSCMs()) {
                if (scm instanceof GitSCM git) {
                    remotes.addAll(git.getUserRemoteConfigs());
                }
            }
        }
        if (remotes.isEmpty()) {
            throw new NoRepositoryYetException(job.getFullName());
        }
        List<Ref> out = new ArrayList<>();
        for (UserRemoteConfig remote : remotes) {
            for (Map.Entry<String, String> e : remoteRefs(job, remote).entrySet()) {
                String refName = e.getKey();
                if (refName.startsWith("refs/heads/")) {
                    out.add(new Ref("branch", refName.substring("refs/heads/".length()), e.getValue(), remote.getUrl()));
                } else if (refName.startsWith("refs/tags/") && !refName.endsWith("^{}")) {
                    out.add(new Ref("tag", refName.substring("refs/tags/".length()), e.getValue(), remote.getUrl()));
                }
            }
        }
        return out;
    }

    private static Map<String, String> remoteRefs(Job<?, ?> job, UserRemoteConfig remote) {
        String url = remote.getUrl();
        String key = job.getFullName() + "\n" + url + "\n" + remote.getCredentialsId();
        Cached cached = CACHE.get(key);
        if (cached != null && System.currentTimeMillis() - cached.at() < CACHE_MILLIS) {
            return cached.refs();
        }
        try {
            GitClient client = Git.with(TaskListener.NULL, new EnvVars()).using("jgit").getClient();
            StandardCredentials credentials = credentials(job, remote);
            if (credentials != null) {
                client.addDefaultCredentials(credentials);
            }
            Map<String, String> refs = new TreeMap<>();
            for (Map.Entry<String, ObjectId> e : client.getRemoteReferences(url, null, false, false).entrySet()) {
                refs.put(e.getKey(), e.getValue().name());
            }
            CACHE.put(key, new Cached(System.currentTimeMillis(), refs));
            return refs;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalArgumentException("interrupted while listing " + url, e);
        } catch (Exception e) {
            // The message goes to the browser: keep it to the URL, never credential details.
            throw new IllegalArgumentException("could not list refs of " + url + " (" + e.getClass().getSimpleName() + ")", e);
        }
    }

    private static StandardCredentials credentials(Job<?, ?> job, UserRemoteConfig remote) {
        String id = remote.getCredentialsId();
        if (id == null || id.isEmpty()) {
            return null;
        }
        return CredentialsMatchers.firstOrNull(
                CredentialsProvider.lookupCredentialsInItem(StandardCredentials.class, job, ACL.SYSTEM2,
                        URIRequirementBuilder.fromUri(remote.getUrl()).build()),
                CredentialsMatchers.withId(id));
    }

    /** Turns a glob ({@code *} any run, {@code ?} one character) into an anchored pattern. */
    static Pattern glob(String glob, String what) {
        if (glob.length() > MAX_GLOB_LENGTH) {
            throw new IllegalArgumentException(what + " filter is longer than " + MAX_GLOB_LENGTH + " characters");
        }
        StringBuilder regex = new StringBuilder();
        for (String part : glob.split("(?<=[*?])|(?=[*?])")) {
            regex.append(switch (part) {
                case "*" -> ".*";
                case "?" -> ".";
                default -> Pattern.quote(part);
            });
        }
        return Pattern.compile(regex.toString(), Pattern.DOTALL);
    }

    private static final Pattern CHUNK = Pattern.compile("\\d+|\\D+");

    /**
     * Orders names the way people read versions: digit runs compare as
     * numbers ({@code v1.10} after {@code v1.9}), and a release comes after
     * its pre-releases ({@code 1.0.0} after {@code 1.0.0-rc1}).
     */
    static int compareVersions(String a, String b) {
        Matcher ma = CHUNK.matcher(a);
        Matcher mb = CHUNK.matcher(b);
        while (true) {
            boolean hasA = ma.find();
            boolean hasB = mb.find();
            if (!hasA && !hasB) {
                return 0;
            }
            if (!hasA) {
                return mb.group().startsWith("-") ? 1 : -1;
            }
            if (!hasB) {
                return ma.group().startsWith("-") ? -1 : 1;
            }
            String ca = ma.group();
            String cb = mb.group();
            int c;
            if (Character.isDigit(ca.charAt(0)) && Character.isDigit(cb.charAt(0))) {
                String na = ca.replaceFirst("^0+(?=\\d)", "");
                String nb = cb.replaceFirst("^0+(?=\\d)", "");
                c = na.length() != nb.length() ? Integer.compare(na.length(), nb.length()) : na.compareTo(nb);
            } else {
                c = ca.compareTo(cb);
            }
            if (c != 0) {
                return c;
            }
        }
    }
}
