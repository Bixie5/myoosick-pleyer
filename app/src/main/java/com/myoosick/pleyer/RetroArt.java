package com.myoosick.pleyer;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
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

    /** Plain amber-LCD art (no cassette): dark LCD glass, scanlines, VU bars, song in amber. Used when a song has no thumbnail. */
    static Bitmap renderLcd(String title, String artist, boolean playing) {
        final int S = 480;
        Bitmap bmp = Bitmap.createBitmap(S, S, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        c.drawColor(0xFF5A3A1D);                                  // wood edge
        p.setColor(0xFF150E00);
        c.drawRoundRect(new RectF(14, 14, S - 14, S - 14), 22, 22, p);   // LCD glass
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(4); p.setColor(AMBER_SOFT);
        c.drawRoundRect(new RectF(14, 14, S - 14, S - 14), 22, 22, p);
        p.setStyle(Paint.Style.FILL);
        // static VU bars along the bottom
        int[] hs = {3, 5, 7, 4, 8, 6, 9, 5, 7, 4, 6, 8, 5, 3, 6, 7, 4, 5};
        float bw = (S - 90f) / hs.length;
        for (int i = 0; i < hs.length; i++) {
            for (int k = 0; k < 10; k++) {
                p.setColor(k < hs[i] ? (k >= 8 ? 0xFFFF3B2A : k >= 6 ? 0xFFFF7A1A : AMBER) : 0xFF2D1E00);
                float x = 45 + i * bw, y = S - 48 - k * 15;
                c.drawRect(x, y - 11, x + bw - 4, y, p);
            }
        }
        // scanlines
        p.setColor(0x40000000);
        for (int y = 14; y < S - 14; y += 4) c.drawRect(14, y, S - 14, y + 1.5f, p);
        p.setColor(AMBER_SOFT);
        fit(c, p, playing ? "PLAY" : "PAUSE", 44, 62, 200, 26, 26, Paint.Align.LEFT);
        p.setColor(AMBER);
        fit(c, p, (title.isEmpty() ? "MYOOSICK PLEYER" : title.toUpperCase()), 44, 150, S - 88, 50, 24, Paint.Align.LEFT);
        p.setColor(AMBER_SOFT);
        if (!artist.isEmpty()) fit(c, p, artist.toUpperCase(), 44, 215, S - 88, 32, 18, Paint.Align.LEFT);
        return bmp;
    }

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
