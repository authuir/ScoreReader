package com.example.scorereader

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

data class RecentScore(
    val uri: Uri,
    val displayName: String,
    val openedAtMs: Long,
    val persistable: Boolean
)

/**
 * Persists the list of recently opened scores in SharedPreferences.
 *
 * - URIs added via the SAF picker are marked `persistable=true` and the caller is
 *   expected to also call `contentResolver.takePersistableUriPermission(...)`.
 * - URIs from `ACTION_GET_CONTENT`, `ACTION_VIEW`, or the built-in scanner are
 *   not durable across reboots; we still keep them in the list so the user can
 *   quickly re-pick the file during the current session.
 */
class RecentsRepository(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun list(): List<RecentScore> {
        val raw = prefs.getString(KEY_RECENTS, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { idx ->
                val o = arr.optJSONObject(idx) ?: return@mapNotNull null
                val uriStr = o.optString("uri").takeIf { it.isNotEmpty() }
                    ?: return@mapNotNull null
                val uri = runCatching { Uri.parse(uriStr) }.getOrNull() ?: return@mapNotNull null
                RecentScore(
                    uri = uri,
                    displayName = o.optString("name").ifEmpty {
                        uri.lastPathSegment ?: uri.toString()
                    },
                    openedAtMs = o.optLong("opened", 0L),
                    persistable = o.optBoolean("persistable", false)
                )
            }.sortedByDescending { it.openedAtMs }
        }.getOrElse { emptyList() }
    }

    fun add(uri: Uri, displayName: String?, persistable: Boolean) {
        val now = System.currentTimeMillis()
        val current = list().toMutableList()
        // Dedupe by URI string (different schemes are intentionally distinct)
        val uriStr = uri.toString()
        current.removeAll { it.uri.toString() == uriStr }
        current.add(
            0,
            RecentScore(
                uri = uri,
                displayName = (displayName ?: uri.lastPathSegment ?: uriStr).take(160),
                openedAtMs = now,
                persistable = persistable
            )
        )
        val trimmed = current.take(MAX_ENTRIES)
        save(trimmed)
    }

    fun remove(uri: Uri) {
        val uriStr = uri.toString()
        val filtered = list().filterNot { it.uri.toString() == uriStr }
        save(filtered)
    }

    fun clear() {
        prefs.edit().remove(KEY_RECENTS).apply()
    }

    private fun save(items: List<RecentScore>) {
        val arr = JSONArray()
        for (it in items) {
            val o = JSONObject()
            o.put("uri", it.uri.toString())
            o.put("name", it.displayName)
            o.put("opened", it.openedAtMs)
            o.put("persistable", it.persistable)
            arr.put(o)
        }
        prefs.edit().putString(KEY_RECENTS, arr.toString()).apply()
    }

    /** Best-effort check whether the URI can still be opened by the app. */
    fun isReachable(recent: RecentScore): Boolean = runCatching {
        when (recent.uri.scheme) {
            "file" -> {
                val path = recent.uri.path ?: return false
                val f = java.io.File(path)
                f.exists() && f.canRead()
            }
            "content" -> {
                // We may have lost the read permission grant on reboot.
                appContext.contentResolver.openInputStream(recent.uri)?.use { /* drain nothing */ }
                true
            }
            else -> false
        }
    }.getOrElse { e ->
        Log.d(TAG, "isReachable(${recent.uri}) failed: ${e.message}")
        false
    }

    /**
     * Tries to attach a persistable read permission to a content URI returned by
     * ACTION_OPEN_DOCUMENT. Safe to call repeatedly; logs on failure.
     */
    fun tryPersistRead(uri: Uri) {
        if (uri.scheme != "content") return
        try {
            appContext.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (e: SecurityException) {
            Log.w(TAG, "Could not persist read permission for $uri: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "ScoreReader/Recents"
        private const val PREFS_NAME = "score_reader_recents"
        private const val KEY_RECENTS = "recents"
        private const val MAX_ENTRIES = 30
    }
}
