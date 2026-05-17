package com.example.scorereader

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Tiny SharedPreferences-backed store for user-added Online groups.
 *
 * The Online tab merges two sources of groups every time it opens:
 *  - the server-provided list returned by `<onlineLibraryUrl>` (a
 *    `groups.json`), which is refreshed on each visit and **never**
 *    persisted locally.
 *  - the user's local subscriptions stored here. These survive across
 *    launches and are the only ones the user can edit (add via the
 *    home-screen "+" button, remove via long-press on a card).
 *
 * Local groups are keyed by a stable, generated `id`. Adding the same
 * `manifestUrl` twice is silently deduped.
 */
class OnlineGroupSubscriptionsRepository(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun listLocal(): List<OnlineGroup> {
        val raw = prefs.getString(KEY_LOCAL_GROUPS, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id").ifEmpty { return@mapNotNull null }
                val url = o.optString("manifestUrl").ifEmpty { return@mapNotNull null }
                OnlineGroup(
                    id = id,
                    title = o.optString("title").ifEmpty { url },
                    description = o.optString("description").ifEmpty { null },
                    manifestUrl = url,
                    count = if (o.has("count") && !o.isNull("count")) o.optInt("count") else null,
                    totalSizeBytes = if (o.has("totalSizeBytes") && !o.isNull("totalSizeBytes"))
                        o.optLong("totalSizeBytes") else null,
                    isLocal = true
                )
            }
        }.getOrElse {
            Log.w(TAG, "Failed to parse local groups: ${it.message}")
            emptyList()
        }
    }

    /** Adds a local subscription, or returns the existing one if URL collides. */
    fun addLocal(title: String, manifestUrl: String): OnlineGroup {
        val trimmedUrl = manifestUrl.trim()
        require(trimmedUrl.isNotEmpty()) { "manifestUrl must not be blank" }
        val current = listLocal().toMutableList()
        val existing = current.firstOrNull { it.manifestUrl == trimmedUrl }
        if (existing != null) return existing
        val id = "local-" + Integer.toHexString(
            (trimmedUrl + System.currentTimeMillis()).hashCode()
        )
        val group = OnlineGroup(
            id = id,
            title = title.trim().ifEmpty { trimmedUrl },
            description = null,
            manifestUrl = trimmedUrl,
            count = null,
            totalSizeBytes = null,
            isLocal = true
        )
        current.add(0, group)
        save(current)
        return group
    }

    fun removeLocal(id: String) {
        val filtered = listLocal().filterNot { it.id == id }
        save(filtered)
    }

    fun clearAll() {
        prefs.edit().remove(KEY_LOCAL_GROUPS).apply()
    }

    private fun save(items: List<OnlineGroup>) {
        val arr = JSONArray()
        for (g in items) {
            val o = JSONObject()
            o.put("id", g.id)
            o.put("title", g.title)
            g.description?.let { o.put("description", it) }
            o.put("manifestUrl", g.manifestUrl)
            g.count?.let { o.put("count", it) }
            g.totalSizeBytes?.let { o.put("totalSizeBytes", it) }
            arr.put(o)
        }
        prefs.edit().putString(KEY_LOCAL_GROUPS, arr.toString()).apply()
    }

    companion object {
        private const val TAG = "ScoreReader/OnlineGroups"
        private const val PREFS_NAME = "score_reader_online_groups"
        private const val KEY_LOCAL_GROUPS = "local_groups"
    }
}
