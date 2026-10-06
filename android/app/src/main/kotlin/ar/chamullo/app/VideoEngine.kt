// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-CHAMULLO-Commercial
// Copyright (C) 2026 Jesús Nicolás Astorga y RESOURCES OPEN DOORS S.A.S
package ar.chamullo.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Range
import android.view.Surface
import android.view.TextureView

/**
 * The picture of a video call (Camino y Carretera §7): the camera goes into the phone's own H.264 encoder and each
 * encoded piece travels as a VIDEO frame; the other side decodes it with its own decoder onto the screen. No libraries.
 *
 * A piece is `flags (1 B) | rotation / 90 (1 B) | bytes`. Flags: [CONFIG], [KEY], [CAMERA_OFF], [WANT_KEY].
 */
class VideoEngine(private val context: Context, private val onPiece: (ByteArray) -> Unit) {
    private val thread = HandlerThread("call-video").apply { start() }
    private val handler = Handler(thread.looper)
    private val cameras = context.getSystemService(CameraManager::class.java)

    private var encoder: MediaCodec? = null
    private var encoderSurface: Surface? = null
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var preview: Surface? = null
    private var config: ByteArray? = null
    @Volatile var front = true; private set
    @Volatile var cameraOn = false; private set
    private var rotation = 0
    @Volatile private var running = true

    /* ---------- sending: camera → encoder ---------- */

    /** Starts my camera into the encoder, and into [local] as a small preview. */
    fun startCamera(local: SurfaceTexture?) = handler.post {
        if (!running) return@post
        if (encoder == null) startEncoder()
        preview = local?.let { it.setDefaultBufferSize(WIDTH, HEIGHT); Surface(it) }
        openCamera()
    }

    fun stopCamera() = handler.post {
        closeCamera()
        cameraOn = false
        onPiece(byteArrayOf(CAMERA_OFF.toByte(), 0))
    }

    fun switchCamera(local: SurfaceTexture?) = handler.post {
        front = !front
        closeCamera()
        preview = local?.let { it.setDefaultBufferSize(WIDTH, HEIGHT); Surface(it) }
        openCamera()
    }

