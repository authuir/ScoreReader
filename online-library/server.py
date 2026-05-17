"""
Tiny zero-dependency HTTP server that exposes the generated multi-group
library (see build_site.py) to the ScoreReader Android app.

Endpoints (served out of online-library/public/ by default):
    GET /                              -> small HTML index for sanity checks
    GET /groups.json                   -> top-level group index
    GET /groups/<id>/library.json      -> per-group manifest
    GET /groups/<id>/scores/<file>     -> raw MusicXML for that group

Run:
    python server.py                          # serve online-library/public on 0.0.0.0:8081
    python server.py --port 9000              # custom port
    python server.py --host 127.0.0.1         # only localhost
    python server.py --root some/other/dir    # serve a different site root

Then point ScoreReader's Settings -> Online library URL at:
    http://<this-machine-ip>:<port>/groups.json
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
DEFAULT_ROOT = HERE / "public"

# Populated in main() once we know which directory to serve.
SERVE_ROOT: Path = DEFAULT_ROOT
GROUPS_JSON: Path = DEFAULT_ROOT / "groups.json"

# Make sure browsers/Android download these as binaries.
mimetypes.add_type("application/vnd.recordare.musicxml", ".mxl")
mimetypes.add_type("application/xml", ".musicxml")
mimetypes.add_type("application/xml", ".xml")


class LibraryHandler(http.server.SimpleHTTPRequestHandler):
    # We serve from SERVE_ROOT, so library.json, groups.json and the
    # groups/<id>/ tree resolve naturally.
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=str(SERVE_ROOT), **kwargs)

    def end_headers(self) -> None:  # CORS for local debugging
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Cache-Control", "public, max-age=300")
        super().end_headers()

    def do_GET(self) -> None:  # noqa: N802 (stdlib signature)
        if self.path in ("", "/"):
            self._send_index()
            return
        # Allow groups.json (top-level index), any nested library.json under
        # groups/, and any raw score served from a scores/ subdirectory under
        # a group. Anything else is hidden so we don't accidentally leak
        # local source.
        rel = self.path.lstrip("/").split("?", 1)[0]
        allowed = (
            rel == "groups.json"
            or rel.endswith("/library.json")
            or "/scores/" in rel
        )
        if not allowed:
            self.send_error(404, "Not Found")
            return
        super().do_GET()

    def _send_index(self) -> None:
        body = [
            "<!doctype html><meta charset='utf-8'>",
            "<title>ScoreReader online library</title>",
            "<h1>ScoreReader online library</h1>",
        ]
        if GROUPS_JSON.exists():
            try:
                index = json.loads(GROUPS_JSON.read_text(encoding="utf-8"))
            except (OSError, json.JSONDecodeError) as exc:
                self.send_error(503, f"groups.json unreadable: {exc}")
                return
            groups = index.get("groups", [])
            body.append(
                f"<p>{len(groups)} group(s). "
                "<a href='/groups.json'>groups.json</a></p>"
            )
            body.append("<ul>")
            for g in groups:
                title = g.get("title") or g.get("id") or "(untitled)"
                url = g.get("url", "")
                count = g.get("count")
                desc = g.get("description") or ""
                body.append(
                    f"<li><a href='/{url}'>{title}</a> "
                    f"<small>({count} scores)</small><br>"
                    f"<small>{desc}</small></li>"
                )
            body.append("</ul>")
        else:
            body.append(
                "<p><b>No manifest found.</b> Drop scores under "
                "<code>online-library/public/groups/&lt;id&gt;/scores/</code> "
                "and run <code>python online-library/build_site.py</code>.</p>"
            )
        html = "\n".join(body).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(html)))
        self.end_headers()
        self.wfile.write(html)


def main() -> int:
    global SERVE_ROOT, GROUPS_JSON

    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--host", default="0.0.0.0")
    p.add_argument("--port", type=int, default=8081)
    p.add_argument(
        "--root",
        default=str(DEFAULT_ROOT),
        help="Directory to serve (default: online-library/public).",
    )
    args = p.parse_args()

    SERVE_ROOT = Path(args.root).resolve()
    GROUPS_JSON = SERVE_ROOT / "groups.json"

    if not SERVE_ROOT.exists():
        print(
            f"ERROR: serve root {SERVE_ROOT} does not exist. "
            "Run `python online-library/build_site.py` first.",
            file=sys.stderr,
        )
        return 2
    if not GROUPS_JSON.exists():
        print(
            "WARNING: groups.json not found under "
            f"{SERVE_ROOT}. Run `python online-library/build_site.py` after "
            "placing scores under public/groups/<id>/scores/.",
            file=sys.stderr,
        )

    with socketserver.ThreadingTCPServer(
        (args.host, args.port), LibraryHandler
    ) as httpd:
        host, port = httpd.server_address[:2]
        print(f"Serving {SERVE_ROOT} on http://{host}:{port}/")
        if GROUPS_JSON.exists():
            print(f"  Groups:    http://{host}:{port}/groups.json")
        print(f"  Index:     http://{host}:{port}/")
        try:
            httpd.serve_forever()
        except KeyboardInterrupt:
            print("\nStopped.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
