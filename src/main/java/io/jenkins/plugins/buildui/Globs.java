package io.jenkins.plugins.buildui;

/**
 * Globs ({@code *} any run, {@code ?} one character, everything else
 * literal) for filters that callers or admins write. They match without a
 * regular expression: {@code .*a.*a.*a.*a.*b}, the regex such a glob used to
 * compile to, took 20 s against 200 {@code a}s (Java 21). This matcher keeps
 * only the last {@code *} to retry from, so a match costs at most glob length
 * times name length steps.
 */
final class Globs {

    static final int MAX_LENGTH = 100;

    private Globs() {}

    /** One checked glob. */
    static final class Glob {
        private final int[] glob;

        private Glob(int[] glob) {
            this.glob = glob;
        }

        boolean matches(String name) {
            int[] s = name.codePoints().toArray();
            int p = 0;
            int i = 0;
            int star = -1;
            int mark = 0;
            while (i < s.length) {
                if (p < glob.length && glob[p] != '*' && (glob[p] == '?' || glob[p] == s[i])) {
                    p++;
                    i++;
                } else if (p < glob.length && glob[p] == '*') {
                    star = p++;
                    mark = i;
                } else if (star >= 0) {
                    p = star + 1;
                    i = ++mark;
                } else {
                    return false;
                }
            }
            while (p < glob.length && glob[p] == '*') {
                p++;
            }
            return p == glob.length;
        }
    }

    static Glob compile(String glob, String what) {
        if (glob.length() > MAX_LENGTH) {
            throw new IllegalArgumentException(what + " filter is longer than " + MAX_LENGTH + " characters");
        }
        return new Glob(glob.codePoints().toArray());
    }
}
