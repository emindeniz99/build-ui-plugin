package io.jenkins.plugins.buildui.json;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * A deliberately small JSON Schema (draft 2020-12) subset that runs on the
 * controller. There is no {@code $ref} or any other keyword that loads a
 * document, and {@code pattern} runs with a time budget. An unsupported
 * keyword fails when the schema is configured, so an author never believes a
 * rule is enforced when it is not.
 */
public final class Schema {

    static final int MAX_PATTERN_LENGTH = 256;
    static final long PATTERN_BUDGET_NANOS = 50_000_000L;

    private static final Set<String> ANNOTATIONS = Set.of(
            "$schema", "$id", "$comment", "title", "description", "default", "examples",
            "readOnly", "writeOnly", "deprecated", "format");
    private static final Set<String> VALIDATIONS = Set.of(
            "type", "enum", "const", "required", "properties", "additionalProperties",
            "minProperties", "maxProperties", "items", "minItems", "maxItems", "uniqueItems",
            "minLength", "maxLength", "pattern", "minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum");
    private static final Set<String> TYPES = Set.of("string", "number", "integer", "boolean", "object", "array", "null");

    private final JsonNode root;

    private Schema(JsonNode root) {
        this.root = root;
    }

    /** Compiles a schema or throws {@link IllegalArgumentException} naming the first problem. */
    public static Schema compile(JsonNode schema) {
        check(schema, "");
        return new Schema(schema);
    }

    public JsonNode node() {
        return root;
    }

    /** Returns one message per violation, each prefixed with a JSON Pointer to the value. */
    public List<String> validate(JsonNode value) {
        List<String> errors = new ArrayList<>();
        validate(root, value, "", errors);
        return errors;
    }

    private static void check(JsonNode s, String at) {
        if (s.isBoolean()) {
            return;
        }
        if (!s.isObject()) {
            throw bad(at, "a schema must be an object or a boolean");
        }
        for (Iterator<Map.Entry<String, JsonNode>> it = s.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            String k = e.getKey();
            JsonNode v = e.getValue();
            if (ANNOTATIONS.contains(k) || k.startsWith("x-")) {
                continue;
            }
            if (!VALIDATIONS.contains(k)) {
                throw bad(at, "keyword '" + k + "' is not supported; supported: " + new java.util.TreeSet<>(VALIDATIONS));
            }
            switch (k) {
                case "type" -> {
                    List<JsonNode> types = new ArrayList<>();
                    if (v.isArray()) v.forEach(types::add); else types.add(v);
                    for (JsonNode t : types) {
                        if (!t.isTextual() || !TYPES.contains(t.asText())) {
                            throw bad(at, "unknown type " + t);
                        }
                    }
                }
                case "enum" -> {
                    if (!v.isArray() || v.isEmpty()) throw bad(at, "enum must be a non-empty array");
                }
                case "required" -> {
                    if (!v.isArray()) throw bad(at, "required must be an array of names");
                    v.forEach(n -> {
                        if (!n.isTextual()) throw bad(at, "required must be an array of names");
                    });
                }
                case "properties" -> {
                    if (!v.isObject()) throw bad(at, "properties must be an object");
                    v.fields().forEachRemaining(p -> check(p.getValue(), at + "/properties/" + p.getKey()));
                }
                case "additionalProperties", "items" -> check(v, at + "/" + k);
                case "pattern" -> {
                    if (!v.isTextual()) throw bad(at, "pattern must be a string");
                    if (v.asText().length() > MAX_PATTERN_LENGTH) throw bad(at, "pattern is longer than " + MAX_PATTERN_LENGTH);
                    try {
                        Pattern.compile(v.asText());
                    } catch (PatternSyntaxException ex) {
                        throw bad(at, "pattern does not compile: " + ex.getDescription());
                    }
                }
                case "uniqueItems" -> {
                    if (!v.isBoolean()) throw bad(at, "uniqueItems must be a boolean");
                }
                case "const" -> { }
                default -> {
                    if (!v.isNumber()) throw bad(at, k + " must be a number");
                }
            }
        }
    }

    private static IllegalArgumentException bad(String at, String message) {
        return new IllegalArgumentException("schema" + (at.isEmpty() ? "" : " at " + at) + ": " + message);
    }

    private static void validate(JsonNode s, JsonNode v, String at, List<String> errors) {
        if (s.isBoolean()) {
            if (!s.asBoolean()) errors.add(where(at) + "no value is allowed here");
            return;
        }
        JsonNode type = s.get("type");
        if (type != null && !matchesType(type, v)) {
            errors.add(where(at) + "expected " + (type.isArray() ? "one of " + type : type.asText()) + ", got " + typeOf(v));
            return;
        }
        JsonNode en = s.get("enum");
        if (en != null && !contains(en, v)) {
            errors.add(where(at) + "must be one of " + en);
        }
        JsonNode cn = s.get("const");
        if (cn != null && !cn.equals(v)) {
            errors.add(where(at) + "must be " + cn);
        }
        if (v.isTextual()) validateString(s, v.asText(), at, errors);
        if (v.isNumber()) validateNumber(s, v.decimalValue(), at, errors);
        if (v.isArray()) validateArray(s, v, at, errors);
        if (v.isObject()) validateObject(s, v, at, errors);
    }

