package com.os4.musiccover;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Stable, opaque cache identities; length prefixes keep settings and track fields unambiguous. */
final class LyricCacheScope {
    private LyricCacheScope() { }

    static String digest(String... fields) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String field : fields) {
                byte[] bytes = (field == null ? "" : field).getBytes(StandardCharsets.UTF_8);
                digest.update((byte) (bytes.length >>> 24));
                digest.update((byte) (bytes.length >>> 16));
                digest.update((byte) (bytes.length >>> 8));
                digest.update((byte) bytes.length);
                digest.update(bytes);
            }
            StringBuilder out = new StringBuilder(64);
            for (byte b : digest.digest()) {
                out.append(Character.forDigit((b >>> 4) & 15, 16));
                out.append(Character.forDigit(b & 15, 16));
            }
            return out.toString();
        } catch (java.security.NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is required by the runtime", unavailable);
        }
    }

    static String track(String configuration, String pkg, String mediaId) {
        return digest("queue-lyrics-v2", configuration, pkg, mediaId);
    }

    /** A result from before A -> B -> A must not complete a new request under the same settings. */
    static boolean current(long requestRevision, String requestConfiguration,
                           long revision, String configuration) {
        return requestRevision == revision && requestConfiguration.equals(configuration);
    }
}
