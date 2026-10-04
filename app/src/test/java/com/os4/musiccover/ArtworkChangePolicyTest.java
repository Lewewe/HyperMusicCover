package com.os4.musiccover;

import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.*;

public class ArtworkChangePolicyTest {
    private static final String YTM = "com.google.android.apps.youtube.music|Song|Artist";
    private static int[] image(int color) {
        int[] pixels = new int[64];
        Arrays.fill(pixels, color);
        return pixels;
    }

    @Test public void videoThumbnailCanReplaceLargerAlbumArtAndSwitchBack() {
        assertTrue(ArtworkChangePolicy.changed(YTM, 1024, 1024, image(0xffaa3333),
                640, 360, image(0xff336699)));
        assertTrue(ArtworkChangePolicy.changed(YTM, 640, 360, image(0xff336699),
                512, 512, image(0xffaa3333)));
    }

    @Test public void differentArtworkAtTheSameResolutionCanReplaceTheCover() {
        assertTrue(ArtworkChangePolicy.changed(YTM, 512, 512, image(0xffaa3333),
                512, 512, image(0xff336699)));
    }

    @Test public void resamplingNoiseDoesNotReplaceASharperCopy() {
        assertFalse(ArtworkChangePolicy.changed(YTM, 1024, 1024, image(0xffaa3333),
                144, 144, image(0xffad3535)));
    }

    @Test public void youtubeVideoUsesTheSamePolicyButOtherPlayersDoNot() {
        assertTrue(ArtworkChangePolicy.changed("com.google.android.youtube|Video", 512, 512,
                image(0xffaa3333), 320, 180, image(0xff336699)));
        assertFalse(ArtworkChangePolicy.changed("com.other.player|Song", 512, 512,
                image(0xffaa3333), 320, 180, image(0xff336699)));
    }

    @Test public void aShapeChangeIsDetectedEvenWhenColorsAreSimilar() {
        assertTrue(ArtworkChangePolicy.changed(YTM, 512, 512, image(0xffaaaaaa),
                640, 360, image(0xffaaaaaa)));
    }

    @Test public void invalidOrMissingSamplesAreNotTreatedAsNewArtwork() {
        assertFalse(ArtworkChangePolicy.changed(YTM, 512, 512, null,
                640, 360, image(0xffaaaaaa)));
        assertFalse(ArtworkChangePolicy.changed(YTM, 0, 512, image(0xffaaaaaa),
                640, 360, image(0xffaaaaaa)));
    }
}
