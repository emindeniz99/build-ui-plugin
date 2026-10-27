package io.jenkins.plugins.buildui.json;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import io.jenkins.plugins.buildui.Contract;

/**
 * A deliberately small JSON Schema (draft 2020-12) subset that runs on the
 * controller. There is no {@code $ref} or any other keyword that loads a
 * document, and all {@code pattern} checks of one document share one time
 * budget, so a value with many strings cannot multiply it. An unsupported
 * keyword fails when the schema is configured, so an author never believes a
 * rule is enforced when it is not.
 */
public final class Schema {

    static final int MAX_PATTERN_LENGTH = Contract.MAX_PATTERN_LENGTH;
    static final long PATTERN_BUDGET_NANOS = 50_000_000L;

    private static final Set<String> ANNOTATIONS = Contract.SCHEMA_ANNOTATIONS;
    private static final Set<String> VALIDATIONS = Contract.SCHEMA_VALIDATIONS;
    private static final Set<String> TYPES = Contract.SCHEMA_TYPES;
    private static final Set<String> GIT_REF_KINDS = Set.of("branch", "tag", "any");

    private final JsonNode root;
    /** Compiled once per schema node, so a document with many strings does not recompile its pattern for each. */
    private final Map<JsonNode, Pattern> patterns;

    private Schema(JsonNode root, Map<JsonNode, Pattern> patterns) {
        this.root = root;
        this.patterns = patterns;
    }

    /** Compiles a schema or throws {@link IllegalArgumentException} naming the first problem. */
    public static Schema compile(JsonNode schema) {
        Map<JsonNode, Pattern> patterns = new IdentityHashMap<>();
        check(schema, "", patterns);
        return new Schema(schema, patterns);
    }

    public JsonNode node() {
        return root;
    }

    /** Returns one message per violation, each prefixed with a JSON Pointer to the value. */
    public List<String> validate(JsonNode value) {
        List<String> errors = new ArrayList<>();
        validate(root, value, "", errors, new Budget(patterns, System.nanoTime() + PATTERN_BUDGET_NANOS));
        return errors;
    }

    /** The pattern time left for one document. */
    private static final class Budget {
        final Map<JsonNode, Pattern> patterns;
        final long deadline;
        boolean spent;

        Budget(Map<JsonNode, Pattern> patterns, long deadline) {
            this.patterns = patterns;
            this.deadline = deadline;
        }
    }

    private static void check(JsonNode s, String at, Map<JsonNode, Pattern> patterns) {
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
            if (k.equals("x-git-ref") && !(v.isBoolean() && v.asBoolean() || v.isTextual() && GIT_REF_KINDS.contains(v.asText()))) {
                throw bad(at, "x-git-ref must be true, \"branch\", \"tag\" or \"any\"");
            }
            if (k.equals("x-git-repository")) {
                // A glob of the job's remote URLs; the same 100-character limit as the gitRefs globs.
                if (!v.isTextual() || v.asText().isEmpty() || v.asText().length() > 100) {
                    throw bad(at, "x-git-repository must be a glob of a repository URL, 1 to 100 characters");
                }
                if (!s.has("x-git-ref")) {
                    throw bad(at, "x-git-repository needs x-git-ref next to it");
                }
            }
            if (ANNOTATIONS.contains(k) || k.startsWith("x-")) {
                continue;
            }
            if (!VALIDATIONS.contains(k)) {
                throw bad(at, "keyword '" + k + "' is not supported; supported: " + VALIDATIONS);
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
                    v.fields().forEachRemaining(p -> check(p.getValue(), at + "/properties/" + p.getKey(), patterns));
                }
                case "additionalProperties", "items" -> check(v, at + "/" + k, patterns);
                case "pattern" -> {
                    if (!v.isTextual()) throw bad(at, "pattern must be a string");
                    if (v.asText().length() > MAX_PATTERN_LENGTH) throw bad(at, "pattern is longer than " + MAX_PATTERN_LENGTH);
                    try {
                        patterns.put(s, Pattern.compile(v.asText()));
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

    private static void validate(JsonNode s, JsonNode v, String at, List<String> errors, Budget budget) {
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
        if (cn != null && !canonical(cn).equals(canonical(v))) {
            errors.add(where(at) + "must be " + cn);
        }
        if (v.isTextual()) validateString(s, v.asText(), at, errors, budget);
        if (v.isNumber()) validateNumber(s, v.decimalValue(), at, errors);
        if (v.isArray()) validateArray(s, v, at, errors, budget);
        if (v.isObject()) validateObject(s, v, at, errors, budget);
    }

    private static void validateString(JsonNode s, String text, String at, List<String> errors, Budget budget) {
        int length = text.codePointCount(0, text.length());
        if (s.has("minLength") && length < s.get("minLength").asInt()) {
            errors.add(where(at) + "must have at least " + s.get("minLength").asInt() + " characters");
        }
        if (s.has("maxLength") && length > s.get("maxLength").asInt()) {
            errors.add(where(at) + "must have at most " + s.get("maxLength").asInt() + " characters");
        }
        // Once the budget is spent the document already has an error, so later patterns are skipped.
        if (s.has("pattern") && !budget.spent) {
            Pattern pattern = budget.patterns.get(s);
            try {
                if (!pattern.matcher(new Budgeted(text, budget.deadline)).find()) {
                    errors.add(where(at) + "must match " + pattern);
                }
            } catch (Budgeted.Exhausted e) {
                budget.spent = true;
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

    private static void validateArray(JsonNode s, JsonNode v, String at, List<String> errors, Budget budget) {
        if (s.has("minItems") && v.size() < s.get("minItems").asInt()) {
            errors.add(where(at) + "must have at least " + s.get("minItems").asInt() + " items");
        }
        if (s.has("maxItems") && v.size() > s.get("maxItems").asInt()) {
            errors.add(where(at) + "must have at most " + s.get("maxItems").asInt() + " items");
        }
        if (s.path("uniqueItems").asBoolean(false)) {
            Set<JsonNode> seen = new HashSet<>();
            for (JsonNode item : v) {
                if (!seen.add(canonical(item))) {
                    errors.add(where(at) + "has duplicate item " + item);
                    break;
                }
            }
        }
        if (s.has("items")) {
            for (int i = 0; i < v.size(); i++) {
                validate(s.get("items"), v.get(i), at + "/" + i, errors, budget);
            }
        }
    }

    private static void validateObject(JsonNode s, JsonNode v, String at, List<String> errors, Budget budget) {
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
                validate(props.get(e.getKey()), e.getValue(), child, errors, budget);
            } else if (extra != null) {
                validate(extra, e.getValue(), child, errors, budget);
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
        JsonNode value = canonical(v);
        for (JsonNode candidate : array) {
            if (canonical(candidate).equals(value)) return true;
        }
        return false;
    }

    /**
     * A copy in which equal numbers are equal nodes ({@code 5.0} and {@code 5}),
     * as JSON Schema compares them; Jackson's own equality tells them apart.
     */
    private static JsonNode canonical(JsonNode v) {
        if (v.isNumber()) {
            return DecimalNode.valueOf(v.decimalValue().stripTrailingZeros());
        }
        if (v.isArray()) {
            ArrayNode copy = JsonNodeFactory.instance.arrayNode(v.size());
            v.forEach(item -> copy.add(canonical(item)));
            return copy;
        }
        if (v.isObject()) {
            ObjectNode copy = JsonNodeFactory.instance.objectNode();
            v.fields().forEachRemaining(e -> copy.set(e.getKey(), canonical(e.getValue())));
            return copy;
        }
        return v;
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

        Budgeted(CharSequence inner, long deadline) {
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