    /** The other side needs a key frame to start (it just turned on, or lost pieces). */
    fun keyFrameWanted() = handler.post {
        encoder?.let { e -> runCatching { e.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) } }
    }

    private fun startEncoder() {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, WIDTH, HEIGHT).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
        }
        val e = runCatching { MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC) }.getOrNull() ?: return
        e.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit
            override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                runCatching {
                    val buf = codec.getOutputBuffer(index)
                    if (buf != null && info.size > 0) {
                        val bytes = ByteArray(info.size).also { buf.position(info.offset); buf.get(it) }
                        when {
                            info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0 -> { config = bytes; send(CONFIG, bytes) }
                            info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0 -> { config?.let { send(CONFIG, it) }; send(KEY, bytes) }
                            else -> send(0, bytes)
                        }
                    }
                    codec.releaseOutputBuffer(index, false)
                }
            }
            override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) { FieldLog.add("LLAMADA", "el codificador de video falló: ${e.message}") }
            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) = Unit
        }, handler)
        runCatching {
            e.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoderSurface = e.createInputSurface()
            e.start()
            encoder = e
        }.onFailure { FieldLog.add("LLAMADA", "este celular no codifica video: ${it.message}"); runCatching { e.release() } }
    }

    private fun send(flags: Int, bytes: ByteArray) { if (cameraOn) onPiece(byteArrayOf(flags.toByte(), (rotation / 90).toByte()) + bytes) }

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        val target = encoderSurface ?: return
        val facing = if (front) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
        val id = cameras.cameraIdList.firstOrNull { cameras.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == facing }
            ?: cameras.cameraIdList.firstOrNull() ?: return
        val chars = cameras.getCameraCharacteristics(id)
        val sensor = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        // The activity stays in portrait: the encoded picture (not mirrored) turns by the sensor's angle, front or back.
        rotation = sensor
        runCatching {
            cameras.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    camera = device
                    val outputs = listOfNotNull(target, preview)
                    @Suppress("DEPRECATION")
                    device.createCaptureSession(outputs, object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(s: CameraCaptureSession) {
                            session = s
                            val req = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                                outputs.forEach { addTarget(it) }
                                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(FPS, FPS))
                            }.build()
                            runCatching { s.setRepeatingRequest(req, null, handler) }
                            cameraOn = true
                            keyFrameWanted()
                        }
                        override fun onConfigureFailed(s: CameraCaptureSession) { FieldLog.add("LLAMADA", "la cámara no arrancó") }
                    }, handler)
                }
                override fun onDisconnected(device: CameraDevice) { device.close(); camera = null }
                override fun onError(device: CameraDevice, error: Int) { device.close(); camera = null; FieldLog.add("LLAMADA", "error de cámara $error") }
            }, handler)
        }.onFailure { FieldLog.add("LLAMADA", "no pude abrir la cámara: ${it.message}") }
    }

    private fun closeCamera() {
        runCatching { session?.close() }; session = null
        runCatching { camera?.close() }; camera = null
        runCatching { preview?.release() }; preview = null
    }

    /* ---------- receiving: pieces → decoder → screen ---------- */

    private var decoder: MediaCodec? = null
    private var remote: Surface? = null
    private var remoteView: TextureView? = null
    private var waitingKey = true
    private val pending = ArrayDeque<Pair<ByteArray, Int>>()
    private val freeInputs = ArrayDeque<Int>()
    @Volatile var remoteCameraOn = false; private set
    /** Called on the main thread when the other camera turns on or off, or the picture's angle changes. */
    var onRemoteState: () -> Unit = {}
    private var remoteRotation = -1

    /** Where the other side's picture goes. */
    fun showRemote(view: TextureView) = handler.post {
        val tex = view.surfaceTexture ?: return@post
        if (remoteView === view && remote != null) return@post
        remoteView = view
        runCatching { remote?.release() }
        remote = Surface(tex)
        onPiece(byteArrayOf(WANT_KEY.toByte(), 0)) // a fresh screen starts from a key frame
    }

    /** The screen went away (the app left the call screen): drop the decoder; it starts again from a key frame. */
    fun hideRemote() = handler.post {
        runCatching { decoder?.stop() }; runCatching { decoder?.release() }; decoder = null
        runCatching { remote?.release() }; remote = null
        remoteView = null
    }

    fun onRemote(piece: ByteArray) = handler.post {
        if (piece.size < 2) return@post
        val flags = piece[0].toInt() and 0xff
        if (flags and WANT_KEY != 0) { keyFrameWanted(); return@post }
        if (flags and CAMERA_OFF != 0) { remoteCameraOn = false; view { onRemoteState() }; return@post }
        if (!remoteCameraOn) { remoteCameraOn = true; view { onRemoteState() } }
        val rot = (piece[1].toInt() and 0xff) * 90
        if (rot != remoteRotation) { remoteRotation = rot; view { fit() } }
        val bytes = piece.copyOfRange(2, piece.size)
        if (flags and CONFIG != 0) { if (decoder == null) startDecoder(bytes); return@post }
        if (decoder == null) { onPiece(byteArrayOf(WANT_KEY.toByte(), 0)); return@post }
        if (waitingKey && flags and KEY == 0) return@post
        waitingKey = false
        pending.addLast(bytes to 0)
        feed()
    }

    private fun startDecoder(config: ByteArray) {
        val surface = remote ?: return
        val d = runCatching { MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC) }.getOrNull() ?: return
        d.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) { freeInputs.addLast(index); feed() }
            override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) { runCatching { codec.releaseOutputBuffer(index, true) } }
            override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) { FieldLog.add("LLAMADA", "el decodificador falló: ${e.message}") }
            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) = Unit
        }, handler)
        runCatching {
            d.configure(MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, WIDTH, HEIGHT), surface, null, 0)
            d.start()
            decoder = d
            waitingKey = true
            pending.clear(); freeInputs.clear()
            pending.addLast(config to MediaCodec.BUFFER_FLAG_CODEC_CONFIG)
        }.onFailure { FieldLog.add("LLAMADA", "este celular no decodifica video: ${it.message}"); runCatching { d.release() } }
    }

    private var pts = 0L
    private fun feed() {
        val d = decoder ?: return
        while (pending.isNotEmpty() && freeInputs.isNotEmpty()) {
            val (bytes, flags) = pending.removeFirst()
            val index = freeInputs.removeFirst()
            runCatching {
                val buf = d.getInputBuffer(index) ?: return@runCatching
                buf.clear()
                if (bytes.size > buf.remaining()) { d.queueInputBuffer(index, 0, 0, pts, 0); return@runCatching }
                buf.put(bytes)
                pts += 1_000_000L / FPS
                d.queueInputBuffer(index, 0, bytes.size, pts, flags)
            }
        }
        while (pending.size > 30) pending.removeFirst()
    }

    private fun view(block: () -> Unit) { remoteView?.post(block) }

    // Turn and scale the other side's picture to fill the screen without stretching.
    private fun fit() {
        val v = remoteView ?: return
        val w = v.width.toFloat(); val h = v.height.toFloat()
        if (w == 0f || h == 0f) return
        val rot = remoteRotation.coerceAtLeast(0)
        // The view stretches the picture to w × h: give it back its own size, turn it, then grow it to cover the view.
        val (shownW, shownH) = if (rot % 180 == 0) WIDTH.toFloat() to HEIGHT.toFloat() else HEIGHT.toFloat() to WIDTH.toFloat()
        val cover = maxOf(w / shownW, h / shownH)
        v.setTransform(Matrix().apply {
            postScale(WIDTH / w, HEIGHT / h, w / 2, h / 2)
            postRotate(rot.toFloat(), w / 2, h / 2)
            postScale(cover, cover, w / 2, h / 2)
        })
    }

    fun refit() = view { fit() }

    fun stop() {
        running = false
        handler.post {
            closeCamera()
            runCatching { encoder?.stop() }; runCatching { encoder?.release() }; encoder = null
            runCatching { encoderSurface?.release() }; encoderSurface = null
            runCatching { decoder?.stop() }; runCatching { decoder?.release() }; decoder = null
            runCatching { remote?.release() }; remote = null
            remoteView = null
            thread.quitSafely()
        }
    }

    companion object {
        const val WIDTH = 640
        const val HEIGHT = 480
        const val FPS = 15
        const val BITRATE = 800_000
        const val CONFIG = 1
        const val KEY = 2
        const val CAMERA_OFF = 4
        const val WANT_KEY = 8
    }
}
