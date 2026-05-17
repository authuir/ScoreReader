package com.example.scorereader

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * Self-update flow.
 *
 *   1. Fetches a tiny JSON manifest at [AppSettings.updateManifestUrl].
 *      Expected shape:
 *      ```json
 *      {
 *        "versionCode": 5,
 *        "versionName": "0.5.0",
 *        "apkUrl": "https://.../app-release.apk",
 *        "releaseNotes": "..."   // optional
 *      }
 *      ```
 *   2. If `versionCode` > `BuildConfig.VERSION_CODE` (and the user hasn't
 *      already dismissed *that exact* code via the "skip this version"
 *      shortcut), prompts via [AlertDialog].
 *   3. On confirmation, downloads the APK into `cacheDir/updates/` and
 *      fires `ACTION_INSTALL_PACKAGE` (or the legacy `ACTION_VIEW` install
 *      intent on pre-N devices).
 *
 * All network work is on [Dispatchers.IO]; UI work (dialogs, toasts) is
 * marshalled back to the main thread. Cancel by cancelling the returned
 * [Job] from [checkAndPromptAsync].
 */
class UpdateManager(private val activity: Activity) {

    private val settings = AppSettings(activity)
    private val mainHandler = Handler(Looper.getMainLooper())

    data class UpdateInfo(
        val versionCode: Int,
        val versionName: String,
        val apkUrl: String,
        val releaseNotes: String?
    )

    /**
     * Kick off a background update check. If a newer build is found, shows
     * the confirmation dialog on the main thread automatically.
     *
     * @param silent when true, errors and "no update available" are
     *               swallowed (typical for the auto-check on startup). When
     *               false, errors and "you're up to date" are surfaced via
     *               toast/dialog (typical for a manual "Check now" button).
     */
    fun checkAndPromptAsync(scope: CoroutineScope, silent: Boolean): Job = scope.launch {
        val url = settings.updateManifestUrl
        val info = try {
            withContext(Dispatchers.IO) { fetchManifest(url) }
        } catch (t: Throwable) {
            Log.w(TAG, "Update check failed at $url", t)
            if (!silent) {
                mainHandler.post {
                    toast(activity.getString(R.string.update_check_failed, t.message ?: t.javaClass.simpleName))
                }
            }
            return@launch
        }

        val current = BuildConfig.VERSION_CODE
        if (info.versionCode <= current) {
            if (!silent) {
                mainHandler.post {
                    toast(activity.getString(R.string.update_no_update, BuildConfig.VERSION_NAME))
                }
            }
            return@launch
        }
        if (silent && settings.skippedUpdateVersionCode == info.versionCode) {
            // User explicitly skipped this exact version; honour that.
            return@launch
        }
        mainHandler.post { showUpdateDialog(info) }
    }

    // ------------------------------------------------------------------

    private fun showUpdateDialog(info: UpdateInfo) {
        if (activity.isFinishing) return
        val notes = info.releaseNotes?.takeIf { it.isNotBlank() }
        val msg = buildString {
            append(activity.getString(
                R.string.update_dialog_message,
                BuildConfig.VERSION_NAME,
                info.versionName
            ))
            if (notes != null) {
                append("\n\n")
                append(notes)
            }
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.update_dialog_title)
            .setMessage(msg)
            .setPositiveButton(R.string.update_dialog_install) { _, _ ->
                downloadAndInstall(info)
            }
            .setNeutralButton(R.string.update_dialog_skip) { _, _ ->
                settings.skippedUpdateVersionCode = info.versionCode
            }
            .setNegativeButton(R.string.update_dialog_later, null)
            .show()
    }

