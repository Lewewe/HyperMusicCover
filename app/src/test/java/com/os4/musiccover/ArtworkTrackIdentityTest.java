package com.os4.musiccover;

import org.junit.Test;
import static org.junit.Assert.*;

public class ArtworkTrackIdentityTest {
    @Test public void saltSungLinesDoNotChangeSongIdentity() {
        assertEquals("Song", ArtworkTrackIdentity.title("com.salt.music", "First line", "Artist - Song"));
        assertEquals("Song", ArtworkTrackIdentity.title("com.salt.music", "Next line", "Artist - Song"));
        assertEquals("New song", ArtworkTrackIdentity.title("com.salt.music", "Next line", "Artist - New song"));
    }

    @Test public void otherPlayersKeepTheirPublishedTitle() {
        assertEquals("Title", ArtworkTrackIdentity.title("com.google.android.apps.youtube.music", "Title", "Artist - Song"));
    }

    @Test public void malformedSaltArtistFallsBackToTitle() {
        assertEquals("Title", ArtworkTrackIdentity.title("com.salt.music", "Title", null));
        assertEquals("Title", ArtworkTrackIdentity.title("com.salt.music", "Title", "Artist"));
        assertEquals("Title", ArtworkTrackIdentity.title("com.salt.music", "Title", "Artist - "));
    }

    @Test public void songNamesCanContainDashes() {
        assertEquals("Song - Live", ArtworkTrackIdentity.title("com.salt.music", "Line", "Artist - Song - Live"));
    }
    @Test public void spotifyArtistReorderingDoesNotChangeArtworkIdentity() {
        assertTrue(ArtworkTrackIdentity.sameArtists("com.spotify.music", "Synthion, HYPERNIGHT", "HYPERNIGHT, Synthion"));
        assertFalse(ArtworkTrackIdentity.sameArtists("com.spotify.music", "Synthion", "Other"));
        assertFalse(ArtworkTrackIdentity.sameArtists("other.player", "A, B", "B, A"));
    }
}
