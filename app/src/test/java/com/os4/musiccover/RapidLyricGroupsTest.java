package com.os4.musiccover;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public class RapidLyricGroupsTest {
    private static List<LyricLine> lines(int... starts) {
        List<LyricLine> out = new ArrayList<>();
        for (int i = 0; i < starts.length; i++) {
            int end = i + 1 < starts.length ? starts[i + 1] : starts[i] + 4000;
            out.add(new LyricLine("Line " + i, null, starts[i], end, false, null, null, null));
        }
        return out;
    }

    private static void range(RapidLyricGroups groups, int index, int first, int last,
                              float[] base, float[] height, float room) {
        long range = groups.range(index, base, height, room);
        assertEquals(first, RapidLyricGroups.first(range));
        assertEquals(last, RapidLyricGroups.last(range));
    }

    @Test public void fastPassageAdvancesInGroupsOfAtMostFourThenReturnsToNormal() {
        RapidLyricGroups groups = new RapidLyricGroups(lines(0, 500, 1000, 1500, 2000, 2500, 7000, 11000));
        float[] base = {0, 50, 100, 150, 200, 250, 300, 350};
        float[] height = {30, 30, 30, 30, 30, 30, 30, 30};
        for (int i = 0; i < 4; i++) range(groups, i, 0, 3, base, height, 400);
        for (int i = 4; i < 6; i++) range(groups, i, 4, 5, base, height, 400);
        assertFalse(groups.grouped(6));
        assertFalse(groups.grouped(7));
    }

    @Test public void longWrappedLinesAndTranslationsSplitAtTheAvailableBandHeight() {
        RapidLyricGroups groups = new RapidLyricGroups(lines(0, 500, 1000, 1500));
        float[] base = {0, 90, 180, 270};
        float[] height = {70, 70, 70, 70};
        range(groups, 0, 0, 1, base, height, 180);
        range(groups, 1, 0, 1, base, height, 180);
        range(groups, 2, 2, 3, base, height, 180);
        range(groups, 3, 2, 3, base, height, 180);
        range(groups, 2, 2, 2, base, height, 50);
    }

    @Test public void seekInEitherDirectionFindsTheSameGroupWithoutPlaybackHistory() {
        RapidLyricGroups groups = new RapidLyricGroups(lines(0, 500, 1000, 1500, 2000, 2500));
        float[] base = {0, 50, 100, 150, 200, 250};
        float[] height = {30, 30, 30, 30, 30, 30};
        range(groups, 5, 4, 5, base, height, 400);
        range(groups, 2, 0, 3, base, height, 400);
        range(groups, 5, 4, 5, base, height, 400);
    }

    @Test public void duetOverlapsSilentBreaksAndSimultaneousLinesAreNotRapidSequentialGroups() {
        List<LyricLine> duet = lines(0, 500);
        duet.set(1, new LyricLine("Answer", null, 500, 4000, true, null, null, null));
        assertFalse(new RapidLyricGroups(duet).grouped(0));
        List<LyricLine> overlap = lines(0, 500);
        overlap.get(0).end = 1800;
        assertFalse(new RapidLyricGroups(overlap).grouped(0));
        List<LyricLine> silence = lines(0, 1000);
        silence.get(0).end = 100;
        assertFalse(new RapidLyricGroups(silence).grouped(0));
        assertFalse(new RapidLyricGroups(lines(0, 0)).grouped(0));
    }

    @Test public void timingThresholdIsBoundedAndSourceTimestampsRemainUnchanged() {
        List<LyricLine> timed = lines(0, 1200, 2401);
        RapidLyricGroups groups = new RapidLyricGroups(timed);
        assertTrue(groups.grouped(0));
        assertFalse(groups.grouped(2));
        assertEquals(1200, timed.get(1).start);
        assertEquals(2401, timed.get(1).end);
        assertFalse(new RapidLyricGroups(new ArrayList<>()).grouped(0));
    }

    @Test public void consecutiveWordTimedLinesStayTogetherWhenTheirRangesOverlap() {
        List<LyricLine> timed = new ArrayList<>();
        timed.add(new LyricLine("Run", null, 0, 1500, false,
                new int[] {0}, new int[] {1500}, new int[] {3}));
        timed.add(new LyricLine("Run", null, 500, 2000, false,
                new int[] {500}, new int[] {2000}, new int[] {3}));
        timed.add(new LyricLine("Runaway", null, 1000, 2500, false,
                new int[] {1000}, new int[] {2500}, new int[] {7}));
        timed.add(new LyricLine("Baby", null, 1500, 3000, false,
                new int[] {1500}, new int[] {3000}, new int[] {4}));
        RapidLyricGroups groups = new RapidLyricGroups(timed);
        for (int i = 0; i < timed.size(); i++) assertTrue(groups.grouped(i));
    }
}
