package io.jenkins.plugins.buildui;

import hudson.Extension;
import java.util.ArrayList;
import java.util.List;
import jenkins.model.GlobalConfiguration;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundSetter;

/** Settings under Manage Jenkins » System » Build UI. */
@Extension
@Symbol("buildUi")
public class BuildUiConfiguration extends GlobalConfiguration {

    private List<ArgoCdServer> argoCdServers = new ArrayList<>();
    private boolean requireApproval;
    private boolean sandboxAll;

    public BuildUiConfiguration() {
        load();
    }

    public static BuildUiConfiguration get() {
        return GlobalConfiguration.all().get(BuildUiConfiguration.class);
    }

    public List<ArgoCdServer> getArgoCdServers() {
        return argoCdServers;
    }

    @DataBoundSetter
    public void setArgoCdServers(List<ArgoCdServer> argoCdServers) {
        this.argoCdServers = argoCdServers == null ? new ArrayList<>() : new ArrayList<>(argoCdServers);
        save();
    }

    /** Off by default: forms run as soon as a Jenkinsfile defines them. See {@link FormApproval}. */
    public boolean isRequireApproval() {
        return requireApproval;
    }

    @DataBoundSetter
    public void setRequireApproval(boolean requireApproval) {
        this.requireApproval = requireApproval;
        save();
    }

    /**
     * Off by default: only forms that ask for it ({@code sandbox: true}) run in
     * a sandboxed iframe. When on, every form or panel with code does.
     */
    public boolean isSandboxAll() {
        return sandboxAll;
    }

    @DataBoundSetter
    public void setSandboxAll(boolean sandboxAll) {
        this.sandboxAll = sandboxAll;
        save();
    }

    ArgoCdServer server(String name) {
        for (ArgoCdServer s : argoCdServers) {
            if (s.getName().equals(name)) {
                return s;
            }
        }
        return null;
    }
}
