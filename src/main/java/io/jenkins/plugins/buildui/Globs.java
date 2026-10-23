package io.jenkins.plugins.buildui;

import java.util.regex.Pattern;

/**
 * Globs ({@code *} any run, {@code ?} one character, everything else
 * literal) for filters that callers or admins write. They compile to
 * anchored patterns, so nobody can pass a backtracking regular expression.
 */
final class Globs {

    static final int MAX_LENGTH = 100;

    private Globs() {}

    static Pattern compile(String glob, String what) {
        if (glob.length() > MAX_LENGTH) {
            throw new IllegalArgumentException(what + " filter is longer than " + MAX_LENGTH + " characters");
        }
        StringBuilder regex = new StringBuilder();
        for (String part : glob.split("(?<=[*?])|(?=[*?])")) {
            regex.append(switch (part) {
                case "*" -> ".*";
                case "?" -> ".";
                default -> Pattern.quote(part);
            });
        }
        return Pattern.compile(regex.toString(), Pattern.DOTALL);
    }
}
