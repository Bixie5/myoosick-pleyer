"""MYOOSICK PLEYER - a retro cassette-deck music player.

Local files are played straight in the browser. Online search, streaming and
downloads are handled here with yt-dlp.

Run:  python app.py
"""
import os
import re
import shutil
import threading
import time
import uuid
import webbrowser
from pathlib import Path

import requests
import yt_dlp
from flask import Flask, Response, jsonify, request, send_from_directory, stream_with_context

BASE = Path(__file__).resolve().parent
STATIC = BASE / "static"
DOWNLOADS = Path(os.environ.get("MYOOSICK PLEYER_DOWNLOADS", BASE / "downloads")).resolve()
DOWNLOADS.mkdir(parents=True, exist_ok=True)

HAS_FFMPEG = shutil.which("ffmpeg") is not None
AUDIO_EXT = {".mp3", ".mp4", ".m4a", ".aac", ".ogg", ".opus", ".webm", ".flac", ".wav", ".mka"}

app = Flask(__name__, static_folder=None)


@app.errorhandler(Exception)
def _json_errors(e):
    """Always answer the UI with JSON (never an HTML error page)."""
    from werkzeug.exceptions import HTTPException
    if isinstance(e, HTTPException):
        return jsonify(error=e.description or e.name), e.code
    app.logger.exception("Unhandled error")
    return jsonify(error=clean_err(e)), 500



# --------------------------------------------------------------------------
# helpers
# --------------------------------------------------------------------------
def is_url(s: str) -> bool:
    return bool(re.match(r"^https?://", s or "", re.I))


def clean_err(e: Exception) -> str:
    msg = re.sub(r"\x1b\[[0-9;]*m", "", str(e))  # strip ANSI colours
    return msg.replace("ERROR: ", "").strip()[:300]


# --------------------------------------------------------------------------
# pages / status
# --------------------------------------------------------------------------
@app.get("/")
def index():
    return send_from_directory(STATIC, "index.html")


@app.get("/api/status")
def status():
    return jsonify(
        ok=True,
        yt_dlp=yt_dlp.version.__version__,
        ffmpeg=HAS_FFMPEG,
        downloads=str(DOWNLOADS),
    )


# --------------------------------------------------------------------------
# search  (text query -> top results, or paste a URL / playlist)
# --------------------------------------------------------------------------
@app.get("/api/search")
def search():
    q = request.args.get("q", "").strip()
    if not q:
        return jsonify(results=[])

    opts = {
        "quiet": True,
        "no_warnings": True,
        "extract_flat": True,
        "skip_download": True,
        "playlistend": 25,
    }
    target = q if is_url(q) else f"ytsearch15:{q}"
    try:
        with yt_dlp.YoutubeDL(opts) as ydl:
            info = ydl.extract_info(target, download=False)
    except Exception as e:  # noqa: BLE001
        return jsonify(error=clean_err(e)), 502

    entries = info.get("entries") if info else None
    if entries is None:
        entries = [info] if info else []

    results = []
    for e in entries:
        if not e:
            continue
        vid = e.get("id")
        url = e.get("url") or e.get("webpage_url")
        if not url or not is_url(url):
            url = f"https://www.youtube.com/watch?v={vid}" if vid else None
        if not url:
            continue
        thumbs = e.get("thumbnails") or []
        thumb = thumbs[-1].get("url") if thumbs else None
        if not thumb and vid:
            thumb = f"https://i.ytimg.com/vi/{vid}/mqdefault.jpg"
        results.append(
            {
                "id": vid,
                "title": e.get("title") or "Untitled",
                "uploader": e.get("uploader") or e.get("channel") or "",
                "duration": e.get("duration"),
                "thumbnail": thumb,
                "url": url,
            }
        )
    return jsonify(results=results)


# --------------------------------------------------------------------------
# discover: "radio" mode - songs related to the one that's playing.
# Uses YouTube's own Mix playlist for the current video (the same engine behind
# YouTube Music radio); falls back to a "songs like ..." search.
# --------------------------------------------------------------------------
_YT_ID = re.compile(r"(?:[?&]v=|youtu\.be/|/shorts/|/embed/)([A-Za-z0-9_-]{11})")
_NOISE = re.compile(
    r"[\(\[][^\)\]]*(official|lyric|video|audio|visuali[sz]er|remaster|4k|hd|mv)[^\)\]]*[\)\]]", re.I
)


def yt_id(url: str):
    m = _YT_ID.search(url or "")
    return m.group(1) if m else None


def norm_title(t: str) -> str:
    t = _NOISE.sub(" ", t or "").lower()
    return re.sub(r"[^a-z0-9]+", " ", t).strip()


