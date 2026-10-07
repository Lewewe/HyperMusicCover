package com.os4.musiccover;

import org.junit.Test;
import static org.junit.Assert.*;

public class ClockSizePolicyTest {
    @Test public void notificationListUsesTheLiveNativeSqueezeInsteadOfTheHistoricalMaximum() {
        float nativeUnit = ClockSizePolicy.nativeUnit(785.9f, 560f, true);
        assertEquals(560f, nativeUnit, 0.001f);
        assertEquals(560f, ClockSizePolicy.coverUnit(117.885f, 785.9f, nativeUnit,
                1f, 0.05f, 1f), 0.001f);
        assertEquals(785.9f, ClockSizePolicy.nativeUnit(785.9f, 560f, false), 0.001f);
        assertEquals(785.9f, ClockSizePolicy.nativeUnit(785.9f, Float.NaN, true), 0.001f);
    }

    @Test public void restoringCoverShrinksDirectlyFromTheNotificationClock() {
        float from = ClockSizePolicy.nativeUnit(785.9f, 560f, true);
        float target = ClockSizePolicy.coverUnit(117.885f, 785.9f, 785.9f,
                1f, 0.05f, 0f);
        float previous = from;
        for (int frame = 0; frame <= 60; frame++) {
            float unit = ClockSizePolicy.transitionUnit(from, target, frame / 60f);
            assertTrue(unit <= previous + 0.001f);
            assertTrue(unit <= 560f);
            previous = unit;
        }
        assertEquals(117.885f, previous, 0.001f);
    }

    @Test public void listToStackShrinksFromTheDrawnClockWithoutGrowingToTheNativeMaximum() {
        float drawn = 392.5f;
        float destination = ClockSizePolicy.coverUnit(117.885f, 785.9f, 784.9f,
                1f, 0.05f, 0f);
        float previous = drawn;
        for (int frame = 0; frame <= 60; frame++) {
            float unit = ClockSizePolicy.transitionUnit(drawn, destination, frame / 60f);
            assertTrue(unit <= previous + 0.001f);
            assertTrue(unit >= destination - 0.001f);
            previous = unit;
        }
        assertEquals(destination, previous, 0.001f);
    }

    @Test public void interruptedSizeTransitionStartsFromItsCurrentDrawnSize() {
        float midway = ClockSizePolicy.transitionUnit(392.5f, 117.885f, 0.4f);
        assertEquals(midway, ClockSizePolicy.transitionUnit(midway, 784.9f, 0f), 0.001f);
        assertEquals(784.9f, ClockSizePolicy.transitionUnit(midway, 784.9f, 1f), 0.001f);
    }
    @Test public void editorResizeDoesNotChangeTheModuleSliderCalibration() {
        float reference = 785.9f;
        float requested = 0.15f * reference;
        assertEquals(117.885f, ClockSizePolicy.coverUnit(requested, reference, 233.2f,
                1f, 0.05f, 0f), 0.01f);
        assertEquals(ClockSizePolicy.coverUnit(requested, reference, 785.9f, 1f, 0.05f, 0f),
                ClockSizePolicy.coverUnit(requested, reference, 233.2f, 1f, 0.05f, 0f), 0.01f);
    }

    @Test public void nativeRestoreUsesTheEditorsCurrentSizeInsteadOfTheSliderReference() {
        float reference = 785.9f;
        for (float fraction : new float[]{0.09f, 0.15f, 0.8f, 1f}) {
            assertEquals(233.2f, ClockSizePolicy.coverUnit(fraction * reference, reference,
                    233.2f, 1f, 0.05f, 1f), 0.01f);
        }
    }
    private static String key(double height, double width) {
        String geometry = ClockSizePolicy.editorKey("all_in_one", 5, height, width, 22, 220, false);
        return ClockSizePolicy.measurementKey("AllInOneClock", geometry, 1220, 2656, 520);
    }

    @Test public void reducingEditorSizeDoesNotRetainTheOldMaximum() {
        String large = key(300, 337);
        String small = key(75, 337);
        assertFalse(ClockSizePolicy.retainLargest(large, small, 785.9f, 233.2f));
        assertFalse(ClockSizePolicy.retainLargest(small, large, 233.2f, 785.9f));
    }

    @Test public void notificationSqueezeDoesNotReplaceTheSameEditorsFullSize() {
        String same = key(75, 337);
        assertTrue(ClockSizePolicy.retainLargest(same, same, 233.2f, 180f));
        assertFalse(ClockSizePolicy.retainLargest(same, same, 180f, 233.2f));
    }

    @Test public void legacyMeasurementsAndDifferentWidthsAreNotReused() {
        assertFalse(ClockSizePolicy.retainLargest("AllInOneClock", key(75, 337), 785.9f, 233.2f));
        assertFalse(ClockSizePolicy.retainLargest(key(75, 337), key(75, 250), 233.2f, 233.2f));
        assertFalse(ClockSizePolicy.retainLargest(key(75, 337), key(75, 337), Float.NaN, 233.2f));
    }
}
