package com.example.scorereader

import android.content.Context
import android.content.SharedPreferences

/**
 * Centralised access to user preferences shared across activities.
 *
 * All keys live in a single `SharedPreferences` file so the settings
 * screen, the home screen, and the two viewers see a consistent view.
 *
 *  - [engine]            which viewer to launch ("webview" / "verovio")
 *  - [webViewZoom]       initial OSMD zoom factor for the WebView viewer
 *  - [verovioScale]      Verovio's `scale` option (percentage, integer)
 *  - [darkMode]          true == dark theme, false == light theme
 */
class AppSettings(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var engine: String
        get() = prefs.getString(KEY_ENGINE, ENGINE_WEBVIEW) ?: ENGINE_WEBVIEW
        set(value) { prefs.edit().putString(KEY_ENGINE, value).apply() }

    val preferVerovio: Boolean get() = engine == ENGINE_VEROVIO

    var webViewZoom: Float
        get() = prefs.getFloat(KEY_WEBVIEW_ZOOM, DEFAULT_WEBVIEW_ZOOM)
        set(value) {
            prefs.edit()
                .putFloat(KEY_WEBVIEW_ZOOM, value.coerceIn(WEBVIEW_ZOOM_MIN, WEBVIEW_ZOOM_MAX))
                .apply()
        }

    var verovioScale: Int
        get() = prefs.getInt(KEY_VEROVIO_SCALE, DEFAULT_VEROVIO_SCALE)
        set(value) {
            prefs.edit()
                .putInt(KEY_VEROVIO_SCALE, value.coerceIn(VEROVIO_SCALE_MIN, VEROVIO_SCALE_MAX))
                .apply()
        }

    var darkMode: Boolean
        get() = prefs.getBoolean(KEY_DARK_MODE, DEFAULT_DARK_MODE)
        set(value) { prefs.edit().putBoolean(KEY_DARK_MODE, value).apply() }

    var onlineLibraryUrl: String
        get() = prefs.getString(KEY_ONLINE_URL, DEFAULT_ONLINE_URL) ?: DEFAULT_ONLINE_URL
        set(value) {
            val trimmed = value.trim()
            prefs.edit().putString(KEY_ONLINE_URL, trimmed.ifEmpty { DEFAULT_ONLINE_URL }).apply()
        }

    companion object {
        const val PREFS = "score_reader_engine" // kept for backwards-compat with the old toggle

        const val KEY_ENGINE = "engine"
        const val KEY_WEBVIEW_ZOOM = "webview_zoom"
        const val KEY_VEROVIO_SCALE = "verovio_scale"
        const val KEY_DARK_MODE = "dark_mode"
        const val KEY_ONLINE_URL = "online_library_url"

        const val ENGINE_WEBVIEW = "webview"
        const val ENGINE_VEROVIO = "verovio"

        // OSMD's `osmd.zoom` is a multiplier; 0.6 matches viewer.js' historic default.
        const val DEFAULT_WEBVIEW_ZOOM = 0.6f
        const val WEBVIEW_ZOOM_MIN = 0.25f
        const val WEBVIEW_ZOOM_MAX = 2.5f

        // Verovio `scale` is a percentage; 40 matches buildPageOptions' historic default.
        const val DEFAULT_VEROVIO_SCALE = 40
        const val VEROVIO_SCALE_MIN = 20
        const val VEROVIO_SCALE_MAX = 80

        const val DEFAULT_DARK_MODE = true

        // Default points at the bundled `online-library/server.py` running on
        // the developer's LAN. Override in Settings → Online library URL.
        const val DEFAULT_ONLINE_URL = "http://192.168.101.198:8081/groups.json"
    }
}
