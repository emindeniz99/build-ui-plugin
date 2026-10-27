package io.jenkins.plugins.buildui;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * One-time tokens for the module of a sandboxed form. A sandboxed frame has an
 * opaque origin, so its requests carry no cookies and cannot pass the
 * Overall/Read check of the content endpoint. The protected frame endpoint
 * mints a token for one content key; the unprotected
 * {@link SandboxAction} serves that key once, within {@link #TTL_MILLIS}.
 */
final class SandboxTokens {

    static final long TTL_MILLIS = 60_000;
    static final int CAPACITY = 10_000;
    /** 128 random bits, hex. */
    static final Pattern TOKEN = Pattern.compile("[0-9a-f]{32}");

    private record Grant(String key, long expires) {}

    private static final SecureRandom RANDOM = new SecureRandom();

    /** Oldest first, so a full store drops the grant closest to expiry. */
    private static final Map<String, Grant> GRANTS = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Grant> eldest) {
            return size() > CAPACITY;
        }
    };

    private SandboxTokens() {}

    static String mint(String contentKey, long now) {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        String token = HexFormat.of().formatHex(bytes);
        synchronized (GRANTS) {
            GRANTS.put(token, new Grant(contentKey, now + TTL_MILLIS));
        }
        return token;
    }

    /** The content key the token was minted for, or null when it is unknown, used or expired. Uses it up. */
    @CheckForNull
    static String redeem(String token, long now) {
        Grant grant;
        synchronized (GRANTS) {
            grant = GRANTS.remove(token);
        }
        return grant == null || now > grant.expires() ? null : grant.key();
    }
}
