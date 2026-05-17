"""
Download all MusicXML scores from the musetrainer/library GitHub repo into
`./scores/` and emit `library.json` with metadata so a tiny HTTP server can
expose the catalog to the ScoreReader app.

Usage:
    python download_library.py            # download missing files
    python download_library.py --force    # re-download everything
    python download_library.py --no-fetch # only regenerate library.json from
                                          # what is already on disk

The script uses only the Python standard library so it can run on a fresh
machine without `pip install`.
"""

from __future__ import annotations

import argparse
import datetime as _dt
import hashlib
import json
import os
import sys
import urllib.error
import urllib.request
from pathlib import Path
from typing import Iterable

REPO_OWNER = "musetrainer"
REPO_NAME = "library"
REPO_REF = "master"
REPO_DIR = "scores"

API_LIST_URL = (
    f"https://api.github.com/repos/{REPO_OWNER}/{REPO_NAME}/contents/{REPO_DIR}"
    f"?ref={REPO_REF}"
)

HERE = Path(__file__).resolve().parent
LOCAL_SCORES = HERE / "scores"
LOCAL_JSON = HERE / "library.json"

USER_AGENT = "ScoreReader-Library-Downloader/1.0"


def _http_get(url: str, *, accept: str | None = None) -> bytes:
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    if accept:
        req.add_header("Accept", accept)
    # GitHub honours `GITHUB_TOKEN` to raise the unauthenticated rate limit
    # from 60 to 5000 req/h; helpful when re-running often.
    token = os.environ.get("GITHUB_TOKEN")
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    with urllib.request.urlopen(req, timeout=60) as resp:  # nosec - public API
        return resp.read()


def list_remote_files() -> list[dict]:
    raw = _http_get(API_LIST_URL, accept="application/vnd.github+json")
    items = json.loads(raw.decode("utf-8"))
    files = [it for it in items if it.get("type") == "file"]
    files.sort(key=lambda it: it["name"].lower())
    return files


def humanize_title(filename: str) -> str:
    """`Fur_Elise_Easy_Piano.mxl` -> `Fur Elise Easy Piano`."""
    stem = filename.rsplit(".", 1)[0]
    title = stem.replace("_", " ").replace("  ", " ").strip()
    # Normalise common artefact: `1st_Movement` already looks fine; `op._28`
    # becomes `op. 28`. Nothing fancier — we leave the rest alone.
    return title


def sha256_of(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def download_missing(files: Iterable[dict], *, force: bool) -> list[Path]:
    LOCAL_SCORES.mkdir(parents=True, exist_ok=True)
    downloaded: list[Path] = []
    for entry in files:
        name = entry["name"]
        url = entry["download_url"]
        dest = LOCAL_SCORES / name
        if dest.exists() and not force:
            # Trust on-disk content unless size disagrees with manifest.
            if dest.stat().st_size == entry.get("size"):
                continue
        print(f"  fetching {name} ({entry.get('size', '?')} bytes)")
        try:
            data = _http_get(url)
        except urllib.error.HTTPError as e:
            print(f"  ! HTTP {e.code} for {name}: {e.reason}", file=sys.stderr)
            continue
        dest.write_bytes(data)
        downloaded.append(dest)
    return downloaded


def build_metadata(files: list[dict]) -> dict:
    entries: list[dict] = []
    total_bytes = 0
    for entry in files:
        name = entry["name"]
        dest = LOCAL_SCORES / name
        if not dest.exists():
            continue
        size = dest.stat().st_size
        total_bytes += size
        entries.append(
            {
                "id": name.rsplit(".", 1)[0],
                "title": humanize_title(name),
                "filename": name,
                "path": f"/scores/{name}",
                "format": name.rsplit(".", 1)[-1].lower(),
                "size_bytes": size,
                "sha256": sha256_of(dest),
                "git_sha": entry.get("sha"),
                "source_url": entry.get("download_url"),
                "html_url": entry.get("html_url"),
            }
        )
    return {
        "schema": 1,
        "generated_at": _dt.datetime.utcnow().replace(microsecond=0).isoformat()
        + "Z",
        "source": {
            "owner": REPO_OWNER,
            "repo": REPO_NAME,
            "ref": REPO_REF,
            "directory": REPO_DIR,
            "html_url": f"https://github.com/{REPO_OWNER}/{REPO_NAME}/tree/"
            f"{REPO_REF}/{REPO_DIR}",
        },
        "count": len(entries),
        "total_size_bytes": total_bytes,
        "items": entries,
    }


def regenerate_from_disk() -> dict:
    files = []
    for path in sorted(LOCAL_SCORES.glob("*")):
        if not path.is_file():
            continue
        files.append(
            {
                "name": path.name,
                "size": path.stat().st_size,
                "sha": None,
                "download_url": None,
                "html_url": None,
            }
        )
    return build_metadata(files)


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument(
        "--force", action="store_true", help="re-download even if file exists"
    )
    p.add_argument(
        "--no-fetch",
        action="store_true",
        help="skip GitHub call; regenerate library.json from local files only",
    )
    args = p.parse_args()

    if args.no_fetch:
        manifest = regenerate_from_disk()
        LOCAL_JSON.write_text(json.dumps(manifest, indent=2), encoding="utf-8")
        print(
            f"Wrote {LOCAL_JSON} with {manifest['count']} entries "
            f"({manifest['total_size_bytes']} bytes)."
        )
        return 0

    print(f"Listing {API_LIST_URL} ...")
    files = list_remote_files()
    print(f"Found {len(files)} remote files.")

    download_missing(files, force=args.force)

    manifest = build_metadata(files)
    LOCAL_JSON.write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    print(
        f"Wrote {LOCAL_JSON} with {manifest['count']} entries "
        f"({manifest['total_size_bytes']} bytes)."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
