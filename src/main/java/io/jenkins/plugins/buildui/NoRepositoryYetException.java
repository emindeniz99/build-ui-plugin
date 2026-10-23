package io.jenkins.plugins.buildui;

/** The job has not checked out any Git repository yet, so its refs are unknown. */
public class NoRepositoryYetException extends IllegalArgumentException {

    public NoRepositoryYetException(String jobName) {
        super("job '" + jobName + "' uses no Git repository yet; run it once so Jenkins records its checkout");
    }
}
