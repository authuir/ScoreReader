package com.example.scorereader

import android.annotation.SuppressLint
import android.content.Context
import android.content.MutableContextWrapper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.webkit.WebViewAssetLoader

/**
 * LRU pool of fully-initialized OSMD WebView instances, keyed by score URI.
 *
 * Reopening a URI that's still in the cache is essentially free: we just
 * re-parent the existing WebView into the new activity's container. The OSMD
 * model and the rendered SVG / canvas remain in memory.
 *
 * Trade-offs:
 *  - Each cached entry can hold tens of MB of rendered output; tune
 *    [maxEntries] for the target hardware.
 *  - All WebViews share the same WebView data dir, so localStorage settings
 *    are shared across instances. However a settings change only re-renders
 *    the *currently visible* WebView; older cached entries keep their old
 *    rendering until they're evicted or the user navigates to one of them
 *    and we explicitly trigger a re-render (not done by default).
 */
class OsmdWebViewPool(
    private val appContext: Context,
    private val maxEntries: Int = 4
) {

    /** Public callbacks for the currently bound viewer activity. */
    interface ViewerCallbacks {
        fun onStage(stage: String)
        fun onRendered(title: String)
        fun onError(message: String)
    }

    /** Returned by [bind]. */
    class Binding internal constructor(
        val webView: WebView,
        /** True if the URI was already cached AND already finished rendering. */
        val isCachedHit: Boolean,
        private val entry: Entry
    ) {
        val isViewerReady: Boolean get() = entry.viewerReady
        val isRendered: Boolean get() = entry.rendered

        /** Run [block] when the OSMD HTML page has finished loading. Fires once. */
        fun onViewerReady(block: () -> Unit) {
            if (entry.viewerReady) block() else entry.onViewerReady = block
        }
    }

    internal class Entry(
        val webView: WebView,
        val contextWrapper: MutableContextWrapper,
        var uri: String? = null,
        var viewerReady: Boolean = false,
        var rendered: Boolean = false,
        var onViewerReady: (() -> Unit)? = null
    )

    // Most-recently-used at end of list, LRU candidate at index 0.
    private val entries = ArrayList<Entry>()
    private var assetLoader: WebViewAssetLoader? = null

    private var currentEntry: Entry? = null
    private var currentCallbacks: ViewerCallbacks? = null

    /** Hook a viewer activity to receive JS-side callbacks for the current entry. */
    fun setCurrentCallbacks(cb: ViewerCallbacks?) {
        currentCallbacks = cb
    }

    /**
     * Attach a WebView for [uri] to [container] (single child). The WebView is
     * either pulled from cache or freshly created. The caller must:
     *  1. If `isCachedHit` is true, simply hide the loading UI.
     *  2. Else wait for `onViewerReady { ... }` and then call
     *     `webView.evaluateJavascript("window.osmdViewer.loadBase64('...')", ...)`.
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun bind(uri: String, container: FrameLayout, activityContext: Context): Binding {
        ensureAssetLoader()

        // 1) Cache hit
        val hit = entries.find { it.uri == uri }
        if (hit != null) {
            promoteToMru(hit)
            attach(hit, container, activityContext)
            currentEntry = hit
            return Binding(hit.webView, isCachedHit = hit.rendered, hit)
        }

        // 2) Cache miss — recycle an idle slot, grow the pool, or evict the LRU
        val idle = entries.find { it.uri == null }
        val entry = when {
            idle != null -> idle
            entries.size < maxEntries -> createEntry().also { entries.add(it) }
            else -> evictLeastRecentlyUsed()
        }
        // Mark this entry for the new URI; reset render bookkeeping.
        entry.uri = uri
        entry.rendered = false
        // If the WebView was previously bound to a different URI, drop that
        // score from JS so we don't accidentally show it.
        if (entry.viewerReady) {
            entry.webView.evaluateJavascript("window.osmdViewer && window.osmdViewer.clear && window.osmdViewer.clear();", null)
        }
        promoteToMru(entry)
        attach(entry, container, activityContext)
        currentEntry = entry
        return Binding(entry.webView, isCachedHit = false, entry)
    }

    /** Detach the WebView from its current parent so the activity can finish. */
    fun unbind(webView: WebView) {
        (webView.parent as? ViewGroup)?.removeView(webView)
        if (currentEntry?.webView === webView) {
            currentEntry = null
            currentCallbacks = null
        }
    }

    /** Force-evict a specific entry (e.g. file is no longer reachable). */
    fun evict(uri: String) {
        val it = entries.find { e -> e.uri == uri } ?: return
        entries.remove(it)
        destroyEntry(it)
    }

    /** Evict everything (called on low-memory). */
    fun evictAll() {
        val active = currentEntry
        val iter = entries.iterator()
        while (iter.hasNext()) {
            val e = iter.next()
            if (e === active) continue
            iter.remove()
            destroyEntry(e)
        }
    }

    fun size(): Int = entries.size

    // -----------------------------------------------------------------------
    // Internals
    // -----------------------------------------------------------------------

    private fun promoteToMru(entry: Entry) {
        entries.remove(entry)
        entries.add(entry)
    }

    private fun evictLeastRecentlyUsed(): Entry {
        // Never evict the currently bound entry.
        val active = currentEntry
        var idx = 0
        while (idx < entries.size && entries[idx] === active) idx++
        if (idx >= entries.size) {
            // Fallback: shouldn't happen because maxEntries >= 1
            return createEntry().also { entries.add(it) }
        }
        val victim = entries.removeAt(idx)
        Log.i(TAG, "Evicting LRU entry for ${victim.uri}")
        // Detach + reset the entry but keep the WebView alive so we can reuse it.
        (victim.webView.parent as? ViewGroup)?.removeView(victim.webView)
        victim.uri = null
        victim.rendered = false
        victim.onViewerReady = null
        return victim
    }

    private fun destroyEntry(entry: Entry) {
        (entry.webView.parent as? ViewGroup)?.removeView(entry.webView)
        try {
            entry.webView.stopLoading()
            entry.webView.removeJavascriptInterface("Android")
            entry.webView.destroy()
        } catch (t: Throwable) {
            Log.w(TAG, "destroyEntry: ${t.message}")
        }
        entry.uri = null
        entry.rendered = false
    }

    private fun attach(entry: Entry, container: FrameLayout, activityContext: Context) {
        entry.contextWrapper.baseContext = activityContext
        (entry.webView.parent as? ViewGroup)?.removeView(entry.webView)
        container.removeAllViews()
        container.addView(
            entry.webView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        entry.webView.requestFocus()
    }

    private fun ensureAssetLoader() {
        if (assetLoader == null) {
            assetLoader = WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(appContext))
                .build()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createEntry(): Entry {
        val wrapper = MutableContextWrapper(appContext)
        val wv = WebView(wrapper)
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.settings.allowFileAccess = false
        wv.settings.allowContentAccess = false
        wv.settings.builtInZoomControls = false
        wv.settings.displayZoomControls = false
        wv.settings.useWideViewPort = true
        wv.settings.loadWithOverviewMode = true
        wv.isHorizontalScrollBarEnabled = false
        wv.overScrollMode = View.OVER_SCROLL_NEVER
        wv.isFocusable = true
        wv.isFocusableInTouchMode = true

        val entry = Entry(wv, wrapper)

        wv.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: android.webkit.WebResourceRequest
            ) = assetLoader?.shouldInterceptRequest(request.url)

            override fun onReceivedError(
                view: WebView?,
                request: android.webkit.WebResourceRequest?,
                error: android.webkit.WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                Log.e(TAG, "WV err ${request?.url} -> ${error?.errorCode} ${error?.description}")
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                entry.viewerReady = true
                val cb = entry.onViewerReady
                entry.onViewerReady = null
                cb?.invoke()
            }
        }

        wv.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                Log.println(
                    when (msg.messageLevel()) {
                        ConsoleMessage.MessageLevel.ERROR -> Log.ERROR
                        ConsoleMessage.MessageLevel.WARNING -> Log.WARN
                        else -> Log.INFO
                    },
                    "ScoreReader/WV",
                    "${msg.sourceId()}:${msg.lineNumber()} ${msg.message()}"
                )
                return true
            }
        }

        wv.addJavascriptInterface(PoolBridge(this, entry), "Android")
        wv.loadUrl("https://appassets.androidplatform.net/assets/osmd/index.html")
        return entry
    }

    // JS callbacks -> route to current activity, but only if the firing entry
    // is the currently bound one (background WebViews may still emit events).
    internal fun reportStage(entry: Entry, stage: String) {
        if (currentEntry === entry) currentCallbacks?.onStage(stage)
    }
    internal fun reportRendered(entry: Entry, title: String) {
        entry.rendered = true
        if (currentEntry === entry) currentCallbacks?.onRendered(title)
    }
    internal fun reportError(entry: Entry, message: String) {
        if (currentEntry === entry) currentCallbacks?.onError(message)
    }

    /** Per-entry JS bridge so concurrent renders don't get confused. */
    private class PoolBridge(
        private val pool: OsmdWebViewPool,
        private val entry: Entry
    ) {
        @JavascriptInterface fun onReady() {}
        @JavascriptInterface fun onStage(stage: String) { pool.reportStage(entry, stage) }
        @JavascriptInterface fun onRendered(title: String) { pool.reportRendered(entry, title) }
        @JavascriptInterface fun onError(message: String) { pool.reportError(entry, message) }
    }

    companion object {
        private const val TAG = "ScoreReader/Pool"
    }
}
