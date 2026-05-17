# ScoreReader online library

Static, group-based MusicXML library that the ScoreReader Android app's
**Online** tab consumes. The directory you maintain (`public/groups/...`)
is the source of truth; `build_site.py` derives every manifest from it,
and `server.py` serves the result locally for development.

## Layout

```
online-library/
├── build_site.py        # scans public/groups/<id>/scores/ and writes manifests
├── server.py            # zero-dependency HTTP server (default root: public/)
├── README.md            # this file
└── public/              # everything served to the app + GitHub Pages
    └── groups/
        └── <group-id>/
            ├── meta.json    # optional: { id, title, description }
            └── scores/
                └── *.mxl    # raw MusicXML (.mxl / .musicxml / .xml)
```

After `build_site.py` runs, the deployable tree adds the generated files
(all git-ignored):

```
public/
├── groups.json                       # top-level index of all groups
├── index.html                        # human-friendly browser
└── groups/<id>/library.json          # per-group score list
```

## Workflow

Requires Python 3.10+. No third-party packages.

### 1. Add a group

```pwsh
cd c:\GitRoot\ScoreReader
New-Item -ItemType Directory -Path online-library\public\groups\classical\scores -Force
Copy-Item path\to\Canon_in_D.mxl online-library\public\groups\classical\scores\
```

Optionally place a `meta.json` next to (i.e. one level above) `scores/`
to override defaults:

```json
{
  "id": "classical",
  "title": "Classical Piano",
  "description": "Bach, Beethoven, Chopin, ..."
}
```

Without `meta.json` the folder name is used for both `id` (slugified) and
`title` (humanized).

### 2. Generate manifests

```pwsh
python online-library\build_site.py
```

Outputs `public/groups.json`, `public/index.html`, and one
`public/groups/<id>/library.json` per group. Re-run whenever you add or
remove scores.

### 3. Serve locally

```pwsh
python online-library\server.py                  # listens on 0.0.0.0:8081
python online-library\server.py --port 9000
python online-library\server.py --host 127.0.0.1
python online-library\server.py --root some/other/site
```

Endpoints:

| URL                                   | Content                       |
| ------------------------------------- | ----------------------------- |
| `GET /`                               | HTML index for sanity checks. |
| `GET /groups.json`                    | top-level group index.        |
| `GET /groups/<id>/library.json`       | per-group score manifest.     |
| `GET /groups/<id>/scores/<file>.mxl`  | raw MusicXML.                 |

CORS is wide-open (`Access-Control-Allow-Origin: *`); LAN dev only.

### 4. Point the app at it

In ScoreReader: **Settings → Online library URL**

```
http://<this-machine-ip>:8081/groups.json
```

The **Online** tab fetches `groups.json`, lists every group, and when
you tap one it fetches that group's `library.json` to list scores.
You can also tap the **+** button in the group list to subscribe to any
other `library.json` URL ad-hoc; those are saved locally on the device
under `SharedPreferences("score_reader_online_groups")`.

## GitHub Pages deploy

`.github/workflows/online-library-pages.yml` runs `build_site.py` and
publishes `online-library/public/` to Pages on every push to `main`.
After enabling Pages (Settings → Pages → Source = GitHub Actions), point
the app at:

```
https://<your-user>.github.io/<your-repo>/groups.json
```

Only the raw `.mxl` files under `public/groups/<id>/scores/` are
committed; `groups.json`, `library.json` and `index.html` are regenerated
by the workflow and excluded by `.gitignore`.