def entry_to_result(e: dict):
    vid = e.get("id")
    url = e.get("url") or e.get("webpage_url")
    if not url or not is_url(url):
        url = f"https://www.youtube.com/watch?v={vid}" if vid else None
    if not url:
        return None
    thumbs = e.get("thumbnails") or []
    thumb = thumbs[-1].get("url") if thumbs else None
    if not thumb and vid:
        thumb = f"https://i.ytimg.com/vi/{vid}/mqdefault.jpg"
    return {
        "id": vid or yt_id(url),
        "title": e.get("title") or "Untitled",
        "uploader": e.get("uploader") or e.get("channel") or "",
        "duration": e.get("duration"),
        "thumbnail": thumb,
        "url": url,
    }


def _flat_entries(target: str, limit: int) -> list:
    opts = {
        "quiet": True,
        "no_warnings": True,
        "extract_flat": True,
        "skip_download": True,
        "playlistend": limit,
    }
    with yt_dlp.YoutubeDL(opts) as ydl:
        info = ydl.extract_info(target, download=False)
    return [e for e in ((info or {}).get("entries") or []) if e]


@app.get("/api/related")
def related():
    seed_url = request.args.get("u", "").strip()
    q = request.args.get("q", "").strip()
    exclude = {x for x in request.args.get("exclude", "").split(",") if x}
    try:
        n = max(1, min(int(request.args.get("n", "20")), 40))
    except ValueError:
        n = 20

    seed_vid = yt_id(seed_url)
    seed_norm = norm_title(q)
    seen_titles = set()
    results = []
    via = None
    errors = []

    def collect(entries):
        for e in entries:
            r = entry_to_result(e)
            if not r or not r["id"]:
                continue
            if r["id"] == seed_vid or r["id"] in exclude:
                continue
            d = r["duration"]
            if d is not None and not (60 <= d <= 720):  # skip clips, mixes, hour-long videos
                continue
            nt = norm_title(r["title"])
            if nt and (nt in seen_titles or (seed_norm and nt == seed_norm)):
                continue  # same song again under another upload
            seen_titles.add(nt)
            results.append(r)

    if seed_vid:
        try:
            collect(_flat_entries(
                f"https://www.youtube.com/watch?v={seed_vid}&list=RD{seed_vid}", 40))
            via = "mix"
        except Exception as e:  # noqa: BLE001
            errors.append(clean_err(e))

    if len(results) < 3 and (q or seed_vid):
        try:
            collect(_flat_entries(f"ytsearch30:songs like {q or seed_vid}", 30))
            via = via or "search"
        except Exception as e:  # noqa: BLE001
            errors.append(clean_err(e))

    if not results and errors:
        return jsonify(error=errors[-1]), 502
    return jsonify(results=results[:n], via=via)


# --------------------------------------------------------------------------
# streaming: resolve a direct audio URL with yt-dlp, then proxy it
# (proxying keeps it same-origin so the visualizer works and Range/seek works)
# --------------------------------------------------------------------------
_stream_cache: dict[str, dict] = {}
_stream_lock = threading.Lock()


def resolve_stream(src: str) -> dict:
    with _stream_lock:
        c = _stream_cache.get(src)
        if c and c["exp"] > time.time():
            return c
    opts = {
        "quiet": True,
        "no_warnings": True,
        "noplaylist": True,
        "format": "bestaudio[ext=m4a]/bestaudio/best",
    }
    with yt_dlp.YoutubeDL(opts) as ydl:
        info = ydl.extract_info(src, download=False)
    if not info:
        raise ValueError("yt-dlp returned no media information")
    if info.get("entries") is not None:
        info = next((e for e in info["entries"] if e), None)
    if not info or not info.get("url"):
        raise ValueError("yt-dlp did not return a playable media URL")
    c = {
        "url": info["url"],
        "headers": info.get("http_headers") or {},
        # Conservative cache lifetime; the upstream may expire earlier.
        "exp": time.time() + 10 * 60,
    }
    with _stream_lock:
        _stream_cache[src] = c
    return c


@app.get("/api/stream")
def stream():
    src = request.args.get("u", "")
    if not is_url(src):
        return jsonify(error="bad url"), 400

    upstream = None
    try:
        # Expiring media URLs are common. Refresh and retry once on likely expiry.
        for attempt in range(2):
            c = resolve_stream(src)
            headers = dict(c.get("headers") or {})
            # Preserve byte seeking and prevent compression from invalidating
            # Content-Length / Content-Range.
            headers["Accept-Encoding"] = "identity"
            if request.headers.get("Range"):
                headers["Range"] = request.headers["Range"]

            upstream = requests.get(
                c["url"], headers=headers, stream=True, timeout=(10, 30)
            )

            if upstream.status_code not in (403, 410) or attempt == 1:
                break

            upstream.close()
            upstream = None
            with _stream_lock:
                _stream_cache.pop(src, None)

        if upstream.status_code >= 400:
            code = upstream.status_code
            upstream.close()
            upstream = None
            app.logger.warning("Audio upstream returned HTTP %s for %s", code, src)
            return jsonify(error=f"upstream returned HTTP {code}"), 502

        out = {"Cache-Control": "no-store"}
        for key in (
            "Content-Type", "Content-Length", "Content-Range", "Accept-Ranges"
        ):
            if key in upstream.headers:
                out[key] = upstream.headers[key]
        out.setdefault("Content-Type", "application/octet-stream")

        response_status = upstream.status_code
        response = upstream

        def generate():
            try:
                for chunk in response.iter_content(chunk_size=64 * 1024):
                    if chunk:
                        yield chunk
            except requests.RequestException:
                app.logger.exception("Upstream audio stream interrupted")
                raise
            finally:
                response.close()

        return Response(
            stream_with_context(generate()),
            status=response_status,
            headers=out,
        )

    except Exception as e:  # noqa: BLE001
        if upstream is not None:
            upstream.close()
        app.logger.exception("Online audio streaming failed")
        return jsonify(error=clean_err(e)), 502


