package com.os4.musiccover;

import org.junit.Test;
import static org.junit.Assert.*;

public class ArtworkScrollStateTest {
    @Test public void firstSetterAndRealMotionWakeButRepeatedLayoutsStayIdle() {
        ArtworkScrollState scroll = new ArtworkScrollState();
        assertTrue(scroll.changed(0, 395f));
        for (int i = 0; i < 100; i++) assertFalse(scroll.changed(0, 395f));
        assertTrue(scroll.changed(0, 414f));
        assertFalse(scroll.changed(0, 414f));
        assertTrue(scroll.changed(0, 395f));
    }

    @Test public void OverscrollChannelsRemainIndependentAndIgnoreInvalidValues() {
        ArtworkScrollState scroll = new ArtworkScrollState();
        assertTrue(scroll.changed(1, 0f));
        assertTrue(scroll.changed(2, 0f));
        assertFalse(scroll.changed(1, Float.NaN));
        assertFalse(scroll.changed(1, 0f));
        assertTrue(scroll.changed(2, 12f));
    }
}
