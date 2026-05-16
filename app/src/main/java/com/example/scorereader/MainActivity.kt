package com.example.scorereader

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.util.Base64
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebViewAssetLoader
import com.example.scorereader.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var assetLoader: WebViewAssetLoader
    private var viewerReady = false
    private var pendingXmlB64: String? = null
    private var hasRestoredLast = false

    // SAF picker (ACTION_OPEN_DOCUMENT) — also requests *persistable* read permission
    // so we can re-open the same content URI after the process is killed.
    private val openDocumentLauncher = registerForActivityResult(
        PersistableOpenDocument()
    ) { uri: Uri? -> if (uri != null) handlePickedUri(uri, persistable = true) }

    // Fallback picker (ACTION_GET_CONTENT) — supported by simpler file managers,
    // but the URIs it returns are NOT persistable, so we don't try to remember them.
    private val getContentLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? -> if (uri != null) handlePickedUri(uri, persistable = false) }

    // Runtime storage permission (needed for the built-in browser on API 23..28)
    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) showBuiltinFileBrowser()
        else Toast.makeText(
            this,
            "Storage permission denied; cannot browse local files.",
            Toast.LENGTH_LONG
        ).show()
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        with(binding.webView) {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.builtInZoomControls = false
            settings.displayZoomControls = false
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true

            // We never want a horizontal scrollbar; left/right is reserved for paging.
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER

            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: android.webkit.WebResourceRequest
                ) = assetLoader.shouldInterceptRequest(request.url)

                override fun onReceivedError(
                    view: WebView?,
                    request: android.webkit.WebResourceRequest?,
                    error: android.webkit.WebResourceError?
                ) {
                    super.onReceivedError(view, request, error)
                    Log.e(
                        TAG,
                        "WebView resource error: ${request?.url} -> " +
                                "${error?.errorCode} ${error?.description}"
                    )
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    viewerReady = true
                    val pending = pendingXmlB64
                    if (pending != null) {
                        pendingXmlB64 = null
                        sendXmlToWebView(pending)
                    } else {
                        applyDefaultZoom()
                        tryRestoreLastFile()
                    }
                }
            }

            // Surface console.log / console.error to logcat so we can diagnose JS issues
            webChromeClient = object : WebChromeClient() {
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

            addJavascriptInterface(JsBridge(this@MainActivity), "Android")
            // Load via WebViewAssetLoader's virtual https origin (avoids file:// quirks)
            loadUrl("https://appassets.androidplatform.net/assets/osmd/index.html")
        }

        binding.btnOpen.setOnClickListener { pickFile() }
        binding.btnZoomIn.setOnClickListener { evalJs("window.osmdViewer.zoomBy(1.1);") }
        binding.btnZoomOut.setOnClickListener { evalJs("window.osmdViewer.zoomBy(1/1.1);") }
        binding.btnPrev.setOnClickListener { evalJs("window.osmdViewer.pageBy(-1);") }
        binding.btnNext.setOnClickListener { evalJs("window.osmdViewer.pageBy(1);") }

        // Honor VIEW intent (file manager / share)
        handleViewIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleViewIntent(intent)
    }

    private fun handleViewIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) {
            intent.data?.let { uri ->
                hasRestoredLast = true // explicit user intent overrides auto-restore
                handlePickedUri(uri, persistable = uri.scheme == "content")
            }
        }
    }

    // ---------------------------------------------------------------------
    // Persistence of last-opened file
    // ---------------------------------------------------------------------

    private fun prefs() =
        getSharedPreferences("score_reader", Context.MODE_PRIVATE)

    private fun rememberLastUri(uri: Uri, persistable: Boolean) {
        prefs().edit()
            .putString(KEY_LAST_URI, uri.toString())
            .putBoolean(KEY_LAST_PERSISTABLE, persistable)
            .apply()
    }

    private fun forgetLastUri() {
        prefs().edit().remove(KEY_LAST_URI).remove(KEY_LAST_PERSISTABLE).apply()
    }

    private fun handlePickedUri(uri: Uri, persistable: Boolean) {
        if (persistable && uri.scheme == "content") {
            // Best-effort: keep read permission across reboots.
            try {
                contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: SecurityException) {
                Log.w(TAG, "Could not persist read permission for $uri: ${e.message}")
            }
        }
        rememberLastUri(uri, persistable)
        hasRestoredLast = true
        loadMusicXmlFromUri(uri)
    }

    private fun tryRestoreLastFile() {
        if (hasRestoredLast) return
        hasRestoredLast = true
        val stored = prefs().getString(KEY_LAST_URI, null) ?: return
        val uri = runCatching { Uri.parse(stored) }.getOrNull() ?: return
        // Don't auto-restore if the saved URI is clearly unreachable.
        val reachable = when (uri.scheme) {
            "file" -> uri.path?.let { File(it).exists() && File(it).canRead() } == true
            "content" -> runCatching {
                contentResolver.openInputStream(uri)?.close(); true
            }.getOrDefault(false)
            else -> false
        }
        if (!reachable) {
            Log.i(TAG, "Saved score is no longer reachable, clearing: $uri")
            forgetLastUri()
            return
        }
        Log.i(TAG, "Auto-restoring last score: $uri")
        loadMusicXmlFromUri(uri)
    }

    // ---------------------------------------------------------------------
    // Loading overlay helpers (callable from JsBridge via runOnUiThread)
    // ---------------------------------------------------------------------

    internal fun showLoading(text: String) = runOnUiThread {
        binding.loadingText.text = text
        binding.loadingOverlay.visibility = View.VISIBLE
    }

    internal fun setLoadingText(text: String) = runOnUiThread {
        binding.loadingText.text = text
    }

    internal fun hideLoading() = runOnUiThread {
        binding.loadingOverlay.visibility = View.GONE
    }

    internal fun onJsStage(stage: String) {
        val text = when (stage) {
            "parsing" -> getString(R.string.msg_loading_parsing)
            "rendering" -> getString(R.string.msg_loading_rendering)
            else -> stage
        }
        setLoadingText(text)
    }

    private fun applyDefaultZoom() {
        // Override the JS default with a smaller, more set-top-box friendly zoom.
        evalJs("if (window.osmdViewer) window.osmdViewer.setZoom($DEFAULT_ZOOM);")
    }

    // ---------------------------------------------------------------------
    // File picking: try SAF -> GET_CONTENT -> built-in browser
    // ---------------------------------------------------------------------

    private val mimeCandidates = arrayOf(
        "application/xml",
        "text/xml",
        "application/vnd.recordare.musicxml",
        "application/vnd.recordare.musicxml+xml",
        "application/octet-stream",
        "*/*"
    )

    private fun pickFile() {
        // 1) ACTION_OPEN_DOCUMENT (SAF). Many STB firmwares lack DocumentsUI.
        try {
            openDocumentLauncher.launch(mimeCandidates)
            return
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "ACTION_OPEN_DOCUMENT not supported: ${e.message}")
        } catch (e: Exception) {
            Log.w(TAG, "ACTION_OPEN_DOCUMENT failed: ${e.message}")
        }

        // 2) ACTION_GET_CONTENT — older but more widely supported
        try {
            getContentLauncher.launch("*/*")
            return
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "ACTION_GET_CONTENT not supported: ${e.message}")
        } catch (e: Exception) {
            Log.w(TAG, "ACTION_GET_CONTENT failed: ${e.message}")
        }

        // 3) Built-in fallback browser, scans common storage locations
        ensureStoragePermissionThen { showBuiltinFileBrowser() }
    }

    private fun ensureStoragePermissionThen(block: () -> Unit) {
        if (Build.VERSION.SDK_INT in Build.VERSION_CODES.M..32) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
            if (granted) block() else storagePermissionLauncher.launch(
                Manifest.permission.READ_EXTERNAL_STORAGE
            )
        } else {
            block()
        }
    }

    private fun showBuiltinFileBrowser() {
        lifecycleScope.launch {
            binding.statusText.text = "Scanning storage…"
            val files = withContext(Dispatchers.IO) { scanForMusicXml() }
            if (files.isEmpty()) {
                Toast.makeText(
                    this@MainActivity,
                    "No MusicXML (.xml / .musicxml / .mxl) files found under storage roots.",
                    Toast.LENGTH_LONG
                ).show()
                binding.statusText.text = getString(R.string.msg_pick_file)
                return@launch
            }
            val labels = files.map { it.absolutePath }.toTypedArray()
            AlertDialog.Builder(this@MainActivity)
                .setTitle("Pick a MusicXML file")
                .setItems(labels) { _, which ->
                    loadMusicXmlFromUri(Uri.fromFile(files[which]))
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            binding.statusText.text = "${files.size} file(s) found"
        }
    }

    private fun scanForMusicXml(): List<File> {
        val roots = linkedSetOf<File>().apply {
            Environment.getExternalStorageDirectory()?.let { add(it) }
            add(File("/sdcard"))
            add(File("/storage"))
            add(File("/mnt"))
        }
        val results = mutableListOf<File>()
        val seen = HashSet<String>()
        val exts = setOf("xml", "musicxml", "mxl")
        for (root in roots) {
            if (!root.exists() || !root.canRead()) continue
            try {
                root.walkTopDown()
                    .onEnter { dir ->
                        val name = dir.name
                        !name.startsWith(".") &&
                            name != "Android" &&
                            name != "data" &&
                            name != "obb"
                    }
                    .maxDepth(6)
                    .filter { it.isFile && it.extension.lowercase() in exts }
                    .forEach { f ->
                        if (seen.add(f.canonicalPath)) results.add(f)
                    }
            } catch (e: Exception) {
                Log.w(TAG, "Scan failed for ${root.absolutePath}: ${e.message}")
            }
            if (results.size >= 500) break
        }
        return results.sortedBy { it.name.lowercase() }
    }

    // ---------------------------------------------------------------------
    // MusicXML loading
    // ---------------------------------------------------------------------

    private fun loadMusicXmlFromUri(uri: Uri) {
        binding.statusText.text = getString(R.string.msg_loading)
        showLoading(getString(R.string.msg_loading_reading))
        lifecycleScope.launch {
            val overall = System.currentTimeMillis()
            val result = runCatching {
                withContext(Dispatchers.IO) { prepareScorePayload(uri) }
            }
            result.onSuccess { payload ->
                binding.statusText.text = uri.lastPathSegment ?: uri.toString()
                val nativeMs = System.currentTimeMillis() - overall
                Log.i(
                    TAG,
                    "timing/native total=${nativeMs}ms  " +
                        "read=${payload.readMs}ms  unzip=${payload.unzipMs}ms  " +
                        "decodeUtf8=${payload.decodeMs}ms  base64=${payload.base64Ms}ms  " +
                        "bytesXml=${payload.xmlBytes}  base64Len=${payload.b64.length}"
                )
                setLoadingText(getString(R.string.msg_loading_parsing))
                sendXmlToWebView(payload.b64)
                // Loading overlay is hidden by JsBridge.onRendered (or onError).
            }.onFailure {
                hideLoading()
                Log.e(TAG, "Failed to load score", it)
                binding.statusText.text =
                    getString(R.string.err_load_failed, it.message ?: it.javaClass.simpleName)
                Toast.makeText(this@MainActivity, binding.statusText.text, Toast.LENGTH_LONG).show()
            }
        }
    }

    private data class ScorePayload(
        val b64: String,
        val xmlBytes: Int,
        val readMs: Long,
        val unzipMs: Long,
        val decodeMs: Long,
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

        val tDec0 = System.currentTimeMillis()
        // Skip the redundant UTF-8 conversion if we already have valid bytes; we
        // base64-encode the raw bytes directly and let the JS side decode UTF-8.
        // (This skips one full-buffer copy/charset scan on the slow STB CPU.)
        val decodeMs = System.currentTimeMillis() - tDec0

        val tB64 = System.currentTimeMillis()
        val b64 = Base64.encodeToString(xmlBytes, Base64.NO_WRAP)
        val base64Ms = System.currentTimeMillis() - tB64

        return ScorePayload(
            b64 = b64,
            xmlBytes = xmlBytes.size,
            readMs = readMs,
            unzipMs = unzipMs,
            decodeMs = decodeMs,
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

    private fun sendXmlToWebView(b64: String) {
        if (!viewerReady) {
            pendingXmlB64 = b64
            return
        }
        // Pass through evaluateJavascript; surrounding the base64 string in single
        // quotes is safe because base64 alphabet doesn't contain a single quote.
        evalJs("window.osmdViewer.loadBase64('$b64');")
    }

    private fun evalJs(script: String) {
        binding.webView.evaluateJavascript(script, null)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Intercept left/right *before* the WebView consumes them so they page
        // through the score instead of triggering horizontal scrolling.
        // We only do this when focus is in the WebView; while the toolbar is
        // focused, DPAD_LEFT/RIGHT should still move focus between buttons.
        val webHasFocus = binding.webView.hasFocus() || binding.webView.isFocused
        if (webHasFocus && event.action == KeyEvent.ACTION_DOWN) {
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
            // Hardware keys that should always page, regardless of focus
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
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onDestroy() {
        binding.webView.apply {
            stopLoading()
            removeJavascriptInterface("Android")
            (parent as? android.view.ViewGroup)?.removeView(this)
            destroy()
        }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ScoreReader"
        private const val KEY_LAST_URI = "last_uri"
        private const val KEY_LAST_PERSISTABLE = "last_uri_persistable"
        private const val DEFAULT_ZOOM = 0.6
    }
}

/**
 * Subclass of OpenDocument that also requests *persistable* read access so we
 * can re-open the same content URI on next launch.
 */
private class PersistableOpenDocument : ActivityResultContracts.OpenDocument() {
    override fun createIntent(context: Context, input: Array<String>): Intent {
        return super.createIntent(context, input)
            .addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )
    }
}
