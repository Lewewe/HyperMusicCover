package com.os4.musiccover;

/** Remember a player's final small bitmap across pill and cover presentation changes. */
final class ArtworkQualityState {
    private String key;
    private int fingerprint;
    private int width;
    private int height;

    void accept(String key, int fingerprint, int width, int height) {
        this.key = identity(key);
        this.fingerprint = fingerprint;
        this.width = width;
        this.height = height;
    }

    boolean accepted(String key, int fingerprint, int width, int height) {
        String identity = identity(key);
        return identity != null && identity.equals(this.key)
                && fingerprint == this.fingerprint && width == this.width && height == this.height;
    }

    private static String identity(String key) {
        if (key == null || key.isEmpty()) return null;
        int from = 0;
        for (int field = 0; field < 3; field++) {
            int delimiter = key.indexOf('|', from);
            if (delimiter < 0) return field == 2 ? key : null;
            if (field == 2) return key.substring(0, delimiter);
            from = delimiter + 1;
        }
        return null;
    }
}
