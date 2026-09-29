package io.bbui.assistant

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.bbui.device.DevicePermission
import io.bbui.device.ShizukuPhoneDevice
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Extended gate deliberately uses only the debug TestTargetActivity, without model/network calls. */
@RunWith(AndroidJUnit4::class)
class DeviceExtendedGateTest : ForegroundDeviceTest() {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val evidence: File get() = File(context.filesDir, "gate-evidence").apply { mkdirs() }

    @Test fun chineseClipboardInputStaysOnVirtualDisplay() {
        val device = connectedDevice()
        try {
            val connection = device.connect()
            val display = connection.getJSONObject("details").getInt("显示屏编号")
            device.setStopped(false)
            var observation = observe(device)
            val target = state(display)
            observation = device.action("点击", params(observation)
                .put("位置", JSONArray().put(target.getInt("inputX")).put(target.getInt("inputY"))))
            val value = "你好，BBUI 中文输入 你好世界"
            observation = device.action("输入内容", params(observation).put("内容", value))
            val result = awaitState(display) { it.optString("text") == value }
            assertEquals(value, result.getString("text"))
            assertEquals(display, observation.getJSONObject("details").getInt("显示屏编号"))
            saveImage(observation, "virtual-chinese-input.png")
            File(evidence, "chinese-input-gate.json").writeText(JSONObject()
                .put("passed", true).put("displayId", display).put("text", result.getString("text"))
                .put("scope", "debug TestTargetActivity only").toString(2))
        } finally { device.setStopped(true); device.close() }
    }

    @Test fun detachedPreviewDedupManualEpochAndReconnectRemainSafe() {
        val device = connectedDevice()
        var previewReader: ImageReader? = null
        var previewThread: HandlerThread? = null
        try {
            val connection = device.connect()
            val display = connection.getJSONObject("details").getInt("显示屏编号")
            assertTrue(display > 0)
            val initial = state(display)
            val baseline = initial.getInt("count")
            val x = initial.getInt("incrementX")
            val y = initial.getInt("incrementY")

            // Attach a real producer surface, drain its frames, then destroy it. The decoder
            // must continue independently for a full minute with no preview surface present.
            val thread = HandlerThread("bbui-gate-preview").apply { start() }
            previewThread = thread
            val reader = ImageReader.newInstance(720, 1280, PixelFormat.RGBA_8888, 3)
            previewReader = reader
            reader.setOnImageAvailableListener({ source -> runCatching { source.acquireLatestImage()?.close() } }, Handler(thread.looper))
            device.attachPreview(reader.surface, 720, 1280)
            Thread.sleep(750)
            device.attachPreview(null, 720, 1280)
            reader.setOnImageAvailableListener(null, null)
            reader.close(); previewReader = null
            thread.quitSafely(); thread.join(2000); previewThread = null

            val detachedAt = SystemClock.elapsedRealtime()
            // Each wait is bounded so a failing instrumentation process is not hidden in one long sleep.
            repeat(12) { Thread.sleep(5000) }
            assertTrue(SystemClock.elapsedRealtime() - detachedAt >= 60000)
            device.setStopped(false)
            var observation = observe(device)
            saveImage(observation, "virtual-after-60s-detached.png")
            assertTrue(observation.getJSONObject("details").getLong("视频帧编号") > 0)

            val frameBeforeClick = observation.getJSONObject("details").getLong("视频帧编号")
            val firstAction = params(observation).put("位置", JSONArray().put(x).put(y))
            observation = device.action("点击", firstAction)
            assertTrue("Decoder stopped producing frames after preview destruction",
                observation.getJSONObject("details").getLong("视频帧编号") > frameBeforeClick)
            assertEquals(baseline + 1, awaitState(display) { it.getInt("count") == baseline + 1 }.getInt("count"))
            val duplicate = device.action("点击", firstAction)
            assertTrue(duplicate.getJSONObject("details").getBoolean("本次未重放"))
            assertEquals("已派发", duplicate.getJSONObject("details").getJSONObject("执行").getString("状态"))
            Thread.sleep(750)
            assertEquals("Duplicate id replayed input", baseline + 1, state(display).getInt("count"))

            // Manual coordinates are preview coordinates (720x1280 fits the 1080x1920 stream).
            device.setStopped(true)
            val width = observation.getJSONObject("details").getInt("宽")
            val height = observation.getJSONObject("details").getInt("高")
            val scale = minOf(720f / width, 1280f / height)
            val px = (720f - width * scale) / 2 + x * scale
            val py = (1280f - height * scale) / 2 + y * scale
            device.setManual(true, 41)
            device.setManual(false, 42)
            device.setManual(true, 43)
            device.touch(0, px, py, 41)
            device.touch(1, px, py, 41)
            Thread.sleep(750)
            assertEquals("Old takeover epoch injected input", baseline + 1, state(display).getInt("count"))
            device.touch(0, px, py, 43)
            Thread.sleep(35)
            device.touch(1, px, py, 43)
            Thread.sleep(750)
            assertEquals(baseline + 2, awaitState(display) { it.getInt("count") == baseline + 2 }.getInt("count"))
            device.setManual(false, 44)

            device.close()
            val recreated = device.connect()
            val newDisplay = recreated.getJSONObject("details").getInt("显示屏编号")
            assertTrue(newDisplay > 0)
            assertEquals(newDisplay, state(newDisplay).getInt("displayId"))
            device.setStopped(false)
            observation = observe(device)
            val renewed = state(newDisplay)
            val renewedCount = renewed.getInt("count")
            observation = device.action("点击", params(observation).put("位置", JSONArray()
                .put(renewed.getInt("incrementX")).put(renewed.getInt("incrementY"))))
            assertEquals(renewedCount + 1, awaitState(newDisplay) { it.getInt("count") == renewedCount + 1 }.getInt("count"))
            saveImage(observation, "virtual-after-reconnect.png")
            File(evidence, "extended-lifecycle-gate.json").writeText(JSONObject().put("passed", true)
                .put("initialDisplayId", display).put("recreatedDisplayId", newDisplay)
                .put("detachedMillis", SystemClock.elapsedRealtime() - detachedAt)
                .put("duplicateRejected", true).put("oldManualEpochRejected", true)
                .put("currentManualEpochWorks", true).put("reconnectInputWorks", true).toString(2))
        } finally {
            device.setManual(false, 1000)
            device.setStopped(true)
            runCatching { device.attachPreview(null, 0, 0) }
            previewReader?.close(); previewThread?.quitSafely()
            device.close()
        }
    }

