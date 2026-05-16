package com.example.scorereader

import android.util.Log
import android.webkit.JavascriptInterface

/**
 * Minimal JS -> native bridge. The viewer page calls these methods to surface
 * status / errors back to the host app. Only @JavascriptInterface-annotated
 * methods are exposed to JavaScript.
 */
class JsBridge(private val activity: MainActivity) {

    @JavascriptInterface
    fun onReady() {
        Log.d(TAG, "OSMD viewer reported ready")
    }

    @JavascriptInterface
    fun onStage(stage: String) {
        Log.d(TAG, "stage: $stage")
        activity.onJsStage(stage)
    }

    @JavascriptInterface
    fun onRendered(title: String) {
        Log.d(TAG, "OSMD finished rendering: $title")
        activity.hideLoading()
    }

    @JavascriptInterface
    fun onError(message: String) {
        Log.e(TAG, "OSMD error: $message")
        activity.hideLoading()
    }

    companion object {
        private const val TAG = "ScoreReader/JS"
    }
}
