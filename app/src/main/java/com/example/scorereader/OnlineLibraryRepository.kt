package com.example.scorereader

import android.content.Context
import android.net.Uri
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/**
 * Single online catalog item returned by the Python `library.json` server.
 *
 * `path` is relative to the manifest URL host, e.g. `/scores/Foo.mxl`.
 */
data class OnlineItem(
    val id: String,
    val title: String,
    val filename: String,
    val path: String,
    val format: String,
    val sizeBytes: Long,
    val sha256: String?,
    val sourceUrl: String?
)

data class OnlineLibrary(
    val manifestUrl: String,
    val baseUrl: String,
    val count: Int,
    val totalSizeBytes: Long,
    val generatedAt: String?,
    val items: List<OnlineItem>
)

/**
 * Fetches and caches the online MusicXML catalog produced by
 * `online-library/server.py`.
 *
 *  - `fetch()` downloads `library.json` and parses it.
 *  - `download(item)` streams `item.path` into the app's cache dir and
 *    returns a `file://` URI ready to be opened by the existing viewer
 *    activities.
 *
 * All network calls are synchronous and intended to be invoked from a
 * background dispatcher (`Dispatchers.IO`).
 */
class OnlineLibraryRepository(context: Context) {

    private val appContext = context.applicationContext

    private val cacheRoot: File
        get() = File(appContext.cacheDir, "online-scores").apply { mkdirs() }

    fun fetch(manifestUrl: String): OnlineLibrary {
        val url = URL(manifestUrl)
        val text = url.openConnection().let { conn ->
            (conn as HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 8_000
                requestMethod = "GET"
                setRequestProperty("Accept", "application/json")
            }
            try {
                conn.inputStream.bufferedReader().use { it.readText() }
            } finally {
                conn.disconnect()
            }
        }
        val json = JSONObject(text)
        val base = baseUrlOf(manifestUrl)
        val arr = json.optJSONArray("items") ?: return OnlineLibrary(
            manifestUrl = manifestUrl,
            baseUrl = base,
            count = 0,
            totalSizeBytes = 0L,
            generatedAt = json.optString("generated_at").ifEmpty { null },
            items = emptyList()
        )
        val items = ArrayList<OnlineItem>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val name = o.optString("filename").ifEmpty { o.optString("id") }
            val path = o.optString("path").ifEmpty { "/scores/$name" }
            items += OnlineItem(
                id = o.optString("id").ifEmpty { name },
                title = o.optString("title").ifEmpty { name },
                filename = name,
                path = path,
                format = o.optString("format", "mxl"),
                sizeBytes = o.optLong("size_bytes", 0L),
                sha256 = o.optString("sha256").ifEmpty { null },
                sourceUrl = o.optString("source_url").ifEmpty { null }
            )
        }
        return OnlineLibrary(
            manifestUrl = manifestUrl,
            baseUrl = base,
            count = json.optInt("count", items.size),
            totalSizeBytes = json.optLong("total_size_bytes", 0L),
            generatedAt = json.optString("generated_at").ifEmpty { null },
            items = items
        )
    }

    /**
     * Downloads `item` from `library.baseUrl` into the local cache and
     * returns a file URI. If a previous download has the expected size it
     * is reused without hitting the network.
     */
    fun download(library: OnlineLibrary, item: OnlineItem): Uri {
        val target = File(cacheRoot, item.filename)
        if (item.sizeBytes > 0 && target.exists() && target.length() == item.sizeBytes) {
            return Uri.fromFile(target)
        }
        val url = URL(resolveDownloadUrl(library, item))
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 60_000
            requestMethod = "GET"
        }
        try {
            if (conn.responseCode !in 200..299) {
                error("HTTP ${conn.responseCode} ${conn.responseMessage} for $url")
            }
            target.outputStream().use { out ->
                conn.inputStream.use { it.copyTo(out) }
            }
        } finally {
            conn.disconnect()
        }
        Log.i(TAG, "Cached $url -> $target (${target.length()} bytes)")
        return Uri.fromFile(target)
    }

    /**
     * Returns the locally cached file for `item` (size-validated) or `null`
     * if the user hasn't downloaded it yet / the cached copy looks stale.
     */
    fun cachedFile(item: OnlineItem): File? {
        val f = File(cacheRoot, item.filename)
        if (!f.exists()) return null
        if (item.sizeBytes > 0 && f.length() != item.sizeBytes) return null
        return f
    }

    fun cachedUri(item: OnlineItem): Uri? = cachedFile(item)?.let(Uri::fromFile)

    private fun resolveDownloadUrl(library: OnlineLibrary, item: OnlineItem): String {
        // Always download from the manifest's own host. `item.sourceUrl`
        // often points at GitHub raw (HTTPS) and Android TVs may not have
        // the right cert chain — staying on the LAN server is reliable
        // and matches what the user signed up for.
        val path = if (item.path.startsWith("/")) item.path else "/${item.path}"
        return library.baseUrl.trimEnd('/') + path
    }

    private fun baseUrlOf(manifestUrl: String): String {
        val uri = URI(manifestUrl)
        val scheme = uri.scheme ?: "http"
        val host = uri.host ?: return manifestUrl
        val portPart = if (uri.port > 0) ":${uri.port}" else ""
        return "$scheme://$host$portPart"
    }

    companion object {
        private const val TAG = "ScoreReader/Online"
    }
}
