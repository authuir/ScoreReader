package com.example.scorereader

import android.app.Application
import android.webkit.WebView
import androidx.appcompat.app.AppCompatDelegate

class ScoreReaderApp : Application() {

    lateinit var webViewPool: OsmdWebViewPool
        private set

    override fun onCreate() {
        super.onCreate()
        // Apply the user's dark-mode preference before any activity inflates
        // its layout so we don't briefly flash the wrong theme.
        val settings = AppSettings(this)
        AppCompatDelegate.setDefaultNightMode(
            if (settings.darkMode) AppCompatDelegate.MODE_NIGHT_YES
            else AppCompatDelegate.MODE_NIGHT_NO
        )

        // Enable WebView debugging in debug builds so chrome://inspect works.
        if (0 != applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
        webViewPool = OsmdWebViewPool(this, maxEntries = MAX_CACHED_VIEWERS)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // On serious memory pressure, drop cached scores (keep the current one
        // if pool has a notion of "current"; for simplicity we drop them all
        // and let the active activity re-load on demand).
        if (level >= TRIM_MEMORY_RUNNING_LOW) {
            webViewPool.evictAll()
        }
    }

    companion object {
        /**
         * Upper bound on cached viewers. Each entry can hold tens of MB of
         * SVG / canvas state for the score it has already rendered, so keep
         * this small on STB hardware.
         */
        const val MAX_CACHED_VIEWERS = 4
    }
}
