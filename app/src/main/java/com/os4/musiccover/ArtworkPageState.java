package com.os4.musiccover;

/** Keeps the artwork in the media player when the selected lyric page has no words. */
final class ArtworkPageState {
    private boolean compactPreferred;

    void preferCompact() { compactPreferred = true; }

    void preferCover() { compactPreferred = false; }

    void onTrackChanged(boolean lyricsWereVisible, boolean sessionEnded) {
        if (sessionEnded) compactPreferred = false;
        else if (lyricsWereVisible) compactPreferred = true;
    }

    boolean compactWithoutLyrics(boolean lyricsEnabled, boolean hasLyrics) {
        return lyricsEnabled && !hasLyrics && compactPreferred;
    }
}
