package io.jenkins.plugins.buildui;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.Extension;
import hudson.model.Job;
import hudson.model.TaskListener;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import jenkins.scm.api.SCMFile;
import jenkins.scm.api.SCMFileSystem;
import jenkins.scm.api.SCMHead;
import jenkins.scm.api.SCMRevision;
import jenkins.scm.api.SCMSource;
import org.jenkinsci.plugins.workflow.cps.CpsScmFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;

/**
 * Reads a formFrom folder through the SCM API's {@link SCMFileSystem}: on the
 * controller, with the job's own SCM and credentials, without a workspace. A
 * branch job of a multibranch Pipeline reads the revision its Jenkinsfile comes
 * from: its head, or for a change request the source does not trust, the
 * revision the source trusts instead. A Pipeline job defined by "Pipeline
 * script from SCM" reads the branch its SCM names. Loads only with Pipeline
 * and the SCM API.
 */
@Extension(optional = true)
public class ScmFormReader implements FormFrom.Reader {

    @Override
    public FormFrom.Files read(Job<?, ?> job, String path) throws IOException, InterruptedException {
        try (SCMFileSystem fs = open(job)) {
            SCMRevision head = fs.getRevision();
            String revision = head == null ? "the branch head" : head.toString();
            SCMFile dir = fs.child(path);
            if (!dir.isDirectory()) {
                throw new IllegalArgumentException("formFrom: no folder '" + path + "' at " + revision + " of the repository of " + job.getFullName());
            }
            return new FormFrom.Files(revision,
                    text(dir, "form.mjs", FormFrom.MAX_CODE_BYTES),
                    text(dir, "form.html", FormFrom.MAX_CODE_BYTES),
                    text(dir, "form.css", FormFrom.MAX_CODE_BYTES),
                    text(dir, "schema.json", FormFrom.MAX_JSON_BYTES),
                    text(dir, "default.json", FormFrom.MAX_JSON_BYTES));
        }
    }

    private static SCMFileSystem open(Job<?, ?> job) throws IOException, InterruptedException {
        SCMSource source = SCMSource.SourceByItem.findSource(job);
        SCMHead head = SCMHead.HeadByItem.findHead(job);
        SCMFileSystem fs;
        if (source != null && head != null) {
            // A change request from an author the source does not trust (a fork's pull request, say)
            // runs the Jenkinsfile of the revision the source trusts instead of its own: workflow-multibranch's
            // SCMBinder and readTrusted fetch the tip, ask getTrustedRevision and open that revision.
            // The form's code and defaults are part of that definition, so they come from the same place.
            SCMRevision tip = source.fetch(head, TaskListener.NULL);
            if (tip == null) {
                throw new IllegalArgumentException("formFrom: the repository of " + job.getFullName() + " has no revision for " + head.getName());
            }
            fs = SCMFileSystem.of(source, head, source.getTrustedRevision(tip, TaskListener.NULL));
        } else if (job instanceof WorkflowJob pipeline && pipeline.getDefinition() instanceof CpsScmFlowDefinition definition) {
            fs = SCMFileSystem.of(job, definition.getScm());
        } else {
            throw new IllegalArgumentException(FormFrom.unsupported(job));
        }
        if (fs == null) {
            throw new IllegalArgumentException("formFrom: the SCM of " + job.getFullName()
                    + " cannot read single files without a checkout (no lightweight checkout support)");
        }
        return fs;
    }

    @CheckForNull
    private static String text(SCMFile dir, String name, int limit) throws IOException, InterruptedException {
        SCMFile file = dir.child(name);
        if (!file.isFile()) {
            return null;
        }
        byte[] bytes;
        try (InputStream in = file.content()) {
            bytes = in.readNBytes(limit + 1);
        }
        if (bytes.length > limit) {
            throw new IllegalArgumentException("formFrom: " + file.getPath() + " is larger than " + limit / 1024 + " KiB");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
