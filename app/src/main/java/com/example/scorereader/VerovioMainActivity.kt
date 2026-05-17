package com.example.scorereader

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.caverock.androidsvg.SVG
import com.example.scorereader.databinding.ActivityVerovioBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Native Verovio viewer.
 *
 * Pipeline per score:
 *   1. Kotlin reads the URI into bytes (mxl unzip if needed).
 *   2. JNI: `vrvToolkit_loadData` parses MusicXML into Verovio's internal MEI.
 *   3. JNI: `vrvToolkit_renderToSVG(page)` produces SVG text per page.
 *   4. AndroidSVG rasterises each page into an ARGB_8888 Bitmap.
 *   5. We show the current page in the ImageView; DPAD-left/right pages.
 *
 * The whole heavy path (load + per-page render + rasterise) runs on
 * Dispatchers.IO. Page bitmaps for the surrounding pages are kept in a tiny
 * LRU so back/forward feels instant.
 */
class VerovioMainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVerovioBinding
    private lateinit var recents: RecentsRepository
    private lateinit var extractor: VerovioResourceExtractor

    /** Verovio toolkit handle. 0L = uninitialized / destroyed. */
    private var toolkit: Long = 0L
    private var pageCount: Int = 0
    private var currentPage: Int = 1
    private val pageBitmaps = HashMap<Int, Bitmap>()

    private var currentUri: Uri? = null
    private var currentDisplayName: String? = null
    private var renderJobToken: Long = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVerovioBinding.inflate(layoutInflater)
        setContentView(binding.root)

        recents = RecentsRepository(this)
        extractor = VerovioResourceExtractor(this)
        applyFullscreenFlags()

        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onDestroy() {
        destroyToolkit()
        for (b in pageBitmaps.values) b.recycle()
        pageBitmaps.clear()
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

    // ---------------------------------------------------------------------
    // Score open pipeline
    // ---------------------------------------------------------------------

    private fun handleIntent(intent: Intent?) {
        val uri = intent?.data ?: return
        currentUri = uri
        currentDisplayName = intent.getStringExtra(MainActivity.EXTRA_DISPLAY_NAME)
            ?: uri.lastPathSegment
        openInVerovio(uri)
    }

    private fun openInVerovio(uri: Uri) {
        val openStart = System.currentTimeMillis()
        showLoading(getString(R.string.msg_loading_reading))
        val jobToken = ++renderJobToken
        // Clear previous page cache so we don't show old content.
        for (b in pageBitmaps.values) b.recycle()
        pageBitmaps.clear()
        binding.scoreImage.setImageBitmap(null)

        lifecycleScope.launch {
            try {
                val info = withContext(Dispatchers.IO) {
                    prepareAndLoad(uri, jobToken)
                }
                if (jobToken != renderJobToken) return@launch
                if (info == null) return@launch
                pageCount = info.pageCount
                currentPage = 1
                val totalReadyMs = System.currentTimeMillis() - openStart
                Log.i(
                    TAG,
                    "timing/native verovio ready in ${totalReadyMs}ms " +
                        "read=${info.readMs}ms unzip=${info.unzipMs}ms " +
                        "load=${info.loadMs}ms pages=${info.pageCount}"
                )
                setLoadingText(getString(R.string.msg_loading_rendering))
                renderAndShow(jobToken, currentPage)
                recents.add(uri, currentDisplayName, persistable = uri.scheme == "content")
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to open in Verovio", t)
                hideLoading()
                Toast.makeText(
                    this@VerovioMainActivity,
                    getString(R.string.err_load_failed, t.message ?: t.javaClass.simpleName),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private data class LoadInfo(
        val pageCount: Int,
        val readMs: Long,
        val unzipMs: Long,
        val loadMs: Long
    )

    /** Runs on Dispatchers.IO. Returns `null` if a newer job invalidated us. */
    private fun prepareAndLoad(uri: Uri, jobToken: Long): LoadInfo? {
        if (toolkit == 0L) {
            val resourcePath = extractor.ensureExtracted()
            VerovioNative.nativeEnableLog(true)
            toolkit = VerovioNative.nativeCreate(resourcePath)
            if (toolkit == 0L) error("Verovio toolkit init failed")
            VerovioNative.nativeSetOptions(toolkit, buildPageOptions())
            Log.i(TAG, "Verovio version: ${VerovioNative.nativeGetVersion(toolkit)}")
        }
        if (jobToken != renderJobToken) return null

        val tRead0 = System.currentTimeMillis()
        val bytes = when (uri.scheme) {
            "file" -> File(uri.path!!).readBytes()
            else -> contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: error("Unable to open URI: $uri")
        }
        val readMs = System.currentTimeMillis() - tRead0

        val tLoad0 = System.currentTimeMillis()
        val ok = if (isZip(bytes)) {
            VerovioNative.nativeLoadZipBuffer(toolkit, bytes)
        } else {
            val text = String(bytes, Charsets.UTF_8)
            VerovioNative.nativeLoadData(toolkit, text)
        }
        val loadMs = System.currentTimeMillis() - tLoad0
        if (!ok) {
            val log = VerovioNative.nativeGetLog(toolkit)
            error("Verovio loadData returned false. log:\n$log")
        }

        val pages = VerovioNative.nativeGetPageCount(toolkit)
        return LoadInfo(
            pageCount = if (pages < 1) 1 else pages,
            readMs = readMs,
            unzipMs = 0L,
            loadMs = loadMs
        )
    }

    private fun isZip(bytes: ByteArray): Boolean =
        bytes.size >= 4 &&
            bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte() &&
            bytes[2] == 0x03.toByte() && bytes[3] == 0x04.toByte()

    /**
     * Build Verovio options so each rendered page exactly fills the display.
     *
     * Verovio paginates based on `pageWidth` x `pageHeight` (units of 1/100 mm
     * when `unit` is default). We pin both to the screen aspect ratio and
     * disable `adjustPageHeight` so every page has identical height. That way
     * one Verovio page == one full TV screen, which is what makes the
     * left/right DPAD feel like real page turning (no scrolling needed).
     */
    private fun buildPageOptions(): String {
        val dm = resources.displayMetrics
        val w = (dm.widthPixels.takeIf { it > 0 } ?: 1920)
        val h = (dm.heightPixels.takeIf { it > 0 } ?: 1080)
        // Use a generous logical page width so a comfortable number of staves
        // and notes fit per line. The exact value is arbitrary; only the
        // ratio between width/height matters for layout, and `scale` sets
        // staff size relative to the page.
        val pageWidth = 2400
        val pageHeight = ((pageWidth.toLong() * h) / w).toInt().coerceAtLeast(400)
        return """
            {
              "pageWidth": $pageWidth,
              "pageHeight": $pageHeight,
              "pageMarginLeft": 50,
              "pageMarginRight": 50,
              "pageMarginTop": 50,
              "pageMarginBottom": 50,
              "scale": 40,
              "adjustPageHeight": false,
              "breaks": "auto"
            }
        """.trimIndent()
    }

    // ---------------------------------------------------------------------
    // Per-page SVG render + rasterise
    // ---------------------------------------------------------------------

    private fun renderAndShow(jobToken: Long, pageNo: Int) {
        val cached = pageBitmaps[pageNo]
        if (cached != null) {
            binding.scoreImage.setImageBitmap(cached)
            hideLoading()
            return
        }
        showLoading(getString(R.string.msg_loading_rendering))
        lifecycleScope.launch {
            val bmp = try {
                withContext(Dispatchers.IO) { renderPageToBitmap(pageNo) }
            } catch (t: Throwable) {
                Log.e(TAG, "Render failed", t)
                null
            }
            if (jobToken != renderJobToken) {
                bmp?.recycle()
                return@launch
            }
            hideLoading()
            if (bmp != null) {
                pageBitmaps[pageNo] = bmp
                binding.scoreImage.setImageBitmap(bmp)
            } else {
                Toast.makeText(
                    this@VerovioMainActivity,
                    "Render failed for page $pageNo",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun renderPageToBitmap(pageNo: Int): Bitmap {
        val handle = toolkit
        if (handle == 0L) error("Toolkit not initialized")
        val tSvg0 = System.currentTimeMillis()
        val svgText = VerovioNative.nativeRenderToSvg(handle, pageNo)
        val svgMs = System.currentTimeMillis() - tSvg0

        val tParse0 = System.currentTimeMillis()
        val svg = SVG.getFromString(svgText)
        val parseMs = System.currentTimeMillis() - tParse0

        // One Verovio page fills the whole TV screen. Bitmap size == display
        // size; AndroidSVG honours the SVG's viewBox + xMidYMid-meet so even
        // tiny aspect mismatches just get a thin letterbox (we configure the
        // page aspect to match the screen, so in practice this is exact).
        val dm = resources.displayMetrics
        val outW = (dm.widthPixels.takeIf { it > 0 } ?: 1920)
        val outH = (dm.heightPixels.takeIf { it > 0 } ?: 1080)

        val tRaster0 = System.currentTimeMillis()
        val bitmap = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        svg.documentWidth = outW.toFloat()
        svg.documentHeight = outH.toFloat()
        svg.renderToCanvas(canvas)
        val rasterMs = System.currentTimeMillis() - tRaster0

        Log.i(
            TAG,
            "timing/native verovio page=$pageNo svg=${svgMs}ms parseSvg=${parseMs}ms " +
                "raster=${rasterMs}ms outSize=${outW}x${outH} svgLen=${svgText.length}"
        )
        return bitmap
    }

    // ---------------------------------------------------------------------
    // Lifecycle / UI helpers
    // ---------------------------------------------------------------------

    private fun destroyToolkit() {
        val h = toolkit
        toolkit = 0L
        if (h != 0L) VerovioNative.nativeDestroy(h)
    }

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
    // Key handling: DPAD left/right pages through the score.
    // ---------------------------------------------------------------------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_PAGE_UP -> {
                    pageBy(-1); return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_PAGE_DOWN -> {
                    pageBy(1); return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun pageBy(dir: Int) {
        if (toolkit == 0L || pageCount == 0) return
        val next = (currentPage + dir).coerceIn(1, pageCount)
        if (next == currentPage) return
        currentPage = next
        renderAndShow(renderJobToken, currentPage)
    }

    companion object {
        private const val TAG = "ScoreReader/Verovio"
    }
}
