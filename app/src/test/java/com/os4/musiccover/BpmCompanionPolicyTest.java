package com.os4.musiccover;

import org.junit.Test;
import static org.junit.Assert.*;

public class BpmCompanionPolicyTest {
    @Test public void onlyTheVisiblePlayingCompanionCaptures() {
        assertTrue(BpmCompanionPolicy.capture(true,true,true,true,true,true,false));
        assertFalse(BpmCompanionPolicy.capture(false,true,true,true,true,true,false));
        assertFalse(BpmCompanionPolicy.capture(true,false,true,true,true,true,false));
        assertFalse(BpmCompanionPolicy.capture(true,true,false,true,true,true,false));
        assertFalse(BpmCompanionPolicy.capture(true,true,true,false,true,true,false));
        assertFalse(BpmCompanionPolicy.capture(true,true,true,true,false,true,false));
        assertFalse(BpmCompanionPolicy.capture(true,true,true,true,true,false,false));
        assertFalse(BpmCompanionPolicy.capture(true,true,true,true,true,true,true));
    }
    @Test public void oldVisualizerAndOldTrackCallbacksAreRejected() {
        Object old = new Object(), current = new Object();
        assertTrue(BpmCompanionPolicy.captureCallback(2,2,current,current));
        assertFalse(BpmCompanionPolicy.captureCallback(1,2,current,current));
        assertFalse(BpmCompanionPolicy.captureCallback(2,2,old,current));
        assertFalse(BpmCompanionPolicy.captureCallback(2,2,null,null));
    }
    @Test public void companionCannotOverrideTheKeepScreenOnSwitch() {
        assertFalse(BpmCompanionPolicy.keepAwake(false,true,false,true,true));
        assertTrue(BpmCompanionPolicy.keepAwake(true,true,false,true,true));
        assertFalse(BpmCompanionPolicy.keepAwake(true,true,true,true,true));
        assertFalse(BpmCompanionPolicy.keepAwake(true,true,false,true,false));
    }
}
