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
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The voice of a call (Camino y Carretera §7): 16 kHz mono 16-bit PCM in 20 ms pieces (640 bytes, 256 kbit/s), plenty
 * for Wi-Fi Direct and with nothing to decode. The phone's own echo canceller and noise suppressor, when it has them.
 */
class AudioEngine(private val context: Context, private val onPiece: (ByteArray) -> Unit) {
    @Volatile private var running = false
    @Volatile var muted = false
    private val incoming = LinkedBlockingQueue<ByteArray>()
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
                if (got == PIECE && !muted) onPiece(buf.copyOf())
            }
        }
        runCatching { r.stop() }; runCatching { r.release() }
    }

    // A small jitter buffer: if the pieces pile up (the island stalled), drop the oldest so the voice stays live.
    private fun play() {
        val t = track ?: return
        runCatching {
            t.play()
            while (running) {
                val p = incoming.poll(200, TimeUnit.MILLISECONDS) ?: continue
                while (incoming.size > MAX_QUEUED) incoming.poll()
                t.write(p, 0, p.size)
            }
        }
        runCatching { t.stop() }; runCatching { t.release() }
    }

    fun onRemote(piece: ByteArray) { if (running && piece.size <= PIECE * 4) incoming.offer(piece) }

    var speaker: Boolean
        get() = audio.isSpeakerphoneOn
        set(on) { audio.isSpeakerphoneOn = on }

    fun stop() {
        if (!running) return
        running = false
        incoming.clear()
        audio.isSpeakerphoneOn = false
        audio.mode = AudioManager.MODE_NORMAL
    }

    companion object {
        const val RATE = 16_000
        /** 20 ms at 16 kHz, 16-bit mono. */
        const val PIECE = RATE / 50 * 2
        /** 160 ms waiting at most. */
        const val MAX_QUEUED = 8
    }
}
