package com.myoosick.pleyer;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.webkit.ConsoleMessage;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.TextView;

import java.net.HttpURLConnection;
import java.net.URL;

public class MainActivity extends Activity {
    private static final int REQ_FILES = 1001;
    private static final int REQ_NOTIF = 1002;

    private WebView web;
    private TextView splash;
    private ValueCallback<Uri[]> chooserCallback;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        web = findViewById(R.id.web);
        splash = findViewById(R.id.splash);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);                 // queue / volume / settings persistence
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);                // content:// files from the picker

        web.setWebViewClient(new WebViewClient());
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (chooserCallback != null) chooserCallback.onReceiveValue(null);
                chooserCallback = cb;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"audio/*", "application/ogg"});
                i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);  // also covers "+ FOLDER": pick many files
                try {
                    startActivityForResult(Intent.createChooser(i, "Pick music"), REQ_FILES);
                } catch (Exception e) {
                    chooserCallback = null;
                    cb.onReceiveValue(null);
                    return false;
                }
                return true;
            }

            @Override
            public boolean onConsoleMessage(ConsoleMessage m) {
                android.util.Log.d("PleyerWeb", m.message());
                return true;
            }
        });

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
        }
        startForegroundService(new Intent(this, PlayerService.class));
        waitForServerThenLoad();
    }

    /** Poll the on-device server until it answers, then show the UI. */
    private void waitForServerThenLoad() {
        new Thread(() -> {
            boolean up = false;
            long deadline = System.currentTimeMillis() + 60_000;
            while (!up && System.currentTimeMillis() < deadline) {
                try {
                    HttpURLConnection c = (HttpURLConnection) new URL(Backend.URL + "api/status").openConnection();
                    c.setConnectTimeout(800);
                    c.setReadTimeout(800);
                    up = c.getResponseCode() == 200;
                    c.disconnect();
                } catch (Exception ignored) {
                }
                if (!up) {
                    try { Thread.sleep(400); } catch (InterruptedException e) { return; }
                }
            }
            final boolean ok = up;
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                if (ok) {
                    web.setVisibility(View.VISIBLE);
                    splash.setVisibility(View.GONE);
                    web.loadUrl(Backend.URL);
                } else {
                    splash.setText("COULD NOT START THE PLAYER\n\nCheck logcat (tag: Pleyer)");
                }
            });
        }, "wait-server").start();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req != REQ_FILES) { super.onActivityResult(req, res, data); return; }
        Uri[] result = null;
        if (res == RESULT_OK && data != null) {
            ClipData clip = data.getClipData();
            if (clip != null) {
                result = new Uri[clip.getItemCount()];
                for (int i = 0; i < result.length; i++) result[i] = clip.getItemAt(i).getUri();
            } else if (data.getData() != null) {
                result = new Uri[]{data.getData()};
            }
        }
        if (chooserCallback != null) chooserCallback.onReceiveValue(result);
        chooserCallback = null;
    }

    // NOTE: onPause() is intentionally NOT forwarded to the WebView, so audio keeps playing
    // when the app is backgrounded or the screen turns off.

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack();
        else moveTaskToBack(true);   // keep playing; use the notification to return
    }
}
