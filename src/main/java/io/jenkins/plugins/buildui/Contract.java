package io.jenkins.plugins.buildui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * What the plugin accepts from a Jenkinsfile: runtime IDs and the JSON Schema
 * keyword subset. The list lives in {@code contract.json} so the browser-free
 * checker in {@code js/scripts/check.mjs} reads the same one.
 */
public final class Contract {

    private static final JsonNode CONTRACT = load();

    public static final List<String> RUNTIMES = strings("runtimes");
    /** Runtimes that compile templates with eval, so enforced CSP stops them. */
    public static final Set<String> EVAL_RUNTIMES = Set.copyOf(strings("evalRuntimes"));
    public static final Set<String> SCHEMA_ANNOTATIONS = Set.copyOf(strings("schemaAnnotations"));
    /** Sorted, so error messages list the keywords in a stable order. */
    public static final Set<String> SCHEMA_VALIDATIONS = Collections.unmodifiableSortedSet(new TreeSet<>(strings("schemaValidations")));
    public static final Set<String> SCHEMA_TYPES = Set.copyOf(strings("schemaTypes"));
    public static final int MAX_PATTERN_LENGTH = CONTRACT.get("maxPatternLength").asInt();

    private Contract() {}

    private static JsonNode load() {
        try (InputStream in = Contract.class.getResourceAsStream("contract.json")) {
            if (in == null) {
                throw new IllegalStateException("contract.json is missing from the plugin");
            }
            return JsonMapper.builder().build().readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<String> strings(String key) {
        List<String> out = new ArrayList<>();
        CONTRACT.get(key).forEach(n -> out.add(n.asText()));
        return List.copyOf(out);
    }
}
