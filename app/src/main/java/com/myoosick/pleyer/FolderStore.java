package com.myoosick.pleyer;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Music folders the user added (Android "folder picker" / SAF tree URIs).
 * The folder list is remembered in SharedPreferences and the read permission is kept
 * (takePersistableUriPermission), so folders survive app restarts.
 * The Python server calls these static methods (via Chaquopy) to list, scan and open files.
 */
public final class FolderStore {
    private static final String PREFS = "folders", KEY = "list";
    private static final String[] AUDIO_EXT = {".mp3", ".m4a", ".mp4", ".aac", ".ogg", ".oga", ".opus", ".flac", ".wav", ".wma", ".webm", ".mka"};
    private static final int MAX_FILES = 20000, MAX_DEPTH = 10;

    private static volatile Context app;

    private FolderStore() {}

    public static void init(Context c) {
        app = c.getApplicationContext();
        loadDurs();
    }

    private static SharedPreferences prefs() {
        return app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static JSONArray load() {
        try {
            return new JSONArray(prefs().getString(KEY, "[]"));
        } catch (JSONException e) {
            return new JSONArray();
        }
    }

    /** JSON: [{"uri": "...", "name": "..."}] */
    public static synchronized String listJson() {
        return load().toString();
    }

    /** Remember a folder the user just picked. Returns false if it was already added. */
    public static synchronized boolean add(Uri tree) {
        try {
            app.getContentResolver().takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception e) {
            Log.w("Pleyer", "could not persist folder permission", e);
        }
        JSONArray list = load();
        String s = tree.toString();
        for (int i = 0; i < list.length(); i++) {
            if (s.equals(list.optJSONObject(i).optString("uri"))) return false;
        }
        try {
            list.put(new JSONObject().put("uri", s).put("name", displayName(tree)));
        } catch (JSONException e) {
            return false;
        }
        prefs().edit().putString(KEY, list.toString()).apply();
        return true;
    }

    public static synchronized void remove(String treeUri) {
        JSONArray list = load(), keep = new JSONArray();
        for (int i = 0; i < list.length(); i++) {
            JSONObject o = list.optJSONObject(i);
            if (o != null && !treeUri.equals(o.optString("uri"))) keep.put(o);
        }
        prefs().edit().putString(KEY, keep.toString()).apply();
        try {
            app.getContentResolver().releasePersistableUriPermission(Uri.parse(treeUri), Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) {
        }
    }

    private static String displayName(Uri tree) {
        String id = DocumentsContract.getTreeDocumentId(tree);
        Uri doc = DocumentsContract.buildDocumentUriUsingTree(tree, id);
        try (Cursor c = app.getContentResolver().query(doc,
                new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && c.getString(0) != null) return c.getString(0);
        } catch (Exception ignored) {
        }
        int i = Math.max(id.lastIndexOf('/'), id.lastIndexOf(':'));
        return i >= 0 && i < id.length() - 1 ? id.substring(i + 1) : id;
    }

    /** JSON: [{"name","path","size","uri"}] for every audio file under the folder (recursive). */
    public static String scanJson(String treeUri) {
        JSONArray out = new JSONArray();
        Uri tree = Uri.parse(treeUri);
        walk(tree, DocumentsContract.getTreeDocumentId(tree), "", out, 0);
        return out.toString();
    }

    private static boolean isAudio(String name, String mime) {
        if (mime != null && (mime.startsWith("audio/") || mime.equals("application/ogg"))) return true;
        if (name == null) return false;
        String n = name.toLowerCase(Locale.ROOT);
        for (String e : AUDIO_EXT) if (n.endsWith(e)) return true;
        return false;
    }

    private static void walk(Uri tree, String docId, String path, JSONArray out, int depth) {
        if (depth > MAX_DEPTH || out.length() >= MAX_FILES) return;
        ContentResolver cr = app.getContentResolver();
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId);
        String[] proj = {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED};
        List<String[]> dirs = new ArrayList<>();
        try (Cursor c = cr.query(children, proj, null, null, null)) {
            if (c == null) return;
            while (c.moveToNext()) {
                String id = c.getString(0), name = c.getString(1), mime = c.getString(2);
                long size = c.isNull(3) ? 0 : c.getLong(3);
                long mod = c.isNull(4) ? 0 : c.getLong(4);
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    dirs.add(new String[]{id, name});
                } else if (isAudio(name, mime) && out.length() < MAX_FILES) {
                    try {
                        out.put(new JSONObject()
                                .put("name", name)
                                .put("path", path)
                                .put("size", size)
                                .put("mod", mod)
                                .put("uri", DocumentsContract.buildDocumentUriUsingTree(tree, id).toString()));
                    } catch (JSONException ignored) {
                    }
                }
            }
        } catch (Exception e) {
            Log.w("Pleyer", "scan failed in " + path, e);
        }
        for (String[] d : dirs) walk(tree, d[0], path.isEmpty() ? d[1] : path + "/" + d[1], out, depth + 1);
    }

