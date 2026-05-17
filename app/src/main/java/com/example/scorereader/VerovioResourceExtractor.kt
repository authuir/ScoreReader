package com.example.scorereader

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

/**
 * Verovio needs its data directory (Bravura SVG glyphs, fonts, etc.) to be
 * available as a regular filesystem path at runtime; it can't read straight
 * out of the APK. We ship the directory bundled and gzipped as
 * `assets/verovio-data.zip` and inflate it once into `filesDir/verovio-data/`.
 * The marker file inside the directory keeps the unzip a no-op on subsequent
 * launches as long as the app's versionCode hasn't changed.
 */
class VerovioResourceExtractor(private val context: Context) {

    private val targetDir: File = File(context.filesDir, DIR_NAME)
    private val marker: File = File(targetDir, MARKER_FILE)

    /** Returns the path to use with `vrvToolkit_setResourcePath`. */
    fun ensureExtracted(): String {
        val stamp = versionStamp()
        if (marker.isFile && marker.readText() == stamp && targetDir.isDirectory) {
            return targetDir.absolutePath
        }
        val t0 = System.currentTimeMillis()
        Log.i(TAG, "Extracting Verovio data to $targetDir")
        if (targetDir.exists()) targetDir.deleteRecursively()
        targetDir.mkdirs()

        var fileCount = 0
        var byteCount = 0L
        context.assets.open(ASSET_ZIP).use { input ->
            ZipInputStream(input).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val out = File(targetDir, entry.name)
                    if (entry.isDirectory) {
                        out.mkdirs()
                    } else {
                        out.parentFile?.mkdirs()
                        FileOutputStream(out).use { fos ->
                            byteCount += zis.copyTo(fos)
                        }
                        fileCount++
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        }
        marker.writeText(stamp)
        val ms = System.currentTimeMillis() - t0
        Log.i(TAG, "Verovio data extracted: $fileCount files, ${byteCount / 1024}KB in ${ms}ms")
        return targetDir.absolutePath
    }

    private fun versionStamp(): String {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return "${info.versionCode}-${info.versionName}"
    }

    companion object {
        private const val TAG = "ScoreReader/VrvExt"
        private const val DIR_NAME = "verovio-data"
        private const val MARKER_FILE = ".version"
        private const val ASSET_ZIP = "verovio-data.zip"
    }
}
