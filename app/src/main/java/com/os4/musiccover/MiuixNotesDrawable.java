// Copyright 2025, compose-miuix-ui contributors
// SPDX-License-Identifier: Apache-2.0
// Adapted from Miuix Notes.Regular for the native SystemUI media card.
// https://github.com/compose-miuix-ui/miuix
package com.os4.musiccover;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import androidx.core.graphics.PathParser;

final class MiuixNotesDrawable extends Drawable {
    private static final float VIEWPORT = 1220.4f;
    private static final String DATA = "M932.7 123.7 Q986.7 151.7 1011.7 202.7 Q1026.7 231.7 1030.2 272.7 Q1033.7 313.7 1033.7 409.7 V964.7 Q1033.7 1012.7 1032."
            + "2 1033.2 Q1030.7 1053.7 1023.7 1067.7 Q1010.7 1093.7 983.7 1107.7 Q969.7 1115.7 949.2 1117.2 Q928.7 1118.7 880.7 1118.7 "
            + "H493.7 Q398.7 1118.7 357.7 1115.2 Q316.7 1111.7 286.7 1096.7 Q233.7 1067.7 208.7 1017.7 Q193.7 988.7 190.2 947.7 Q186.7 "
            + "906.7 186.7 810.7 V255.7 Q186.7 207.7 188.2 187.2 Q189.7 166.7 196.7 152.7 Q209.7 126.7 236.7 112.7 Q250.7 104.7 271.2 1"
            + "03.2 Q291.7 101.7 339.7 101.7 H725.7 Q821.7 101.7 862.7 105.2 Q903.7 108.7 932.7 123.7 Z M271.7 228.7 V855.7 Q271.7 910."
            + "7 273.7 934.7 Q275.7 958.7 283.7 974.7 Q299.7 1004.7 329.7 1020.7 Q346.7 1028.7 370.2 1030.7 Q393.7 1032.7 449.7 1032.7 "
            + "H908.7 Q933.7 1032.7 941.2 1024.7 Q948.7 1016.7 948.7 990.7 V363.7 Q948.7 309.7 946.7 285.7 Q944.7 261.7 935.7 245.7 Q92"
            + "1.7 216.7 889.7 199.7 Q873.7 191.7 850.2 189.7 Q826.7 187.7 770.7 187.7 H302.7 Q285.7 187.7 278.7 196.7 Q271.7 205.7 271"
            + ".7 228.7 Z M677.7 382.7 V407.7 Q677.7 423.7 669.2 430.2 Q660.7 436.7 643.7 436.7 H424.7 Q406.7 436.7 398.7 430.2 Q390.7 "
            + "423.7 390.7 405.7 V382.7 Q390.7 365.7 398.7 358.7 Q406.7 351.7 424.7 351.7 H643.7 Q661.7 351.7 669.7 358.7 Q677.7 365.7 "
            + "677.7 382.7 Z M829.7 814.7 V839.7 Q829.7 855.7 821.7 862.2 Q813.7 868.7 796.7 868.7 H422.7 Q406.7 868.7 398.7 861.7 Q390"
            + ".7 854.7 390.7 839.7 V814.7 Q390.7 797.7 399.2 790.7 Q407.7 783.7 422.7 783.7 H796.7 Q813.7 783.7 821.7 790.7 Q829.7 797"
            + ".7 829.7 814.7 Z M829.7 597.7 V622.7 Q829.7 638.7 821.7 645.2 Q813.7 651.7 796.7 651.7 H422.7 Q406.7 651.7 398.7 644.7 Q"
            + "390.7 637.7 390.7 622.7 V597.7 Q390.7 580.7 399.2 573.7 Q407.7 566.7 422.7 566.7 H796.7 Q813.7 566.7 821.7 573.7 Q829.7 "
            + "580.7 829.7 597.7 Z";
    private final Path path = PathParser.createPathFromPathData(DATA);
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    MiuixNotesDrawable() { paint.setColor(0xFFFFFFFF); }

    @Override public void draw(Canvas canvas) {
        float side = Math.min(getBounds().width(), getBounds().height());
        if (side <= 0f) return;
        int save = canvas.save();
        canvas.translate(getBounds().left + (getBounds().width() - side) / 2f,
                getBounds().top + (getBounds().height() - side) / 2f);
        canvas.scale(side / VIEWPORT, side / VIEWPORT);
        canvas.translate(0f, VIEWPORT);
        canvas.scale(1f, -1f);
        canvas.drawPath(path, paint);
        canvas.restoreToCount(save);
    }

    @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
    @Override public void setColorFilter(ColorFilter filter) {
        paint.setColorFilter(filter); invalidateSelf();
    }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
