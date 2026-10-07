package com.os4.musiccover;

import org.junit.Test;
import static org.junit.Assert.*;

public class ArtworkScrollStateTest {
    @Test public void repeatedLayoutDoesNotCountAsMotion() {
        ArtworkScrollState state = new ArtworkScrollState();
        assertFalse(state.changed(0, 120f));
        for (int i = 0; i < 1000; i++) assertFalse(state.changed(0, 120f));
        assertTrue(state.changed(0, 121f));
        assertFalse(state.changed(0, 121f));
    }

    @Test public void overscrollSidesAreIndependent() {
        ArtworkScrollState state = new ArtworkScrollState();
        assertFalse(state.changed(1, 0f));
        assertFalse(state.changed(2, 0f));
        assertTrue(state.changed(1, 8f));
        assertFalse(state.changed(2, 0f));
        assertTrue(state.changed(1, 0f));
        assertFalse(state.changed(1, 0f));
    }

    @Test public void invalidScrollDoesNotDestroyLastPosition() {
        ArtworkScrollState state = new ArtworkScrollState();
        state.changed(0, 10f);
        assertFalse(state.changed(0, Float.NaN));
        assertFalse(state.changed(0, Float.POSITIVE_INFINITY));
        assertFalse(state.changed(0, 10f));
    }
}
