package io.jenkins.plugins.buildui;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.Util;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import hudson.security.ACL;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import java.net.URI;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;

/** One ArgoCD server an admin lets forms read application status from. */
public class ArgoCdServer extends AbstractDescribableImpl<ArgoCdServer> {

    private final String name;
    private final String url;
    private final String credentialsId;
    private String apps = "*";

    @DataBoundConstructor
    public ArgoCdServer(String name, String url, String credentialsId) {
        this.name = Util.fixEmptyAndTrim(name) == null ? "default" : name.trim();
        this.url = Util.fixEmptyAndTrim(url) == null ? "" : url.trim().replaceAll("/+$", "");
        this.credentialsId = Util.fixEmptyAndTrim(credentialsId);
    }

    public String getName() {
        return name;
    }

    public String getUrl() {
        return url;
    }

    public String getCredentialsId() {
        return credentialsId;
    }

    /** Glob of application names forms may read, for example {@code web-*}; default {@code *}. */
    public String getApps() {
        return apps;
    }

    @DataBoundSetter
    public void setApps(String apps) {
        this.apps = Util.fixEmptyAndTrim(apps) == null ? "*" : apps.trim();
    }

    boolean allows(String app) {
        return Globs.compile(apps, "apps").matches(app);
    }

    @Extension
    public static class DescriptorImpl extends Descriptor<ArgoCdServer> {

        @NonNull
        @Override
        public String getDisplayName() {
            return "ArgoCD server";
        }

        @POST
        public FormValidation doCheckUrl(@QueryParameter String value) {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);
            try {
                URI uri = URI.create(value.trim());
                if (!"https".equals(uri.getScheme()) && !"http".equals(uri.getScheme())) {
                    return FormValidation.error("Use an http or https URL");
                }
                return "http".equals(uri.getScheme())
                        ? FormValidation.warning("The token travels in clear text over http")
                        : FormValidation.ok();
            } catch (IllegalArgumentException e) {
                return FormValidation.error("Not a URL: " + e.getMessage());
            }
        }

        @POST
        public ListBoxModel doFillCredentialsIdItems(@QueryParameter String credentialsId) {
            if (!Jenkins.get().hasPermission(Jenkins.ADMINISTER)) {
                return new StandardListBoxModel().includeCurrentValue(credentialsId);
            }
            return new StandardListBoxModel()
                    .includeEmptyValue()
                    .includeMatchingAs(ACL.SYSTEM2, Jenkins.get(), StringCredentials.class, java.util.List.of(), CredentialsMatchers.always())
                    .includeCurrentValue(credentialsId);
        }
    }
}
