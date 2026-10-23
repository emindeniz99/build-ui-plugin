package io.jenkins.plugins.buildui;

import java.util.List;

/**
 * Runtime IDs the plugin ships. An ID names a major version, so a patch
 * update keeps it and existing Jenkinsfiles keep working.
 */
public final class Runtimes {

    public static final String NONE = "none";
    public static final String PREACT = "preact11-htm3";
    public static final String SCHEMA_FORM = "schema-form";

    public static final List<String> ALL = List.of(NONE, PREACT, SCHEMA_FORM);

    private Runtimes() {}

    static String check(String id) {
        if (!ALL.contains(id)) {
            throw new IllegalArgumentException("unknown runtime '" + id + "'; available: " + ALL);
        }
        return id;
    }
}
