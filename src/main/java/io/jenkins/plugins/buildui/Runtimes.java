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
    public static final String ALPINE_CSP = "alpine3-csp";
    public static final String ALPINE = "alpine3";
    public static final String VUE = "vue3";

    public static final List<String> ALL = Contract.RUNTIMES;

    private Runtimes() {}

    static String check(String id) {
        if (!ALL.contains(id)) {
            throw new IllegalArgumentException("unknown runtime '" + id + "'; available: " + ALL);
        }
        return id;
    }

    /** Whether the runtime compiles templates with eval, which enforced CSP blocks. */
    static boolean needsEval(String id) {
        return Contract.EVAL_RUNTIMES.contains(id);
    }

    /** False for a runtime that needs eval while an admin has not allowed such runtimes. */
    static boolean allowed(String id) {
        return !needsEval(id) || BuildUiConfiguration.get().isAllowEvalRuntimes();
    }
}
