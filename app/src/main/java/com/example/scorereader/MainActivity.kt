package com.example.scorereader

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.webkit.WebView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.scorereader.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Fullscreen MusicXML viewer. The WebView itself is owned by [OsmdWebViewPool];
 * this activity is just a thin host that hands a URI to the pool and reacts to
 * its callbacks.
 */
class MainActivity : AppCompatActivity(), OsmdWebViewPool.ViewerCallbacks {

    private lateinit var binding: ActivityMainBinding
    private lateinit var recents: RecentsRepository
    private lateinit var pool: OsmdWebViewPool

    private var currentBinding: OsmdWebViewPool.Binding? = null
    private var currentUri: Uri? = null
    private var currentDisplayName: String? = null
    private var pendingDeliveryOnReady: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        recents = RecentsRepository(this)
        pool = (application as ScoreReaderApp).webViewPool

        binding.toolbar.visibility = View.GONE
        applyFullscreenFlags()

        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Same activity, new URI — release the previous binding first.
        releaseBinding()
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        pool.setCurrentCallbacks(this)
    }

    override fun onPause() {
        super.onPause()
        pool.setCurrentCallbacks(null)
    }

    override fun onDestroy() {
        releaseBinding()
        super.onDestroy()
    }

    private fun applyFullscreenFlags() {
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
    }

    private fun handleIntent(intent: Intent?) {
        val uri = intent?.data ?: return
        val name = intent.getStringExtra(EXTRA_DISPLAY_NAME) ?: uri.lastPathSegment
        currentUri = uri
        currentDisplayName = name
        openInPool(uri, name)
    }

    /** Ask the pool for a WebView; only do IO + send to JS on a cache miss. */
    private fun openInPool(uri: Uri, displayName: String?) {
        val uriKey = uri.toString()
        val bind = pool.bind(uriKey, binding.webContainer, this)
        currentBinding = bind
        pool.setCurrentCallbacks(this)

        if (bind.isCachedHit) {
            // Already rendered. Nothing to do.
            Log.i(TAG, "Cache HIT for $uri")
            hideLoading()
            return
        }

        Log.i(TAG, "Cache MISS for $uri (viewerReady=${bind.isViewerReady})")
        showLoading(getString(R.string.msg_loading_reading))

        // Read MusicXML on a background thread; deliver to JS once viewer is ready.
        lifecycleScope.launch {
            val overall = System.currentTimeMillis()
            val result = runCatching {
                withContext(Dispatchers.IO) { prepareScorePayload(uri) }
            }
            result.onSuccess { payload ->
                val nativeMs = System.currentTimeMillis() - overall
                Log.i(
                    TAG,
                    "timing/native total=${nativeMs}ms  " +
                        "read=${payload.readMs}ms  unzip=${payload.unzipMs}ms  " +
                        "base64=${payload.base64Ms}ms  bytesXml=${payload.xmlBytes}"
                )
                setLoadingText(getString(R.string.msg_loading_parsing))
                deliverWhenReady(bind, payload.b64)
            }.onFailure {
                hideLoading()
                Log.e(TAG, "Failed to load score", it)
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.err_load_failed, it.message ?: it.javaClass.simpleName),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun deliverWhenReady(bind: OsmdWebViewPool.Binding, b64: String) {
        val send: () -> Unit = {
            // If the user navigated away between IO and viewer-ready, skip.
            if (currentBinding === bind) {
                bind.webView.evaluateJavascript(
                    "window.osmdViewer.loadBase64('$b64');", null
                )
            }
        }
        if (bind.isViewerReady) send() else bind.onViewerReady(send)
    }

    private fun releaseBinding() {
        val b = currentBinding ?: return
        currentBinding = null
        pool.unbind(b.webView)
    }

    // ---------------------------------------------------------------------
    // Pool -> Activity callbacks
    // ---------------------------------------------------------------------

    override fun onStage(stage: String) {
        val text = when (stage) {
            "parsing" -> getString(R.string.msg_loading_parsing)
            "rendering" -> getString(R.string.msg_loading_rendering)
            else -> stage
        }
        setLoadingText(text)
    }

    override fun onRendered(title: String) {
        Log.d(TAG, "rendered: $title")
        hideLoading()
        val uri = currentUri
        if (uri != null) {
            recents.add(uri, currentDisplayName, persistable = uri.scheme == "content")
        }
    }

    override fun onError(message: String) {
        Log.e(TAG, "viewer error: $message")
        hideLoading()
    }

    // ---------------------------------------------------------------------
    // MusicXML IO + decoding (Kotlin side)
    // ---------------------------------------------------------------------

    private data class ScorePayload(
        val b64: String,
        val xmlBytes: Int,
        val readMs: Long,
        val unzipMs: Long,
        val base64Ms: Long
    )

    private fun prepareScorePayload(uri: Uri): ScorePayload {
        val tRead0 = System.currentTimeMillis()
        val bytes = when (uri.scheme) {
            "file" -> File(uri.path!!).readBytes()
            else -> contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: error("Unable to open URI: $uri")
        }
        val readMs = System.currentTimeMillis() - tRead0

        val tUnzip0 = System.currentTimeMillis()
        val (xmlBytes, fromMxl) = if (isZip(bytes)) {
            extractMusicXmlBytesFromMxl(bytes) to true
        } else {
            bytes to false
        }
        val unzipMs = if (fromMxl) System.currentTimeMillis() - tUnzip0 else 0L

        val tB64 = System.currentTimeMillis()
        val b64 = Base64.encodeToString(xmlBytes, Base64.NO_WRAP)
        val base64Ms = System.currentTimeMillis() - tB64

        return ScorePayload(
            b64 = b64,
            xmlBytes = xmlBytes.size,
            readMs = readMs,
            unzipMs = unzipMs,
            base64Ms = base64Ms
        )
    }

    private fun isZip(bytes: ByteArray): Boolean =
        bytes.size >= 4 &&
            bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte() &&
            bytes[2] == 0x03.toByte() && bytes[3] == 0x04.toByte()

    private fun extractMusicXmlBytesFromMxl(bytes: ByteArray): ByteArray {
        ZipInputStream(bytes.inputStream()).use { zis ->
            var entry = zis.nextEntry
            var fallback: ByteArray? = null
            while (entry != null) {
                val name = entry.name
                if (!entry.isDirectory && (name.endsWith(".xml", true) ||
                            name.endsWith(".musicxml", true))
                ) {
                    val out = ByteArrayOutputStream()
                    zis.copyTo(out)
                    val content = out.toByteArray()
                    if (!name.equals("META-INF/container.xml", true)) {
                        return content
                    }
                    fallback = content
                }
                entry = zis.nextEntry
            }
            return fallback ?: error("No MusicXML entry found inside .mxl")
        }
    }

    // ---------------------------------------------------------------------
    // Loading overlay helpers
    // ---------------------------------------------------------------------

    private fun showLoading(text: String) = runOnUiThread {
        binding.loadingText.text = text
        binding.loadingOverlay.visibility = View.VISIBLE
    }

    private fun setLoadingText(text: String) = runOnUiThread {
        binding.loadingText.text = text
    }

    private fun hideLoading() = runOnUiThread {
        binding.loadingOverlay.visibility = View.GONE
    }

    // ---------------------------------------------------------------------
    // JS evaluation shortcuts
    // ---------------------------------------------------------------------

    private fun evalJs(script: String) {
        currentBinding?.webView?.evaluateJavascript(script, null)
    }

    // ---------------------------------------------------------------------
    // Settings dialog (MENU key)
    // ---------------------------------------------------------------------

    private fun showSettingsDialog() {
        val wv = currentBinding?.webView ?: return
        wv.evaluateJavascript(
            "(window.osmdViewer && window.osmdViewer.getSettings) ? window.osmdViewer.getSettings() : 'null';"
        ) { raw ->
            val json = unquoteJsString(raw)
            val current = runCatching { org.json.JSONObject(json) }.getOrNull()
            if (current == null) {
                Toast.makeText(this, "Viewer not ready yet.", Toast.LENGTH_SHORT).show()
                return@evaluateJavascript
            }
            val labels = arrayOf(
                "Canvas backend (faster)",
                "Compact layout",
                "Show title",
                "Show subtitle",
                "Show composer",
                "Show lyricist",
                "Show part names",
                "Show credits",
                "Show measure numbers"
            )
            val keys = arrayOf(
                "backend",
                "compact",
                "drawTitle",
                "drawSubtitle",
                "drawComposer",
                "drawLyricist",
                "drawPartNames",
                "drawCredits",
                "drawMeasureNumbers"
            )
            val checked = BooleanArray(labels.size) { i ->
                if (keys[i] == "backend") current.optString("backend") == "canvas"
                else current.optBoolean(keys[i], false)
            }
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Render settings")
                .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                    checked[which] = isChecked
                }
                .setPositiveButton("Apply") { _, _ ->
                    val out = org.json.JSONObject()
                    for (i in keys.indices) {
                        if (keys[i] == "backend") {
                            out.put("backend", if (checked[i]) "canvas" else "svg")
                        } else {
                            out.put(keys[i], checked[i])
                        }
                    }
                    val js = "window.osmdViewer.applySettings(${org.json.JSONObject.quote(out.toString())});"
                    showLoading(getString(R.string.msg_loading_rendering))
                    evalJs(js)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun unquoteJsString(raw: String?): String {
        if (raw.isNullOrEmpty() || raw == "null") return "{}"
        return runCatching {
            val arr = org.json.JSONArray("[$raw]")
            arr.getString(0)
        }.getOrDefault(raw)
    }

    // ---------------------------------------------------------------------
    // Key handling
    // ---------------------------------------------------------------------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    evalJs("window.osmdViewer.pageBy(-1);")
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    evalJs("window.osmdViewer.pageBy(1);")
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_MEDIA_REWIND -> {
                evalJs("window.osmdViewer.pageBy(-1);"); true
            }
            KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                evalJs("window.osmdViewer.pageBy(1);"); true
            }
            KeyEvent.KEYCODE_PLUS, KeyEvent.KEYCODE_EQUALS, KeyEvent.KEYCODE_ZOOM_IN -> {
                evalJs("window.osmdViewer.zoomBy(1.1);"); true
            }
            KeyEvent.KEYCODE_MINUS, KeyEvent.KEYCODE_ZOOM_OUT -> {
                evalJs("window.osmdViewer.zoomBy(1/1.1);"); true
            }
            KeyEvent.KEYCODE_MENU -> {
                showSettingsDialog(); true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    companion object {
        private const val TAG = "ScoreReader"
        const val EXTRA_DISPLAY_NAME = "display_name"
    }
}
