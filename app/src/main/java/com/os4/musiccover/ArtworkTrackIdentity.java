package com.os4.musiccover;

/** Use stable song metadata when a player publishes the sung line as TITLE. */
final class ArtworkTrackIdentity {
    private ArtworkTrackIdentity() {}

    static String title(String packageName, String title, String artist) {
        if ("com.salt.music".equals(packageName) && artist != null) {
            int dash = artist.indexOf(" - ");
            if (dash >= 0 && dash + 3 < artist.length()) {
                String song = artist.substring(dash + 3).trim();
                if (!song.isEmpty()) return song;
            }
        }
        return title;
    }
    static boolean sameArtists(String pkg, String a, String b) {
        if (!"com.spotify.music".equals(pkg)) return false;
        java.util.Set<String> first = artists(a);
        return !first.isEmpty() && first.equals(artists(b));
    }

    private static java.util.Set<String> artists(String value) {
        java.util.Set<String> result = new java.util.HashSet<>();
        for (String artist : value.split(",")) {
            String name = artist.trim();
            if (!name.isEmpty()) result.add(name);
        }
        return result;
    }
}
