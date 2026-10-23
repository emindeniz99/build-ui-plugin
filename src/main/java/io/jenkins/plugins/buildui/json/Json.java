package io.jenkins.plugins.buildui.json;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.util.Iterator;

/**
 * Parses untrusted JSON (build values, schemas) with hard limits, so a
 * caller of the REST API cannot make the controller parse a huge or deeply
 * nested document.
 */
public final class Json {

    public static final int MAX_CHARS = 256 * 1024;
    public static final int MAX_DEPTH = 32;
    public static final int MAX_ARRAY_ITEMS = 1000;
    public static final int MAX_OBJECT_PROPERTIES = 1000;

    private static final JsonMapper MAPPER = JsonMapper.builder(JsonFactory.builder()
                    .streamReadConstraints(StreamReadConstraints.builder()
                            .maxNestingDepth(MAX_DEPTH)
                            .maxStringLength(MAX_CHARS)
                            .maxDocumentLength(MAX_CHARS)
                            .build())
                    .build())
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .build();

    private Json() {}

    /** Parses {@code text} or throws {@link IllegalArgumentException} with a message fit for the user. */
    public static JsonNode parse(String text, String what) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException(what + " is empty");
        }
        if (text.length() > MAX_CHARS) {
            throw new IllegalArgumentException(what + " is " + text.length() + " characters; the limit is " + MAX_CHARS);
        }
        JsonNode node;
        try {
            node = MAPPER.readTree(text);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(what + " is not valid JSON: " + e.getOriginalMessage(), e);
        }
        if (node == null || node.isMissingNode()) {
            throw new IllegalArgumentException(what + " is empty");
        }
        checkSize(node, what);
        return node;
    }

    public static String write(JsonNode node) {
        try {
            return MAPPER.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void checkSize(JsonNode node, String what) {
        if (node.isArray() && node.size() > MAX_ARRAY_ITEMS) {
            throw new IllegalArgumentException(what + " has an array with " + node.size() + " items; the limit is " + MAX_ARRAY_ITEMS);
        }
        if (node.isObject() && node.size() > MAX_OBJECT_PROPERTIES) {
            throw new IllegalArgumentException(what + " has an object with " + node.size() + " properties; the limit is " + MAX_OBJECT_PROPERTIES);
        }
        for (Iterator<JsonNode> it = node.elements(); it.hasNext(); ) {
            checkSize(it.next(), what);
        }
    }
}