# --------------------------------------------------------------------------
# downloads (background jobs, polled by the UI)
# --------------------------------------------------------------------------
jobs: dict[str, dict] = {}


def run_download(job_id: str, src: str) -> None:
    job = jobs[job_id]

    def hook(d):
        if d["status"] == "downloading":
            total = d.get("total_bytes") or d.get("total_bytes_estimate") or 0
            if total:
                job["progress"] = round(d.get("downloaded_bytes", 0) / total * 100, 1)
            job["status"] = "downloading"
        elif d["status"] == "finished":
            job["progress"] = 100
            job["status"] = "converting" if HAS_FFMPEG else "finishing"

    opts = {
        "quiet": True,
        "no_warnings": True,
        "noplaylist": True,
        "outtmpl": str(DOWNLOADS / "%(title).150B.%(ext)s"),
        "windowsfilenames": True,
        "progress_hooks": [hook],
    }
    if HAS_FFMPEG:
        opts["format"] = "bestaudio/best"
        opts["postprocessors"] = [
            {"key": "FFmpegExtractAudio", "preferredcodec": "mp3", "preferredquality": "192"},
            {"key": "FFmpegMetadata"},
        ]
    else:
        # no ffmpeg: grab a browser-playable audio stream as-is
        opts["format"] = "bestaudio[ext=m4a]/bestaudio[ext=webm]/bestaudio"

    try:
        with yt_dlp.YoutubeDL(opts) as ydl:
            info = ydl.extract_info(src, download=True)
        path = None
        rd = (info or {}).get("requested_downloads") or []
        if rd:
            path = rd[0].get("filepath")
        job["file"] = Path(path).name if path else None
        job["status"] = "done"
    except Exception as e:  # noqa: BLE001
        job["status"] = "error"
        job["error"] = clean_err(e)


@app.post("/api/download")
def download():
    data = request.get_json(silent=True) or {}
    src = data.get("url", "")
    if not is_url(src):
        return jsonify(error="bad url"), 400
    job_id = uuid.uuid4().hex[:8]
    jobs[job_id] = {
        "status": "starting",
        "progress": 0,
        "title": data.get("title", ""),
        "file": None,
        "error": None,
    }
    threading.Thread(target=run_download, args=(job_id, src), daemon=True).start()
    return jsonify(job=job_id)


@app.get("/api/download/<job_id>")
def download_status(job_id):
    job = jobs.get(job_id)
    if not job:
        return jsonify(error="unknown job"), 404
    return jsonify(job)


# --------------------------------------------------------------------------
# library (everything in ./downloads)
# --------------------------------------------------------------------------
def safe_file(name: str) -> Path | None:
    p = (DOWNLOADS / name).resolve()
    if p.parent != DOWNLOADS or not p.is_file():
        return None
    return p


@app.get("/api/library")
def library():
    items = []
    for p in DOWNLOADS.iterdir():
        if p.is_file() and p.suffix.lower() in AUDIO_EXT:
            st = p.stat()
            items.append({"name": p.name, "size": st.st_size, "mtime": st.st_mtime})
    items.sort(key=lambda x: x["mtime"], reverse=True)
    return jsonify(items=items)


@app.get("/downloads/<path:name>")
def serve_download(name):
    if not safe_file(name):
        return jsonify(error="not found"), 404
    return send_from_directory(DOWNLOADS, name, conditional=True)


@app.delete("/api/library/<path:name>")
def delete_download(name):
    p = safe_file(name)
    if not p:
        return jsonify(error="not found"), 404
    p.unlink()
    return jsonify(ok=True)


@app.get("/static/<path:name>")
def static_files(name):
    return send_from_directory(STATIC, name)


if __name__ == "__main__":
    port = int(os.environ.get("PORT", 5000))
    print(f"\n  MYOOSICK PLEYER  ->  http://127.0.0.1:{port}")
    print(f"  downloads  ->  {DOWNLOADS}")
    print(f"  ffmpeg     ->  {'found (mp3 downloads)' if HAS_FFMPEG else 'not found (native audio downloads)'}\n")
    if not os.environ.get("NO_BROWSER"):
        threading.Timer(1.0, lambda: webbrowser.open(f"http://127.0.0.1:{port}")).start()
    app.run(host="127.0.0.1", port=port, threaded=True)
