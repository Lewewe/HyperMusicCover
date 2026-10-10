package com.os4.musiccover;

/** Capture and beat animations belong only to a visible, playing companion on the lit screen. */
final class BpmCompanionPolicy {
    private BpmCompanionPolicy() {}

    static boolean capture(boolean companion, boolean shown, boolean attached,
                           boolean screenOn, boolean keyguard, boolean playing, boolean still) {
        return companion && shown && attached && screenOn && keyguard && playing && !still;
    }

    static boolean captureCallback(long token, long generation, Object source, Object active) {
        return token == generation && source != null && source == active;
    }

    static boolean keepAwake(boolean setting, boolean shown, boolean aod,
                             boolean content, boolean playing) {
        return setting && shown && !aod && content && playing;
    }
}
