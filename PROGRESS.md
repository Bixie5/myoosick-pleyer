# Android work log (update after each step; resume from here)

Order agreed with the user: one feature at a time, Android only.

1. [x] Lockscreen + notification controls (code done + page-side tested; needs on-phone check)
2. [x] Move +FILES / +FOLDER from QUEUE to LIBRARY (done, tested)
3. [x] Real "add folder" library (SAF tree uri, persisted, plays from folder) (done; Python+page tested, Java untested on device)
4. [x] Close app fully when swiped from recents (done; needs on-phone check)

Step 1 files: PlayerService.java, MainActivity.java, index.html, res/drawable/ic_*.xml

Step 2+3 files: FolderStore.java (new), MainActivity.java, Backend.java, python/android_api.py (new), python/android_main.py, index.html

Step 4 files: PlayerService.java, AndroidManifest.xml

Step 5 files: PlayerService.java, RetroArt.java (new), res/layout/notif_quick.xml (new), res/drawable/*.xml (ic_*, k_*, q_*), res/drawable-nodpi/retro_cassette.png (new)

Delivery method: changed-files.zip (drag `app` folder into GitHub upload page) + full project zip.

5. [x] Retro look for notification player (done; needs on-phone check)
   Plan: res/drawable-nodpi/retro_cassette.png (pre-rendered) + RetroArt.java overlays title/artist; pixel-art icons (generated); custom RemoteViews quick card (notif_quick.xml)

## 6. Media card art + speed (done, untested on device)
- Media card art = song thumbnail (web tracks) else amber LCD-strip card (RetroArt.renderLcd); thumbnail fetched off-thread.
- Lag: removed full-screen CRT overlays/text-shadows on Android, visualizer capped ~30fps and idle-aware, queue rebuilt only when visible, notification updates coalesced, no console logging.

## 7. Search checks local folders first (done, Android only)
- Search scans added folders (cached after first scan), shows "ON THIS PHONE" matches first, then online results below.

## 8. Folder songs play straight from the folder (done, Android)
- Playing from a folder/search no longer fills the queue; only the playing song is in the queue. Next/prev/auto-advance walk the folder in order, or shuffled when shuffle is on. Songs added with "+" stay queued and play first.

## 9. Notification art + close button (done)
- No thumbnail -> no custom art (default blank look). Thumbnail -> square amber-framed thumbnail.
- Quick-controls card has an X key in the right corner that stops playback and closes the app.

## 10. Notification buttons: smooth icons, close replaces repeat (done)
- Smooth standard vector icons instead of pixel icons; round keys on the quick card; repeat removed from the media card and replaced by Close (stops + closes the app); X removed from the quick card.
- (quick-controls card keeps its original pixel keys/icons; only the media card icons are smooth)

## 11. Library: RECENT button (done, Android)
- Removed +FILES, +SAVED and the FOLDERS/SAVED TAPES headings. New RECENT button lists the 100 most recently added songs (folders by file date + saved downloads); plays straight from that list. FolderStore scan now reports file modified time.

## 12. Most played / Recently played (done, Android library)
- Library has ADDED, PLAYED, MOST PLAYED buttons. A play counts after ~5 s of playback. PLAYED = last 100. MOST PLAYED = top 100, needs 2+ plays, a newcomer must beat the bottom song's count by 1 to enter once full. Stored in localStorage (myoosick_pleyer.stats).

## 13. Playlists, likes, selection (done, Android)
- Plan: PLAYLISTS button in Library (+ PLAYLIST, retro heart = liked songs, rename/delete), long-press multi-select with popup (add to playlist / queue / info), swipe-right = like. Data in localStorage (myoosick_pleyer.playlists). All in index.html, Android only.

## 14. Library as a vertical menu (done, Android)
- Library = 5 stacked full-name buttons: FOLDERS, PLAYLISTS, MOST PLAYED, RECENTLY PLAYED, RECENTLY ADDED. Opening one hides the others (it stays on top, marked with a down arrow); tap it again to return. + FOLDER and REFRESH (and the saved downloads) live inside FOLDERS.
- (library buttons restyled: dark amber small buttons, still a vertical menu)

## 15. DISC button taps (done, Android)
- Single tap opens DISC (radio not changed); tapping it again goes back; a quick double tap turns the radio on / off.

## 16. ALL SONGS in the Library (done, Android)
- New ALL SONGS button (under FOLDERS): every file from all added folders, sort by NAME / DATE / SIZE / LENGTH (tap again to flip direction). Virtual list: only visible rows exist in the page, so 5000+ songs don't lag. Lengths are read once on a background thread (FolderStore.durStart, cached in files/durations.json) the first time LENGTH is chosen.

## 17. Reorder the queue by dragging (done, Android)
- Each queue row has a ≡ grip; press and drag it up/down (list auto-scrolls near the edges); the playing song keeps playing and its position is tracked.

## 18. DISC only for online songs (done, Android)
- Radio is active only while the playing song is an online one. Local/folder songs play normally (folder order / shuffle) even with radio on; the DISC view says radio only works with online songs.

## 19. Remembered folder scans (done, Android)
- Each folder's scan is saved on the phone (app dir/.scan_cache), so lists open instantly after a restart. A quiet background rescan runs ~5 s after launch and on REFRESH; if anything changed the open list updates and "LIBRARY UPDATED" shows. Removing a folder deletes its cache.

## 20. Back button / back swipe (done, Android)
- Back closes the innermost thing first: popup -> selection -> keyboard -> open playlist/folder -> library list -> any page -> cassette deck page. Only when already on the cassette deck does back send the app to the background (it keeps playing). MainActivity.onBackPressed asks the page (window.__back).

## 21. FLAC with broken cover art (done, Android)
- /saf/file now serves .flac files without their embedded PICTURE blocks (android_api._flac_plan/_open_reader), so a malformed cover image can't stop playback. Seeking still works (byte ranges are remapped). Tested with a FLAC that reproduces ffmpeg's "Could not read mimetype from an attached picture" and fails in Chromium (error 4) -> plays after the fix.
