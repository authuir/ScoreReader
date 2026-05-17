package com.example.scorereader

/**
 * Minimal JNI bindings to Verovio's C wrapper. The single shared library
 * `libscorereader-verovio.so` ships Verovio's full C++ engine + its
 * `tools/c_wrapper.cpp` + our `verovio-jni.cpp` shim.
 *
 * The opaque [Long] handle returned by [nativeCreate] must be passed to all
 * other methods. The owning side is responsible for calling [nativeDestroy].
 */
object VerovioNative {

    init {
        System.loadLibrary("scorereader-verovio")
    }

    external fun nativeCreate(resourcePath: String): Long
    external fun nativeDestroy(handle: Long)
    external fun nativeSetResourcePath(handle: Long, path: String): Boolean
    external fun nativeSetOptions(handle: Long, jsonOptions: String): Boolean
    external fun nativeLoadData(handle: Long, data: String): Boolean
    external fun nativeLoadZipBuffer(handle: Long, data: ByteArray): Boolean
    external fun nativeGetPageCount(handle: Long): Int
    external fun nativeRenderToSvg(handle: Long, pageNo: Int): String
    external fun nativeGetVersion(handle: Long): String
    external fun nativeGetLog(handle: Long): String
    external fun nativeEnableLog(enabled: Boolean)

    /**
     * Renders the loaded score to a Standard MIDI File (`.mid`) at
     * [filename]. Returns `false` if the toolkit hasn't loaded a score
     * yet, or if the file couldn't be written.
     */
    external fun nativeRenderToMidiFile(handle: Long, filename: String): Boolean

    /**
     * Returns the real-time onset of the element with the given xml:id,
     * in milliseconds from the start of the piece. Returns -1 if the
     * element can't be found or the toolkit has no score loaded.
     */
    external fun nativeGetTimeForElement(handle: Long, xmlId: String): Int
}
