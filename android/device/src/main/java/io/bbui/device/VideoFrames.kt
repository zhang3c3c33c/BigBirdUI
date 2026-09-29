package io.bbui.device

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaFormat
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Surface
import com.android.grafika.gles.EglCore
import com.android.grafika.gles.FullFrameRect
import com.android.grafika.gles.OffscreenSurface
import com.android.grafika.gles.Texture2dProgram
import com.android.grafika.gles.WindowSurface
import java.io.DataInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import io.bbui.core.PreviewViewport

internal data class CapturedFrame(val bitmap: Bitmap, val width: Int, val height: Int,
    val sequence: Long, val generation: Long, val timestamp: Long)

/** One decoder; an independent EGL consumer supplies both snapshots and optional previews. */
internal class VideoFrames(private val input: InputStream, private val onSession: (Int, Int) -> Unit,
    private val onFailure: (Throwable) -> Unit) {
    private val running = AtomicBoolean(true)
    private val glThread = HandlerThread("bbui-frames").apply { start() }
    private val handler = Handler(glThread.looper)
    private val changed = Object()
    private val codecLock = Any()
    private var codec: MediaCodec? = null
    private lateinit var egl: EglCore
    private lateinit var offscreen: OffscreenSurface
    private lateinit var quad: FullFrameRect
    private lateinit var texture: SurfaceTexture
    private lateinit var decoderSurface: Surface
    private var preview: WindowSurface? = null
    private var previewWidth = 0
    private var previewHeight = 0
    private var textureId = 0
    private val transform = FloatArray(16)
    @Volatile var width = 0; private set
    @Volatile var height = 0; private set
    @Volatile var sequence = 0L; private set
    @Volatile var generation = 0L; private set
    @Volatile var failure: Throwable? = null; private set
    private var acquiredAt = 0L
    private var reader: Thread? = null
    private var output: Thread? = null

    init {
        gl {
            egl = EglCore(null, 0)
            offscreen = OffscreenSurface(egl, 1, 1).also { it.makeCurrent() }
            quad = FullFrameRect(Texture2dProgram(Texture2dProgram.ProgramType.TEXTURE_EXT))
            createDecoderSurface()
        }
    }

    private fun createDecoderSurface() {
        textureId = quad.createTextureObject()
        val source = SurfaceTexture(textureId)
        texture = source
        source.setOnFrameAvailableListener({
                if (running.get() && source === texture && width > 0 && height > 0) {
                    try {
                        offscreen.makeCurrent()
                        source.updateTexImage()
                        source.getTransformMatrix(transform)
                        drawOffscreen()
                        sequence++
                        acquiredAt = SystemClock.elapsedRealtime()
                        drawPreview()
                        synchronized(changed) { changed.notifyAll() }
                    } catch (error: Throwable) { fail(error) }
                }
            }, handler)
        decoderSurface = Surface(source)
    }

    fun start() {
        output = Thread({
            val info = MediaCodec.BufferInfo()
            try {
                while (running.get()) {
                    synchronized(codecLock) {
                        codec?.let { current ->
                            val index = current.dequeueOutputBuffer(info, 10000)
                            if (index >= 0) current.releaseOutputBuffer(index, info.size > 0)
                        }
                    }
                    Thread.sleep(1)
                }
            } catch (error: Throwable) { if (running.get()) fail(error) }
        }, "bbui-decoder-output").apply { isDaemon = true; start() }
        reader = Thread({
            try {
                val stream = DataInputStream(input)
                check(stream.readInt() == 0x68323634) { "scrcpy 视频编码不是 H.264" }
                while (running.get()) {
                    val flags = stream.readLong()
                    val length = stream.readInt()
                    if (!running.get()) break
                    if (flags < 0) {
                        val w = (flags and 0xffffffffL).toInt()
                        check(w in 1..8192 && length in 1..8192) { "scrcpy 视频尺寸无效" }
                        changeSession(w, length)
                    } else {
                        check(length in 1..(32 * 1024 * 1024)) { "scrcpy 视频包长度无效" }
                        val bytes = ByteArray(length).also { stream.readFully(it) }
                        val isConfig = flags and (1L shl 62) != 0L
                        var queued = false
                        while (!queued && running.get()) {
                            synchronized(codecLock) {
                                val current = checkNotNull(codec) { "视频 session header 缺失" }
                                val index = current.dequeueInputBuffer(10000)
                                if (index >= 0) {
                                    val buffer = checkNotNull(current.getInputBuffer(index))
                                    check(buffer.capacity() >= bytes.size) { "视频包超出解码器容量" }
                                    buffer.clear(); buffer.put(bytes)
                                    current.queueInputBuffer(index, 0, bytes.size, flags and ((1L shl 61) - 1),
                                        if (isConfig) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0)
                                    queued = true
                                }
                            }
                            if (!queued) Thread.sleep(1)
                        }
                    }
                }
            } catch (error: Throwable) { if (running.get()) fail(error) }
        }, "bbui-video-input").apply { isDaemon = true; start() }
    }

    private fun changeSession(w: Int, h: Int) {
        synchronized(codecLock) { codec?.stop(); codec?.release(); codec = null }
        gl {
            decoderSurface.release(); texture.release()
            GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
            width = w; height = h; sequence = 0; acquiredAt = 0; generation++
            offscreen.release()
            offscreen = OffscreenSurface(egl, w, h).also { it.makeCurrent() }
            createDecoderSurface()
            texture.setDefaultBufferSize(w, h)
            onSession(w, h)
        }
        synchronized(codecLock) {
            codec = MediaCodec.createDecoderByType("video/avc").also {
                val format = MediaFormat.createVideoFormat("video/avc", w, h)
                format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4 * 1024 * 1024)
                it.configure(format, decoderSurface, null, 0)
                it.start()
            }
        }
    }

    fun attach(surface: Surface?, w: Int, h: Int) = gl {
        preview?.release(); preview = null
        previewWidth = w; previewHeight = h
        if (surface != null && surface.isValid && w > 0 && h > 0) {
            preview = WindowSurface(egl, surface, false)
            if (sequence > 0) drawPreview()
        }
        offscreen.makeCurrent()
    }

    fun capture(): CapturedFrame {
        val until = SystemClock.elapsedRealtime() + 10000
        synchronized(changed) {
            while (sequence == 0L && failure == null && running.get()) {
                val remaining = until - SystemClock.elapsedRealtime()
                check(remaining > 0) { "虚拟屏未产生画面" }
                changed.wait(remaining)
            }
        }
        failure?.let { throw IllegalStateException("视频已断开", it) }
        check(running.get()) { "视频已停止" }
        var result: CapturedFrame? = null
        gl {
            check(sequence > 0) { "屏幕旋转中，请重新查看" }
            drawOffscreen()
            val data = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
            GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, data)
            check(GLES20.glGetError() == GLES20.GL_NO_ERROR) { "离屏截图失败" }
            data.rewind()
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(data)
            val flipped = Bitmap.createBitmap(bitmap, 0, 0, width, height, Matrix().apply { setScale(1f, -1f) }, false)
            if (flipped !== bitmap) bitmap.recycle()
            result = CapturedFrame(flipped, width, height, sequence, generation, acquiredAt)
        }
        return checkNotNull(result)
    }

    private fun drawOffscreen() {
        offscreen.makeCurrent()
        GLES20.glViewport(0, 0, width, height)
        quad.drawFrame(textureId, transform)
    }

    private fun drawPreview() {
        val window = preview ?: return
        if (width == 0 || height == 0) return
        val viewport = PreviewViewport.fit(previewWidth, previewHeight, width, height) ?: return
        window.makeCurrent()
        GLES20.glViewport(0, 0, previewWidth, previewHeight)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glViewport(viewport.left, previewHeight - viewport.top - viewport.height, viewport.width, viewport.height)
        quad.drawFrame(textureId, transform)
        if (!window.swapBuffers()) { preview?.release(); preview = null }
        offscreen.makeCurrent()
    }

    private fun gl(block: () -> Unit) {
        if (Thread.currentThread() === glThread) { block(); return }
        val done = CountDownLatch(1)
        var error: Throwable? = null
        check(handler.post { try { block() } catch (caught: Throwable) { error = caught } finally { done.countDown() } })
        check(done.await(12, TimeUnit.SECONDS)) { "视频 GL 线程响应超时" }
        error?.let { throw it }
    }

    private fun fail(error: Throwable) {
        failure = error
        synchronized(changed) { changed.notifyAll() }
        onFailure(error)
    }

    fun close() {
        if (!running.getAndSet(false)) return
        synchronized(changed) { changed.notifyAll() }
        val done = CountDownLatch(1)
        Thread({
            try {
                runCatching { input.close() }
                reader?.join(500); output?.join(500)
                // stop/release can block inside a vendor codec despite dequeue deadlines.
                // Never hold the caller's action lock while waiting indefinitely for it.
                synchronized(codecLock) { runCatching { codec?.stop() }; runCatching { codec?.release() }; codec = null }
                runCatching { gl {
                    preview?.release(); preview = null
                    offscreen.makeCurrent(); decoderSurface.release(); texture.release()
                    GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
                    quad.release(true); offscreen.release(); egl.release()
                } }
                glThread.quitSafely()
            } finally { done.countDown() }
        }, "bbui-codec-cleanup").apply { isDaemon = true; start() }
        if (!done.await(2500, TimeUnit.MILLISECONDS)) {
            android.util.Log.w("BBUI", "Vendor codec cleanup exceeded 2500ms; isolated cleanup continues")
        }
    }
}
