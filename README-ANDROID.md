# MYOOSICK PLEYER - Android

Your player, unchanged, packaged as an Android app. `app.py` and `static/index.html`
(in `app/src/main/assets/pleyer/`) are byte-for-byte your files. The app embeds Python
(via Chaquopy), runs your Flask + yt-dlp server on the phone at 127.0.0.1:18765, and shows it
in a full-screen WebView. Search, streaming, downloads and the library all work the same way.

## Build the APK - pick ONE

### A) No installs: GitHub builds it for you (easiest)
1. Create a new private repo on github.com and upload this whole folder (keep the `.github` folder).
2. Open the repo's **Actions** tab -> **Build APK** -> wait ~5-8 min (or click *Run workflow*).
3. Open the finished run -> **Artifacts** -> download `myoosick-pleyer-apk` -> unzip -> `app-debug.apk`.

### B) Android Studio
1. Install Android Studio (it brings its own JDK/SDK) and Python 3.12 (Chaquopy needs one on the PC).
2. File -> Open -> this folder. Let Gradle sync (needs internet the first time).
3. Build -> Build Bundle(s)/APK(s) -> Build APK(s). Output: `app/build/outputs/apk/debug/app-debug.apk`.
   If sync complains it can't find Python, set `buildPython "C:/Path/to/python.exe"` inside the
   `python { }` block of `app/build.gradle`.

## Install on the phone
Copy `app-debug.apk` to the phone, open it, and allow "install unknown apps" when asked.
Allow notifications on first launch (needed for background playback).

## How it behaves
- **Local music:** `+ FILES` opens Android's picker (multi-select). `+ FOLDER` also opens the
  multi-file picker, because Android WebView has no folder picker - select all the songs you want.
- **Online:** SEARCH tab, paste links, stream, queue, download - same as desktop.
- **Downloads** are saved to `Android/data/com.myoosick.pleyer/files/Music/` (visible in any file manager).
  There is no ffmpeg on the phone, so downloads are the native m4a/webm audio (plays fine), not MP3.
- **Background play:** a foreground service + notification keeps the server and audio alive with the
  screen off. Back button sends the app to the background instead of quitting; swipe it away to stop.
- **If YouTube search/playback breaks later:** yt-dlp is pinned at build time. Rebuild the APK to pull the latest yt-dlp.

## Notes
- This is a debug-signed APK (fine for personal use). For Play Store you'd need a release keystore.
- Chaquopy 16.0.0 / AGP 8.5.2 are set in `build.gradle`; bump them if a newer sync warning appears.

## Discover (radio) mode
Press **DISC** (or `D` on a keyboard). While it's on, the app keeps the queue topped up with songs
similar to the one playing, using YouTube's own "Mix" recommendations. Picks are semi-random (close matches +
older leftovers, and every 5th hop it jumps off an earlier song), never repeat, and skip clips / hour-long videos.
Works with local files too (it matches by artist/title). Unplayable songs are skipped automatically.
