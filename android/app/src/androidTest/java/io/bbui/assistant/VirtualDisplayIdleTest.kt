package io.bbui.assistant

import android.hardware.display.DisplayManager
import android.os.SystemClock
import android.provider.Settings
import android.view.Display
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.bbui.device.ShizukuPhoneDevice
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** No real apps/model calls. Idle beyond the device timeout, then tap the debug counter once. */
@RunWith(AndroidJUnit4::class)
class VirtualDisplayIdleTest : ForegroundDeviceTest() {
    @Test(timeout = 210000) fun idleDisplayStaysOnAndFreshObservationOfOldFrameRemainsUsable() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val displays = context.getSystemService(DisplayManager::class.java)
        val timeout = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT)
        assertTrue("Test device timeout must be 5–120 seconds", timeout in 5000..120000)
        val device = ShizukuPhoneDevice(context, useTestTarget = true)
        var id = -1
        try {
            id = device.connect().getJSONObject("details").getInt("显示屏编号")
            fun observe() = device.action("查看", JSONObject()).getJSONObject("details")
            val first = observe()
            assertTrue(first.getJSONObject("观察").getBoolean("元数据已确认"))
            assertTrue(first.getJSONObject("观察").getBoolean("可用于输入"))
            // No input or observation during the idle interval; only scrcpy keep_active runs.
            SystemClock.sleep(timeout.toLong() + 12000)
            assertEquals(Display.STATE_ON, displays.getDisplay(id)?.state)
            assertEquals("ON", observe().getJSONObject("屏幕").getString("powerState"))
            device.setStopped(false)
            // Deterministic old-frame regression: keep actual pixels, age only the decoder timestamp.
            // The fresh capture must still validate its live display/window/generation.
            val frames = ShizukuPhoneDevice::class.java.getDeclaredField("frames").apply { isAccessible = true }.get(device)!!
            frames.javaClass.getDeclaredField("acquiredAt").apply { isAccessible = true }
                .setLong(frames, SystemClock.elapsedRealtime() - 190000)
            val fresh = observe()
            assertTrue("Must exercise an old encoded frame", fresh.getLong("视频帧年龄毫秒") > 180000)
            assertTrue(fresh.getJSONObject("观察").getBoolean("可用于输入"))
            val stateFile = File(context.noBackupFilesDir, "test-target-$id.json")
            val target = JSONObject(stateFile.readText())
            val action = JSONObject().put("屏幕会话", "virtual").put("动作编号", UUID.randomUUID().toString())
                .put("截图编号", fresh.getString("截图编号"))
                .put("位置", JSONArray().put(target.getInt("incrementX")).put(target.getInt("incrementY")))
            val result = device.action("点击", action).getJSONObject("details")
            assertEquals(result.optString("错误"), "已派发", result.getJSONObject("执行").getString("状态"))
            val until = SystemClock.elapsedRealtime() + 5000
            while (JSONObject(stateFile.readText()).getInt("count") != target.getInt("count") + 1 && SystemClock.elapsedRealtime() < until) SystemClock.sleep(50)
            assertEquals(target.getInt("count") + 1, JSONObject(stateFile.readText()).getInt("count"))
            File(context.filesDir, "gate-evidence").mkdirs()
            File(context.filesDir, "gate-evidence/virtual-display-idle.json").writeText(JSONObject()
                .put("timeoutMs", timeout).put("idleMs", timeout + 12000).put("displayStayedOn", true)
                .put("oldFrameAgeMs", fresh.getLong("视频帧年龄毫秒")).put("freshObservationAccepted", true)
                .put("counterIncrementedOnce", true).toString(2))
        } finally {
            device.close()
            val until = SystemClock.elapsedRealtime() + 10000
            while (id > 0 && displays.getDisplay(id) != null && SystemClock.elapsedRealtime() < until) SystemClock.sleep(100)
            if (id > 0) assertNull("Closing the device must release the display/keep-active worker", displays.getDisplay(id))
        }
    }
}
