package io.bbui.assistant

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PixelFormat
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.bbui.device.DevicePermission
import io.bbui.device.ShizukuPhoneDevice
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** No accounts or real apps: exercise only the debug target activity. */
@RunWith(AndroidJUnit4::class)
class DeviceGateTest : ForegroundDeviceTest() {
    @Test fun virtualScreenInputAndCaptureAreIsolated() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val until = System.currentTimeMillis() + 10000
        while (!DevicePermission.available() && System.currentTimeMillis() < until) Thread.sleep(100)
        assertTrue("Start Shizuku and grant BBUI access before this test", DevicePermission.granted())
        assertTrue("Grant BBUI overlay permission before this test", Settings.canDrawOverlays(context))
        val device = ShizukuPhoneDevice(context, useTestTarget = true, diagnosticScreenshots = true)
        val overlayClicks = AtomicInteger()
        val window = context.getSystemService(WindowManager::class.java)
        var overlay: Button? = null
        val evidence = File(context.filesDir, "gate-evidence").apply { mkdirs() }
        try {
            val connected = device.connect()
            val detail = connected.getJSONObject("details")
            val displayId = detail.getInt("显示屏编号")
            assertTrue(displayId > 0)
            val targetFile = File(context.noBackupFilesDir, "test-target-$displayId.json")
            fun state(): JSONObject {
                val deadline = System.currentTimeMillis() + 5000
                while (System.currentTimeMillis() < deadline) {
                    try {
                        val value = JSONObject(targetFile.readText())
                        if (value.optInt("incrementX") > 0) return value
                    } catch (_: Exception) { }
                    Thread.sleep(100)
                }
                error("Test target did not publish its bounds")
            }
            val initial = state()
            val x = initial.getInt("incrementX")
            val y = initial.getInt("incrementY")
            instrumentation.runOnMainSync {
                overlay = Button(context).apply {
                    text = "BBUI OVERLAY ONLY"
                    setBackgroundColor(Color.MAGENTA)
                    setTextColor(Color.BLACK)
                    setOnClickListener { overlayClicks.incrementAndGet() }
                }
                val params = WindowManager.LayoutParams(360, 160,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply {
                    gravity = Gravity.TOP or Gravity.LEFT
                    this.x = maxOf(0, x - 180)
                    this.y = maxOf(0, y - 80)
                }
                window.addView(overlay, params)
            }
            Thread.sleep(300)
            instrumentation.uiAutomation.takeScreenshot()?.let { image ->
                File(evidence, "physical-overlay.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                image.recycle()
            }
            device.setStopped(false)
            fun observe() = device.action("查看", JSONObject().put("执行后等待毫秒", 0).put("屏幕会话", "virtual"))
            var observation = observe()
            val bytes = android.util.Base64.decode(observation.getJSONArray("content").getJSONObject(1).getString("data"), android.util.Base64.DEFAULT)
            val imageBlock = observation.getJSONArray("content").getJSONObject(1)
            val extension = if (imageBlock.getString("mimeType") == "image/jpeg") "jpg" else "png"
            File(evidence, "virtual-no-overlay.$extension").writeBytes(bytes)
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            val screenshot = observation.getJSONObject("details")
            assertEquals(screenshot.getInt("宽"), bitmap.width)
            assertEquals(screenshot.getInt("高"), bitmap.height)
            val original = File(context.cacheDir, "bbui-diagnostics/${screenshot.getString("截图编号")}.png").readBytes()
            assertTrue("Transport image grew", bytes.size <= original.size)
            val originalBitmap = BitmapFactory.decodeByteArray(original, 0, original.size)
            assertEquals(originalBitmap.width, bitmap.width)
            assertEquals(originalBitmap.height, bitmap.height)
            originalBitmap.recycle()
            var magenta = 0
            for (py in 0 until bitmap.height step 4) for (px in 0 until bitmap.width step 4) {
                val pixel = bitmap.getPixel(px, py)
                if (Color.red(pixel) > 230 && Color.blue(pixel) > 230 && Color.green(pixel) < 25) magenta++
            }
            bitmap.recycle()
            assertEquals("Physical overlay entered model image", 0, magenta)
            val baseline = initial.getInt("count")
            repeat(20) {
                val params = JSONObject().put("执行后等待毫秒", 750).put("屏幕会话", "virtual")
                    .put("截图编号", observation.getJSONObject("details").getString("截图编号"))
                    .put("动作编号", UUID.randomUUID().toString())
                    .put("位置", org.json.JSONArray().put(x).put(y))
                observation = device.action("点击", params)
            }
            assertEquals(baseline + 20, state().getInt("count"))
            assertEquals("Virtual input hit physical controls", 0, overlayClicks.get())

            val stale = observation.getJSONObject("details").getString("截图编号")
            observe()
            val invalid = JSONObject().put("执行后等待毫秒", 0).put("屏幕会话", "virtual")
                .put("截图编号", stale).put("动作编号", UUID.randomUUID().toString())
                .put("位置", org.json.JSONArray().put(x).put(y))
            assertEquals("未派发", device.action("点击", invalid).getJSONObject("details").getJSONObject("执行").getString("状态"))
            device.setStopped(true)
            assertEquals("未派发", device.action("点击", invalid.put("动作编号", UUID.randomUUID().toString()))
                .getJSONObject("details").getJSONObject("执行").getString("状态"))
            assertEquals(baseline + 20, state().getInt("count"))
            File(evidence, "display-gate.json").writeText(JSONObject().put("passed", true)
                .put("displayId", displayId).put("virtualClicks", 20).put("overlayClicks", overlayClicks.get())
                .put("magentaPixelsInModelImage", magenta).put("staleRejected", true).put("stopRejected", true).toString(2))
        } finally {
            device.setStopped(true)
            instrumentation.runOnMainSync { overlay?.let { window.removeView(it) } }
            device.close()
        }
    }
}
