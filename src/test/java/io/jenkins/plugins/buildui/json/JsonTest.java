package io.jenkins.plugins.buildui.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** A REST caller controls the value text, so parsing it must have hard limits. */
class JsonTest {

    @Test
    void rejectsDeepNestingThatCouldExhaustTheControllerStack() {
        String deep = "[".repeat(Json.MAX_DEPTH + 5) + "]".repeat(Json.MAX_DEPTH + 5);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Json.parse(deep, "value"));
        assertTrue(e.getMessage().startsWith("value is not valid JSON"), e.getMessage());
    }

    @Test
    void rejectsOversizedDocumentsBeforeParsingThem() {
        String big = "\"" + "x".repeat(Json.MAX_CHARS) + "\"";
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Json.parse(big, "value"));
        assertTrue(e.getMessage().contains("the limit is " + Json.MAX_CHARS), e.getMessage());
    }

    @Test
    void rejectsHugeArraysEvenWhenTheTextIsShort() {
        String many = "[" + "0,".repeat(Json.MAX_ARRAY_ITEMS) + "0]";
        assertThrows(IllegalArgumentException.class, () -> Json.parse(many, "value"));
    }

    @Test
    void rejectsDuplicateKeysBecauseTwoReadersCouldSeeDifferentValues() {
        assertThrows(IllegalArgumentException.class, () -> Json.parse("{\"env\":\"dev\",\"env\":\"prod\"}", "value"));
    }

    @Test
    void writesCompactJsonForTheBuild() {
        assertEquals("{\"a\":[1,2]}", Json.write(Json.parse("{ \"a\" : [ 1, 2 ] }", "value")));
    }
}
