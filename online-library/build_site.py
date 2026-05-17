"""
Generate the deployable static site for the ScoreReader online library.

Given a directory layout like::

    online-library/public/
    └── scores/
        ├── My_Score_A.mxl
        ├── My_Score_B.musicxml
        └── ...

this script rewrites two files in place (idempotent)::

    online-library/public/library.json   # metadata manifest (schema 1)
    online-library/public/index.html     # human-friendly index page

The output directory is then ready to be uploaded as a GitHub Pages
artifact and consumed by the ScoreReader Android app via::

    https://<user>.github.io/<repo>/library.json

The Android client treats `library.json`'s URL as the manifest URL and
resolves each item's `path` relative to the manifest's *directory*, so the
GitHub Pages sub-path (`/<repo>/`) just works as long as `path` starts with
`scores/...` (no leading slash).

Usage:
    python build_site.py                              # writes ./public
    python build_site.py --site-dir online-library/public
"""

from __future__ import annotations

import argparse
import datetime as _dt
import hashlib
import html
import json
from pathlib import Path

SUPPORTED_EXTENSIONS = {".mxl", ".musicxml", ".xml"}


def humanize_title(filename: str) -> str:
    """`Fur_Elise_Easy_Piano.mxl` -> `Fur Elise Easy Piano`."""
    stem = filename.rsplit(".", 1)[0]
    return stem.replace("_", " ").replace("  ", " ").strip()


def sha256_of(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def discover_scores(scores_dir: Path) -> list[dict]:
    entries: list[dict] = []
    for path in sorted(scores_dir.glob("*")):
        if not path.is_file():
            continue
        if path.suffix.lower() not in SUPPORTED_EXTENSIONS:
            continue
        size = path.stat().st_size
        entries.append(
            {
                "id": path.stem,
                "title": humanize_title(path.name),
                "filename": path.name,
                # Path is RELATIVE to library.json so the Android client can
                # resolve it correctly under any base URL (LAN host, GitHub
                # Pages sub-path, custom domain, ...).
                "path": f"scores/{path.name}",
                "format": path.suffix.lower().lstrip("."),
                "size_bytes": size,
                "sha256": sha256_of(path),
            }
        )
    return entries


def build_manifest(entries: list[dict]) -> dict:
    return {
        "schema": 1,
        "generated_at": _dt.datetime.utcnow()
        .replace(microsecond=0)
        .isoformat()
        + "Z",
        "source": {
            "kind": "github-pages",
            "description": "User-curated MusicXML library deployed via GitHub Pages.",
        },
        "count": len(entries),
        "total_size_bytes": sum(int(e.get("size_bytes", 0)) for e in entries),
        "items": entries,
    }


def render_index_html(manifest: dict) -> str:
    items = manifest.get("items", [])
    total = manifest.get("total_size_bytes", 0)
    generated = manifest.get("generated_at", "")
    rows: list[str] = []
    for it in items:
        size_kb = max(1, int(it.get("size_bytes", 0)) // 1024)
        href = html.escape(it["path"])
        title = html.escape(it["title"])
        filename = html.escape(it["filename"])
        rows.append(
            f"        <tr>"
            f"<td><a href='{href}'>{title}</a></td>"
            f"<td class='mono'>{filename}</td>"
            f"<td class='num'>{size_kb}&nbsp;KB</td>"
            f"</tr>"
        )
    rows_html = "\n".join(rows) if rows else (
        "        <tr><td colspan='3'><em>No scores uploaded yet — drop "
        "<code>.mxl</code> files into <code>online-library/public/scores/</code>"
        " on GitHub and the deploy workflow will pick them up automatically.</em>"
        "</td></tr>"
    )
    total_mb = total / (1024 * 1024) if total else 0
    return f"""<!doctype html>
<html lang='en'>
<head>
  <meta charset='utf-8'>
  <meta name='viewport' content='width=device-width, initial-scale=1'>
  <title>ScoreReader online library</title>
  <style>
    :root {{ color-scheme: light dark; }}
    body {{ font-family: system-ui, -apple-system, Segoe UI, Roboto, sans-serif;
           max-width: 960px; margin: 2rem auto; padding: 0 1rem; line-height: 1.5; }}
    h1 {{ margin-bottom: 0.25rem; }}
    .meta {{ color: #6a737d; margin-bottom: 1.5rem; }}
    .card {{ border: 1px solid rgba(127,127,127,.3); border-radius: 8px;
             padding: 1rem 1.25rem; margin: 1rem 0; }}
    code, .mono {{ font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
                   font-size: .9em; }}
    table {{ width: 100%; border-collapse: collapse; }}
    th, td {{ text-align: left; padding: .35rem .5rem;
              border-bottom: 1px solid rgba(127,127,127,.2); }}
    td.num {{ text-align: right; white-space: nowrap; }}
    a {{ color: #0969da; text-decoration: none; }}
    a:hover {{ text-decoration: underline; }}
  </style>
</head>
<body>
  <h1>ScoreReader online library</h1>
  <p class='meta'>
    {len(items)} score{'s' if len(items) != 1 else ''}
    &middot; {total_mb:.2f}&nbsp;MB total
    &middot; generated {html.escape(generated)}
  </p>

  <div class='card'>
    <strong>Point the ScoreReader Android app at:</strong><br>
    <code id='manifest-url'></code>
    <script>
      document.getElementById('manifest-url').textContent =
        new URL('library.json', window.location.href).href;
    </script>
  </div>

  <table>
    <thead>
      <tr><th>Title</th><th>File</th><th class='num'>Size</th></tr>
    </thead>
    <tbody>
{rows_html}
    </tbody>
  </table>

  <p class='meta' style='margin-top:2rem'>
    Manifest: <a href='library.json'>library.json</a>
  </p>
</body>
</html>
"""


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument(
        "--site-dir",
        default="online-library/public",
        help="Path to the deployable site directory (must contain scores/).",
    )
    args = p.parse_args()

    site_dir = Path(args.site_dir).resolve()
    scores_dir = site_dir / "scores"
    scores_dir.mkdir(parents=True, exist_ok=True)

    entries = discover_scores(scores_dir)
    manifest = build_manifest(entries)

    manifest_path = site_dir / "library.json"
    index_path = site_dir / "index.html"

    manifest_path.write_text(
        json.dumps(manifest, indent=2, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )
    index_path.write_text(render_index_html(manifest), encoding="utf-8")

    print(
        f"Wrote {manifest_path} ({manifest['count']} entries, "
        f"{manifest['total_size_bytes']} bytes)."
    )
    print(f"Wrote {index_path}.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
