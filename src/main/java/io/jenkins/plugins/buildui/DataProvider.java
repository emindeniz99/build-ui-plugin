package io.jenkins.plugins.buildui;

import com.fasterxml.jackson.databind.JsonNode;
import hudson.ExtensionList;
import hudson.ExtensionPoint;

/**
 * Server-side data for {@code ui.query(id, args)}. It runs as the user who
 * opened the form, so it sees only what that user may read. Throw
 * {@link IllegalArgumentException} for bad arguments; the caller gets 400.
 */
public abstract class DataProvider implements ExtensionPoint {

    /** The ID a module passes to {@code ui.query}. */
    public abstract String getId();

    public abstract JsonNode query(JsonNode args);

    static DataProvider find(String id) {
        for (DataProvider provider : ExtensionList.lookup(DataProvider.class)) {
            if (provider.getId().equals(id)) {
                return provider;
            }
        }
        return null;
    }
}
