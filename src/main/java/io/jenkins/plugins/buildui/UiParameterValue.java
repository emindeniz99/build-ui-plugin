package io.jenkins.plugins.buildui;

import hudson.EnvVars;
import hudson.model.Run;
import hudson.model.StringParameterValue;
import java.util.logging.Logger;

/**
 * The JSON string a {@link UiParameterDefinition} produced. It is a
 * {@link StringParameterValue}, so {@code params.NAME} and downstream
 * {@code build} steps see a plain string.
 */
public class UiParameterValue extends StringParameterValue {

    private static final long serialVersionUID = 1L;
    private static final Logger LOGGER = Logger.getLogger(UiParameterValue.class.getName());

    /** Larger values stay in {@code params} but do not become an environment variable of build processes. */
    static final int MAX_ENV_CHARS = 32 * 1024;

    public UiParameterValue(String name, String json, String description) {
        super(name, json, description);
    }

    @Override
    public void buildEnvironment(Run<?, ?> build, EnvVars env) {
        String json = (String) getValue();
        if (json != null && json.length() > MAX_ENV_CHARS) {
            LOGGER.fine(() -> "Not exporting " + getName() + " (" + json.length() + " chars) as an environment variable of " + build);
            return;
        }
        super.buildEnvironment(build, env);
    }
}
