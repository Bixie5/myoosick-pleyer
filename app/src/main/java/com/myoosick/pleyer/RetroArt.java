package com.myoosick.pleyer;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;

/** Turns a song thumbnail into the square, amber-framed art shown on the lockscreen / notification. */
final class RetroArt {
    private static final int AMBER_SOFT = 0xFFC98A00;

    private RetroArt() {}

    /** Square, centre-cropped thumbnail with a thin amber frame. */
    static Bitmap fromThumb(Bitmap src) {
        final int S = 480;
        int w = src.getWidth(), h = src.getHeight(), side = Math.min(w, h);
        Rect from = new Rect((w - side) / 2, (h - side) / 2, (w + side) / 2, (h + side) / 2);
        Bitmap out = Bitmap.createBitmap(S, S, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        c.drawBitmap(src, from, new Rect(0, 0, S, S), p);
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(10); p.setColor(AMBER_SOFT);
        c.drawRect(5, 5, S - 5, S - 5, p);
        return out;
    }
}
