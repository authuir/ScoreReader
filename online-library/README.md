# ScoreReader online library

A small Python helper that mirrors the public-domain MusicXML library at
[`musetrainer/library`](https://github.com/musetrainer/library) into this
repo and exposes it via a tiny HTTP server, so the ScoreReader Android app
can browse and stream scores from your LAN/dev box.

## Layout

```
online-library/
├── download_library.py   # fetches *.mxl from GitHub + rebuilds library.json
├── server.py             # zero-dependency HTTP server
├── library.json          # generated metadata (commit-friendly)
└── scores/               # *.mxl content (git-ignored)
```

## Step 1 — download + generate metadata

Requires Python 3.10+.

```pwsh
cd online-library
python download_library.py
```

The script:
- Calls the GitHub Contents API to enumerate `scores/`.
- Downloads each `.mxl` into `online-library/scores/` (skips files that already
  match the upstream `size`).
- Writes `library.json` with per-item `{id, title, filename, path, format,
  size_bytes, sha256, git_sha, source_url, html_url}` plus a top-level
  `{schema, generated_at, source, count, total_size_bytes}`.

Useful flags:

| Flag           | Meaning                                                     |
| -------------- | ----------------------------------------------------------- |
| `--force`      | Re-download even if a local file already exists.            |
| `--no-fetch`   | Skip GitHub; just regenerate `library.json` from disk.      |

Set `GITHUB_TOKEN` in the environment to raise the unauthenticated rate
limit from 60 to 5000 req/h:

```pwsh
$env:GITHUB_TOKEN = "ghp_xxx..."
python download_library.py
```

## Step 2 — serve the library

```pwsh
python server.py                   # listens on 0.0.0.0:8081
python server.py --port 9000
python server.py --host 127.0.0.1
```

Endpoints exposed:

- `GET /`                    HTML index for sanity checks.
- `GET /library.json`        the metadata manifest.
- `GET /scores/<name>.mxl`   the raw MusicXML (zipped) file.

CORS is wide-open (`Access-Control-Allow-Origin: *`) so this is suitable for
LAN development only.

## Step 3 — wire the app

Point the upcoming "Online" tab at:

```
http://<this-machine-ip>:8081/library.json
```

The Android client should fetch `library.json`, list `items[].title`, and
when the user picks an entry, GET `items[].path` (relative URL) from the
same host.

## Notes

- `scores/*.mxl` is git-ignored; the upstream repo is the source of truth.
  Re-running `download_library.py` brings a fresh checkout back to the same
  state.
- `library.json` *is* committed so the Android app and CI can know what to
  expect without hitting GitHub.
