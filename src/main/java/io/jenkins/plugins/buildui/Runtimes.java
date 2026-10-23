package io.jenkins.plugins.buildui;

import java.util.List;
import java.util.logging.Logger;

/**
 * Runtime IDs the plugin ships. An ID names a major version, so a patch
 * update keeps it and existing Jenkinsfiles keep working.
 */
public final class Runtimes {

    public static final String NONE = "none";
    public static final String PREACT = "preact11-htm3";
    public static final String SCHEMA_FORM = "schema-form";

    public static final List<String> ALL = Contract.RUNTIMES;

    private static final Logger LOGGER = Logger.getLogger(Runtimes.class.getName());

    private Runtimes() {}

    static String check(String id) {
        if (!ALL.contains(id)) {
            throw new IllegalArgumentException("unknown runtime '" + id + "'; available: " + ALL);
        }
        return id;
    }

    /** For IDs read from disk, which skipped {@link #check}: an unknown one becomes {@link #PREACT}. */
    static String orDefault(String id, String owner) {
        if (id != null && ALL.contains(id)) {
            return id;
        }
        LOGGER.warning(() -> "Unknown build-ui runtime '" + id + "' in " + owner + "; using " + PREACT);
        return PREACT;
    }
}
