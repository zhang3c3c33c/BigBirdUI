package io.bbui.assistant

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.bbui.device.ScreenshotEncoder
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.random.Random

@RunWith(AndroidJUnit4::class)
class ScreenshotCompressionTest {
    @Test fun jpegKeepsDimensionsAndSourcePixels() {
        val width = 1080
        val height = 1920
        val random = Random(42)
        val pixels = IntArray(width * height) { i ->
            val x = i % width
            val y = i / width
            val grain = random.nextInt(-8, 9)
            Color.rgb((80 + x * 140 / width + grain).coerceIn(0, 255),
                (90 + y * 120 / height + grain).coerceIn(0, 255), (150 + grain).coerceIn(0, 255))
        }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.WHITE
        canvas.drawRect(32f, 80f, 1048f, 480f, paint)
        paint.color = Color.BLACK
        listOf(18f, 24f, 32f, 48f).forEachIndexed { index, size ->
            paint.textSize = size
            canvas.drawText("中文小字：搜索附近餐厅 价格 ¥123.45 / ABC 012345", 48f, 130f + index * 80f, paint)
        }
        val source = bitmap.copy(Bitmap.Config.ARGB_8888, false)
        val started = SystemClock.elapsedRealtime()
        val encoded = ScreenshotEncoder.encode(bitmap)
        val elapsed = SystemClock.elapsedRealtime() - started
        assertEquals("image/jpeg", encoded.mimeType)
        assertEquals(0xff, encoded.modelBytes[0].toInt() and 255)
        assertEquals(0xd8, encoded.modelBytes[1].toInt() and 255)
        assertTrue(encoded.modelBytes.size < encoded.originalPng.size)
        assertFalse(bitmap.isRecycled)
        assertTrue("Encoding changed observation pixels", bitmap.sameAs(source))
        val png = BitmapFactory.decodeByteArray(encoded.originalPng, 0, encoded.originalPng.size)
        val jpeg = BitmapFactory.decodeByteArray(encoded.modelBytes, 0, encoded.modelBytes.size)
        assertTrue("Archived PNG changed original pixels", png.sameAs(source))
        assertEquals(width, jpeg.width)
        assertEquals(height, jpeg.height)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val evidence = File(context.filesDir, "gate-evidence").apply { mkdirs() }
        File(evidence, "compression-original.png").writeBytes(encoded.originalPng)
        File(evidence, "compression-model.jpg").writeBytes(encoded.modelBytes)
        val report = JSONObject().put("width", width).put("height", height)
            .put("pngBytes", encoded.originalPng.size).put("modelBytes", encoded.modelBytes.size)
            .put("mimeType", encoded.mimeType).put("encodeMillis", elapsed)
        // Benchmark an existing local screen when available; never access a model or replay input.
        File(context.filesDir, "device-runs").listFiles()?.filter { it.extension == "png" }
            ?.maxByOrNull { it.length() }?.let { file ->
                BitmapFactory.decodeFile(file.path)?.let { original ->
                    val begin = SystemClock.elapsedRealtime()
                    val actual = ScreenshotEncoder.encode(original)
                    report.put("existingScreen", JSONObject().put("width", original.width).put("height", original.height)
                        .put("pngBytes", actual.originalPng.size).put("modelBytes", actual.modelBytes.size)
                        .put("mimeType", actual.mimeType).put("encodeMillis", SystemClock.elapsedRealtime() - begin))
                    File(evidence, "compression-screen-original.png").writeBytes(actual.originalPng)
                    File(evidence, "compression-screen-model.${if (actual.mimeType == "image/jpeg") "jpg" else "png"}")
                        .writeBytes(actual.modelBytes)
                    original.recycle()
                }
            }
        File(evidence, "compression.json").writeText(report.toString(2))
        listOf(bitmap, source, png, jpeg).forEach { it.recycle() }
    }

    @Test fun flatScreenKeepsSmallerLosslessPng() {
        val bitmap = Bitmap.createBitmap(1080, 1920, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        val encoded = ScreenshotEncoder.encode(bitmap)
        assertEquals("image/png", encoded.mimeType)
        assertSame(encoded.originalPng, encoded.modelBytes)
        assertFalse(bitmap.isRecycled)
        bitmap.recycle()
    }
}
