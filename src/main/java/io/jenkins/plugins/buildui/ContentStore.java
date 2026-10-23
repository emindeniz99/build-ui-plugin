package io.jenkins.plugins.buildui;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Holds the module, CSS and HTML text of every rendered form, keyed by the
 * SHA-256 of its bytes. The hash in a URL is only a cache key: the endpoint
 * serves bytes that a parameter definition registered, nothing else.
 */
public final class ContentStore {

    static final int CAPACITY = 2000;

    public record Content(byte[] bytes, String contentType) {}

    private static final Map<String, Content> ENTRIES = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Content> eldest) {
            return size() > CAPACITY;
        }
    };

    private ContentStore() {}

    /** Registers {@code text} and returns its key, for example {@code 3f2a…e1.mjs}. */
    public static String put(String text, String extension, String contentType) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        String key = sha256(bytes) + "." + extension;
        synchronized (ENTRIES) {
            ENTRIES.putIfAbsent(key, new Content(bytes, contentType));
        }
        return key;
    }

    public static Content get(String key) {
        synchronized (ENTRIES) {
            return ENTRIES.get(key);
        }
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
