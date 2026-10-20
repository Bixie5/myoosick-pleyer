package com.myoosick.pleyer;

import android.content.Context;
import android.content.res.AssetManager;
import android.os.Environment;

import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Copies the bundled player to real files and starts its Flask server in embedded Python. */
final class Backend {
    static final int PORT = 18765;
    static final String URL = "http://127.0.0.1:" + PORT + "/";

    private static boolean started = false;

    private Backend() {}

    static synchronized void start(Context ctx) throws IOException {
        if (started) return;
        Context app = ctx.getApplicationContext();

        // app.py needs real files on disk (it serves ./static), so unpack the assets.
        File dir = new File(app.getFilesDir(), "pleyer");
        copyAssets(app.getAssets(), "pleyer", dir);

        // Downloads go to Android/data/<package>/files/Music (visible in any file manager).
        File music = app.getExternalFilesDir(Environment.DIRECTORY_MUSIC);
        if (music == null) music = new File(app.getFilesDir(), "downloads");

        if (!Python.isStarted()) Python.start(new AndroidPlatform(app));
        Python.getInstance().getModule("android_main")
                .callAttr("start", dir.getAbsolutePath(), music.getAbsolutePath(), PORT);
        started = true;
    }

    private static void copyAssets(AssetManager am, String asset, File out) throws IOException {
        String[] children = am.list(asset);
        if (children != null && children.length > 0) {
            if (!out.exists() && !out.mkdirs()) throw new IOException("mkdir " + out);
            for (String c : children) copyAssets(am, asset + "/" + c, new File(out, c));
        } else {
            try (InputStream in = am.open(asset); OutputStream os = new FileOutputStream(out)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            }
        }
    }
}
