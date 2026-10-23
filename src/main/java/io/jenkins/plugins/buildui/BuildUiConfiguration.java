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

    ArgoCdServer server(String name) {
        for (ArgoCdServer s : argoCdServers) {
            if (s.getName().equals(name)) {
                return s;
            }
        }
        return null;
    }
}
