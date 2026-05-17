package com.example.scorereader

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.scorereader.databinding.ActivitySettingsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Single-screen preferences UI:
 *   - Pick the rendering engine (WebView + OSMD or native Verovio)
 *   - Pick a default zoom for each engine
 *   - Toggle dark mode (immediately recreates activities)
 *   - Trigger the legacy device-wide score scanner
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var settings: AppSettings
    private lateinit var recents: RecentsRepository

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
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settings = AppSettings(this)
        recents = RecentsRepository(this)

        wireEngineRadio()
        wireWebViewZoom()
        wireVerovioScale()
        wireDarkModeSwitch()
        wireScanButton()
        wireOnlineUrl()
        wireUpdate()
    }

    // ---------------------------------------------------------------------
    // Engine
    // ---------------------------------------------------------------------

    private fun wireEngineRadio() {
        val checked =
            if (settings.preferVerovio) binding.radioEngineVerovio.id
            else binding.radioEngineWebview.id
        binding.groupEngine.check(checked)
        binding.groupEngine.setOnCheckedChangeListener { _, id ->
            settings.engine = when (id) {
                binding.radioEngineVerovio.id -> AppSettings.ENGINE_VEROVIO
                else -> AppSettings.ENGINE_WEBVIEW
            }
        }
    }

    // ---------------------------------------------------------------------
    // Zoom sliders
    // ---------------------------------------------------------------------

    // SeekBar.progress is 0..max. We map this onto:
    //   - WebView OSMD zoom: 0.25..2.5  (slider value = zoom*100 - 25, max=225)
    //   - Verovio scale:     20..80     (slider value = scale - 20,    max=60)

    private fun wireWebViewZoom() {
        val initialPct = (settings.webViewZoom * 100f).toInt()
            .coerceIn((AppSettings.WEBVIEW_ZOOM_MIN * 100).toInt(),
                      (AppSettings.WEBVIEW_ZOOM_MAX * 100).toInt())
        binding.seekWebviewZoom.progress = initialPct - (AppSettings.WEBVIEW_ZOOM_MIN * 100).toInt()
        binding.labelWebviewZoom.text =
            getString(R.string.settings_zoom_webview_label, initialPct)

        binding.seekWebviewZoom.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                val pct = p + (AppSettings.WEBVIEW_ZOOM_MIN * 100).toInt()
                binding.labelWebviewZoom.text =
                    getString(R.string.settings_zoom_webview_label, pct)
                if (fromUser) {
                    settings.webViewZoom = pct / 100f
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) = Unit
            override fun onStopTrackingTouch(sb: SeekBar?) = Unit
        })
    }

    private fun wireVerovioScale() {
        val initial = settings.verovioScale
            .coerceIn(AppSettings.VEROVIO_SCALE_MIN, AppSettings.VEROVIO_SCALE_MAX)
        binding.seekVerovioScale.progress = initial - AppSettings.VEROVIO_SCALE_MIN
        binding.labelVerovioScale.text =
            getString(R.string.settings_zoom_verovio_label, initial)

        binding.seekVerovioScale.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                val v = p + AppSettings.VEROVIO_SCALE_MIN
                binding.labelVerovioScale.text =
                    getString(R.string.settings_zoom_verovio_label, v)
                if (fromUser) {
                    settings.verovioScale = v
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) = Unit
            override fun onStopTrackingTouch(sb: SeekBar?) = Unit
        })
    }

    // ---------------------------------------------------------------------
    // Dark mode
    // ---------------------------------------------------------------------

    private fun wireDarkModeSwitch() {
        binding.switchDarkMode.isChecked = settings.darkMode
        binding.switchDarkMode.setOnCheckedChangeListener { _, checked ->
            settings.darkMode = checked
            AppCompatDelegate.setDefaultNightMode(
                if (checked) AppCompatDelegate.MODE_NIGHT_YES
                else AppCompatDelegate.MODE_NIGHT_NO
            )
            // setDefaultNightMode recreates running activities automatically.
        }
    }

    // ---------------------------------------------------------------------
    // Scan storage (moved here from HomeActivity)
    // ---------------------------------------------------------------------

    private fun wireScanButton() {
        binding.btnScanStorage.setOnClickListener {
            ensureStoragePermissionThen { showBuiltinFileBrowser() }
        }
    }

    private fun wireOnlineUrl() {
        binding.editOnlineUrl.setText(settings.onlineLibraryUrl)
        binding.editOnlineUrl.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                settings.onlineLibraryUrl = s?.toString().orEmpty()
            }
        })
    }

    private fun wireUpdate() {
        binding.editUpdateUrl.setText(settings.updateManifestUrl)
        binding.editUpdateUrl.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                settings.updateManifestUrl = s?.toString().orEmpty()
            }
        })
        binding.btnCheckUpdate.setOnClickListener {
            // Clear the "skipped" memory so an explicit user-initiated check
            // always offers any available newer version.
            settings.skippedUpdateVersionCode = 0
            UpdateManager(this).checkAndPromptAsync(lifecycleScope, silent = false)
        }
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
                Toast.makeText(this@SettingsActivity, R.string.msg_no_files_found, Toast.LENGTH_LONG).show()
                return@launch
            }
            val labels = files.map { it.absolutePath }.toTypedArray()
            AlertDialog.Builder(this@SettingsActivity)
                .setTitle(R.string.settings_scan_storage)
                .setItems(labels) { _, which ->
                    val f = files[which]
                    val uri = Uri.fromFile(f)
                    recents.add(uri, f.name, persistable = false)
                    val targetClass =
                        if (settings.preferVerovio) VerovioMainActivity::class.java
                        else MainActivity::class.java
                    startActivity(Intent(this@SettingsActivity, targetClass).apply {
                        action = Intent.ACTION_VIEW
                        data = uri
                        putExtra(MainActivity.EXTRA_DISPLAY_NAME, f.name)
                    })
                    finish()
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
        private const val TAG = "ScoreReader/Settings"
    }
}
