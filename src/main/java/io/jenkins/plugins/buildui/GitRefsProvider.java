package io.jenkins.plugins.buildui;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardCredentials;
import com.cloudbees.plugins.credentials.domains.URIRequirementBuilder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.umd.cs.findbugs.annotations.CheckForNull;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import jenkins.model.Jenkins;
import jenkins.triggers.SCMTriggerItem;
import org.eclipse.jgit.lib.ObjectId;
import org.jenkinsci.plugins.gitclient.Git;
import org.jenkinsci.plugins.gitclient.GitClient;

/**
 * {@code ui.query('gitRefs', { job, repository, branches, tags, search, sort, offset, limit })}:
 * branches and tags of the Git repositories that job already uses. The browser
 * never names a URL to contact: {@code repository} only picks among the job's
 * own remotes, so the controller only contacts servers a job is configured for
 * (no SSRF), and the job's credentials stay on the controller. Filters are
 * globs ({@code *}, {@code ?}) and {@code search} is a plain substring, never
 * regular expressions, so a caller cannot make the controller backtrack. Needs
 * Build permission on the job, because every uncached call contacts the
 * remote. Loads only with the Git plugin.
 *
 * <p>The answer is {@code { branches, tags, total: { branches, tags } }}.
 * {@code total} counts the entries after filtering and before paging, so a
 * form knows whether {@code offset + limit} leaves more to fetch.
 */
@Extension(optional = true)
public class GitRefsProvider extends DataProvider implements RefLookup {

    static final long CACHE_MILLIS = 30_000;
    static final int DEFAULT_LIMIT = 200;
    static final int MAX_LIMIT = 1000;
    static final int MAX_SEARCH = 100;
    /** Repositories with many tags take MBs each, so the cache keeps only the most recently used. */
    static final int CACHE_ENTRIES = 256;
    private static final Map<String, Cached> CACHE = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Cached> eldest) {
            return size() > CACHE_ENTRIES;
        }
    };

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
        Globs.Glob branchGlob = Globs.compile(args.path("branches").asText("*"), "branches");
        Globs.Glob tagGlob = Globs.compile(args.path("tags").asText("*"), "tags");
        String repository = args.hasNonNull("repository") ? args.get("repository").asText() : null;
        String search = args.path("search").asText("");
        if (search.length() > MAX_SEARCH) {
            throw new IllegalArgumentException("search is longer than " + MAX_SEARCH + " characters");
        }
        String needle = search.toLowerCase(Locale.ROOT);
        String sort = args.path("sort").asText("name");
        if (!sort.equals("name") && !sort.equals("version-desc")) {
            throw new IllegalArgumentException("sort must be 'name' or 'version-desc'");
        }
        int offset = Math.max(args.path("offset").asInt(0), 0);
        int limit = Math.min(Math.max(args.path("limit").asInt(DEFAULT_LIMIT), 1), MAX_LIMIT);

        List<Ref> branches = new ArrayList<>();
        List<Ref> tags = new ArrayList<>();
        for (Ref ref : refs(job, repository)) {
            if (!needle.isEmpty() && !ref.name().toLowerCase(Locale.ROOT).contains(needle)) {
                continue;
            }
            if (ref.kind().equals("branch") && branchGlob.matches(ref.name())) {
                branches.add(ref);
            } else if (ref.kind().equals("tag") && tagGlob.matches(ref.name())) {
                tags.add(ref);
            }
        }
        Comparator<Ref> order = sort.equals("version-desc")
                ? Comparator.comparing(Ref::name, GitRefsProvider::compareVersions).reversed()
                : Comparator.comparing(Ref::name);
        branches.sort(order);
        tags.sort(order);

        ObjectNode result = JsonNodeFactory.instance.objectNode();
        write(result.putArray("branches"), branches, offset, limit);
        write(result.putArray("tags"), tags, offset, limit);
        result.putObject("total").put("branches", branches.size()).put("tags", tags.size());
        return result;
    }

    @Override
    public boolean hasRef(Job<?, ?> job, String name, String kind, @CheckForNull String repository) {
        for (Ref ref : refs(job, repository)) {
            if (ref.name().equals(name) && (kind.equals("any") || ref.kind().equals(kind))) {
                return true;
            }
        }
        return false;
    }

    private static void write(ArrayNode out, List<Ref> refs, int offset, int limit) {
        int from = Math.min(offset, refs.size());
        for (Ref ref : refs.subList(from, Math.min(from + limit, refs.size()))) {
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

    /**
     * All branches and tags of the Git remotes the job uses, without peeled
     * {@code ^{}} entries. {@code repository}, when set, is a glob that keeps
     * only the remotes whose configured URL it matches. It selects among the
     * job's remotes and is never contacted itself.
     */
    static List<Ref> refs(Job<?, ?> job, @CheckForNull String repository) {
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
        if (repository != null) {
            Globs.Glob glob = Globs.compile(repository, "repository");
            remotes.removeIf(remote -> {
                String url = remote.getUrl();
                return url == null || !glob.matches(url);
            });
            if (remotes.isEmpty()) {
                throw new IllegalArgumentException("no Git repository of " + job.getFullName() + " matches '" + repository + "'");
            }
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
        synchronized (CACHE) {
            Cached cached = CACHE.get(key);
            if (cached != null && System.currentTimeMillis() - cached.at() < CACHE_MILLIS) {
                return cached.refs();
            }
            CACHE.remove(key);
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
            remember(key, refs);
            return refs;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalArgumentException("interrupted while listing " + url, e);
        } catch (Exception e) {
            // The message goes to the browser: keep it to the URL, never credential details.
            throw new IllegalArgumentException("could not list refs of " + url + " (" + e.getClass().getSimpleName() + ")", e);
        }
    }

    static void remember(String key, Map<String, String> refs) {
        synchronized (CACHE) {
            CACHE.put(key, new Cached(System.currentTimeMillis(), refs));
        }
    }

    static int cacheSize() {
        synchronized (CACHE) {
            return CACHE.size();
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

    private static final Pattern CHUNK = Pattern.compile("\\d+|\\D+");

    /**
     * Orders names the way people read versions: digit runs compare as
     * numbers ({@code v1.10} after {@code v1.9}), and a release comes after
     * its pre-releases ({@code 1.0.0} after {@code 1.0.0-rc1}). The order is
     * consistent, so sorting cannot throw: a chunk starting with {@code -}
     * ranks below the end of the name, and every other chunk above it.
     */
    static int compareVersions(String a, String b) {
        Matcher ma = CHUNK.matcher(a);
        Matcher mb = CHUNK.matcher(b);
        while (true) {
            String ca = ma.find() ? ma.group() : null;
            String cb = mb.find() ? mb.group() : null;
            int ra = rank(ca);
            int rb = rank(cb);
            if (ra != rb) {
                return Integer.compare(ra, rb);
            }
            if (ca == null) {
                return 0;
            }
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

    /** 0: a pre-release chunk ({@code -rc1}); 1: the end of the name; 2: any other chunk. */
    private static int rank(String chunk) {
        return chunk == null ? 1 : chunk.startsWith("-") ? 0 : 2;
    }
}
