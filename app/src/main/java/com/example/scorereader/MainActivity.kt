package com.example.scorereader

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.util.Base64
import android.util.Log
import android.view.KeyEvent
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
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
    private var pendingXml: String? = null

    // SAF picker (ACTION_OPEN_DOCUMENT) — may be unsupported on some STBs
    private val openDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> if (uri != null) loadMusicXmlFromUri(uri) }

    // Fallback picker (ACTION_GET_CONTENT) — supported by simpler file managers
    private val getContentLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? -> if (uri != null) loadMusicXmlFromUri(uri) }

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
                    pendingXml?.let {
                        renderXmlInWebView(it)
                        pendingXml = null
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
            intent.data?.let { loadMusicXmlFromUri(it) }
        }
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
        lifecycleScope.launch {
            val xml = runCatching { withContext(Dispatchers.IO) { readMusicXml(uri) } }
            xml.onSuccess {
                binding.statusText.text = uri.lastPathSegment ?: uri.toString()
                renderXmlInWebView(it)
            }.onFailure {
                Log.e(TAG, "Failed to load score", it)
                binding.statusText.text =
                    getString(R.string.err_load_failed, it.message ?: it.javaClass.simpleName)
                Toast.makeText(this@MainActivity, binding.statusText.text, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun readMusicXml(uri: Uri): String {
        val bytes = when (uri.scheme) {
            "file" -> File(uri.path!!).readBytes()
            else -> contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: error("Unable to open URI: $uri")
        }
        // .mxl files are ZIP containers; extract the underlying MusicXML
        return if (isZip(bytes)) extractMusicXmlFromMxl(bytes) else bytes.toString(Charsets.UTF_8)
    }

    private fun isZip(bytes: ByteArray): Boolean =
        bytes.size >= 4 &&
            bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte() &&
            bytes[2] == 0x03.toByte() && bytes[3] == 0x04.toByte()

    private fun extractMusicXmlFromMxl(bytes: ByteArray): String {
        ZipInputStream(bytes.inputStream()).use { zis ->
            var entry = zis.nextEntry
            var fallback: String? = null
            while (entry != null) {
                val name = entry.name
                if (!entry.isDirectory && (name.endsWith(".xml", true) ||
                            name.endsWith(".musicxml", true))
                ) {
                    val out = ByteArrayOutputStream()
                    zis.copyTo(out)
                    val content = out.toString(Charsets.UTF_8.name())
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

    private fun renderXmlInWebView(xml: String) {
        if (!viewerReady) {
            pendingXml = xml
            return
        }
        val b64 = Base64.encodeToString(xml.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        evalJs("window.osmdViewer.loadBase64('$b64');")
    }

    private fun evalJs(script: String) {
        binding.webView.evaluateJavascript(script, null)
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
    }
}