    private fun connectedDevice(): ShizukuPhoneDevice {
        val deadline = SystemClock.elapsedRealtime() + 10000
        while (!DevicePermission.available() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(100)
        assertTrue("Start Shizuku and grant BBUI access before this gate", DevicePermission.granted())
        return ShizukuPhoneDevice(context, useTestTarget = true)
    }

    private fun observe(device: ShizukuPhoneDevice): JSONObject = device.action("查看", JSONObject()
        .put("执行后等待毫秒", 750).put("屏幕会话", "virtual"))

    private fun params(observation: JSONObject): JSONObject = JSONObject().put("执行后等待毫秒", 750)
        .put("屏幕会话", "virtual").put("动作编号", UUID.randomUUID().toString())
        .put("截图编号", observation.getJSONObject("details").getString("截图编号"))

    private fun state(display: Int): JSONObject = awaitState(display) { it.optInt("incrementX") > 0 && it.optInt("width") > 0 }

    private fun awaitState(display: Int, predicate: (JSONObject) -> Boolean): JSONObject {
        val file = File(context.noBackupFilesDir, "test-target-$display.json")
        val deadline = SystemClock.elapsedRealtime() + 8000
        var last = "not published"
        while (SystemClock.elapsedRealtime() < deadline) {
            try {
                val result = JSONObject(file.readText())
                last = result.toString()
                if (result.getInt("displayId") == display && predicate(result)) return result
            } catch (_: Exception) { }
            Thread.sleep(100)
        }
        error("Test target state did not match for display $display: $last")
    }

    private fun saveImage(observation: JSONObject, name: String) {
        val bytes = Base64.decode(observation.getJSONArray("content").getJSONObject(1).getString("data"), Base64.DEFAULT)
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        assertNotNull("Observation did not contain a valid PNG", decoded)
        decoded.recycle()
        File(evidence, name).writeBytes(bytes)
    }
}
