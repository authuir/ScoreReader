package com.example.scorereader

import android.Manifest
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
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.example.scorereader.databinding.ActivityHomeBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class HomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHomeBinding
    private lateinit var recents: RecentsRepository
    private lateinit var adapter: RecentsAdapter

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
        adapter = RecentsAdapter(emptyList()) { item -> openRecent(item) }

        val columns = resources.displayMetrics.widthPixels.let { w ->
            // ~360dp wide cards
            val dp = (w / resources.displayMetrics.density).toInt()
            (dp / 360).coerceIn(1, 4)
        }
        binding.recyclerRecents.layoutManager = GridLayoutManager(this, columns)
        binding.recyclerRecents.adapter = adapter

        binding.btnOpen.setOnClickListener { pickFile() }
        binding.btnScan.setOnClickListener {
            ensureStoragePermissionThen { showBuiltinFileBrowser() }
        }
        binding.btnEngine.setOnClickListener { toggleEngine() }
        refreshEngineLabel()
    }

    override fun onResume() {
        super.onResume()
        refreshRecents()
    }

    private fun refreshRecents() {
        val items = recents.list()
        adapter.submit(items)
        binding.emptyState.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        binding.recyclerRecents.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
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
        val targetClass = if (preferVerovio()) {
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
    // Engine toggle (WebView vs Verovio)
    // ---------------------------------------------------------------------

    private fun engineSharedPrefs() =
        getSharedPreferences("score_reader_engine", Context.MODE_PRIVATE)

    private fun preferVerovio(): Boolean =
        engineSharedPrefs().getString("engine", "webview") == "verovio"

    private fun toggleEngine() {
        val now = if (preferVerovio()) "webview" else "verovio"
        engineSharedPrefs().edit().putString("engine", now).apply()
        refreshEngineLabel()
        Toast.makeText(
            this,
            getString(if (now == "verovio") R.string.engine_verovio else R.string.engine_webview),
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun refreshEngineLabel() {
        binding.btnEngine.setText(
            if (preferVerovio()) R.string.engine_verovio else R.string.engine_webview
        )
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
    // 3-level pick flow: SAF -> ACTION_GET_CONTENT -> built-in scanner
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
        Toast.makeText(this, R.string.msg_scanning, Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val files = withContext(Dispatchers.IO) { scanForMusicXml() }
            if (files.isEmpty()) {
                Toast.makeText(this@HomeActivity, R.string.msg_no_files_found, Toast.LENGTH_LONG).show()
                return@launch
            }
            val labels = files.map { it.absolutePath }.toTypedArray()
            AlertDialog.Builder(this@HomeActivity)
                .setTitle(R.string.action_scan)
                .setItems(labels) { _, which ->
                    onPicked(Uri.fromFile(files[which]), persistable = false)
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
