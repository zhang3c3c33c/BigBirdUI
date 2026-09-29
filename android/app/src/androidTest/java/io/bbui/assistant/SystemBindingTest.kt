package io.bbui.assistant

import android.app.Activity
import android.content.Intent
import android.view.WindowManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.bbui.device.ShizukuPhoneDevice
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SystemBindingTest {
    private var host: Activity? = null
    @Before fun foregroundTestHost() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        host = instrumentation.startActivitySync(Intent(instrumentation.targetContext, TestTargetActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        instrumentation.runOnMainSync { host?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    @After fun closeTestHost() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { host?.finish() }
    }
    @Test fun bindingAndReadOnlyQueriesDoNotCreateVirtualDisplay() {
        val device = ShizukuPhoneDevice(InstrumentationRegistry.getInstrumentation().targetContext)
        try {
            device.bindSystem()
            val screens = device.action("列出屏幕", JSONObject()).getJSONObject("details").getJSONArray("屏幕")
            assertEquals(0, screens.length())
            val applications = device.action("列出应用", JSONObject().put("关键词", "io.bbui.assistant"))
            assertFalse(applications.optBoolean("isError"))
            assertTrue(applications.getJSONObject("details").getInt("返回数量") > 0)
            assertEquals("未创建", applications.getJSONObject("details").getJSONObject("通道").getString("控制"))
            assertEquals("未创建", applications.getJSONObject("details").getJSONObject("通道").getString("视频"))
            assertEquals(0, device.action("列出屏幕", JSONObject()).getJSONObject("details").getJSONArray("屏幕").length())
        } finally { device.close() }
    }

    @Test fun applicationQueryThenLazyGuiKeepsTheConversationRunning() {
        val device = ShizukuPhoneDevice(InstrumentationRegistry.getInstrumentation().targetContext, useTestTarget = true)
        val state = ConversationStore().apply {
            sessions["isolated-test"] = JSONObject().put("id", "isolated-test").put("title", "测试")
            selected = "isolated-test"
            running = JSONObject().put("sessionId", selected).put("runId", "1")
        }
        fun event(type: String) = JSONObject().put("type", type).put("sessionId", "isolated-test").put("runId", "1")
        fun accept(result: JSONObject) {
            assertFalse(result.optBoolean("isError"))
            state.accept(event("phone_action_result").put("details", result.getJSONObject("details")))
            assertEquals("running", state.controlMode)
            assertFalse(state.paused)
        }
        try {
            device.bindSystem(); device.setStopped(false)
            state.accept(event("run_started"))
            accept(device.action("列出应用", JSONObject().put("关键词", "io.bbui.assistant")))
            accept(device.action("列出屏幕", JSONObject()))
            val connected = device.connect()
            assertTrue(connected.getJSONObject("details").getInt("显示屏编号") > 0)
            device.setStopped(false)
            val observation = device.action("查看", JSONObject().put("执行后等待毫秒", 300))
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val screenshotId = observation.getJSONObject("details").getString("截图编号")
            assertFalse(java.io.File(context.filesDir, "device-runs/$screenshotId.png").exists())
            assertFalse(java.io.File(context.cacheDir, "bbui-diagnostics/$screenshotId.png").exists())
            assertTrue(observation.getJSONArray("content").getJSONObject(1).getString("data").isNotEmpty())
            assertEquals("可用", observation.getJSONObject("details").getJSONObject("通道").getString("控制"))
            accept(observation)
            state.accept(event("agent_settled"))
            assertEquals("idle", state.controlMode); assertFalse(state.paused)
            assertNull(state.running); assertNull(state.continuation)
            assertFalse(state.controlSnapshot().getBoolean("canResume"))

            // A real quarantine must still report a fault, even on read-only queries.
            device.quarantineInput("test uncertain dispatch")
            val fault = device.action("列出屏幕", JSONObject()).getJSONObject("details")
            assertEquals("不可用", fault.getJSONObject("通道").getString("控制"))
            assertEquals("test uncertain dispatch", fault.getJSONObject("通道").getString("输入故障"))
        } finally { device.close() }
    }
}
