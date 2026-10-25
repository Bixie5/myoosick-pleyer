package com.myoosick.pleyer;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.text.TextUtils;

/**
 * Draws the retro cassette used as cover art on the lockscreen / notification player
 * (same look as the in-app tape, with the song title on the label).
 */
final class CassetteArt {
    private CassetteArt() {}

    private static final int W = 512;

    static Bitmap render(String title, String artist) {
        Bitmap bmp = Bitmap.createBitmap(W, W, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        // ---- wood panel
        int[] wood = {0xFF6B4423, 0xFF5A3A1D, 0xFF744C27, 0xFF5F3E1F};
        int[] wid = {14, 6, 12, 6};
        p.setStyle(Paint.Style.FILL);
        int x = 0, i = 0;
        while (x < W) {
            p.setColor(wood[i % 4]);
            c.drawRect(x, 0, x + wid[i % 4], W, p);
            x += wid[i % 4];
            i++;
        }

        // ---- cassette (drawn in a 320x210 space, scaled up)
        c.save();
        c.translate(36, 70);
        c.scale(1.375f, 1.375f);

        fillStroke(c, p, new RectF(3, 3, 317, 207), 14, 0xFF34302B, 0xFF111111, 3);
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFF15130F);
        c.drawCircle(16, 16, 3.5f, p);
        c.drawCircle(304, 16, 3.5f, p);
        c.drawCircle(16, 194, 3.5f, p);
        c.drawCircle(304, 194, 3.5f, p);

        // label
        RectF label = new RectF(24, 22, 296, 108);
        fillStroke(c, p, label, 6, 0xFFF1E6C8, 0xFF1B1914, 2);
        c.save();
        Path clip = new Path();
        clip.addRoundRect(label, 6, 6, Path.Direction.CW);
        c.clipPath(clip);
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFFE8742A); c.drawRect(24, 22, 296, 40, p);
        p.setColor(0xFFE0412B); c.drawRect(24, 34, 296, 40, p);
        p.setColor(0xFF2F8F85); c.drawRect(24, 40, 296, 45, p);
        c.restore();

        TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        tp.setTypeface(Typeface.create("serif-monospace", Typeface.NORMAL));
        tp.setColor(0xFFFFFFFF);
        tp.setTextSize(7);
        c.drawText("A", 34, 35, tp);
        tp.setTextAlign(Paint.Align.RIGHT);
        c.drawText("C-60", 286, 35, tp);

        tp.setTextAlign(Paint.Align.CENTER);
        tp.setColor(0xFF2A241C);
        String t = (title == null || title.isEmpty()) ? "INSERT TAPE" : title.toUpperCase();
        float size = 19;
        tp.setTextSize(size);
        while (size > 11 && tp.measureText(t) > 244) tp.setTextSize(size -= 1);
        t = TextUtils.ellipsize(t, tp, 244, TextUtils.TruncateAt.END).toString();
        c.drawText(t, 160, 74, tp);

        tp.setColor(0xFF6B5B3F);
        tp.setTextSize(12);
        String a = (artist == null || artist.isEmpty()) ? "SIDE A" : artist;
        a = TextUtils.ellipsize(a, tp, 244, TextUtils.TruncateAt.END).toString();
        c.drawText(a, 160, 96, tp);

        // tape window + reels
        fillStroke(c, p, new RectF(58, 114, 262, 172), 29, 0xFF0F0E0C, 0xFF555555, 2);
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFF3B2A1D);
        c.drawCircle(100, 143, 26, p);
        c.drawCircle(220, 143, 14, p);
        reel(c, p, 100, 143);
        reel(c, p, 220, 143);

        // bottom
        Path trap = new Path();
        trap.moveTo(70, 178); trap.lineTo(250, 178); trap.lineTo(270, 202); trap.lineTo(50, 202); trap.close();
        p.setStyle(Paint.Style.FILL); p.setColor(0xFF26231F); c.drawPath(trap, p);
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(2); p.setColor(0xFF111111); c.drawPath(trap, p);
        p.setStyle(Paint.Style.FILL); p.setColor(0xFF0F0E0C);
        c.drawCircle(104, 191, 5, p);
        c.drawCircle(216, 191, 5, p);
        c.drawRoundRect(new RectF(140, 186, 150, 196), 2, 2, p);
        c.drawRoundRect(new RectF(170, 186, 180, 196), 2, 2, p);
        c.restore();

        // ---- little amber LCD strip under the tape
        fillStroke(c, p, new RectF(36, 400, 476, 456), 10, 0xFF150E00, 0xFF2A241C, 4);
        TextPaint lp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        lp.setTypeface(Typeface.create("monospace", Typeface.BOLD));
        lp.setColor(0xFFFFB000);
        lp.setTextSize(30);
        lp.setTextAlign(Paint.Align.CENTER);
        c.drawText("MYOOSICK PLEYER", 256, 438, lp);
        return bmp;
    }

    private static void fillStroke(Canvas c, Paint p, RectF r, float rad, int fill, int stroke, float sw) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(fill);
        c.drawRoundRect(r, rad, rad, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(sw);
        p.setColor(stroke);
        c.drawRoundRect(r, rad, rad, p);
    }

    private static void reel(Canvas c, Paint p, float cx, float cy) {
        p.setStyle(Paint.Style.FILL); p.setColor(0xFFECE6D4); c.drawCircle(cx, cy, 12, p);
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(1.5f); p.setColor(0xFF222222); c.drawCircle(cx, cy, 12, p);
        p.setStrokeWidth(3); p.setStrokeCap(Paint.Cap.ROUND);
        c.drawLine(cx, cy - 11, cx, cy + 11, p);
        c.drawLine(cx - 9.5f, cy - 5.5f, cx + 9.5f, cy + 5.5f, p);
        c.drawLine(cx - 9.5f, cy + 5.5f, cx + 9.5f, cy - 5.5f, p);
        p.setStrokeCap(Paint.Cap.BUTT);
        p.setStyle(Paint.Style.FILL); p.setColor(0xFFECE6D4); c.drawCircle(cx, cy, 4.5f, p);
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(1.5f); p.setColor(0xFF222222); c.drawCircle(cx, cy, 4.5f, p);
    }
}
