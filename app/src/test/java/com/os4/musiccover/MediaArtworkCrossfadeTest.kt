package com.os4.musiccover

import org.junit.Assert.*
import org.junit.Test

class MediaArtworkCrossfadeTest {
    @Test fun rapidTrackChangesKeepOnlyLatestPendingArtwork() {
        val fade = MediaArtworkCrossfade<String>(100)
        assertTrue(fade.offer("first"))
        assertTrue(fade.offer("second"))
        assertFalse(fade.offer("third"))
        assertFalse(fade.offer("fourth"))
        fade.draw(0)
        assertEquals(1f, fade.draw(100), 0f)
        assertEquals("fourth", fade.completeFrame())
        assertEquals("fourth", fade.target)
        assertNull(fade.pending)
    }

    @Test fun hiddenTimeDoesNotSkipTheVisibleTransition() {
        val fade = MediaArtworkCrossfade<String>(100)
        fade.offer("first"); fade.offer("second")
        fade.draw(0)
        val fraction = fade.draw(40)
        fade.suspendDrawing()
        assertEquals(fraction, fade.draw(20000), 0f)
        assertNull(fade.completeFrame())
        assertEquals(1f, fade.draw(20060), 0f)
        assertNull(fade.completeFrame())
        assertFalse(fade.active)
    }
}