    private fun downloadAndInstall(info: UpdateInfo) {
        val progress = AlertDialog.Builder(activity)
            .setTitle(R.string.update_downloading_title)
            .setMessage(activity.getString(R.string.update_downloading_message, 0))
            .setCancelable(false)
            .create()
        progress.show()

        Thread({
            val apkFile = try {
                downloadApk(info.apkUrl) { read, total ->
                    if (total > 0) {
                        val pct = ((read * 100L) / total).toInt().coerceIn(0, 100)
                        mainHandler.post {
                            progress.setMessage(
                                activity.getString(R.string.update_downloading_message, pct)
                            )
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "APK download failed", t)
                mainHandler.post {
                    progress.dismiss()
                    AlertDialog.Builder(activity)
                        .setTitle(R.string.update_failed_title)
                        .setMessage(
                            activity.getString(
                                R.string.update_failed_message,
                                t.message ?: t.javaClass.simpleName
                            )
                        )
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
                return@Thread
            }
            mainHandler.post {
                progress.dismiss()
                launchInstaller(apkFile)
            }
        }, "UpdateDownloader").also { it.isDaemon = true; it.start() }
    }

    private fun launchInstaller(apkFile: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !activity.packageManager.canRequestPackageInstalls()
        ) {
            // Send the user to system settings to grant the install
            // permission first, then they can retry from the launcher.
            toast(activity.getString(R.string.update_grant_install_required))
            val intent = Intent(
                android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${activity.packageName}")
            )
            try {
                activity.startActivity(intent)
            } catch (_: Throwable) {
                toast(activity.getString(R.string.update_grant_install_required))
            }
            return
        }

        // Primary path: PackageInstaller session. Works on stripped-down
        // STB / TV ROMs that don't register an Activity for ACTION_VIEW
        // with vnd.android.package-archive (which throws
        // "No activity found to handle intent").
        if (installViaPackageInstaller(apkFile)) return

        // Fallback: legacy ACTION_VIEW path for ROMs where the
        // PackageInstaller route is somehow unavailable.
        val intent = Intent(Intent.ACTION_VIEW).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val uri = FileProvider.getUriForFile(
                activity,
                "${activity.packageName}.updates.fileprovider",
                apkFile
            )
            setDataAndType(uri, "application/vnd.android.package-archive")
        }
        try {
            activity.startActivity(intent)
        } catch (t: Throwable) {
            Log.e(TAG, "Installer launch failed", t)
            toast(activity.getString(R.string.update_install_launch_failed, t.message ?: ""))
        }
    }

    /**
     * Installs [apkFile] through the [PackageInstaller] session API.
     *
     * Returns true if the session was successfully created, written and
     * committed. The actual install result is reported asynchronously via
     * [UpdateInstallReceiver] (system shows its own confirmation dialog).
     */
    private fun installViaPackageInstaller(apkFile: File): Boolean {
        return try {
            val pi = activity.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL
            ).apply {
                setAppPackageName(activity.packageName)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
                }
            }
            val sessionId = pi.createSession(params)
            pi.openSession(sessionId).use { session ->
                apkFile.inputStream().use { input ->
                    session.openWrite("scorereader_update", 0, apkFile.length()).use { out ->
                        input.copyTo(out)
                        session.fsync(out)
                    }
                }
                session.commit(buildStatusSender(sessionId))
            }
            true
        } catch (t: Throwable) {
            Log.e(TAG, "PackageInstaller install failed", t)
            toast(activity.getString(R.string.update_install_launch_failed, t.message ?: ""))
            false
        }
    }

    private fun buildStatusSender(sessionId: Int): android.content.IntentSender {
        val intent = Intent(activity, UpdateInstallReceiver::class.java).apply {
            action = UpdateInstallReceiver.ACTION_INSTALL_STATUS
            setPackage(activity.packageName)
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // The system fills EXTRA_INTENT etc. into the broadcast intent,
            // so PendingIntent must be mutable on API 31+.
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        return PendingIntent.getBroadcast(activity, sessionId, intent, flags).intentSender
    }

    // ------------------------------------------------------------------
    // Network
    // ------------------------------------------------------------------

    @Throws(IOException::class)
    private fun fetchManifest(urlString: String): UpdateInfo {
        val conn = openConnection(urlString)
        conn.requestMethod = "GET"
        conn.connectTimeout = TIMEOUT_MS
        conn.readTimeout = TIMEOUT_MS
        conn.setRequestProperty("Accept", "application/json")
        conn.connect()
        val code = conn.responseCode
        if (code !in 200..299) {
            conn.disconnect()
            throw IOException("HTTP $code from $urlString")
        }
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()
        val json = JSONObject(body)
        val versionCode = json.optInt("versionCode", -1)
        val versionName = json.optString("versionName", "")
        val apkUrl = json.optString("apkUrl", "")
        if (versionCode < 0 || apkUrl.isEmpty()) {
            throw IOException("Manifest missing versionCode/apkUrl")
        }
        val notes = json.optString("releaseNotes").takeIf { it.isNotBlank() }
        return UpdateInfo(versionCode, versionName, apkUrl, notes)
    }

    @Throws(IOException::class)
    private fun downloadApk(
        urlString: String,
        onProgress: (read: Long, total: Long) -> Unit
    ): File {
        val dir = File(activity.cacheDir, "updates").apply { mkdirs() }
        // Wipe any previous downloads so we don't leave stale APKs lying
        // around the cache dir indefinitely.
        dir.listFiles()?.forEach { if (it.isFile) it.delete() }

        val outFile = File(dir, "scorereader-update.apk")
        val conn = openConnection(urlString)
        conn.requestMethod = "GET"
        conn.connectTimeout = TIMEOUT_MS
        conn.readTimeout = TIMEOUT_MS * 2
        conn.instanceFollowRedirects = true
        conn.connect()
        val code = conn.responseCode
        if (code !in 200..299) {
            conn.disconnect()
            throw IOException("HTTP $code from $urlString")
        }
        // contentLengthLong is API 24+ — we support API 23, so parse the header
        // ourselves and fall back to the (Int) contentLength field.
        val total = conn.getHeaderField("Content-Length")?.toLongOrNull()
            ?: conn.contentLength.toLong().takeIf { it > 0 }
            ?: -1L
        var read: Long = 0
        conn.inputStream.use { input ->
            outFile.outputStream().use { output ->
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    output.write(buf, 0, n)
                    read += n
                    onProgress(read, total)
                }
            }
        }
        conn.disconnect()
        return outFile
    }

    private fun openConnection(urlString: String): HttpURLConnection {
        val url = URL(urlString)
        val conn = url.openConnection() as HttpURLConnection
        if (conn is HttpsURLConnection) {
            // TlsTrustInstaller has already installed our augmented trust
            // manager as the JVM default, so no per-connection setup needed.
        }
        return conn
    }

    private fun toast(msg: String) {
        android.widget.Toast.makeText(activity, msg, android.widget.Toast.LENGTH_LONG).show()
    }

    companion object {
        private const val TAG = "ScoreReader/Update"
        private const val TIMEOUT_MS = 15_000
    }
}