    private static void validateString(JsonNode s, String text, String at, List<String> errors) {
        int length = text.codePointCount(0, text.length());
        if (s.has("minLength") && length < s.get("minLength").asInt()) {
            errors.add(where(at) + "must have at least " + s.get("minLength").asInt() + " characters");
        }
        if (s.has("maxLength") && length > s.get("maxLength").asInt()) {
            errors.add(where(at) + "must have at most " + s.get("maxLength").asInt() + " characters");
        }
        if (s.has("pattern")) {
            String pattern = s.get("pattern").asText();
            try {
                if (!Pattern.compile(pattern).matcher(new Budgeted(text)).find()) {
                    errors.add(where(at) + "must match " + pattern);
                }
            } catch (Budgeted.Exhausted e) {
                errors.add(where(at) + "pattern " + pattern + " took too long to evaluate");
            }
        }
    }

    private static void validateNumber(JsonNode s, BigDecimal n, String at, List<String> errors) {
        if (s.has("minimum") && n.compareTo(s.get("minimum").decimalValue()) < 0) {
            errors.add(where(at) + "must be >= " + s.get("minimum"));
        }
        if (s.has("maximum") && n.compareTo(s.get("maximum").decimalValue()) > 0) {
            errors.add(where(at) + "must be <= " + s.get("maximum"));
        }
        if (s.has("exclusiveMinimum") && n.compareTo(s.get("exclusiveMinimum").decimalValue()) <= 0) {
            errors.add(where(at) + "must be > " + s.get("exclusiveMinimum"));
        }
        if (s.has("exclusiveMaximum") && n.compareTo(s.get("exclusiveMaximum").decimalValue()) >= 0) {
            errors.add(where(at) + "must be < " + s.get("exclusiveMaximum"));
        }
    }

    private static void validateArray(JsonNode s, JsonNode v, String at, List<String> errors) {
        if (s.has("minItems") && v.size() < s.get("minItems").asInt()) {
            errors.add(where(at) + "must have at least " + s.get("minItems").asInt() + " items");
        }
        if (s.has("maxItems") && v.size() > s.get("maxItems").asInt()) {
            errors.add(where(at) + "must have at most " + s.get("maxItems").asInt() + " items");
        }
        if (s.path("uniqueItems").asBoolean(false)) {
            Set<JsonNode> seen = new HashSet<>();
            for (JsonNode item : v) {
                if (!seen.add(item)) {
                    errors.add(where(at) + "has duplicate item " + item);
                    break;
                }
            }
        }
        if (s.has("items")) {
            for (int i = 0; i < v.size(); i++) {
                validate(s.get("items"), v.get(i), at + "/" + i, errors);
            }
        }
    }

    private static void validateObject(JsonNode s, JsonNode v, String at, List<String> errors) {
        if (s.has("minProperties") && v.size() < s.get("minProperties").asInt()) {
            errors.add(where(at) + "must have at least " + s.get("minProperties").asInt() + " properties");
        }
        if (s.has("maxProperties") && v.size() > s.get("maxProperties").asInt()) {
            errors.add(where(at) + "must have at most " + s.get("maxProperties").asInt() + " properties");
        }
        if (s.has("required")) {
            for (JsonNode name : s.get("required")) {
                if (!v.has(name.asText())) {
                    errors.add(where(at) + "is missing required property '" + name.asText() + "'");
                }
            }
        }
        JsonNode props = s.path("properties");
        JsonNode extra = s.get("additionalProperties");
        for (Iterator<Map.Entry<String, JsonNode>> it = v.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            String child = at + "/" + e.getKey().replace("~", "~0").replace("/", "~1");
            if (props.has(e.getKey())) {
                validate(props.get(e.getKey()), e.getValue(), child, errors);
            } else if (extra != null) {
                validate(extra, e.getValue(), child, errors);
            }
        }
    }

    private static boolean matchesType(JsonNode type, JsonNode v) {
        if (type.isArray()) {
            for (JsonNode t : type) {
                if (matchesType(t, v)) return true;
            }
            return false;
        }
        return switch (type.asText()) {
            case "string" -> v.isTextual();
            case "number" -> v.isNumber();
            case "integer" -> v.isNumber() && v.decimalValue().stripTrailingZeros().scale() <= 0;
            case "boolean" -> v.isBoolean();
            case "object" -> v.isObject();
            case "array" -> v.isArray();
            case "null" -> v.isNull();
            default -> false;
        };
    }

    private static boolean contains(JsonNode array, JsonNode v) {
        for (JsonNode candidate : array) {
            if (candidate.equals(v)) return true;
        }
        return false;
    }

    private static String typeOf(JsonNode v) {
        if (v.isTextual()) return "string";
        if (v.isNumber()) return "number";
        if (v.isBoolean()) return "boolean";
        if (v.isObject()) return "object";
        if (v.isArray()) return "array";
        return "null";
    }

    private static String where(String at) {
        return (at.isEmpty() ? "/" : at) + ": ";
    }

    /** Stops a regular expression that backtracks past its time budget (ReDoS guard). */
    private static final class Budgeted implements CharSequence {
        static final class Exhausted extends RuntimeException {
            Exhausted() {
                super(null, null, false, false);
            }
        }

        private final CharSequence inner;
        private final long deadline;

        Budgeted(CharSequence inner) {
            this(inner, System.nanoTime() + PATTERN_BUDGET_NANOS);
        }

        private Budgeted(CharSequence inner, long deadline) {
            this.inner = inner;
            this.deadline = deadline;
        }

        @Override
        public char charAt(int index) {
            if (System.nanoTime() > deadline) throw new Exhausted();
            return inner.charAt(index);
        }

        @Override
        public int length() {
            return inner.length();
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return new Budgeted(inner.subSequence(start, end), deadline);
        }

        @Override
        public String toString() {
            return inner.toString();
        }
    }
}
