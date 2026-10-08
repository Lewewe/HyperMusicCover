package com.os4.musiccover;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class SpotifyQueuePositionTest {
    private static final String A = "0AbCdEfGhIjKlMnOpQrStU";
    private static final String B = "1BcDeFgHiJkLmNoPqRsTuV";

    @Test public void metadataIdBeatsStaleActiveQueueId() {
        assertEquals(1, SpotifyQueuePosition.find("spotify:track:" + B, "Second", "Artist",
                new String[]{"spotify:track:" + A, "spotify:track:" + B},
                new String[]{"First", "Second"}, new String[]{"Artist", "Artist"}));
    }

    @Test public void uniqueTitleAndArtistCanIdentifyItemWithoutMediaId() {
        assertEquals(1, SpotifyQueuePosition.find(null, "Second", "Artist",
                new String[]{null, null}, new String[]{"First", "Second"},
                new String[]{"Artist", "Artist"}));
    }

    @Test public void duplicateFallbackDoesNotGuess() {
        assertEquals(-1, SpotifyQueuePosition.find(null, "Same", "Artist",
                new String[]{null, null}, new String[]{"Same", "Same"},
                new String[]{"Artist", "Artist"}));
    }
}
