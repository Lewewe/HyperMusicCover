package com.os4.musiccover;

import java.util.Arrays;
import java.util.Random;

/** Small, deterministic palette and gradient placement shared by both rendering processes. */
final class CoverBackdropPalette {
    private CoverBackdropPalette() {}

    static int[] colors(int[] pixels) {
        int[] counts = new int[512];
        long[] reds = new long[512], greens = new long[512], blues = new long[512];
        for (int color : pixels) {
            if ((color >>> 24) < 128) continue;
            int r = (color >>> 16) & 255, g = (color >>> 8) & 255, b = color & 255;
            int bin = (r >>> 5) * 64 + (g >>> 5) * 8 + (b >>> 5);
            counts[bin]++;
            reds[bin] += r; greens[bin] += g; blues[bin] += b;
        }
        int[] result = new int[3];
        int used = 0;
        while (used < result.length) {
            int best = -1;
            for (int bin = 0; bin < counts.length; bin++) {
                if (counts[bin] == 0) continue;
                int color = 0xff000000 | (int) (reds[bin] / counts[bin]) << 16
                        | (int) (greens[bin] / counts[bin]) << 8
                        | (int) (blues[bin] / counts[bin]);
                boolean distinct = true;
                for (int i = 0; i < used; i++) {
                    if (distance(color, result[i]) < 70 * 70) distinct = false;
                }
                if (distinct && (best < 0 || counts[bin] > counts[best])) best = bin;
            }
            if (best < 0) break;
            result[used++] = 0xff000000 | (int) (reds[best] / counts[best]) << 16
                    | (int) (greens[best] / counts[best]) << 8
                    | (int) (blues[best] / counts[best]);
            counts[best] = 0;
        }
        if (used == 0) return new int[]{0xff000000};
        return Arrays.copyOf(result, used);
    }

    private static int distance(int a, int b) {
        int r = ((a >>> 16) & 255) - ((b >>> 16) & 255);
        int g = ((a >>> 8) & 255) - ((b >>> 8) & 255);
        int blue = (a & 255) - (b & 255);
        return r * r + g * g + blue * blue;
    }

    /** Each row contains normalized x, y, radius and opacity for one soft color region. */
    static float[][] regions(int[] colors) {
        Random random = new Random(Arrays.hashCode(colors));
        float[][] regions = new float[3][4];
        for (int i = 0; i < regions.length; i++) {
            regions[i][0] = 0.1f + random.nextFloat() * 0.8f;
            regions[i][1] = (i + 0.15f + random.nextFloat() * 0.7f) / 3f;
            regions[i][2] = 0.5f + random.nextFloat() * 0.25f;
            regions[i][3] = 0.30f + random.nextFloat() * 0.10f;
        }
        return regions;
    }
}
