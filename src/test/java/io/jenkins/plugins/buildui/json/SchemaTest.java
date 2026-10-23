package io.jenkins.plugins.buildui.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class SchemaTest {

    private static List<String> errors(String schema, String value) {
        return Schema.compile(Json.parse(schema, "schema")).validate(Json.parse(value, "value"));
    }

    private static final String BUILD = """
            {"type":"object","required":["branch","services"],"additionalProperties":false,
             "properties":{
               "branch":{"type":"string","minLength":1,"pattern":"^[\\\\w./-]+$"},
               "services":{"type":"array","minItems":1,"uniqueItems":true,"items":{"enum":["api","worker","web"]}},
               "replicas":{"type":"integer","minimum":1,"maximum":5}}}""";

    @Test
    void acceptsAValueThatFollowsEveryRule() {
        assertEquals(List.of(), errors(BUILD, "{\"branch\":\"main\",\"services\":[\"api\"],\"replicas\":2}"));
    }

    @Test
    void namesEachViolationWithItsPathSoTheAuthorCanFixTheForm() {
        List<String> e = errors(BUILD, "{\"branch\":\"\",\"services\":[\"api\",\"db\",\"api\"],\"replicas\":9,\"extra\":1}");
        assertTrue(e.contains("/branch: must have at least 1 characters"), e::toString);
        assertTrue(e.contains("/services: has duplicate item \"api\""), e::toString);
        assertTrue(e.stream().anyMatch(m -> m.startsWith("/services/1: must be one of")), e::toString);
        assertTrue(e.contains("/replicas: must be <= 5"), e::toString);
        assertTrue(e.contains("/extra: no value is allowed here"), e::toString);
    }

    @Test
    void rejectsAMissingRequiredFieldBecauseTheBuildRelyOnIt() {
        assertEquals(List.of("/: is missing required property 'services'"), errors(BUILD, "{\"branch\":\"main\"}"));
    }

    @Test
    void treatsOnePointZeroAsAnIntegerLikeJsonSchemaDoes() {
        assertEquals(List.of(), errors("{\"type\":\"integer\"}", "1.0"));
        assertEquals(1, errors("{\"type\":\"integer\"}", "1.5").size());
    }

    @Test
    void refusesRefSoTheControllerNeverFetchesARemoteSchema() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> Schema.compile(Json.parse("{\"$ref\":\"https://evil.example/s.json\"}", "schema")));
        assertTrue(e.getMessage().contains("'$ref' is not supported"), e.getMessage());
    }

    @Test
    void refusesUnknownKeywordsSoNoRuleIsSilentlyIgnored() {
        assertThrows(IllegalArgumentException.class,
                () -> Schema.compile(Json.parse("{\"type\":\"object\",\"oneOf\":[]}", "schema")));
        assertThrows(IllegalArgumentException.class,
                () -> Schema.compile(Json.parse("{\"properties\":{\"a\":{\"minLenght\":1}}}", "schema")));
    }

    @Test
    void allowsExtensionKeywordsThatTheDeclarativeFormReads() {
        assertEquals(List.of(), errors("{\"type\":\"object\",\"properties\":{\"env\":{\"type\":\"string\",\"x-show-if\":{\"deploy\":true}}}}", "{}"));
    }

    @Test
    void stopsCatastrophicBacktrackingInsteadOfHangingTheController() {
        // Java 21 runs ^(a+)+$ in linear time, but this one took 9.7 s without the budget.
        String csv = "1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20,21,22,23,24,25,26,27,28,29,30";
        long start = System.nanoTime();
        List<String> e = errors("{\"type\":\"string\",\"pattern\":\"^(.*?,){14}P\"}", "\"" + csv + "\"");
        long millis = (System.nanoTime() - start) / 1_000_000;
        assertEquals(List.of("/: pattern ^(.*?,){14}P took too long to evaluate"), e);
        assertTrue(millis < 2000, "took " + millis + " ms");
    }

    @Test
    void thePatternBudgetCoversTheWholeDocumentSoManyStringsCannotMultiplyIt() {
        // One request carries both schema and value, so a per-string budget let 100 strings cost 100 x 50 ms.
        String csv = "\"" + "a,".repeat(30) + "\"";
        String value = "[" + String.join(",", java.util.Collections.nCopies(100, csv)) + "]";
        long start = System.nanoTime();
        List<String> e = errors("{\"type\":\"array\",\"items\":{\"type\":\"string\",\"pattern\":\"^(.*?,){14}P\"}}", value);
        long millis = (System.nanoTime() - start) / 1_000_000;
        assertEquals(1, e.size(), e::toString);
        assertTrue(e.get(0).endsWith("took too long to evaluate"), e::toString);
        assertTrue(millis < 1000, "took " + millis + " ms");
    }

    @Test
    void aNumberPastTheDoubleRangeIsValidatedInsteadOfCrashing() {
        // 1e400 used to become Infinity, and decimalValue() threw: a 500 for the user, an exception in the queue.
        assertEquals(List.of("/: must be <= 5"), errors("{\"type\":\"number\",\"maximum\":5}", "1e400"));
        assertEquals(List.of(), errors("{\"type\":\"integer\",\"minimum\":5}", "1e400"));
    }

    @Test
    void comparesNumbersByValueInEnumConstAndUniqueItemsLikeJsonSchema() {
        // A script that writes 5.0 means the same number as a schema that says 5.
        assertEquals(List.of(), errors("{\"enum\":[5,\"x\"]}", "5.0"));
        assertEquals(List.of(), errors("{\"const\":{\"n\":[1]}}", "{\"n\":[1.0]}"));
        assertEquals(List.of("/: has duplicate item 1.0"), errors("{\"type\":\"array\",\"uniqueItems\":true}", "[1,1.0]"));
        assertEquals(1, errors("{\"enum\":[5]}", "5.5").size());
    }

    @Test
    void rejectsAPatternThatDoesNotCompileWhenTheJobIsConfigured() {
        assertThrows(IllegalArgumentException.class, () -> Schema.compile(Json.parse("{\"pattern\":\"(\"}", "schema")));
    }
}
