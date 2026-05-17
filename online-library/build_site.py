"""
Generate the deployable static site for the ScoreReader online library.

Group-based layout, matching the Android app's "Online" tab
(group list -> scores list)::

    online-library/public/
    └── groups/
        ├── classical/
        │   ├── meta.json          # optional: id/title/description overrides
        │   └── scores/
        │       ├── ...mxl
        │       └── ...musicxml
        └── jazz/
            └── scores/
                └── ...mxl

After running this script the deployment tree looks like::

    online-library/public/
    ├── groups.json                # lists every group + URL to its library.json.
    │                              # Point the app at this.
    ├── index.html                 # human-friendly browser
    └── groups/<id>/library.json   # one per group

The Android app fetches `groups.json` (configured under Settings ->
Online library URL) and drills into the chosen group's `library.json`
to list scores.

Usage::

    python build_site.py
    python build_site.py --site-dir online-library/public
"""

from __future__ import annotations

import argparse
import datetime as _dt
import hashlib
import html
import json
import re
from pathlib import Path

SUPPORTED_EXTENSIONS = {".mxl", ".musicxml", ".xml"}


# --------------------------------------------------------------------------
# Helpers
# --------------------------------------------------------------------------

def humanize_title(filename: str) -> str:
    """`Fur_Elise_Easy_Piano.mxl` -> `Fur Elise Easy Piano`."""
    stem = filename.rsplit(".", 1)[0] if "." in filename else filename
    return stem.replace("_", " ").replace("-", " ").replace("  ", " ").strip()


def sha256_of(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def now_utc_iso() -> str:
    return _dt.datetime.utcnow().replace(microsecond=0).isoformat() + "Z"


def _load_meta(path: Path) -> dict:
    if not path.exists():
        return {}
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except Exception as e:
        print(f"  ! Could not parse {path}: {e}")
        return {}


_ID_SAFE = re.compile(r"[^A-Za-z0-9_.-]+")


def slugify(value: str) -> str:
    """Make a folder-name-safe id string."""
    return _ID_SAFE.sub("-", value.strip()).strip("-") or "group"


# --------------------------------------------------------------------------
# Per-group manifest (library.json)
# --------------------------------------------------------------------------

def discover_scores(scores_dir: Path) -> list[dict]:
    """Build the `items` list for a single group's `library.json`.

    `path` is RELATIVE to that library.json (so the Android client resolves
    it correctly under any sub-path).
    """
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
                "path": f"scores/{path.name}",
                "format": path.suffix.lower().lstrip("."),
                "size_bytes": size,
                "sha256": sha256_of(path),
            }
        )
    return entries


def build_group_manifest(scores_dir: Path) -> dict:
    entries = discover_scores(scores_dir)
    return {
        "schema": 1,
        "generated_at": now_utc_iso(),
        "count": len(entries),
        "total_size_bytes": sum(int(e.get("size_bytes", 0)) for e in entries),
        "items": entries,
    }


# --------------------------------------------------------------------------
# Top-level groups.json
# --------------------------------------------------------------------------

