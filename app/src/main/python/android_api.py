"""Extra routes for the Android app: music folders the user added (see FolderStore.java).

Added next to the original app.py without changing it:
  GET    /api/folders             -> {"folders": [{"uri","name"}]}
  DELETE /api/folders?u=<tree>    -> forget a folder
  GET    /api/folders/scan?u=...  -> {"tracks": [{"name","path","size","mod","uri"}], "cached": bool}
                                     (the result is remembered on disk; add &fresh=1 to rescan the folder)
  POST   /api/durations/start     -> body: JSON list of document URIs; reads their lengths in the background
  GET    /api/durations?since=N   -> {"running","done","total","next","map":{uri: ms}}
  GET    /saf/file?u=<doc uri>    -> the audio bytes (with Range support, so seeking works)
"""
import hashlib
import json
import mimetypes
import os
import re

from flask import Response, jsonify, request

_MIME = {".mp3": "audio/mpeg", ".m4a": "audio/mp4", ".mp4": "audio/mp4", ".aac": "audio/aac",
         ".ogg": "audio/ogg", ".oga": "audio/ogg", ".opus": "audio/ogg", ".flac": "audio/flac",
         ".wav": "audio/wav", ".wma": "audio/x-ms-wma", ".webm": "audio/webm", ".mka": "audio/x-matroska"}


def _fs():
    from java import jclass  # only exists on the phone

    return jclass("com.myoosick.pleyer.FolderStore")


def _folders():
    return json.loads(str(_fs().listJson()))


# Remembered folder scans, so opening the library does not rescan thousands of files every launch.
def _cache_dir():
    return os.path.join(os.getcwd(), ".scan_cache")


def _cache_file(tree):
    return os.path.join(_cache_dir(), hashlib.sha1(tree.encode("utf-8")).hexdigest() + ".json")


def _cache_read(tree):
    try:
        with open(_cache_file(tree), "r", encoding="utf-8") as f:
            data = json.load(f)
        return data if isinstance(data, list) else None
    except Exception:  # noqa: BLE001
        return None


def _cache_write(tree, tracks):
    try:
        os.makedirs(_cache_dir(), exist_ok=True)
        tmp = _cache_file(tree) + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(tracks, f)
        os.replace(tmp, _cache_file(tree))
    except Exception:  # noqa: BLE001
        pass


def _cache_drop(tree):
    try:
        os.remove(_cache_file(tree))
    except OSError:
        pass


def _flac_plan(f, size):
    """FLAC files with a broken embedded cover picture (bad MIME type / size) can make the browser's player give up.
    Playback never needs the picture, so we serve the file without its PICTURE blocks.
    Returns (new_header_bytes, audio_start) when the file has picture blocks, else None."""
    try:
        f.seek(0)
        if f.read(4) != b"fLaC":
            return None
        pos, kept, found = 4, [], False
        while True:
            f.seek(pos)
            h = f.read(4)
            if len(h) < 4:
                return None
            last, typ, ln = h[0] & 0x80, h[0] & 0x7F, int.from_bytes(h[1:4], "big")
            end = pos + 4 + ln
            if end > size:
                return None
            if typ == 6:
                found = True
            else:
                f.seek(pos + 4)
                kept.append((typ, f.read(ln)))
            pos = end
            if last:
                break
        if not found or not kept:
            return None
        header = b"fLaC"
        for i, (typ, body) in enumerate(kept):
            header += bytes([(0x80 if i == len(kept) - 1 else 0) | typ]) + len(body).to_bytes(3, "big") + body
        return header, pos
    except Exception:  # noqa: BLE001
        return None


def _open_reader(f, size, ext):
    """-> (size, read(start, length) generator). For most files that is the file itself."""
    plan = _flac_plan(f, size) if ext == ".flac" else None
    hdr, audio_start = plan if plan else (b"", 0)
    vsize = (len(hdr) + size - audio_start) if plan else size

    def read(start, length):
        left, pos = length, start
        if plan and pos < len(hdr):
            part = hdr[pos:pos + left]
            left -= len(part)
            pos += len(part)
            yield part
        if left > 0:
            f.seek((audio_start + pos - len(hdr)) if plan else pos)
            while left > 0:
                chunk = f.read(min(64 * 1024, left))
                if not chunk:
                    break
                left -= len(chunk)
                yield chunk

    return vsize, read


def register(app):
    @app.get("/api/folders")
    def folders_list():
        return jsonify(folders=_folders())

    @app.delete("/api/folders")
    def folders_remove():
        u = request.args.get("u", "")
        _fs().remove(u)
        _cache_drop(u)
        return jsonify(ok=True)

    @app.get("/api/folders/scan")
    def folders_scan():
        tree = request.args.get("u", "")
        if not any(f["uri"] == tree for f in _folders()):
            return jsonify(error="unknown folder"), 404
        if request.args.get("fresh") != "1":
            cached = _cache_read(tree)
            if cached is not None:
                return jsonify(tracks=cached, cached=True)
        tracks = json.loads(str(_fs().scanJson(tree)))
        tracks.sort(key=lambda t: (t.get("path", "").lower(), t["name"].lower()))
        _cache_write(tree, tracks)
        return jsonify(tracks=tracks, cached=False)

    @app.post("/api/durations/start")
    def durations_start():
        uris = request.get_json(force=True, silent=True) or []
        _fs().durStart(json.dumps([u for u in uris if isinstance(u, str)]))
        return jsonify(ok=True)

    @app.get("/api/durations")
    def durations():
        since = int(request.args.get("since", "0") or 0)
        return Response(str(_fs().durJson(since)), mimetype="application/json")

    @app.get("/saf/file")
    def saf_file():
        uri = request.args.get("u", "")
        # only documents inside a folder the user added
        if not any(uri.startswith(f["uri"] + "/document/") for f in _folders()):
            return jsonify(error="not in an added folder"), 403
        name = request.args.get("n", "") or uri
        ext = os.path.splitext(name)[1].lower()
        mime = _MIME.get(ext) or mimetypes.guess_type(name)[0] or "audio/mpeg"

        try:
            fd = int(_fs().openFd(uri))
        except Exception as e:  # noqa: BLE001
            return jsonify(error="cannot open file: " + str(e)[:200]), 404
        f = os.fdopen(fd, "rb")
        size, reader = _open_reader(f, os.fstat(fd).st_size, ext)

        start, end, status = 0, size - 1, 200
        rng = request.headers.get("Range")
        if rng and size > 0:
            m = re.match(r"bytes=(\d*)-(\d*)$", rng.strip())
            if not m or (m.group(1) == "" and m.group(2) == ""):
                f.close()
                return Response(status=416, headers={"Content-Range": "bytes */%d" % size})
            if m.group(1) == "":                         # suffix range: last N bytes
                start = max(0, size - int(m.group(2)))
            else:
                start = int(m.group(1))
                if m.group(2) != "":
                    end = min(end, int(m.group(2)))
            if start >= size or start > end:
                f.close()
                return Response(status=416, headers={"Content-Range": "bytes */%d" % size})
            status = 206

        length = end - start + 1

        def body():
            try:
                yield from reader(start, length)
            finally:
                f.close()

        headers = {"Content-Type": mime, "Accept-Ranges": "bytes", "Content-Length": str(length),
                   "Cache-Control": "no-store"}
        if status == 206:
            headers["Content-Range"] = "bytes %d-%d/%d" % (start, end, size)
        return Response(body(), status=status, headers=headers)
