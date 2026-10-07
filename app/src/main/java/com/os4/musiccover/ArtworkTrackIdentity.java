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
}
