package io.bbui.assistant

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.bbui.device.DevicePermission
import io.bbui.device.ShizukuPhoneDevice
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Connects only the debug target. Never sends a model request or input to a real application. */
@RunWith(AndroidJUnit4::class)
class DisplayLifecycleTest : ForegroundDeviceTest() {
    @Test(timeout = 180000) fun reuseStopDetachAndRebuildInvalidateOldObservations() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue("Shizuku must be authorized", DevicePermission.granted())
        val device = ShizukuPhoneDevice(context, useTestTarget = true)
        try {
            val first = device.connect().getJSONObject("details")
            val environment = first.getString("环境编号")
            val display = first.getInt("显示屏编号")
            assertTrue(environment.isNotBlank()); assertTrue(display > 0)
            device.attachPreview(null, 0, 0)
            device.setStopped(true)
            val reused = device.connect().getJSONObject("details")
            assertEquals(environment, reused.getString("环境编号"))
            assertEquals(display, reused.getInt("显示屏编号"))
            val oldObservation = reused.getString("截图编号")
            device.close()
            val rebuilt = device.connect().getJSONObject("details")
            assertNotEquals(environment, rebuilt.getString("环境编号"))
            device.setStopped(false)
            device.action("查看", JSONObject().put("屏幕会话", "virtual"))
            val stale = device.action("点击", JSONObject().put("屏幕会话", "virtual")
                .put("截图编号", oldObservation).put("动作编号", UUID.randomUUID().toString())
                .put("位置", org.json.JSONArray().put(1).put(1)))
            val details = stale.getJSONObject("details")
            assertEquals("未派发", details.getJSONObject("执行").getString("状态"))
            assertEquals(rebuilt.getString("环境编号"), details.getString("环境编号"))
        } finally { device.close() }
    }
}
