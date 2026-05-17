# ScoreReader

An Android MusicXML sheet-music reader designed for **Android 6.0 (API 23)** set-top boxes.
Two rendering engines ship in the same APK and can be toggled at runtime from
the home screen:

- **WebView + OSMD** (default) — [OpenSheetMusicDisplay](https://github.com/opensheetmusicdisplay/opensheetmusicdisplay)
  running inside Android's stock `WebView`.
- **Verovio (JNI)** — the [Verovio](https://www.verovio.org/) C++ engraver
  compiled into `libscorereader-verovio.so`, with SVG output rasterised by
  AndroidSVG and drawn straight onto an `ImageView`.

## Features

- Reads MusicXML files: `.xml`, `.musicxml`, and zipped `.mxl`
- Loads scores from local storage via the Storage Access Framework
- Opens MusicXML files dispatched from a file manager (`ACTION_VIEW` intent filter)
- Works offline — OSMD library is bundled into `app/src/main/assets/osmd/`
- D-pad / remote-control friendly toolbar for set-top boxes
- Zoom + page navigation hooked up to both buttons and remote keys
  (`+`/`−`, `PAGE_UP`/`PAGE_DOWN`, media rewind/fast-forward, zoom-in/zoom-out)

## Project layout

```
ScoreReader/
├── build.gradle.kts             // root build script
├── settings.gradle.kts
├── gradle.properties
├── gradle/wrapper/gradle-wrapper.properties
├── scripts/fetch-osmd.ps1       // re-downloads the OSMD bundle
├── vendor/
│   ├── verovio-6.1.0.zip        // upstream Verovio sources (checked in)
│   └── verovio-version-6.1.0/   // extracted by `Expand-Archive`, git-ignored
└── app/
    ├── build.gradle.kts
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml
        ├── cpp/                              // Verovio JNI engine
        │   ├── CMakeLists.txt
        │   └── verovio-jni.cpp
        ├── java/com/example/scorereader/
        │   ├── HomeActivity.kt
        │   ├── MainActivity.kt               // WebView + OSMD viewer
        │   ├── VerovioMainActivity.kt        // Native (JNI) viewer
        │   ├── VerovioNative.kt              // JNI bindings
        │   ├── VerovioResourceExtractor.kt   // unpacks verovio-data.zip
        │   └── JsBridge.kt
        ├── assets/
        │   ├── osmd/                          // ES5 OSMD bundle
        │   └── verovio-data.zip               // Bravura SVG glyphs + fonts
        └── res/                               // layouts, themes, strings, icon
```

## Building

### 1. Prerequisites

- Android Studio Hedgehog (or newer) / command-line Android SDK
- JDK 17 (bundled with Android Studio)
- Android SDK Platform 34, Build-Tools 34.x
- **Android NDK `27.1.12297006`** and **CMake `3.22.1`** — required for the
  Verovio native engine. Install via Android Studio's SDK Manager
  (SDK Tools → "Show Package Details" → tick the matching NDK / CMake
  versions). The Gradle script pins these exact versions; using a newer
  NDK works but will trigger an automatic re-download on first build.
- Android emulator or device with API 23+ (set-top boxes are typically
  `armeabi-v7a`; the fat APK also ships `arm64-v8a`)

### 2. Extract the vendored Verovio source

The Verovio source tarball is checked into `vendor/verovio-6.1.0.zip`
(~28 MB). It must be expanded **once** before the first native build —
the extracted tree (`vendor/verovio-version-6.1.0/`) is git-ignored:

```powershell
# From the repo root
Expand-Archive -Path vendor\verovio-6.1.0.zip -DestinationPath vendor -Force
```

The CMake build at `app/src/main/cpp/CMakeLists.txt` reads sources from
`vendor/verovio-version-6.1.0/{src,include,libmei,tools}` and synthesises
the `git_commit.h` stub that upstream normally generates from a bash
script. The pre-built glyph/font data ships as
`app/src/main/assets/verovio-data.zip` (1.6 MB) and is checked in directly
— there is no separate step to regenerate it.

### 3. Generate the Gradle wrapper (one-time)

If `gradlew` / `gradlew.bat` / `gradle-wrapper.jar` are missing (they are *not*
checked in by default for size reasons), generate them once:

```powershell
# In the project root
gradle wrapper --gradle-version 8.4
```

After that you can use `./gradlew` (Linux/macOS) or `.\gradlew.bat` (Windows).

Android Studio creates these files automatically the first time you open the
project, so this step is optional if you use the IDE.

### 4. Build & install

Full clean-room flow on Windows / PowerShell:

```powershell
# 0. one-time prerequisites
#    - Android SDK Platform 34, Build-Tools 34.x, NDK 27.1.12297006, CMake 3.22.1
#    - JDK 17 (Android Studio bundles one at:
#        "C:\Program Files (x86)\Android\openjdk\jdk-17.0.14")

# 1. unzip the vendored Verovio source
Expand-Archive -Path vendor\verovio-6.1.0.zip -DestinationPath vendor -Force

# 2. (first checkout only) make sure the Gradle wrapper exists
# gradle wrapper --gradle-version 8.4

# 3. point Gradle at JDK 17 and build the debug APK
$env:JAVA_HOME = "C:\Program Files (x86)\Android\openjdk\jdk-17.0.14"
.\gradlew.bat :app:assembleDebug --no-daemon

# 4. install to a connected device / set-top box
.\gradlew.bat :app:installDebug
```

The first build compiles ~285 Verovio C++ files for **both** `arm64-v8a` and
`armeabi-v7a`, which takes around 8–10 minutes on a modern laptop. Subsequent
incremental builds finish in under a minute. Output APK:
`app/build/outputs/apk/debug/app-debug.apk` (~36 MB fat APK).

> **Tip:** if the very first native build fails on a random `.cpp` file with
> no obvious error text (e.g. a transient ninja FAILED line on
> `oriscus.cpp`), wipe the per-ABI CMake state and try again — it has been
> observed when antivirus software locks freshly emitted `.o` files:
>
> ```powershell
> Remove-Item app\.cxx -Recurse -Force -ErrorAction SilentlyContinue
> .\gradlew.bat :app:assembleDebug --no-daemon
> ```

### 5. Switching engines at runtime

On the home screen the **Engine: WebView / Engine: Verovio (JNI)** button
cycles between the two viewers. The choice is persisted in
`SharedPreferences("score_reader_engine")` and applied to every score
opened from then on. The WebView engine is the default.

### 6. Refreshing the OSMD library

The OSMD bundle that ships under `app/src/main/assets/osmd/` has been
**transpiled down to ES5** so it can parse on Android 6.0's stock WebView
(Chromium 44). To re-fetch and re-transpile (requires Node.js):

```powershell
# Defaults to OSMD 1.8.7; pass -Version to pick another release.
powershell -ExecutionPolicy Bypass -File .\scripts\fetch-osmd.ps1 -Version 1.8.7

# If your target WebView already supports ES2020+, skip the slow transpile:
powershell -ExecutionPolicy Bypass -File .\scripts\fetch-osmd.ps1 -SkipTranspile
```

The Babel toolchain lives in `tools/transpile/` and is installed lazily the
first time the script runs.

## Usage

1. Launch the app — you will see a blank score area and a toolbar.
2. Tap **Open MusicXML** and pick a `.xml`, `.musicxml`, or `.mxl` file.
3. Use **Zoom +/−** and **Previous/Next** to navigate.
4. On a set-top box, you can also:
   - Press `+` / `−` on the remote to zoom
   - Press `PAGE_UP` / `PAGE_DOWN` or media rewind/forward to page
   - Use the D-pad to focus toolbar buttons

You can also open MusicXML files directly from a file manager — the app
registers an `ACTION_VIEW` intent filter for XML MIME types.

## Continuous integration & releases

Three GitHub Actions workflows live under `.github/workflows/`:

| Workflow | File | Trigger | What it does |
| -------- | ---- | ------- | ------------ |
| Android CI | `android-ci.yml` | push / PR to `main` | Builds `:app:assembleDebug` on Ubuntu (JDK 17 + NDK 27.1.12297006 + CMake 3.22.1) and uploads `app-debug.apk` as a workflow artifact. |
| Release | `android-release.yml` | tag `v*` (or manual dispatch) | Builds **both** debug and release APKs, attaches them to a new GitHub Release named after the tag. |
| Pages | `online-library-pages.yml` | push to `main` touching `online-library/public/**` (or manual) | Runs `online-library/build_site.py` and deploys `online-library/public/` to GitHub Pages. |

### Cutting a release

```pwsh
# bump versionName/versionCode in app/build.gradle.kts first
git tag v1.2.0
git push origin v1.2.0
```

The release workflow produces two artifacts:

- `ScoreReader-v1.2.0-release.apk` — `assembleRelease` signed with the
  project's **debug key** so the APK is directly installable on TV/STB
  devices without exposing real signing material. This is intentional;
  swap in a proper `signingConfig` in `app/build.gradle.kts` when shipping
  to a store.
- `ScoreReader-v1.2.0-debug.apk` — `assembleDebug` for troubleshooting.

### Hosting your own online library on GitHub Pages

The `online-library/public/` folder is deployed as a static site that the
Android app's "Online" tab consumes via a two-level browser
(**groups → scores**):

```
online-library/public/
├── groups.json              # auto-regenerated; the app fetches this first
├── groups/
│   ├── classical/
│   │   ├── meta.json        # optional: id/title/description overrides
│   │   └── scores/
│   │       └── *.mxl
│   └── jazz/
│       └── scores/
│           └── *.mxl
└── index.html               # auto-regenerated
```

To publish your own scores:

1. Enable Pages on the repo: **Settings → Pages → Build and deployment →
   Source: GitHub Actions**.
2. Create one folder per group under
   `online-library/public/groups/<group-id>/scores/` and drop your `.mxl`
   files in. An optional
   `online-library/public/groups/<group-id>/meta.json` lets you override
   the auto-generated `id` / `title` / `description`.
3. Push (or upload via the GitHub web UI — **Add file → Upload files**
   while inside the target folder). The Pages workflow runs automatically
   and republishes:
   - `https://<user>.github.io/<repo>/groups.json` ← point the app here
   - `https://<user>.github.io/<repo>/groups/<id>/library.json`
   - `https://<user>.github.io/<repo>/groups/<id>/scores/...`
4. In the app, open **Settings → Online library URL** and paste the
   `groups.json` URL.

From the Online tab the user can also tap the **+** button to add an
extra group locally by pasting a `library.json` URL (handy for testing or
mixing in libraries published from other repos / hosts). Long-press a
local card to remove it. Server-provided groups (from `groups.json`)
can't be removed in-app on purpose — change the source.

To preview the manifest locally before pushing:

```pwsh
python online-library\build_site.py --site-dir online-library\public
# Then open online-library/public/index.html in a browser.
```

## How it works

- `MainActivity` hosts a single `WebView` and serves
  `app/src/main/assets/osmd/index.html` through
  [`WebViewAssetLoader`](https://developer.android.com/reference/androidx/webkit/WebViewAssetLoader)
  on `https://appassets.androidplatform.net/`. This avoids the deprecated /
  insecure `file://` scheme and works on API 23+.
- When the user picks a file, Kotlin reads the bytes through `ContentResolver`,
  unzips `.mxl` containers in-process, base64-encodes the resulting MusicXML
  string and hands it to JavaScript via `evaluateJavascript`.
- `viewer.js` decodes the payload and calls `osmd.load(xml)` /
  `osmd.render()` from the OpenSheetMusicDisplay API.

## Notes for Android 6.0 set-top boxes

- The stock WebView on Android 6.0 is Chromium 44, which **cannot parse**
  modern JS syntax like optional chaining (`?.`) used by OSMD's published
  bundle. ScoreReader works around this by shipping the OSMD bundle
  pre-transpiled to ES5; see [Refreshing the OSMD library](#4-refreshing-the-osmd-library).
- Many set-top box firmwares strip the Storage Access Framework (no
  DocumentsUI). When `ACTION_OPEN_DOCUMENT` and `ACTION_GET_CONTENT` both
  fail, the app falls back to a **built-in scanner** that walks
  `/sdcard`, `/storage`, `/mnt` (up to depth 6) and presents matching
  `.xml` / `.musicxml` / `.mxl` files in a dialog.
- Hardware acceleration is enabled in the manifest; if you observe rendering
  glitches on low-end SoCs you can disable it on the `WebView` only.

## License

This scaffolding is provided as-is for application development.
OpenSheetMusicDisplay is licensed under the BSD-3-Clause license — see its
upstream repository for details. Verovio is licensed under LGPL-3.0; see
`vendor/verovio-6.1.0.zip` → `COPYING.LESSER` for the full text.
