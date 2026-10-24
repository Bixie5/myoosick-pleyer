"""Extra routes for the Android app: music folders the user added (see FolderStore.java).

Added next to the original app.py without changing it:
  GET    /api/folders             -> {"folders": [{"uri","name"}]}
  DELETE /api/folders?u=<tree>    -> forget a folder
  GET    /api/folders/scan?u=...  -> {"tracks": [{"name","path","size","uri"}]}
  GET    /saf/file?u=<doc uri>    -> the audio bytes (with Range support, so seeking works)
"""
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


def register(app):
    @app.get("/api/folders")
    def folders_list():
        return jsonify(folders=_folders())

    @app.delete("/api/folders")
    def folders_remove():
        _fs().remove(request.args.get("u", ""))
        return jsonify(ok=True)

    @app.get("/api/folders/scan")
    def folders_scan():
        tree = request.args.get("u", "")
        if not any(f["uri"] == tree for f in _folders()):
            return jsonify(error="unknown folder"), 404
        tracks = json.loads(str(_fs().scanJson(tree)))
        tracks.sort(key=lambda t: (t.get("path", "").lower(), t["name"].lower()))
        return jsonify(tracks=tracks)

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
        size = os.fstat(fd).st_size

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
        f.seek(start)

        def body():
            left = length
            try:
                while left > 0:
                    chunk = f.read(min(64 * 1024, left))
                    if not chunk:
                        break
                    left -= len(chunk)
                    yield chunk
            finally:
                f.close()

        headers = {"Content-Type": mime, "Accept-Ranges": "bytes", "Content-Length": str(length),
                   "Cache-Control": "no-store"}
        if status == 206:
            headers["Content-Range"] = "bytes %d-%d/%d" % (start, end, size)
        return Response(body(), status=status, headers=headers)