    /** Opens one document and hands back a raw file descriptor (Python wraps it in a file object). */
    public static int openFd(String docUri) throws Exception {
        ParcelFileDescriptor pfd = app.getContentResolver().openFileDescriptor(Uri.parse(docUri), "r");
        if (pfd == null) throw new java.io.FileNotFoundException(docUri);
        return pfd.detachFd();
    }

    // ------------------------------------------------------------------ song lengths (for sorting by duration)
    // Reading a song's length means opening it, so it is done once, on a low-priority background thread,
    // and remembered in files/durations.json. The page asks for progress and only fetches what is new.
    private static final java.util.concurrent.ConcurrentHashMap<String, Long> durs = new java.util.concurrent.ConcurrentHashMap<>();
    private static final List<String> durOrder = java.util.Collections.synchronizedList(new ArrayList<>());
    private static volatile boolean durRunning;
    private static volatile int durDone, durTotal;

    private static java.io.File durFile() {
        return new java.io.File(app.getFilesDir(), "durations.json");
    }

    private static void loadDurs() {
        try {
            java.io.File f = durFile();
            if (!f.exists()) return;
            byte[] b = new byte[(int) f.length()];
            try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
                int off = 0, n;
                while (off < b.length && (n = in.read(b, off, b.length - off)) > 0) off += n;
            }
            JSONObject o = new JSONObject(new String(b, "UTF-8"));
            java.util.Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next();
                durs.put(k, o.optLong(k, 0));
                durOrder.add(k);
            }
        } catch (Throwable t) {
            Log.w("Pleyer", "durations load", t);
        }
    }

    private static synchronized void saveDurs() {
        try {
            JSONObject o = new JSONObject();
            for (java.util.Map.Entry<String, Long> e : durs.entrySet()) o.put(e.getKey(), e.getValue());
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(durFile())) {
                out.write(o.toString().getBytes("UTF-8"));
            }
        } catch (Throwable t) {
            Log.w("Pleyer", "durations save", t);
        }
    }

    /** Start reading the lengths of any of these files (JSON array of document URIs) we do not know yet. */
    public static synchronized void durStart(String urisJson) {
        if (durRunning) return;
        final List<String> todo = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(urisJson);
            for (int i = 0; i < a.length(); i++) {
                String u = a.getString(i);
                if (!durs.containsKey(u)) todo.add(u);
            }
        } catch (JSONException e) {
            return;
        }
        durTotal = todo.size();
        durDone = 0;
        if (todo.isEmpty()) return;
        durRunning = true;
        Thread t = new Thread(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            try {
                for (String u : todo) {
                    long ms = 0;
                    MediaMetadataRetriever r = new MediaMetadataRetriever();
                    try {
                        r.setDataSource(app, Uri.parse(u));
                        String d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                        if (d != null) ms = Long.parseLong(d);
                    } catch (Throwable ignored) {
                    } finally {
                        try { r.release(); } catch (Throwable ignored) { }
                    }
                    durs.put(u, ms);
                    durOrder.add(u);
                    durDone++;
                    if (durDone % 250 == 0) saveDurs();
                }
            } finally {
                saveDurs();
                durRunning = false;
            }
        }, "song-lengths");
        t.setDaemon(true);
        t.start();
    }

    /** JSON: {"running":bool,"done":n,"total":n,"next":cursor,"map":{uri: ms}} with only the entries after `since`. */
    public static String durJson(int since) {
        JSONObject o = new JSONObject();
        try {
            JSONObject map = new JSONObject();
            int next;
            synchronized (durOrder) {
                next = durOrder.size();
                for (int i = Math.max(0, since); i < next; i++) {
                    String u = durOrder.get(i);
                    Long v = durs.get(u);
                    if (v != null) map.put(u, v);
                }
            }
            o.put("running", durRunning).put("done", durDone).put("total", durTotal).put("next", next).put("map", map);
        } catch (JSONException ignored) {
        }
        return o.toString();
    }
}
