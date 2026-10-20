"""Starts the unmodified MYOOSICK PLEYER Flask server inside the Android app."""
import os
import sys
import threading


def start(app_dir, downloads_dir, port):
    # app.py reads this (odd-looking, but it's the exact name in app.py)
    os.environ["MYOOSICK PLEYER_DOWNLOADS"] = downloads_dir
    os.environ["NO_BROWSER"] = "1"
    os.environ.setdefault("XDG_CACHE_HOME", os.path.join(app_dir, ".cache"))
    os.makedirs(downloads_dir, exist_ok=True)
    os.chdir(app_dir)
    if app_dir not in sys.path:
        sys.path.insert(0, app_dir)

    import app as player  # the original app.py, untouched

    def serve():
        player.app.run(host="127.0.0.1", port=int(port), threaded=True, use_reloader=False)

    threading.Thread(target=serve, name="flask", daemon=True).start()
