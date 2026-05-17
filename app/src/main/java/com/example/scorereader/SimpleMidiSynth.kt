package com.example.scorereader

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import java.io.File
import kotlin.math.PI
import kotlin.math.sin

/**
 * Tiny self-contained MIDI synthesiser used as a fallback when the device
 * ROM lacks SoniVox / MediaPlayer cannot decode `.mid` files (common on
 * stripped-down Android TV boxes).
 *
 *  - Parses Standard MIDI File format 0 and 1 (no SMPTE division).
 *  - Renders to mono 22.05 kHz PCM via [AudioTrack] streaming mode.
 *  - Sine voices with a short attack/release envelope. Drum channel 10
 *    (0-indexed 9) is silenced — note numbers there are GM drum kit IDs,
 *    not pitches, so without a sample bank they'd sound wrong.
 *  - Up to 64 simultaneous voices; further note-ons are dropped.
 *  - Bounded playback: [play] takes start and end times in ms and stops
 *    automatically when the end is reached so callers can use it for
 *    page-at-a-time playback without external polling.
 */
class SimpleMidiSynth {

    interface Listener {
        fun onStateChange(playing: Boolean)
        fun onError(msg: String)
    }

    var listener: Listener? = null

    private var events: List<NoteEvent> = emptyList()
    private var totalDurationUs: Long = 0

    private var renderThread: Thread? = null
    @Volatile private var shouldStop: Boolean = false
    @Volatile private var playing: Boolean = false

    fun setMidiFile(file: File): Boolean {
        stop()
        return try {
            val (evs, dur) = SmfReader.read(file)
            events = evs
            totalDurationUs = dur
            Log.i(TAG, "MIDI parsed: ${evs.size} events, total ${dur / 1000}ms")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "MIDI parse failed", t)
            events = emptyList()
            totalDurationUs = 0
            listener?.onError("parse: ${t.javaClass.simpleName}: ${t.message}")
            false
        }
    }

    fun totalDurationMs(): Int = (totalDurationUs / 1000).toInt()
    fun eventCount(): Int = events.size
    fun isPlaying(): Boolean = playing

    fun play(fromMs: Int, endMs: Int) {
        stop()
        if (events.isEmpty()) {
            listener?.onError("no events parsed")
            return
        }
        val startUs = fromMs.toLong() * 1000L
        val endUs = if (endMs >= Int.MAX_VALUE / 1000) Long.MAX_VALUE
                    else endMs.toLong() * 1000L
        shouldStop = false
        playing = true
        listener?.onStateChange(true)
        renderThread = Thread({
            try {
                runSynth(startUs, endUs)
            } catch (t: Throwable) {
                Log.e(TAG, "synth thread crashed", t)
                listener?.onError("synth: ${t.javaClass.simpleName}: ${t.message}")
            } finally {
                playing = false
                listener?.onStateChange(false)
            }
        }, "SimpleMidiSynth").also {
            it.isDaemon = true
            it.start()
        }
    }

    fun stop() {
        shouldStop = true
        renderThread?.let {
            try { it.join(500) } catch (_: InterruptedException) {}
        }
        renderThread = null
    }

    fun release() { stop() }

    // ------------------------------------------------------------------
    // Audio rendering
    // ------------------------------------------------------------------

    private fun runSynth(startUs: Long, endUs: Long) {
        val sampleRate = SAMPLE_RATE
        val bufFrames = 1024
        val attr = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val fmt = AudioFormat.Builder()
            .setSampleRate(sampleRate)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufBytes = maxOf(minBuf, bufFrames * 2 * 4)

        val track = AudioTrack(
            attr,
            fmt,
            bufBytes,
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE
        )
        try {
            track.play()
        } catch (t: Throwable) {
            listener?.onError("AudioTrack.play(): ${t.message}")
            track.release()
            return
        }

        val voices = ArrayList<Voice>(64)
        val buf = ShortArray(bufFrames)
        var idx = events.binarySearchByTimeUs(startUs)
        var currentUs = startUs
        val frameUs = (bufFrames * 1_000_000L) / sampleRate

        try {
            while (!shouldStop && currentUs < endUs) {
                val bufEndUs = currentUs + frameUs
                while (idx < events.size && events[idx].timeUs < bufEndUs) {
                    val ev = events[idx++]
                    if (ev.timeUs >= endUs) break
                    applyEvent(ev, voices)
                }
                if (idx >= events.size && voices.isEmpty()) break

                for (i in 0 until bufFrames) {
                    var sample = 0f
                    val it = voices.iterator()
                    while (it.hasNext()) {
                        val v = it.next()
                        sample += v.next(sampleRate)
                        if (v.isFinished()) it.remove()
                    }
                    // Soft clip + master gain. 0.18 keeps a 10-voice piano
                    // chord just shy of clipping.
                    val s = (sample * 0.18f).coerceIn(-1f, 1f)
                    buf[i] = (s * Short.MAX_VALUE).toInt().toShort()
                }
                track.write(buf, 0, bufFrames)
                currentUs += frameUs
            }
        } finally {
            try { track.stop() } catch (_: Throwable) {}
            try { track.release() } catch (_: Throwable) {}
        }
    }

    private fun applyEvent(ev: NoteEvent, voices: MutableList<Voice>) {
        when (ev.type) {
            NoteEvent.TYPE_ON -> {
                if (ev.channel == 9) return // GM drum channel — skip
                if (voices.size >= MAX_VOICES) return
                voices.add(Voice(ev.note, ev.velocity / 127f))
            }
            NoteEvent.TYPE_OFF -> {
                for (v in voices) {
                    if (v.note == ev.note && !v.released) {
                        v.release()
                        break
                    }
                }
            }
        }
    }

    private class Voice(val note: Int, private val velocity: Float) {
        private val freq: Float = (440.0 * Math.pow(2.0, (note - 69) / 12.0)).toFloat()
        private var phase: Double = 0.0
        var released: Boolean = false
            private set
        private var attackSamples: Int = 0
        private var releaseSamples: Int = 0

        fun release() { released = true; releaseSamples = 0 }

        fun isFinished(): Boolean = released && releaseSamples >= RELEASE_LEN

        fun next(sampleRate: Int): Float {
            phase += 2.0 * PI * freq / sampleRate
            if (phase > 2.0 * PI) phase -= 2.0 * PI
            val s = sin(phase).toFloat()
            val env: Float = when {
                released -> {
                    releaseSamples++
                    val t = (releaseSamples.toFloat() / RELEASE_LEN).coerceIn(0f, 1f)
                    (1f - t)
                }
                attackSamples < ATTACK_LEN -> {
                    attackSamples++
                    attackSamples.toFloat() / ATTACK_LEN
                }
                else -> 1f
            }
            return s * env * velocity
        }

        companion object {
            // ~10ms attack, ~80ms release at 22050 Hz
            private const val ATTACK_LEN = 220
            private const val RELEASE_LEN = 1760
        }
    }

    private fun List<NoteEvent>.binarySearchByTimeUs(targetUs: Long): Int {
        var lo = 0; var hi = size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (this[mid].timeUs < targetUs) lo = mid + 1 else hi = mid
        }
        return lo
    }

    data class NoteEvent(
        val timeUs: Long,
        val type: Int,
        val channel: Int,
        val note: Int,
        val velocity: Int
    ) {
        companion object {
            const val TYPE_ON = 1
            const val TYPE_OFF = 2
        }
    }

    companion object {
        private const val TAG = "ScoreReader/Synth"
        private const val SAMPLE_RATE = 22050
        private const val MAX_VOICES = 64
    }
}
