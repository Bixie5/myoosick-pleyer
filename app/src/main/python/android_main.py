"""Starts the unmodified MYOOSICK PLEYER Flask server inside the Android app."""
import os
import sys
import threading
import time


# YouTube has no single client that keeps working: the normal ones need a JavaScript runtime
# (a phone has none) and some need "PO tokens". So for YouTube we try several clients in turn,
# check that the stream URL really answers, and remember the one that worked.
_CLIENTS = ["visionos", "android_vr", "tv_simply", "ios", "android"]
_GOOD = {"client": None}
# direct https audio only (the proxy can't play HLS), best audio first, anything as a last resort
_AUDIO_FMT = "bestaudio[protocol=https][ext=m4a]/bestaudio[protocol=https]/best[protocol=https]/bestaudio/best"


def _probe(info):
    """None if the stream URL answers from this device, else a short reason."""
    import requests

    url = info.get("url")
    if not url:
        return "no direct url"
    if ".m3u8" in url or str(info.get("protocol", "")).startswith("m3u8"):
        return "HLS only"
    headers = dict(info.get("http_headers") or {})
    headers["Range"] = "bytes=0-1"
    headers["Accept-Encoding"] = "identity"
    try:
        r = requests.get(url, headers=headers, stream=True, timeout=(8, 12))
        code = r.status_code
        r.close()
    except Exception as e:  # noqa: BLE001
        return "net " + str(e)[:50]
    return None if code < 400 else "HTTP %d" % code


def _patch_yt_dlp():
    import yt_dlp

    cls = yt_dlp.YoutubeDL
    if getattr(cls, "_pleyer_patched", False):
        return
    orig_init, orig_extract = cls.__init__, cls.extract_info

    def init(self, params=None, *a, **kw):
        params = dict(params or {})
        if not params.get("extract_flat"):          # search / playlist listing is left alone
            params["format"] = _AUDIO_FMT
        orig_init(self, params, *a, **kw)

    def extract_info(self, url, *a, **kw):
        p = getattr(self, "params", None)
        if p is None or p.get("extract_flat"):
            return orig_extract(self, url, *a, **kw)
        download = kw.get("download", a[0] if a else True)
        is_yt = "youtube.com" in url or "youtu.be" in url
        if not is_yt:                                # other sites: just retry once
            try:
                return orig_extract(self, url, *a, **kw)
            except Exception:  # noqa: BLE001
                time.sleep(0.6)
                return orig_extract(self, url, *a, **kw)

        order = list(_CLIENTS)
        if _GOOD["client"] in order:
            order.remove(_GOOD["client"])
            order.insert(0, _GOOD["client"])
        order.append(None)                           # None = yt-dlp's own default clients
        report = []
        for client in order:
            ea = p.setdefault("extractor_args", {}).setdefault("youtube", {})
            if client:
                ea["player_client"] = [client]
            else:
                ea.pop("player_client", None)
            name = client or "default"
            try:
                info = orig_extract(self, url, *a, **kw)
            except Exception as e:  # noqa: BLE001
                msg = str(e).replace("ERROR: ", "").replace("\n", " ")
                report.append("%s: %s" % (name, msg[:70]))
                print("yt-dlp %s failed: %s" % (name, msg[:200]))
                continue
            if download or not isinstance(info, dict) or info.get("entries") is not None:
                _GOOD["client"] = client
                return info
            err = _probe(info)
            if err is None:
                _GOOD["client"] = client
                print("yt-dlp: using client %s" % name)
                return info
            report.append("%s: %s" % (name, err))
            print("yt-dlp %s unusable: %s" % (name, err))
        raise Exception("yt-dlp %s | %s" % (yt_dlp.version.__version__, " | ".join(report)))

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
