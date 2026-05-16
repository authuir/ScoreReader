# ScoreReader

An Android MusicXML sheet-music reader designed for **Android 6.0 (API 23)** set-top boxes.
Rendering is performed by [OpenSheetMusicDisplay](https://github.com/opensheetmusicdisplay/opensheetmusicdisplay)
running inside a `WebView`, while file I/O and UI live in native Kotlin code.

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
└── app/
    ├── build.gradle.kts
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/com/example/scorereader/
        │   ├── MainActivity.kt
        │   └── JsBridge.kt
        ├── assets/osmd/
        │   ├── index.html
        │   ├── viewer.js
        │   └── opensheetmusicdisplay.min.js   // fetched on setup
        └── res/                              // layouts, themes, strings, icon
```

## Building

### 1. Prerequisites

- Android Studio Hedgehog (or newer) / command-line Android SDK
- JDK 17 (bundled with Android Studio)
- Android SDK Platform 34, Build-Tools 34.x
- Android emulator or device with API 23+

### 2. Generate the Gradle wrapper (one-time)

If `gradlew` / `gradlew.bat` / `gradle-wrapper.jar` are missing (they are *not*
checked in by default for size reasons), generate them once:

```powershell
# In the project root
gradle wrapper --gradle-version 8.4
```

After that you can use `./gradlew` (Linux/macOS) or `.\gradlew.bat` (Windows).

Android Studio creates these files automatically the first time you open the
project, so this step is optional if you use the IDE.

### 3. Build & install

```powershell
.\gradlew.bat installDebug
```

or simply press **Run** in Android Studio.

### 4. Refreshing the OSMD library

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
upstream repository for details.
