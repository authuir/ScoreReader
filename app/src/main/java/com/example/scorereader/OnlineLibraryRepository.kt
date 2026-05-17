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
        val text = httpGetText(manifestUrl)
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
     * Fetches the *root* endpoint, which must be a `groups.json` — a list
     * of group descriptors, each pointing to its own `library.json` via a
     * relative or absolute URL.
     *
     * Group `url`s are resolved against the manifest's directory using the
     * same rules as score `path`s (see [resolveDownloadUrl]).
     */
    fun fetchGroups(rootUrl: String): OnlineGroupIndex {
        val text = httpGetText(rootUrl)
        val json = JSONObject(text)
        val generatedAt = json.optString("generated_at").ifEmpty { null }

        val groupsArray = json.optJSONArray("groups")
            ?: throw IllegalStateException("Response from $rootUrl has no `groups` array")

        val out = ArrayList<OnlineGroup>(groupsArray.length())
        for (i in 0 until groupsArray.length()) {
            val o = groupsArray.optJSONObject(i) ?: continue
            val groupUrl = o.optString("url")
            if (groupUrl.isEmpty()) continue
            val resolved = resolveManifestUrl(rootUrl, groupUrl)
            val id = o.optString("id").ifEmpty { "group-$i" }
            val title = o.optString("title").ifEmpty { id }
            out += OnlineGroup(
                id = id,
                title = title,
                description = o.optString("description").ifEmpty { null },
                manifestUrl = resolved,
                count = if (o.has("count") && !o.isNull("count")) o.optInt("count") else null,
                totalSizeBytes = if (o.has("total_size_bytes") && !o.isNull("total_size_bytes"))
                    o.optLong("total_size_bytes") else null,
                isLocal = false
            )
        }
        return OnlineGroupIndex(rootUrl = rootUrl, generatedAt = generatedAt, groups = out)
    }

    private fun httpGetText(urlString: String): String {
        val url = URL(urlString)
        return (url.openConnection() as HttpURLConnection).run {
            connectTimeout = 8_000
            readTimeout = 8_000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
            try {
                inputStream.bufferedReader().use { it.readText() }
            } finally {
                disconnect()
            }
        }
    }

    /**
     * Resolves a child URL (e.g. a group's `url`) against the manifest URL
     * using the same conventions as score `path`s:
     *  - already absolute (`http://...` / `https://...`) — return as-is.
     *  - absolute path (`/foo/bar.json`) — resolve against manifest host.
     *  - relative (`groups/x/library.json`) — resolve against manifest dir.
     */
    private fun resolveManifestUrl(manifestUrl: String, child: String): String {
        if (child.startsWith("http://") || child.startsWith("https://")) return child
        return if (child.startsWith("/")) {
            hostOnlyBaseOf(manifestUrl).trimEnd('/') + child
        } else {
            baseUrlOf(manifestUrl).trimEnd('/') + "/" + child.trimStart('/')
        }
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
        // the right cert chain — staying on the manifest host is reliable
        // and matches what the user signed up for.
        //
        // We support two conventions for `item.path`:
        //  - Absolute ("/scores/foo.mxl") — resolves against the manifest
        //    host:port only. Used by the LAN dev server where library.json
        //    sits at the URL root.
        //  - Relative ("scores/foo.mxl") — resolves against the manifest's
        //    *directory*. Required for GitHub Pages project sites where
        //    library.json lives under a sub-path (e.g. `/<repo>/`).
        return if (item.path.startsWith("/")) {
            hostOnlyBaseOf(library.manifestUrl).trimEnd('/') + item.path
        } else {
            library.baseUrl.trimEnd('/') + "/" + item.path.trimStart('/')
        }
    }

    private fun baseUrlOf(manifestUrl: String): String {
        // Directory containing the manifest, e.g.
        //   https://user.github.io/repo/library.json  ->  https://user.github.io/repo
        //   http://192.168.0.5:8081/library.json      ->  http://192.168.0.5:8081
        val uri = URI(manifestUrl)
        val scheme = uri.scheme ?: "http"
        val host = uri.host ?: return manifestUrl
        val portPart = if (uri.port > 0) ":${uri.port}" else ""
        val rawPath = uri.rawPath ?: ""
        val dir = rawPath.substringBeforeLast('/', missingDelimiterValue = "")
        return "$scheme://$host$portPart$dir"
    }

    private fun hostOnlyBaseOf(manifestUrl: String): String {
        // Scheme + host + port, no path. Used to resolve `/scores/...` style
        // absolute paths against the manifest's origin.
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
