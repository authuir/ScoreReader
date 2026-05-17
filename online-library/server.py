"""
Tiny zero-dependency HTTP server that exposes the downloaded MusicXML
library to the ScoreReader Android app.

Endpoints:
    GET /                        -> very small HTML index for sanity checks
    GET /library.json            -> the metadata manifest
    GET /scores/<filename>.mxl   -> raw MusicXML (compressed) file

Run:
    python server.py                     # listen on 0.0.0.0:8081
    python server.py --port 9000         # custom port
    python server.py --host 127.0.0.1    # only localhost

Then point ScoreReader's "Online" tab at:
    http://<this-machine-ip>:<port>/library.json
"""

from __future__ import annotations

import argparse
import http.server
import json
import mimetypes
import socketserver
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
LOCAL_SCORES = HERE / "scores"
LOCAL_JSON = HERE / "library.json"

# Make sure browsers/Android download these as binaries.
mimetypes.add_type("application/vnd.recordare.musicxml", ".mxl")
mimetypes.add_type("application/xml", ".musicxml")
mimetypes.add_type("application/xml", ".xml")


class LibraryHandler(http.server.SimpleHTTPRequestHandler):
    # We serve from `HERE`, so library.json and scores/ resolve naturally.
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=str(HERE), **kwargs)

    def end_headers(self) -> None:  # CORS for local debugging
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Cache-Control", "public, max-age=300")
        super().end_headers()

    def do_GET(self) -> None:  # noqa: N802 (stdlib signature)
        if self.path in ("", "/"):
            self._send_index()
            return
        # Hide everything that isn't the manifest or a score file.
        rel = self.path.lstrip("/").split("?", 1)[0]
        if rel != "library.json" and not rel.startswith("scores/"):
            self.send_error(404, "Not Found")
            return
        super().do_GET()

    def _send_index(self) -> None:
        try:
            manifest = json.loads(LOCAL_JSON.read_text(encoding="utf-8"))
        except FileNotFoundError:
            self.send_error(503, "library.json missing; run download_library.py")
            return
        items = manifest.get("items", [])
        body = [
            "<!doctype html><meta charset='utf-8'>",
            "<title>ScoreReader online library</title>",
            "<h1>ScoreReader online library</h1>",
            f"<p>{len(items)} scores. ",
            "<a href='/library.json'>library.json</a></p>",
            "<ul>",
        ]
        for it in items:
            body.append(
                f"<li><a href='{it['path']}'>{it['title']}</a> "
                f"<small>({it['size_bytes']} bytes)</small></li>"
            )
        body.append("</ul>")
        html = "\n".join(body).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(html)))
        self.end_headers()
        self.wfile.write(html)


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--host", default="0.0.0.0")
    p.add_argument("--port", type=int, default=8081)
    args = p.parse_args()

    if not LOCAL_JSON.exists():
        print(
            "WARNING: library.json not found. Run `python download_library.py` "
            "first to populate the catalog.",
            file=sys.stderr,
        )

    with socketserver.ThreadingTCPServer(
        (args.host, args.port), LibraryHandler
    ) as httpd:
        host, port = httpd.server_address[:2]
        print(f"Serving {HERE} on http://{host}:{port}/")
        print(f"  Manifest:  http://{host}:{port}/library.json")
        print(f"  Index:     http://{host}:{port}/")
        try:
            httpd.serve_forever()
        except KeyboardInterrupt:
            print("\nStopped.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
