// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-CHAMULLO-Commercial
// Copyright (C) 2026 Jesús Nicolás Astorga y RESOURCES OPEN DOORS S.A.S
package ar.chamullo.app

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import ar.chamullo.core.Voice

/**
 * The voice of a call (Camino y Carretera §7): 16 kHz mono 16-bit PCM in 20 ms pieces (640 bytes, 256 kbit/s), plenty
 * for Wi-Fi Direct and with nothing to decode. The phone's own echo canceller and noise suppressor, when it has them.
 */
class AudioEngine(private val context: Context, private val onPiece: (ByteArray) -> Unit) {
    @Volatile private var running = false
    @Volatile var muted = false
    // §7.3: numbered pieces, each sent three times; the receiver orders them and plays with a margin.
    private val packer = Voice.Packer()
    private val unpacker = Voice.Unpacker()
    private val playout = Voice.Playout(delay = MARGIN)
    private var legacySeq = 0L
    private var record: AudioRecord? = null
    private var track: AudioTrack? = null
    private val audio = context.getSystemService(AudioManager::class.java)

    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        running = true
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        val inMin = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = runCatching { AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(inMin, PIECE * 4)) }.getOrNull()?.takeIf { it.state == AudioRecord.STATE_INITIALIZED }
        record = rec
        rec?.let { r ->
            if (AcousticEchoCanceler.isAvailable()) runCatching { AcousticEchoCanceler.create(r.audioSessionId)?.enabled = true }
            if (NoiseSuppressor.isAvailable()) runCatching { NoiseSuppressor.create(r.audioSessionId)?.enabled = true }
        }
        val outMin = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(maxOf(outMin, PIECE * 4)).setTransferMode(AudioTrack.MODE_STREAM).build()
        Thread { capture() }.apply { name = "call-mic"; isDaemon = true; priority = Thread.MAX_PRIORITY }.start()
        Thread { play() }.apply { name = "call-speaker"; isDaemon = true; priority = Thread.MAX_PRIORITY }.start()
    }

    private fun capture() {
        val r = record ?: return
        runCatching {
            r.startRecording()
            val buf = ByteArray(PIECE)
            while (running) {
                var got = 0
                while (got < PIECE && running) { val n = r.read(buf, got, PIECE - got); if (n <= 0) break; got += n }
                if (got == PIECE && !muted) onPiece(packer.next(buf))
            }
        }
        runCatching { r.stop() }; runCatching { r.release() }
    }

    // The jitter buffer (Camino y Carretera §7.3): one piece every 20 ms, in order. A piece that never came is filled with
    // the last one at half volume, then silence; with nothing at all, silence, and after a second of it, gather again.
    private fun play() {
        val t = track ?: return
        val silence = ByteArray(PIECE)
        var last: ByteArray? = null
        var missing = 0
        var dry = 0
        runCatching {
            t.play()
            while (running) {
                val next = playout.next()
                val out = when {
                    next == null -> { if (++dry > DRY_RESET) { playout.reset(); dry = 0; last = null }; silence }
                    next.second != null -> { dry = 0; missing = 0; last = next.second; next.second!! }
                    else -> { dry = 0; if (missing++ == 0 && last != null) softer(last!!) else silence }
                }
                t.write(out, 0, out.size) // blocks: the speaker sets the 20 ms pace
            }
        }
        runCatching { t.stop() }; runCatching { t.release() }
    }

    // Half volume: 16-bit little-endian samples.
    private fun softer(piece: ByteArray): ByteArray {
        val out = piece.copyOf()
        var i = 0
        while (i + 1 < out.size) {
            val v = ((out[i + 1].toInt() shl 8) or (out[i].toInt() and 0xff)).toShort() / 2
            out[i] = v.toByte(); out[i + 1] = (v shr 8).toByte()
            i += 2
        }
        return out
    }

    fun onRemote(payload: ByteArray) {
        if (!running) return
        if (Voice.isLegacy(payload)) { playout.put(legacySeq++, payload); return } // a phone before 0.9.9
        for ((seq, pcm) in unpacker.accept(payload)) playout.put(seq, pcm)
    }

    var speaker: Boolean
        get() = audio.isSpeakerphoneOn
        set(on) { audio.isSpeakerphoneOn = on }

    fun stop() {
        if (!running) return
        running = false
        playout.reset()
        audio.isSpeakerphoneOn = false
        audio.mode = AudioManager.MODE_NORMAL
    }

    companion object {
        const val RATE = 16_000
        /** 20 ms at 16 kHz, 16-bit mono. */
        const val PIECE = RATE / 50 * 2
        /** 100 ms of margin: time for a piece's second and third copies to arrive. */
        const val MARGIN = 5
        /** A second without voice: gather the margin again before sounding. */
        const val DRY_RESET = 50
    }
}