def emit_group(
    *,
    group_dir: Path,
    relative_dir: str,
    default_id: str,
    default_title: str,
    default_description: str | None,
) -> dict | None:
    """Write a group's library.json and return the groups.json entry.

    `relative_dir` is the group's directory path relative to the site root,
    e.g. ""              for the legacy top-level group,
         "groups/jazz"   for a nested group.
    Returns None if the group has no playable files (so we don't surface
    empty groups in the index).
    """
    scores_dir = group_dir / "scores"
    if not scores_dir.is_dir():
        return None
    manifest = build_group_manifest(scores_dir)
    if manifest["count"] == 0:
        return None

    library_path = group_dir / "library.json"
    library_path.write_text(
        json.dumps(manifest, indent=2, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )

    meta = _load_meta(group_dir / "meta.json")
    rel = relative_dir.strip("/")
    library_url = f"{rel}/library.json" if rel else "library.json"
    return {
        "id": meta.get("id", default_id),
        "title": meta.get("title", default_title),
        "description": meta.get("description", default_description),
        "url": library_url,
        "count": manifest["count"],
        "total_size_bytes": manifest["total_size_bytes"],
    }


def discover_groups(site_dir: Path) -> list[dict]:
    """Discover every group under `site_dir/groups/` and emit per-group manifests.

    Returns the list of group descriptors for `groups.json`.
    """
    groups: list[dict] = []

    groups_root = site_dir / "groups"
    if groups_root.is_dir():
        for sub in sorted(p for p in groups_root.iterdir() if p.is_dir()):
            descriptor = emit_group(
                group_dir=sub,
                relative_dir=f"groups/{sub.name}",
                default_id=slugify(sub.name),
                default_title=humanize_title(sub.name),
                default_description=None,
            )
            if descriptor is not None:
                groups.append(descriptor)

    return groups


def build_groups_index(groups: list[dict]) -> dict:
    return {
        "schema": 1,
        "generated_at": now_utc_iso(),
        "count": len(groups),
        "total_size_bytes": sum(int(g.get("total_size_bytes", 0)) for g in groups),
        "groups": groups,
    }


# --------------------------------------------------------------------------
# index.html
# --------------------------------------------------------------------------

def render_index_html(index: dict) -> str:
    groups = index.get("groups", [])
    total = index.get("total_size_bytes", 0)
    generated = index.get("generated_at", "")
    rows: list[str] = []
    for g in groups:
        size_mb = (g.get("total_size_bytes", 0) or 0) / (1024 * 1024)
        count = g.get("count", 0)
        href = html.escape(g["url"])
        title = html.escape(g["title"])
        desc = html.escape(g.get("description") or "")
        rows.append(
            "        <tr>"
            f"<td><a href='{href}'>{title}</a><br>"
            f"<small class='muted'>{desc}</small></td>"
            f"<td class='num'>{count}</td>"
            f"<td class='num'>{size_mb:.2f}&nbsp;MB</td>"
            "</tr>"
        )
    rows_html = "\n".join(rows) if rows else (
        "        <tr><td colspan='3'><em>No groups yet. Create a new group "
        "under <code>online-library/public/groups/&lt;name&gt;/scores/</code> "
        "and the deploy workflow will pick it up automatically.</em></td></tr>"
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
    th, td {{ text-align: left; padding: .5rem .5rem;
              border-bottom: 1px solid rgba(127,127,127,.2); vertical-align: top; }}
    td.num, th.num {{ text-align: right; white-space: nowrap; }}
    .muted {{ color: #6a737d; }}
    a {{ color: #0969da; text-decoration: none; }}
    a:hover {{ text-decoration: underline; }}
  </style>
</head>
<body>
  <h1>ScoreReader online library</h1>
  <p class='meta'>
    {len(groups)} group{'s' if len(groups) != 1 else ''}
    &middot; {total_mb:.2f}&nbsp;MB total
    &middot; generated {html.escape(generated)}
  </p>

  <div class='card'>
    <strong>Point the ScoreReader Android app at:</strong><br>
    <code id='manifest-url'></code>
    <script>
      document.getElementById('manifest-url').textContent =
        new URL('groups.json', window.location.href).href;
    </script>
  </div>

  <table>
    <thead>
      <tr><th>Group</th><th class='num'>Scores</th><th class='num'>Size</th></tr>
    </thead>
    <tbody>
{rows_html}
    </tbody>
  </table>

  <p class='meta' style='margin-top:2rem'>
    Manifests:
    <a href='groups.json'>groups.json</a>
  </p>
</body>
</html>
"""


# --------------------------------------------------------------------------
# Entry point
# --------------------------------------------------------------------------

def main() -> int:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument(
        "--site-dir",
        default="online-library/public",
        help="Path to the deployable site directory.",
    )
    args = p.parse_args()

    site_dir = Path(args.site_dir).resolve()
    site_dir.mkdir(parents=True, exist_ok=True)

    groups = discover_groups(site_dir)
    index = build_groups_index(groups)

    groups_path = site_dir / "groups.json"
    index_path = site_dir / "index.html"

    groups_path.write_text(
        json.dumps(index, indent=2, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )
    index_path.write_text(render_index_html(index), encoding="utf-8")

    print(
        f"Wrote {groups_path} ({index['count']} groups, "
        f"{index['total_size_bytes']} bytes)."
    )
    for g in groups:
        print(f"  - {g['id']}: {g['count']} scores @ {g['url']}")
    print(f"Wrote {index_path}.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
