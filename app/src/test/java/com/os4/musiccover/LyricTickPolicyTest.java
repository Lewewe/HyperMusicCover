package com.os4.musiccover;

import org.junit.Test;
import static org.junit.Assert.*;

public class LyricTickPolicyTest {
    @Test public void quickLinesWakeAtTheRealBoundaryInsteadOfTheOldOneSecondLead() {
        assertEquals(80L, LyricTickPolicy.delay(1120, 1200));
        assertEquals(10L, LyricTickPolicy.delay(1190, 1200));
        assertEquals(1L, LyricTickPolicy.delay(1199, 1200));
    }

    @Test public void normalEarlyScrollAndInterludeTransitionsUseTheirRendererTimes() {
        assertEquals(200L, LyricTickPolicy.delay(5800, 6000));
        assertEquals(350L, LyricTickPolicy.delay(6000, 6350));
    }

    @Test public void LongGapsPauseAndEndOfLyricsKeepTheBoundedHeartbeat() {
        assertEquals(500L, LyricTickPolicy.delay(1000, 10000));
        assertEquals(500L, LyricTickPolicy.delay(1000, -1));
        assertEquals(1L, LyricTickPolicy.delay(1000, 1000));
        assertEquals(1L, LyricTickPolicy.delay(1000, 900));
    }
}
