package com.example.scorereader

import android.os.Handler
import android.os.Looper
import java.io.File

/**
 * Plays the MIDI rendering of a Verovio score *one page at a time*.
 *
 * Backed by [SimpleMidiSynth] (an in-process sine-wave MIDI synthesiser)
 * because many Android TV STB ROMs ship without SoniVox and so cannot
 * play `.mid` files through the system MediaPlayer.
 *
 *  - [setMidiFile] sets the source `.mid` file (rendered by Verovio's
 *    [VerovioNative.nativeRenderToMidiFile]).
 *  - [setPageStartMs] feeds in each page's MIDI onset, measured in ms
 *    from the start of the piece. Page N's playback range is
 *    `[startMs(N), startMs(N+1))` (or `[startMs(N), totalDuration)` for
 *    the last page).
 *  - [toggle] starts / pauses playback bounded to the given page. The
 *    synthesiser stops automatically when it crosses the page boundary.
 *
 * Lifecycle: call [pauseIfPlaying] from `onPause`, [release] from
 * `onDestroy`.
 */
class PagePlayer {

    private val synth = SimpleMidiSynth()
    private var midiFile: File? = null
    private val pageStartMs = HashMap<Int, Int>()
    private var activePage: Int = -1
    private val handler = Handler(Looper.getMainLooper())

    /** Notified whenever the playing/paused state visibly changes. */
    var onStateChange: ((playing: Boolean) -> Unit)? = null

    /** Notified when the synthesiser fails (parse or audio init). */
    var onError: ((String) -> Unit)? = null

    /** Last error string seen since the most recent [toggle] call. */
    var lastError: String? = null
        private set

    init {
        synth.listener = object : SimpleMidiSynth.Listener {
            override fun onStateChange(playing: Boolean) {
                handler.post { onStateChange?.invoke(playing) }
            }
            override fun onError(msg: String) {
                lastError = msg
                handler.post { onError?.invoke(msg) }
            }
        }
    }

    fun setMidiFile(file: File) {
        midiFile = file
        if (!synth.setMidiFile(file)) {
            lastError = "synth.setMidiFile() returned false"
        }
    }

    fun setPageStartMs(page: Int, ms: Int) {
        if (page < 1 || ms < 0) return
        pageStartMs[page] = ms
    }

    fun clearPageBounds() {
        pageStartMs.clear()
    }

    /** Diagnostic helper: returns the cached MIDI onset (ms) for [page] or
     *  -1 if no start has been resolved yet. */
    fun debugPageStartMs(page: Int): Int = pageStartMs[page] ?: -1

    /** Diagnostic snapshot of current synth state. */
    fun debugState(): String =
        "synth playing=${synth.isPlaying()} events=${synth.eventCount()} dur=${synth.totalDurationMs()}ms"

    fun isPlaying(): Boolean = synth.isPlaying()

    /**
     * Single-button "play current page / pause" toggle.
     *
     *  - First press on a page (or after pause / different page): start
     *    playback at that page's start time, bounded by the next page's
     *    start time (or end-of-piece for the last page).
     *  - Press while playing the same page: stop playback.
     *
     * Returns `true` if the press was consumed.
     */
    fun toggle(page: Int): Boolean {
        lastError = null
        if (midiFile == null) { lastError = "midiFile==null"; return false }
        val startMs = pageStartMs[page] ?: run {
            lastError = "no startMs for page=$page"; return false
        }
        val endMs = pageStartMs[page + 1] ?: Int.MAX_VALUE

        if (synth.isPlaying() && activePage == page) {
            synth.stop()
            return true
        }
        synth.stop()
        activePage = page
        synth.play(startMs, endMs)
        return true
    }

    /** Pause playback if active. Cheap no-op if already paused. */
    fun pauseIfPlaying() {
        if (synth.isPlaying()) synth.stop()
    }

    fun release() {
        synth.release()
        activePage = -1
    }
}
