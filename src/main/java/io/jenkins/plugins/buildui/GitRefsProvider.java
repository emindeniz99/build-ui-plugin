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
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import jenkins.model.Jenkins;
import jenkins.triggers.SCMTriggerItem;
import org.eclipse.jgit.lib.ObjectId;
import org.jenkinsci.plugins.gitclient.Git;
import org.jenkinsci.plugins.gitclient.GitClient;

/**
 * {@code ui.query('gitRefs', { job: 'folder/name' })}: branches and tags of
 * the Git repositories that job already uses. The browser never names a URL,
 * so the controller only contacts servers a job is configured for (no SSRF),
 * and the job's credentials stay on the controller. Needs Build permission on
 * the job, because every call contacts the remote. Loads only when the Git
 * plugin is installed.
 */
@Extension(optional = true)
public class GitRefsProvider extends DataProvider {

    static final long CACHE_MILLIS = 30_000;
    private static final Map<String, Cached> CACHE = new ConcurrentHashMap<>();

    private record Cached(long at, Map<String, String> refs) {}

    @Override
    public String getId() {
        return "gitRefs";
    }

    @Override
    public JsonNode query(JsonNode args) {
        String name = args.path("job").asText("");
        if (name.isEmpty()) {
            throw new IllegalArgumentException("gitRefs needs a 'job' argument");
        }
        Job<?, ?> job = Jenkins.get().getItemByFullName(name, Job.class);
        if (job == null) {
            throw new IllegalArgumentException("no job '" + name + "' that you can read");
        }
        job.checkPermission(Item.BUILD);
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
            throw new IllegalArgumentException("job '" + name + "' uses no Git repository yet; run it once so Jenkins records its checkout");
        }

        ObjectNode result = JsonNodeFactory.instance.objectNode();
        ArrayNode branches = result.putArray("branches");
        ArrayNode tags = result.putArray("tags");
        for (UserRemoteConfig remote : remotes) {
            for (Map.Entry<String, String> ref : refs(job, remote).entrySet()) {
                String refName = ref.getKey();
                if (refName.startsWith("refs/heads/")) {
                    branches.addObject().put("name", refName.substring("refs/heads/".length())).put("sha", ref.getValue()).put("repository", remote.getUrl());
                } else if (refName.startsWith("refs/tags/") && !refName.endsWith("^{}")) {
                    tags.addObject().put("name", refName.substring("refs/tags/".length())).put("sha", ref.getValue()).put("repository", remote.getUrl());
                }
            }
        }
        return result;
    }

    private static Map<String, String> refs(Job<?, ?> job, UserRemoteConfig remote) {
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
}
