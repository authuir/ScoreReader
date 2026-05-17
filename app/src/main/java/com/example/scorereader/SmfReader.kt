package com.example.scorereader

import java.io.File

/**
 * Minimal Standard MIDI File parser. Supports SMF format 0/1, ticks-per-
 * quarter division (the only kind musicxml/verovio emits), Set-Tempo meta
 * events, NoteOn/NoteOff. Everything else is structurally skipped so the
 * tick counter stays accurate.
 *
 * Returns a chronological flat list of [SimpleMidiSynth.NoteEvent]
 * (timeUs is absolute microseconds from track start), plus the total
 * piece length in microseconds.
 */
internal object SmfReader {

    fun read(file: File): Pair<List<SimpleMidiSynth.NoteEvent>, Long> {
        val data = file.readBytes()
        return parse(data)
    }

    private fun parse(data: ByteArray): Pair<List<SimpleMidiSynth.NoteEvent>, Long> {
        val r = Reader(data)
        val headerId = r.readAscii(4)
        if (headerId != "MThd") error("not SMF: header=$headerId")
        val headerLen = r.u32()
        if (headerLen < 6) error("bad MThd len=$headerLen")
        /* format */ r.u16()
        val ntrks = r.u16()
        val division = r.u16()
        if (division and 0x8000 != 0) error("SMPTE division not supported")
        val ppq = division
        // Skip any extra header bytes if header length > 6.
        if (headerLen > 6) r.skip(headerLen - 6)

        // Pass 1: parse every track into a single tick-sorted list of raw
        // events. SMF format 1 splits tempo into track 0, but parsing all
        // tracks into one merge stream covers both formats uniformly.
        val raw = ArrayList<RawEvent>(1024)
        for (t in 0 until ntrks) {
            val chunkId = r.readAscii(4)
            val chunkLen = r.u32()
            val chunkEnd = r.pos + chunkLen
            if (chunkId != "MTrk") {
                r.pos = chunkEnd
                continue
            }
            parseTrack(r, chunkEnd, raw)
            r.pos = chunkEnd
        }
        raw.sortBy { it.tick }

        // Pass 2: walk raw events in tick order, accumulating microseconds
        // through any Set-Tempo events we encounter.
        var curUsPerQ = 500_000 // 120 BPM default
        var prevTick = 0L
        var curUs = 0L
        val out = ArrayList<SimpleMidiSynth.NoteEvent>(raw.size)
        for (re in raw) {
            val deltaTicks = re.tick - prevTick
            if (deltaTicks > 0) {
                curUs += (deltaTicks * curUsPerQ.toLong()) / ppq
                prevTick = re.tick
            }
            when {
                re.isTempo -> { curUsPerQ = re.tempoUsPerQ }
                else -> {
                    val high = re.status and 0xF0
                    val ch = re.status and 0x0F
                    when (high) {
                        0x90 -> {
                            if (re.d2 == 0) {
                                out.add(SimpleMidiSynth.NoteEvent(
                                    curUs, SimpleMidiSynth.NoteEvent.TYPE_OFF, ch, re.d1, 0))
                            } else {
                                out.add(SimpleMidiSynth.NoteEvent(
                                    curUs, SimpleMidiSynth.NoteEvent.TYPE_ON, ch, re.d1, re.d2))
                            }
                        }
                        0x80 -> {
                            out.add(SimpleMidiSynth.NoteEvent(
                                curUs, SimpleMidiSynth.NoteEvent.TYPE_OFF, ch, re.d1, re.d2))
                        }
                    }
                }
            }
        }
        return Pair(out, curUs)
    }

    private fun parseTrack(r: Reader, trkEnd: Int, out: MutableList<RawEvent>) {
        var tick = 0L
        var running = 0
        while (r.pos < trkEnd) {
            val delta = r.vlq()
            tick += delta
            if (r.pos >= trkEnd) break
            var status = r.u8()
            if (status < 0x80) {
                // running status: re-use last and rewind one byte
                r.pos--
                status = running
            } else {
                if (status != 0xFF) running = status
            }
            when (status) {
                0xFF -> {
                    val metaType = r.u8()
                    val len = r.vlq().toInt()
                    if (metaType == 0x51 && len == 3) {
                        val a = r.u8(); val b = r.u8(); val c = r.u8()
                        out.add(RawEvent(
                            tick = tick,
                            status = 0xFF, d1 = 0x51, d2 = 0,
                            isTempo = true,
                            tempoUsPerQ = (a shl 16) or (b shl 8) or c
                        ))
                    } else {
                        r.skip(len)
                    }
                }
                0xF0, 0xF7 -> {
                    val len = r.vlq().toInt()
                    r.skip(len)
                }
                else -> {
                    val high = status and 0xF0
                    when (high) {
                        0x80, 0x90, 0xA0, 0xB0, 0xE0 -> {
                            val d1 = r.u8(); val d2 = r.u8()
                            if (high == 0x80 || high == 0x90) {
                                out.add(RawEvent(tick, status, d1, d2))
                            }
                        }
                        0xC0, 0xD0 -> { r.u8() }
                        else -> {
                            // unknown — best-effort skip to avoid desync
                        }
                    }
                }
            }
        }
    }

    private data class RawEvent(
        val tick: Long,
        val status: Int,
        val d1: Int,
        val d2: Int,
        val isTempo: Boolean = false,
        val tempoUsPerQ: Int = 0
    )

    private class Reader(val data: ByteArray) {
        var pos: Int = 0
        fun u8(): Int {
            val b = data[pos].toInt() and 0xFF
            pos++
            return b
        }
        fun u16(): Int {
            val a = u8(); val b = u8()
            return (a shl 8) or b
        }
        fun u32(): Int {
            val a = u8(); val b = u8(); val c = u8(); val d = u8()
            return (a shl 24) or (b shl 16) or (c shl 8) or d
        }
        fun vlq(): Long {
            var v = 0L
            for (i in 0 until 4) {
                val b = u8()
                v = (v shl 7) or (b and 0x7F).toLong()
                if (b and 0x80 == 0) return v
            }
            return v
        }
        fun readAscii(n: Int): String {
            val s = String(data, pos, n, Charsets.US_ASCII)
            pos += n
            return s
        }
        fun skip(n: Int) { pos += n }
    }
}
