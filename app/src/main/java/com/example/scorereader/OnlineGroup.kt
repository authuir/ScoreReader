package com.example.scorereader

/**
 * One row in the Online tab's *group list* view. The Online tab is a small
 * two-level browser:
 *
 *   1. **Groups view** — cards represent a curated bucket of scores. Each
 *      group has its own `manifestUrl` (a `library.json`) and is either
 *      provided by the server-side `groups.json` (`isLocal=false`) or
 *      added by the user via the "+" button on the home screen
 *      (`isLocal=true`).
 *   2. **Scores view** — drilling into a group fetches its `manifestUrl`
 *      and falls back to the existing [OnlineAdapter] layout to list the
 *      individual scores.
 */
data class OnlineGroup(
    val id: String,
    val title: String,
    val description: String?,
    /** Always absolute after [OnlineLibraryRepository.fetchGroups] resolves it. */
    val manifestUrl: String,
    /** Best-effort score count from `groups.json`; `null` if unknown. */
    val count: Int?,
    /** Best-effort total size; `null` if unknown. */
    val totalSizeBytes: Long?,
    /** `true` if the user added this group from the in-app dialog. */
    val isLocal: Boolean
)

/**
 * Parsed `groups.json` response merged with any user-added local groups.
 *
 * `rootUrl` is whatever the user configured under Settings → Online library
 * URL; we keep it around for diagnostics and to dedupe groups by URL.
 */
data class OnlineGroupIndex(
    val rootUrl: String,
    val generatedAt: String?,
    val groups: List<OnlineGroup>
)
