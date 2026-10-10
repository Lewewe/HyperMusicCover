package com.os4.musiccover;

import org.junit.Test;
import static org.junit.Assert.*;

public class CompanionLayoutTest {
    @Test public void tallClockLeavesReadableRoomInsteadOfHidingTheCompanion() {
        assertEquals(1449f, CompanionLayout.clockRoom(1635f, 1709f, 3.25f), .001f);
    }
    @Test public void shorterClockAndNotificationLimitsRemainUnchanged() {
        assertEquals(1200f, CompanionLayout.clockRoom(1200f, 1709f, 3.25f), .001f);
        assertEquals(1200f, CompanionLayout.clockRoom(1200f, Float.NaN, 3.25f), .001f);
    }
    @Test public void tightGapShrinksTheWholeBlock() {
        assertEquals(.25f, CompanionLayout.fitScale(400, 50, 300, 200), .001f);
    }
    @Test public void wideFaceAndLongMessageStayWithinTheWidth() {
        assertEquals(.5f, CompanionLayout.fitScale(300, 500, 600, 200), .001f);
    }
    @Test public void extraRoomKeepsTheOriginalPrSize() {
        assertEquals(1f, CompanionLayout.fitScale(600, 400, 300, 200), .001f);
    }
    @Test public void allContentsIncludingPeakPulseStayInsideTheBand() {
        for (float height : new float[]{1, 40, 200, 800}) {
            float fit = CompanionLayout.fitScale(400, height, 500, 180);
            assertTrue(fit * 500 <= 400.001f);
            assertTrue(fit * 180 <= height + .001f);
        }
    }
    @Test public void invalidOrHiddenBandDoesNotDrawStaleContents() {
        assertEquals(0, CompanionLayout.fitScale(400, -1, 300, 200), 0);
        assertEquals(0, CompanionLayout.fitScale(400, Float.NaN, 300, 200), 0);
        assertEquals(0, CompanionLayout.fitScale(400, 100, 0, 200), 0);
    }
}
