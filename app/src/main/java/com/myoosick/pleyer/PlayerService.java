package com.myoosick.pleyer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONObject;

/**
 * Foreground service. Keeps the process + Python server alive, and shows the player on the
 * lockscreen and in the notification shade (media controls + a "quick controls" card).
 * Buttons are forwarded to the page (MainActivity.sendCommand); the page reports its state back
 * through JsBridge -> PlayerService.update().
 */
public class PlayerService extends Service {
    private static final String CHANNEL = "pleyer";
    private static final String ACTION_PREFIX = "com.myoosick.pleyer.cmd.";
    private static final int ID_MEDIA = 1, ID_QUICK = 2;

    /** What the page last told us. */
    static final class State {
        String title = "", artist = "", repeat = "off";
        boolean playing, shuffle, disc;
        long pos, dur;
        int vol = 80;
    }

    private static volatile State last = new State();
    private static volatile PlayerService instance;

    private final Handler main = new Handler(Looper.getMainLooper());
    private MediaSession session;
    private NotificationManager nm;
    private PowerManager.WakeLock wakeLock;

    /** Called (from any thread) with the page's JSON state. */
    static void update(String json) {
        try {
            JSONObject o = new JSONObject(json);
            State s = new State();
            s.title = o.optString("title", "");
            s.artist = o.optString("artist", "");
            s.playing = o.optBoolean("playing", false);
            s.pos = o.optLong("pos", 0);
            s.dur = o.optLong("dur", 0);
            s.shuffle = o.optBoolean("shuffle", false);
            s.repeat = o.optString("repeat", "off");
            s.disc = o.optBoolean("disc", false);
            s.vol = o.optInt("vol", 80);
            last = s;
        } catch (Exception e) {
            Log.w("Pleyer", "bad state json", e);
            return;
        }
        PlayerService svc = instance;
        if (svc != null) svc.main.post(svc::refresh);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        nm = getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Player controls", NotificationManager.IMPORTANCE_LOW);
        ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);

        session = new MediaSession(this, "MyoosickPleyer");
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { MainActivity.sendCommand("play"); }
            @Override public void onPause() { MainActivity.sendCommand("pause"); }
            @Override public void onStop() { MainActivity.sendCommand("pause"); }
            @Override public void onSkipToNext() { MainActivity.sendCommand("next"); }
            @Override public void onSkipToPrevious() { MainActivity.sendCommand("prev"); }
            @Override public void onSeekTo(long pos) { MainActivity.sendCommand("seek:" + pos); }
            @Override public void onCustomAction(String action, Bundle extras) { MainActivity.sendCommand(action); }
        });
        session.setActive(true);

        Notification first = buildMedia(last);
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(ID_MEDIA, first, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(ID_MEDIA, first);
        }
        refresh();

        PowerManager pm = getSystemService(PowerManager.class);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pleyer:audio");
        wakeLock.acquire();

        new Thread(() -> {
            try {
                Backend.start(this);
            } catch (Throwable t) {
                Log.e("Pleyer", "backend failed to start", t);
            }
        }, "backend-start").start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Notification buttons arrive here as "com.myoosick.pleyer.cmd.<name>"
        if (intent != null && intent.getAction() != null && intent.getAction().startsWith(ACTION_PREFIX)) {
            MainActivity.sendCommand(intent.getAction().substring(ACTION_PREFIX.length()));
        }
        return START_STICKY;
    }

    // ---------------------------------------------------------------- notifications

    private PendingIntent cmd(String name, int code) {
        Intent i = new Intent(this, PlayerService.class).setAction(ACTION_PREFIX + name);
        return PendingIntent.getService(this, code, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private Notification.Action act(int icon, String label, String name, int code) {
        return new Notification.Action.Builder(Icon.createWithResource(this, icon), label, cmd(name, code)).build();
    }

    private PendingIntent openApp() {
        Intent i = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static int repeatIcon(String r) {
        return "one".equals(r) ? R.drawable.ic_repeat_one : "all".equals(r) ? R.drawable.ic_repeat_on : R.drawable.ic_repeat;
    }

    private Notification buildMedia(State s) {
        boolean has = !s.title.isEmpty();
        Notification.MediaStyle style = new Notification.MediaStyle()
                .setShowActionsInCompactView(1, 2, 3);
        if (session != null) style.setMediaSession(session.getSessionToken());
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle(has ? s.title : "MYOOSICK PLEYER")
                .setContentText(has ? s.artist : "Nothing playing")
                .setContentIntent(openApp())
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setColor(0xFFE8742A)
                .addAction(act(s.shuffle ? R.drawable.ic_shuffle_on : R.drawable.ic_shuffle, "Shuffle", "shuffle", 10))
                .addAction(act(R.drawable.ic_prev, "Previous", "prev", 11))
                .addAction(s.playing ? act(R.drawable.ic_pause, "Pause", "pause", 12)
                                     : act(R.drawable.ic_play, "Play", "play", 12))
                .addAction(act(R.drawable.ic_next, "Next", "next", 13))
                .addAction(act(repeatIcon(s.repeat), "Repeat", "repeat", 14))
                .setStyle(style)
                .build();
    }

    /** Second card: the things the 5-button media card has no room for. */
    private Notification buildQuick(State s) {
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle("Quick controls")
                .setContentText("Radio " + (s.disc ? "ON" : "OFF") + "  |  Volume " + s.vol + "%")
                .setContentIntent(openApp())
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .addAction(act(s.disc ? R.drawable.ic_disc_on : R.drawable.ic_disc, s.disc ? "Radio: ON" : "Radio: OFF", "disc", 20))
                .addAction(act(R.drawable.ic_vol_down, "Vol -", "voldown", 21))
                .addAction(act(R.drawable.ic_vol_up, "Vol +", "volup", 22))
                .build();
    }

    private void refresh() {
        State s = last;
        boolean has = !s.title.isEmpty();
        session.setMetadata(new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, has ? s.title : "MYOOSICK PLEYER")
                .putString(MediaMetadata.METADATA_KEY_ARTIST, has ? s.artist : "Nothing playing")
                .putLong(MediaMetadata.METADATA_KEY_DURATION, s.dur)
                .build());
        PlaybackState.Builder pb = new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                        | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS
                        | PlaybackState.ACTION_SEEK_TO | PlaybackState.ACTION_STOP)
                .setState(s.playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                        s.pos, s.playing ? 1f : 0f, SystemClock.elapsedRealtime())
                // Android 13+ lockscreen/shade player shows these in its spare slots
                .addCustomAction("shuffle", "Shuffle", s.shuffle ? R.drawable.ic_shuffle_on : R.drawable.ic_shuffle)
                .addCustomAction("repeat", "Repeat", repeatIcon(s.repeat))
                .addCustomAction("disc", "Radio", s.disc ? R.drawable.ic_disc_on : R.drawable.ic_disc)
                .addCustomAction("voldown", "Volume down", R.drawable.ic_vol_down)
                .addCustomAction("volup", "Volume up", R.drawable.ic_vol_up);
        session.setPlaybackState(pb.build());
        session.setActive(true);
        nm.notify(ID_MEDIA, buildMedia(s));
        nm.notify(ID_QUICK, buildQuick(s));
    }

    @Override
    public void onDestroy() {
        instance = null;
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (session != null) { session.setActive(false); session.release(); }
        nm.cancel(ID_QUICK);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
