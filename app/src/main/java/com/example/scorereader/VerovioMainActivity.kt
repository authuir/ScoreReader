package com.example.scorereader

import android.content.Intent
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.caverock.androidsvg.SVG
import com.caverock.androidsvg.SVGExternalFileResolver
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
    private lateinit var settings: AppSettings

    /** Verovio toolkit handle. 0L = uninitialized / destroyed. */
    private var toolkit: Long = 0L
    private var pageCount: Int = 0
    private var currentPage: Int = 1
    private val pageBitmaps = HashMap<Int, Bitmap>()

    private var currentUri: Uri? = null
    private var currentDisplayName: String? = null
    private var renderJobToken: Long = 0L
    /** Last `verovioScale` value we actually rendered. Used by `onResume` to
     *  detect Settings changes and trigger a fresh render. */
    private var lastAppliedScale: Int = -1

    /** MIDI-backed page-at-a-time playback (DPAD center / ENTER). */
    private val pagePlayer = PagePlayer()
    private var midiFile: File? = null
    /** First measure xml:id we found on each rendered page. Populated lazily
     *  as pages are rasterised so playback bounds can be resolved without
     *  pre-rendering the whole score. */
    private val firstMeasureIdByPage = HashMap<Int, String>()
    /** All measures (id + pixel bbox) we extracted from each page's SVG. */
    private val pageMeasures = HashMap<Int, SvgMeasureExtractor.ExtractResult>()
    /** Measure xml:id → MIDI onset (ms from start of piece). Filled lazily
     *  after the MIDI render completes and each page is rendered. */
    private val measureStartMs = HashMap<String, Int>()
    /** True once the toolkit has produced a MIDI file for the current score. */
    private var midiReady: Boolean = false
    /** UI ticker for the active-measure highlight while playback runs. */
    private val highlightTicker = Handler(Looper.getMainLooper())
    private var highlightTickerRunning: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVerovioBinding.inflate(layoutInflater)
        setContentView(binding.root)

        recents = RecentsRepository(this)
        extractor = VerovioResourceExtractor(this)
        settings = AppSettings(this)
        applyFullscreenFlags()
        registerSvgFontResolver(assets)

        pagePlayer.onStateChange = { playing ->
            runOnUiThread {
                binding.playbackIndicator.visibility = if (playing) View.VISIBLE else View.GONE
            }
        }
        pagePlayer.onError = { msg ->
            Log.w(TAG, "PagePlayer error: $msg")
        }

        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // Settings → Verovio scale slider may have changed while we were
        // backgrounded. Re-run the open pipeline with the new scale so the
        // change is visible immediately.
        val uri = currentUri ?: return
        val newScale = settings.verovioScale
        if (toolkit != 0L && pageCount > 0 && newScale != lastAppliedScale) {
            Log.i(TAG, "verovio scale changed $lastAppliedScale -> $newScale, re-rendering")
            openInVerovio(uri)
        }
    }

    override fun onPause() {
        pagePlayer.pauseIfPlaying()
        stopHighlightTicker()
        binding.measureHighlight.clear()
        super.onPause()
    }

    override fun onDestroy() {
        stopHighlightTicker()
        pagePlayer.release()
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
        // Reset playback state — the new score has its own MIDI / page map.
        pagePlayer.release()
        pagePlayer.clearPageBounds()
        firstMeasureIdByPage.clear()
        pageMeasures.clear()
        measureStartMs.clear()
        stopHighlightTicker()
        binding.measureHighlight.clear()
        midiFile = null
        midiReady = false

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
                // Render MIDI in the background; play/pause becomes usable
                // once this finishes.
                renderMidiAsync(jobToken)
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
            Log.i(TAG, "Verovio version: ${VerovioNative.nativeGetVersion(toolkit)}")
        }
        // Re-apply page options on every open so scale changes made in
        // Settings (or display rotations) take effect on the next score.
        val opts = buildPageOptions()
        Log.i(TAG, "verovio options: $opts")
        VerovioNative.nativeSetOptions(toolkit, opts)
        lastAppliedScale = settings.verovioScale
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
     * Build Verovio options so each rendered page exactly fills the display
     * and the Settings slider actually changes how big notes look on screen.
     *
     * Verovio's `scale` option controls *staff size* relative to a fixed
     * `pageWidth`/`pageHeight` (units of 1/100 mm). On its own it just
     * changes how many measures fit on a page, not how large each note
     * appears, because we always rasterise the SVG to fill the screen.
     *
     * Instead we keep Verovio's internal `scale` pinned at 100 and treat
     * the user's slider as a *zoom factor* that scales `pageWidth` /
     * `pageHeight` inversely: a smaller logical page means fewer measures
     * per page, which after fit-to-screen makes every note appear larger.
     *
     * With `svgViewBox: true`, Verovio also emits a `viewBox` attribute so
     * AndroidSVG honours `documentWidth/Height` and properly scales the
     * page-shaped SVG to the screen.
     */
    private fun buildPageOptions(): String {
        val dm = resources.displayMetrics
        val w = (dm.widthPixels.takeIf { it > 0 } ?: 1920)
        val h = (dm.heightPixels.takeIf { it > 0 } ?: 1080)
        // Reference page width that yields a comfortable density at zoom=1.0
        // (i.e. when the slider sits at its "native" value).
        val baseWidth = 2400
        val sliderScale = settings.verovioScale
            .coerceIn(AppSettings.VEROVIO_SCALE_MIN, AppSettings.VEROVIO_SCALE_MAX)
        val nativeScale = AppSettings.DEFAULT_VEROVIO_SCALE.toFloat()
        val zoom = (sliderScale.toFloat() / nativeScale).coerceIn(0.25f, 4f)
        val pageWidth = (baseWidth / zoom).toInt().coerceAtLeast(400)
        val pageHeight = ((pageWidth.toLong() * h) / w).toInt().coerceAtLeast(400)
        return """
            {
              "pageWidth": $pageWidth,
              "pageHeight": $pageHeight,
              "pageMarginLeft": 50,
              "pageMarginRight": 50,
              "pageMarginTop": 50,
              "pageMarginBottom": 50,
              "scale": 100,
              "adjustPageHeight": false,
              "breaks": "auto",
              "svgViewBox": true
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

        // Remember the first measure on this page so PagePlayer can resolve
        // a MIDI onset for it later. The lookup itself happens lazily, so
        // this is cheap (a single regex scan over the SVG string).
        extractFirstMeasureId(svgText)?.let { id ->
            firstMeasureIdByPage[pageNo] = id
            resolvePageStartIfReady(pageNo, id)
        }

        // Also extract *all* measures + their viewBox-space bounding boxes
        // so the playback ticker can highlight whichever one is currently
        // sounding. The overlay applies the viewBox -> screen transform
        // itself so it stays correct even when the ImageView's drawn area
        // differs from the bitmap size (status bar / cutout insets).
        val extracted = SvgMeasureExtractor.extract(svgText)
        if (extracted.measures.isNotEmpty()) {
            pageMeasures[pageNo] = extracted
            // Resolve onsets immediately if MIDI is already rendered.
            if (midiReady) {
                for (m in extracted.measures) resolveMeasureStartIfReady(m.id)
            }
        }

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
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER,
                KeyEvent.KEYCODE_SPACE,
                KeyEvent.KEYCODE_BUTTON_A,
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                KeyEvent.KEYCODE_MEDIA_PLAY,
                KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                    if (event.repeatCount == 0) togglePlayback()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun pageBy(dir: Int) {
        if (toolkit == 0L || pageCount == 0) return
        val next = (currentPage + dir).coerceIn(1, pageCount)
        if (next == currentPage) return
        // Navigating to a different page — stop any in-flight playback so we
        // never bleed the previous page's audio over the new one.
        pagePlayer.pauseIfPlaying()
        stopHighlightTicker()
        binding.measureHighlight.clear()
        currentPage = next
        renderAndShow(renderJobToken, currentPage)
    }

    // ---------------------------------------------------------------------
    // MIDI playback for the current page
    // ---------------------------------------------------------------------

    private fun togglePlayback() {
        if (toolkit == 0L || pageCount == 0) {
            Log.i(TAG, "togglePlayback skipped toolkit=$toolkit pages=$pageCount")
            return
        }
        if (midiFile == null || !midiReady) {
            Log.i(TAG, "togglePlayback MIDI not ready midiReady=$midiReady file=${midiFile != null}")
            return
        }
        // Ensure the current page's start time is known. If the SVG for this
        // page has already been rendered we have the measure id; otherwise
        // we fall back to deriving it on demand (rare — only fires if the
        // user hits play before the first render completes).
        val measureId = firstMeasureIdByPage[currentPage]
        if (measureId != null) {
            resolvePageStartIfReady(currentPage, measureId)
        } else {
            Log.i(TAG, "togglePlayback no measure id for page=$currentPage; priming")
            primePageStartAsync(currentPage)
        }
        // Make sure we also know where this page ends — i.e. where the next
        // page begins. Trigger an SVG render (no rasterise) if missing.
        if (currentPage < pageCount && firstMeasureIdByPage[currentPage + 1] == null) {
            primePageStartAsync(currentPage + 1)
        }
        val consumed = pagePlayer.toggle(currentPage)
        if (consumed && pagePlayer.isPlaying()) {
            startHighlightTicker()
        } else {
            stopHighlightTicker()
            binding.measureHighlight.clear()
        }
    }

    /**
     * Render the full score to a MIDI file on disk. We do this off the UI
     * thread once per score open. `getTimeForElement` only returns sensible
     * values *after* RenderToMIDI(File) has run, so the page-start
     * resolution below depends on this completing first.
     */
    private fun renderMidiAsync(jobToken: Long) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                val handle = toolkit
                if (handle == 0L) return@withContext Triple<File?, Boolean, Long>(null, false, 0L)
                val out = File(cacheDir, "verovio-midi-$jobToken.mid")
                if (out.exists()) out.delete()
                val tMidi0 = System.currentTimeMillis()
                val ok = VerovioNative.nativeRenderToMidiFile(handle, out.absolutePath)
                val midiMs = System.currentTimeMillis() - tMidi0
                if (!ok || !out.exists() || out.length() <= 0L) {
                    Log.w(TAG, "renderToMIDIFile failed (ok=$ok, exists=${out.exists()}, size=${out.length()})")
                    Triple<File?, Boolean, Long>(null, ok, out.length())
                } else {
                    Log.i(TAG, "timing/native verovio midi=${midiMs}ms size=${out.length()} -> ${out.absolutePath}")
                    Triple<File?, Boolean, Long>(out, true, midiMs)
                }
            }
            if (jobToken != renderJobToken) {
                result.first?.delete()
                return@launch
            }
            val file = result.first
            if (file == null) {
                midiReady = false
                Log.w(TAG, "MIDI render FAILED ok=${result.second} bytes=${result.third}")
                return@launch
            }
            midiFile = file
            pagePlayer.setMidiFile(file)
            midiReady = true
            Log.i(TAG, "MIDI ready ${file.length()}B in ${result.third}ms pages cached=${firstMeasureIdByPage.size}")
            // Resolve any page starts whose measure id we already cached
            // while the MIDI was being rendered.
            for ((page, id) in firstMeasureIdByPage.toMap()) {
                resolvePageStartIfReady(page, id)
            }
            // Also resolve onset times for every measure we already extracted
            // from rendered pages, so the playback highlight can light up
            // immediately on the first toggle.
            for ((_, result) in pageMeasures.toMap()) {
                for (m in result.measures) resolveMeasureStartIfReady(m.id)
            }
        }
    }

    /**
     * If both the MIDI is ready and we know the first-measure id for [page],
     * push the resolved start time into [pagePlayer]. Safe to call multiple
     * times — PagePlayer overwrites entries idempotently.
     */
    private fun resolvePageStartIfReady(page: Int, measureId: String) {
        if (!midiReady || toolkit == 0L) return
        val ms = VerovioNative.nativeGetTimeForElement(toolkit, measureId)
        if (ms < 0) {
            Log.i(TAG, "resolvePageStartIfReady: page=$page id=$measureId no MIDI time")
            return
        }
        Log.i(TAG, "resolvePageStartIfReady: page=$page id=$measureId -> ${ms}ms")
        pagePlayer.setPageStartMs(page, ms)
    }

    /** Resolve and cache the MIDI onset for a single measure id. */
    private fun resolveMeasureStartIfReady(measureId: String) {
        if (!midiReady || toolkit == 0L) return
        if (measureStartMs.containsKey(measureId)) return
        val ms = VerovioNative.nativeGetTimeForElement(toolkit, measureId)
        if (ms >= 0) measureStartMs[measureId] = ms
    }

    // ---------------------------------------------------------------------
    // Active-measure highlight ticker
    // ---------------------------------------------------------------------

    private val highlightRunnable = object : Runnable {
        override fun run() {
            if (!highlightTickerRunning) return
            if (!pagePlayer.isPlaying()) {
                stopHighlightTicker()
                binding.measureHighlight.clear()
                return
            }
            updateMeasureHighlight()
            highlightTicker.postDelayed(this, HIGHLIGHT_INTERVAL_MS)
        }
    }

    private fun startHighlightTicker() {
        if (highlightTickerRunning) return
        highlightTickerRunning = true
        highlightTicker.post(highlightRunnable)
    }

    private fun stopHighlightTicker() {
        highlightTickerRunning = false
        highlightTicker.removeCallbacks(highlightRunnable)
    }

    private fun updateMeasureHighlight() {
        val result = pageMeasures[currentPage] ?: return
        val measures = result.measures
        if (measures.isEmpty()) return
        val posMs = pagePlayer.positionMs()
        // Find the measure on the current page whose [start, nextStart)
        // contains posMs. Measures with unknown onset are skipped.
        var bestBox: android.graphics.RectF? = null
        var bestStart = Int.MIN_VALUE
        for (m in measures) {
            val start = measureStartMs[m.id] ?: continue
            if (start <= posMs && start > bestStart) {
                bestStart = start
                bestBox = m.bbox
            }
        }
        binding.measureHighlight.setMeasure(bestBox, result.viewBox)
    }

    /** Render just enough of a page's SVG to harvest its first measure id
     *  (no rasterisation). Used to fill in the end-of-page boundary when
     *  the user requests playback before navigating to the next page. */
    private fun primePageStartAsync(page: Int) {
        if (page in firstMeasureIdByPage) return
        if (page < 1 || page > pageCount) return
        val token = renderJobToken
        lifecycleScope.launch(Dispatchers.IO) {
            val handle = toolkit
            if (handle == 0L) return@launch
            val svgText = try {
                VerovioNative.nativeRenderToSvg(handle, page)
            } catch (t: Throwable) {
                Log.w(TAG, "primePageStart: render page=$page failed", t)
                return@launch
            }
            if (token != renderJobToken) return@launch
            val id = extractFirstMeasureId(svgText) ?: return@launch
            withContext(Dispatchers.Main) {
                firstMeasureIdByPage[page] = id
                resolvePageStartIfReady(page, id)
            }
        }
    }

    /**
     * Find the first `<g class="measure" id="...">` (or `id=` before
     * `class=`) in a Verovio-rendered SVG string and return its xml:id.
     */
    private fun extractFirstMeasureId(svg: String): String? {
        // Verovio emits both class then id and id then class depending on
        // version, so try both orders. The class list is always exactly
        // "measure" for the surrounding group.
        MEASURE_CLASS_ID.find(svg)?.groupValues?.getOrNull(1)?.let { return it }
        MEASURE_ID_CLASS.find(svg)?.groupValues?.getOrNull(1)?.let { return it }
        return null
    }

    companion object {
        private const val TAG = "ScoreReader/Verovio"
        private const val HIGHLIGHT_INTERVAL_MS = 60L

        /** Matches a measure `<g>` whose `class` attribute appears before `id`. */
        private val MEASURE_CLASS_ID =
            Regex("""<g\b[^>]*\bclass="[^"]*\bmeasure\b[^"]*"[^>]*\bid="([^"]+)"""")
        /** Matches a measure `<g>` whose `id` attribute appears before `class`. */
        private val MEASURE_ID_CLASS =
            Regex("""<g\b[^>]*\bid="([^"]+)"[^>]*\bclass="[^"]*\bmeasure\b[^"]*"""")

        /** Loaded once per process — AndroidSVG's resolver is global. */
        @Volatile
        private var fontResolverRegistered: Boolean = false

        /**
         * Register a global [SVGExternalFileResolver] so that `<text>` elements
         * in Verovio's SVG output (dynamics, expressions, tempo numbers, etc.)
         * can resolve their `font-family` to real SMuFL typefaces bundled in
         * the app's assets. Without this, AndroidSVG falls back to the default
         * sans-serif font, which doesn't include the SMuFL private-use glyphs
         * and renders them as empty boxes.
         */
        @Synchronized
        fun registerSvgFontResolver(assets: AssetManager) {
            if (fontResolverRegistered) return
            val leland = runCatching { Typeface.createFromAsset(assets, "fonts/Leland.otf") }
                .onFailure { Log.w(TAG, "Failed to load Leland.otf: ${it.message}") }.getOrNull()
            val leipzig = runCatching { Typeface.createFromAsset(assets, "fonts/Leipzig.ttf") }
                .onFailure { Log.w(TAG, "Failed to load Leipzig.ttf: ${it.message}") }.getOrNull()
            val bravura = runCatching { Typeface.createFromAsset(assets, "fonts/Bravura.otf") }
                .onFailure { Log.w(TAG, "Failed to load Bravura.otf: ${it.message}") }.getOrNull()

            // Make a sensible fallback: any music font we have, prefer Leland.
            val fallback = leland ?: bravura ?: leipzig

            SVG.registerExternalFileResolver(object : SVGExternalFileResolver() {
                override fun resolveFont(
                    fontFamily: String?,
                    fontWeight: Int,
                    fontStyle: String?
                ): Typeface? {
                    if (fontFamily == null) return null
                    return when {
                        fontFamily.equals("Leland", ignoreCase = true) -> leland ?: fallback
                        fontFamily.equals("Leipzig", ignoreCase = true) -> leipzig ?: fallback
                        fontFamily.equals("Bravura", ignoreCase = true) -> bravura ?: fallback
                        // Verovio also emits "VerovioText" and the SMuFL face
                        // names for fallback chains; route those to whatever
                        // music font we have so private-use glyphs resolve.
                        fontFamily.contains("Verovio", ignoreCase = true) -> fallback
                        else -> null
                    }
                }
            })
            fontResolverRegistered = true
            Log.i(
                TAG,
                "SVG font resolver registered (leland=${leland != null}, " +
                    "leipzig=${leipzig != null}, bravura=${bravura != null})"
            )
        }
    }
}
