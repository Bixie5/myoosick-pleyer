package com.myoosick.pleyer;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.text.TextUtils;

/**
 * Draws the "album art" shown on the lockscreen / notification: the app's cassette deck
 * (pre-drawn in res/drawable-nodpi/retro_cassette.png, 480x480) with the current song written
 * on the tape label and the artist on the amber LCD strip.
 */
final class RetroArt {
    private static final int INK = 0xFF2A241C, INK_SOFT = 0xFF6B5B3F, AMBER = 0xFFFFB000, AMBER_SOFT = 0xFFC98A00;

    private RetroArt() {}

    static Bitmap render(Context ctx, String title, String artist, boolean playing) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inMutable = true;
        o.inScaled = false;
        Bitmap base = BitmapFactory.decodeResource(ctx.getResources(), R.drawable.retro_cassette, o);
        if (base == null) return null;
        Canvas c = new Canvas(base);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));

        String t = title.isEmpty() ? "INSERT TAPE" : title.toUpperCase();
        p.setColor(INK);
        fit(c, p, t, 240, 158, 262, 40, 20, Paint.Align.CENTER);
        p.setColor(INK_SOFT);
        fit(c, p, artist.isEmpty() ? "SIDE A" : artist.toUpperCase(), 240, 196, 262, 22, 14, Paint.Align.CENTER);

        p.setColor(AMBER_SOFT);
        fit(c, p, playing ? "PLAY" : "PAUSE", 56, 354, 200, 22, 22, Paint.Align.LEFT);
        p.setColor(AMBER);
        fit(c, p, artist.isEmpty() ? "MYOOSICK PLEYER" : artist.toUpperCase(), 56, 404, 280, 36, 18, Paint.Align.LEFT);
        return base;
    }

    /** Draw text vertically centred on (x, centerY), shrinking it (then ellipsizing) to fit maxW. */
    private static void fit(Canvas c, Paint p, String text, float x, float centerY, float maxW,
                            float maxSize, float minSize, Paint.Align align) {
        float size = maxSize;
        p.setTextSize(size);
        while (p.measureText(text) > maxW && size > minSize) {
            size -= 1;
            p.setTextSize(size);
        }
        if (p.measureText(text) > maxW) {
            text = TextUtils.ellipsize(text, new TextPaint(p), maxW, TextUtils.TruncateAt.END).toString();
        }
        Paint.FontMetrics fm = p.getFontMetrics();
        p.setTextAlign(align);
        c.drawText(text, x, centerY - (fm.ascent + fm.descent) / 2f, p);
    }
}
