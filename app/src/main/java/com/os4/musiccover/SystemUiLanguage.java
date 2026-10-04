package com.os4.musiccover;

import android.content.res.Resources;

/** System UI follows the device language, independently of the module app's language. */
final class SystemUiLanguage {
    private SystemUiLanguage() {}

    static boolean isChinese() {
        return "zh".equals(Resources.getSystem().getConfiguration().getLocales().get(0).getLanguage());
    }

    static String text(String chinese, String english) {
        return isChinese() ? chinese : english;
    }
}
