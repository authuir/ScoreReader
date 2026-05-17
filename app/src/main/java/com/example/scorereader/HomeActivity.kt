package com.example.scorereader

import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.google.android.material.tabs.TabLayout
import com.example.scorereader.databinding.ActivityHomeBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class HomeActivity : AppCompatActivity() {

    private enum class HomeTab { RECENT, FAVORITE, ONLINE }

    /** What the Online tab is currently showing. */
    private enum class OnlineMode { GROUPS, SCORES }

    private lateinit var binding: ActivityHomeBinding
    private lateinit var recents: RecentsRepository
    private lateinit var adapter: RecentsAdapter
    private lateinit var settings: AppSettings
    private lateinit var onlineRepo: OnlineLibraryRepository
    private lateinit var onlineAdapter: OnlineAdapter
    private lateinit var groupsRepo: OnlineGroupSubscriptionsRepository
    private lateinit var groupAdapter: OnlineGroupAdapter
    private var selectedTab: HomeTab = HomeTab.RECENT
    private var onlineMode: OnlineMode = OnlineMode.GROUPS
    private var onlineLibrary: OnlineLibrary? = null
    private var currentGroup: OnlineGroup? = null
    private var onlineGroupIndex: OnlineGroupIndex? = null
    private var onlineLoadJob: kotlinx.coroutines.Job? = null
    private var groupsLoadJob: kotlinx.coroutines.Job? = null
    private var openOnlineJob: kotlinx.coroutines.Job? = null

    private val openDocumentLauncher = registerForActivityResult(
        PersistableOpenDocument()
    ) { uri: Uri? -> if (uri != null) onPicked(uri, persistable = true) }

    private val getContentLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? -> if (uri != null) onPicked(uri, persistable = false) }

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        recents = RecentsRepository(this)
        settings = AppSettings(this)
        onlineRepo = OnlineLibraryRepository(this)
        groupsRepo = OnlineGroupSubscriptionsRepository(this)
        adapter = RecentsAdapter(
            emptyList(),
            onOpen = { item -> openRecent(item) },
            onToggleFavorite = { item -> toggleFavorite(item) }
        )
        onlineAdapter = OnlineAdapter(
            emptyList(),
            onOpen = { item -> openOnline(item) },
            onToggleFavorite = { row -> toggleFavoriteOnline(row) }
        )
        groupAdapter = OnlineGroupAdapter(
            emptyList(),
            onOpen = { group -> openOnlineGroup(group) },
            onLongPress = { group -> onGroupLongPress(group) }
        )

        val columns = resources.displayMetrics.widthPixels.let { w ->
            // ~360dp wide cards
            val dp = (w / resources.displayMetrics.density).toInt()
            (dp / 360).coerceIn(1, 4)
        }
        binding.recyclerRecents.layoutManager = GridLayoutManager(this, columns)
        binding.recyclerRecents.adapter = adapter
        setupTabs()

        binding.btnOpen.setOnClickListener { pickFile() }
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.btnAddGroup.setOnClickListener { showAddGroupDialog() }

        // Background self-update check. Silent on errors / "no update" so
        // an offline STB simply sees nothing happen.
        UpdateManager(this).checkAndPromptAsync(lifecycleScope, silent = true)
    }

    override fun onResume() {
        super.onResume()
        refreshRecents()
    }

    private fun refreshRecents() {
        val items = recents.list()
        val filtered = when (selectedTab) {
            HomeTab.RECENT -> items
            HomeTab.FAVORITE -> items.filter { it.isFavorite }
            HomeTab.ONLINE -> emptyList()
        }

        // The "+" button only makes sense when picking groups under Online.
        val showAddGroup = (selectedTab == HomeTab.ONLINE && onlineMode == OnlineMode.GROUPS)
        binding.addGroupContainer.visibility = if (showAddGroup) View.VISIBLE else View.GONE

        if (selectedTab == HomeTab.ONLINE) {
            when (onlineMode) {
                OnlineMode.GROUPS -> renderGroupsView()
                OnlineMode.SCORES -> renderScoresView()
            }
            return
        }

        binding.recyclerRecents.adapter = adapter
        adapter.submit(filtered)
        val emptyText = when (selectedTab) {
            HomeTab.RECENT -> getString(R.string.empty_recents)
            HomeTab.FAVORITE -> getString(R.string.empty_favorites)
            HomeTab.ONLINE -> getString(R.string.empty_online)
        }
        binding.emptyState.text = emptyText
        binding.emptyState.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        binding.recyclerRecents.visibility = if (filtered.isEmpty()) View.GONE else View.VISIBLE
    }

    /** GROUPS mode: show the merged list of server + local groups. */
    private fun renderGroupsView() {
        binding.recyclerRecents.adapter = groupAdapter
        val groups = mergedGroups()
        groupAdapter.submit(groups)
        val hasServerData = onlineGroupIndex != null
        val showEmpty = groups.isEmpty()
        binding.emptyState.text = when {
            !hasServerData -> getString(R.string.empty_online_groups_loading)
            showEmpty -> getString(R.string.empty_online_groups)
            else -> ""
        }
        binding.emptyState.visibility = if (showEmpty) View.VISIBLE else View.GONE
        binding.recyclerRecents.visibility = if (showEmpty) View.GONE else View.VISIBLE
        if (!hasServerData) loadOnlineGroups()
    }

    /** SCORES mode: show the scores for `currentGroup`. */
    private fun renderScoresView() {
        binding.recyclerRecents.adapter = onlineAdapter
        val cached = onlineLibrary
        if (cached != null) {
            onlineAdapter.submit(buildOnlineRows(cached))
            val showEmpty = cached.items.isEmpty()
            binding.emptyState.text =
                if (showEmpty) getString(R.string.empty_online_loaded)
                else getString(R.string.empty_online)
            binding.emptyState.visibility = if (showEmpty) View.VISIBLE else View.GONE
            binding.recyclerRecents.visibility = if (showEmpty) View.GONE else View.VISIBLE
        } else {
            onlineAdapter.submit(emptyList())
            binding.emptyState.text = getString(R.string.empty_online_loading)
            binding.emptyState.visibility = View.VISIBLE
            binding.recyclerRecents.visibility = View.GONE
            val group = currentGroup
            if (group != null) loadOnlineLibrary(group.manifestUrl)
        }
    }

    /** Server groups first (server-defined order), then user-added groups
     *  not already present at the same URL. */
    private fun mergedGroups(): List<OnlineGroup> {
        val server = onlineGroupIndex?.groups.orEmpty()
        val seen = HashSet<String>().apply { addAll(server.map { it.manifestUrl }) }
        val local = groupsRepo.listLocal().filter { seen.add(it.manifestUrl) }
        return server + local
    }

    /** Fetches the configured root URL (groups.json). */
    private fun loadOnlineGroups() {
        val url = settings.onlineLibraryUrl
        groupsLoadJob?.cancel()
        groupsLoadJob = lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { onlineRepo.fetchGroups(url) }
            }
            if (selectedTab != HomeTab.ONLINE || onlineMode != OnlineMode.GROUPS) return@launch
            result.onSuccess { index ->
                onlineGroupIndex = index
                val groups = mergedGroups()
                groupAdapter.submit(groups)
                val showEmpty = groups.isEmpty()
                binding.emptyState.text = if (showEmpty)
                    getString(R.string.empty_online_groups)
                else ""
                binding.emptyState.visibility = if (showEmpty) View.VISIBLE else View.GONE
                binding.recyclerRecents.visibility = if (showEmpty) View.GONE else View.VISIBLE
            }.onFailure { e ->
                Log.w(TAG, "Failed to load online groups $url", e)
                // Even if the server is unreachable, surface the user's
                // local groups so they aren't locked out of their own
                // subscriptions.
                val groups = groupsRepo.listLocal()
                groupAdapter.submit(groups)
                if (groups.isEmpty()) {
                    binding.emptyState.text = getString(
                        R.string.err_online_load,
                        e.message ?: e.javaClass.simpleName
                    )
                    binding.emptyState.visibility = View.VISIBLE
                    binding.recyclerRecents.visibility = View.GONE
                } else {
                    binding.emptyState.visibility = View.GONE
                    binding.recyclerRecents.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun loadOnlineLibrary(manifestUrl: String) {
        onlineLoadJob?.cancel()
        onlineLoadJob = lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { onlineRepo.fetch(manifestUrl) }
            }
            if (selectedTab != HomeTab.ONLINE || onlineMode != OnlineMode.SCORES) return@launch
            result.onSuccess { lib ->
                onlineLibrary = lib
                onlineAdapter.submit(buildOnlineRows(lib))
                val showEmpty = lib.items.isEmpty()
                binding.emptyState.text =
                    if (showEmpty) getString(R.string.empty_online_loaded)
                    else getString(R.string.empty_online)
                binding.emptyState.visibility = if (showEmpty) View.VISIBLE else View.GONE
                binding.recyclerRecents.visibility = if (showEmpty) View.GONE else View.VISIBLE
            }.onFailure { e ->
                Log.w(TAG, "Failed to load online library $manifestUrl", e)
                binding.emptyState.text =
                    getString(R.string.err_online_load, e.message ?: e.javaClass.simpleName)
                binding.emptyState.visibility = View.VISIBLE
                binding.recyclerRecents.visibility = View.GONE
            }
        }
    }

    private fun openOnlineGroup(group: OnlineGroup) {
        currentGroup = group
        onlineMode = OnlineMode.SCORES
        onlineLibrary = null
        refreshRecents()
    }

    private fun onGroupLongPress(group: OnlineGroup) {
        if (!group.isLocal) {
            Toast.makeText(this, R.string.msg_group_remove_server, Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_remove_group_title)
            .setMessage(getString(R.string.dialog_remove_group_message, group.title))
            .setPositiveButton(R.string.action_remove) { _, _ ->
                groupsRepo.removeLocal(group.id)
                Toast.makeText(
                    this,
                    getString(R.string.msg_group_removed, group.title),
                    Toast.LENGTH_SHORT
                ).show()
                groupAdapter.submit(mergedGroups())
                refreshRecents()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showAddGroupDialog() {
        val padding = (resources.displayMetrics.density * 16).toInt()
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(padding, padding / 2, padding, 0)
        }
        val nameInput = android.widget.EditText(this).apply {
            hint = getString(R.string.add_group_name_hint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        val urlInput = android.widget.EditText(this).apply {
            hint = getString(R.string.add_group_url_hint)
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        container.addView(nameInput)
        container.addView(urlInput)
        AlertDialog.Builder(this)
            .setTitle(R.string.add_group_title)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = nameInput.text?.toString()?.trim().orEmpty()
                val url = urlInput.text?.toString()?.trim().orEmpty()
                if (url.isEmpty()) return@setPositiveButton
                val group = groupsRepo.addLocal(name, url)
                Toast.makeText(
                    this,
                    getString(R.string.msg_group_added, group.title),
                    Toast.LENGTH_SHORT
                ).show()
                groupAdapter.submit(mergedGroups())
                refreshRecents()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Back key: if we're inside a group's scores list, pop back to the
     *  group list instead of leaving Home. */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (selectedTab == HomeTab.ONLINE && onlineMode == OnlineMode.SCORES) {
            onlineMode = OnlineMode.GROUPS
            currentGroup = null
            onlineLibrary = null
            refreshRecents()
            return
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    private fun openOnline(item: OnlineItem) {
        val lib = onlineLibrary ?: return
        if (openOnlineJob?.isActive == true) return
        // If we already have a verified local copy, skip the network and
        // open straight away. Otherwise stream from the LAN server.
        val cachedUri = onlineRepo.cachedUri(item)
        if (cachedUri != null) {
            recents.add(cachedUri, item.title, persistable = false)
            startViewer(cachedUri, item.title)
            return
        }
        Toast.makeText(this, getString(R.string.msg_downloading, item.title), Toast.LENGTH_SHORT)
            .show()
        openOnlineJob = lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { onlineRepo.download(lib, item) }
            }
            result.onSuccess { uri ->
                // Remember the download as a recent so the user can re-open
                // without going back online.
                recents.add(uri, item.title, persistable = false)
                if (selectedTab == HomeTab.ONLINE) {
                    onlineAdapter.submit(buildOnlineRows(lib))
                }
                startViewer(uri, item.title)
            }.onFailure { e ->
                Log.e(TAG, "Failed to download ${item.path}", e)
                Toast.makeText(
                    this@HomeActivity,
                    getString(R.string.err_online_download, e.message ?: e.javaClass.simpleName),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun buildOnlineRows(lib: OnlineLibrary): List<OnlineRow> {
        val recentByUri = recents.list().associateBy { it.uri.toString() }
        return lib.items.map { item ->
            val cachedUri = onlineRepo.cachedUri(item)
            val cachedKey = cachedUri?.toString()
            val fav = cachedKey?.let { recentByUri[it]?.isFavorite } ?: false
            OnlineRow(item = item, isCached = cachedUri != null, isFavorite = fav)
        }
    }

    private fun toggleFavoriteOnline(row: OnlineRow) {
        val cachedUri = onlineRepo.cachedUri(row.item)
        if (cachedUri == null) {
            Toast.makeText(this, R.string.msg_online_favorite_needs_cache, Toast.LENGTH_SHORT)
                .show()
            return
        }
        // Make sure the cached file has a recent entry so favorite + Recent
        // tab pick it up automatically.
        if (recents.list().none { it.uri.toString() == cachedUri.toString() }) {
            recents.add(cachedUri, row.item.title, persistable = false)
        }
        val nowFavorite = recents.toggleFavorite(cachedUri)
        Toast.makeText(
            this,
            if (nowFavorite) R.string.msg_favorited else R.string.msg_unfavorited,
            Toast.LENGTH_SHORT
        ).show()
        val lib = onlineLibrary
        if (lib != null) onlineAdapter.submit(buildOnlineRows(lib))
    }

    private fun setupTabs() {
        binding.homeTabs.removeAllTabs()
        binding.homeTabs.addTab(binding.homeTabs.newTab().setText(R.string.tab_recent))
        binding.homeTabs.addTab(binding.homeTabs.newTab().setText(R.string.tab_favorite))
        binding.homeTabs.addTab(binding.homeTabs.newTab().setText(R.string.tab_online))
        binding.homeTabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                selectedTab = when (tab.position) {
                    1 -> HomeTab.FAVORITE
                    2 -> HomeTab.ONLINE
                    else -> HomeTab.RECENT
                }
                // Switching to / re-entering Online always starts at the
                // group list — drilling into a group is intentionally a
                // single-screen affair.
                if (selectedTab == HomeTab.ONLINE) {
                    onlineMode = OnlineMode.GROUPS
                    currentGroup = null
                    onlineLibrary = null
                }
                refreshRecents()
            }
            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })
        binding.homeTabs.getTabAt(0)?.select()
    }

    private fun toggleFavorite(item: RecentScore) {
        val nowFavorite = recents.toggleFavorite(item.uri)
        Toast.makeText(
            this,
            if (nowFavorite) R.string.msg_favorited else R.string.msg_unfavorited,
            Toast.LENGTH_SHORT
        ).show()
        refreshRecents()
    }

    // ---------------------------------------------------------------------
    // Open a recent card
    // ---------------------------------------------------------------------

    private fun openRecent(item: RecentScore) {
        if (!recents.isReachable(item)) {
            recents.remove(item.uri)
            refreshRecents()
            Toast.makeText(this, R.string.msg_recent_unreachable, Toast.LENGTH_LONG).show()
            return
        }
        startViewer(item.uri, item.displayName)
    }

    private fun startViewer(uri: Uri, displayName: String?) {
        val targetClass = if (settings.preferVerovio) {
            VerovioMainActivity::class.java
        } else {
            MainActivity::class.java
        }
        val intent = Intent(this, targetClass).apply {
            action = Intent.ACTION_VIEW
            data = uri
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (displayName != null) putExtra(MainActivity.EXTRA_DISPLAY_NAME, displayName)
        }
        startActivity(intent)
    }

    // ---------------------------------------------------------------------
    // Picker callback
    // ---------------------------------------------------------------------

    private fun onPicked(uri: Uri, persistable: Boolean) {
        val display = queryDisplayName(uri) ?: uri.lastPathSegment ?: uri.toString()
        if (persistable) recents.tryPersistRead(uri)
        recents.add(uri, display, persistable)
        refreshRecents()
        startViewer(uri, display)
    }

    private fun queryDisplayName(uri: Uri): String? {
        if (uri.scheme != "content") return uri.lastPathSegment
        return runCatching {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
            }
        }.getOrNull()
    }

    // ---------------------------------------------------------------------
    // 2-level pick flow: SAF -> ACTION_GET_CONTENT. The built-in storage
    // scanner has moved to SettingsActivity ("Scan device for scores").
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
        try {
            openDocumentLauncher.launch(mimeCandidates)
            return
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "ACTION_OPEN_DOCUMENT not supported: ${e.message}")
        } catch (e: Exception) {
            Log.w(TAG, "ACTION_OPEN_DOCUMENT failed: ${e.message}")
        }
        try {
            getContentLauncher.launch("*/*")
            return
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "ACTION_GET_CONTENT not supported: ${e.message}")
        } catch (e: Exception) {
            Log.w(TAG, "ACTION_GET_CONTENT failed: ${e.message}")
        }
        // Neither SAF nor GET_CONTENT — fall back to built-in device scan.
        ensureStoragePermissionThen { showBuiltinFileBrowser() }
    }

    private fun ensureStoragePermissionThen(block: () -> Unit) {
        if (Build.VERSION.SDK_INT in Build.VERSION_CODES.M..32) {
            val granted = ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
            if (granted) block() else storagePermissionLauncher.launch(
                android.Manifest.permission.READ_EXTERNAL_STORAGE
            )
        } else {
            block()
        }
    }

    private fun showBuiltinFileBrowser() {
        Toast.makeText(this, R.string.msg_scanning, Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val files = withContext(Dispatchers.IO) { scanForMusicXml() }
            if (files.isEmpty()) {
                Toast.makeText(this@HomeActivity, R.string.msg_no_files_found, Toast.LENGTH_LONG).show()
                return@launch
            }
            val labels = files.map { it.absolutePath }.toTypedArray()
            AlertDialog.Builder(this@HomeActivity)
                .setTitle(R.string.settings_scan_storage)
                .setItems(labels) { _, which ->
                    val f = files[which]
                    val uri = Uri.fromFile(f)
                    onPicked(uri, persistable = false)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
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

    companion object {
        private const val TAG = "ScoreReader/Home"
    }
}

/**
 * Subclass of OpenDocument that requests *persistable* read access so we
 * can re-open the same content URI on next launch.
 */
class PersistableOpenDocument : ActivityResultContracts.OpenDocument() {
    override fun createIntent(context: Context, input: Array<String>): Intent {
        return super.createIntent(context, input)
            .addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )
    }
}
