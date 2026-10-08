package com.os4.musiccover;

/** Resolves Spotify's current item when its MediaSession active queue id is stale. */
final class SpotifyQueuePosition {
    private SpotifyQueuePosition() {}

    /**
     * Returns a queue position only when current metadata identifies exactly one item.  Spotify's
     * active queue id can still name the song that just ended, so it is deliberately not used.
     */
    static int find(String currentMediaId, String currentTitle, String currentArtist,
                    String[] mediaIds, String[] titles, String[] artists) {
        String id = trackId(currentMediaId);
        if (id != null) {
            int match = uniqueId(id, mediaIds);
            if (match >= 0) return match;
        }
        if (empty(currentTitle) || empty(currentArtist)) return -1;
        int match = -1;
        for (int i = 0; i < titles.length; i++) {
            if (!same(currentTitle, titles[i]) || !same(currentArtist, artists[i])) continue;
            if (match >= 0) return -1;
            match = i;
        }
        return match;
    }

    static String trackId(String value) {
        if (value == null) return null;
        int slash = Math.max(value.lastIndexOf(':'), value.lastIndexOf('/'));
        String id = (slash >= 0 ? value.substring(slash + 1) : value).trim();
        return id.matches("[A-Za-z0-9]{22}") ? id : null;
    }

    private static int uniqueId(String current, String[] mediaIds) {
        int match = -1;
        for (int i = 0; i < mediaIds.length; i++) {
            if (!current.equals(trackId(mediaIds[i]))) continue;
            if (match >= 0) return -1;
            match = i;
        }
        return match;
    }

    private static boolean same(String a, String b) {
        return a != null && b != null && a.trim().equalsIgnoreCase(b.trim());
    }

    private static boolean empty(String value) {
        return value == null || value.trim().isEmpty();
    }
}
