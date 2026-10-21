"""Starts the unmodified MYOOSICK PLEYER Flask server inside the Android app."""
import os
import sys
import threading
import time


def _patch_yt_dlp():
    """YouTube's normal clients need an external JavaScript runtime, which a phone doesn't have.
    The 'android_vr' client works without one. So, for non-search calls:
      - prefer android_vr
      - allow muxed formats as a fallback (android_vr sometimes only offers 360p audio+video)
      - retry a few times; the last try goes back to yt-dlp's default clients
    Search / playlist listing (extract_flat) is left alone."""
    import yt_dlp

    cls = yt_dlp.YoutubeDL
    if getattr(cls, "_pleyer_patched", False):
        return
    orig_init, orig_extract = cls.__init__, cls.extract_info

    def init(self, params=None, *a, **kw):
        params = dict(params or {})
        if not params.get("extract_flat"):
            ea = dict(params.get("extractor_args") or {})
            yt = dict(ea.get("youtube") or {})
            yt.setdefault("player_client", ["android_vr"])
            ea["youtube"] = yt
            params["extractor_args"] = ea
            fmt = params.get("format")
            if fmt and not fmt.endswith("/best"):
                params["format"] = fmt + "/best"
        orig_init(self, params, *a, **kw)

    def extract_info(self, *a, **kw):
        p = getattr(self, "params", None) or {}
        if p.get("extract_flat"):
            return orig_extract(self, *a, **kw)
        last = None
        for attempt in range(3):
            if attempt == 2:  # last resort: yt-dlp's own default clients
                try:
                    p["extractor_args"]["youtube"].pop("player_client", None)
                except Exception:  # noqa: BLE001
                    pass
            try:
                return orig_extract(self, *a, **kw)
            except Exception as e:  # noqa: BLE001
                last = e
                print("yt-dlp attempt %d failed: %s" % (attempt + 1, str(e)[:200]))
                time.sleep(0.6)
        raise last

    cls.__init__, cls.extract_info, cls._pleyer_patched = init, extract_info, True


def start(app_dir, downloads_dir, port):
    # app.py reads this (odd-looking, but it's the exact name in app.py)
    os.environ["MYOOSICK PLEYER_DOWNLOADS"] = downloads_dir
    os.environ["NO_BROWSER"] = "1"
    os.environ.setdefault("XDG_CACHE_HOME", os.path.join(app_dir, ".cache"))
    os.makedirs(downloads_dir, exist_ok=True)
    os.chdir(app_dir)
    if app_dir not in sys.path:
        sys.path.insert(0, app_dir)

    _patch_yt_dlp()
    import app as player  # the original app.py

    def serve():
        player.app.run(host="127.0.0.1", port=int(port), threaded=True, use_reloader=False)

    threading.Thread(target=serve, name="flask", daemon=True).start()
