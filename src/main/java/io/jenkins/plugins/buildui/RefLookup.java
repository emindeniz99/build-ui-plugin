package io.jenkins.plugins.buildui;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.ExtensionList;
import hudson.model.Job;

/**
 * Answers whether a branch or tag exists in a job's repositories, for the
 * {@code x-git-ref} schema keyword. {@link GitRefsProvider} implements it
 * when the Git plugin is installed; this interface keeps Git classes out of
 * the parameter code.
 */
public interface RefLookup {

    /**
     * {@code kind} is {@code branch}, {@code tag} or {@code any}.
     * {@code repository} is null for every repository of the job, or a glob
     * of the remote URLs to look in ({@code x-git-repository}).
     */
    boolean hasRef(Job<?, ?> job, String name, String kind, @CheckForNull String repository);

    static RefLookup find() {
        for (DataProvider provider : ExtensionList.lookup(DataProvider.class)) {
            if (provider instanceof RefLookup lookup) {
                return lookup;
            }
        }
        return null;
    }
}
