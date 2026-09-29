package io.bbui.assistant

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

/** No model requests. Inputs target only the debug counter; the clone check only launches an app. */
@RunWith(AndroidJUnit4::class)
class ToolFactsTest : ForegroundDeviceTest() {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun device(): ShizukuPhoneDevice {
        val until = System.currentTimeMillis() + 10000
        while (!DevicePermission.available() && System.currentTimeMillis() < until) Thread.sleep(100)
        assertTrue(DevicePermission.granted())
        return ShizukuPhoneDevice(context, useTestTarget = true)
    }
    private fun details(value: JSONObject) = value.getJSONObject("details")
    private fun execution(value: JSONObject) = details(value).getJSONObject("执行").getString("状态")
    private fun observe(device: ShizukuPhoneDevice) = device.action("查看", JSONObject().put("执行后等待毫秒", 300))
    private fun state(display: Int): JSONObject {
        val file = File(context.noBackupFilesDir, "test-target-$display.json")
        val until = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < until) {
            runCatching { JSONObject(file.readText()) }.getOrNull()?.let { if (it.optInt("incrementX") > 0) return it }
            Thread.sleep(100)
        }
        error("debug target missing")
    }
    private fun params(view: JSONObject, target: JSONObject) = JSONObject()
        .put("动作编号", UUID.randomUUID().toString()).put("执行后等待毫秒", 500)
        .put("截图编号", details(view).getString("截图编号"))
        .put("位置", JSONArray().put(target.getInt("incrementX")).put(target.getInt("incrementY")))

    @Test fun rejectedCallsPreserveObservationAndDoNotStopTheNextAction() {
        val phone = device()
        try {
            val display = details(phone.connect()).getInt("显示屏编号")
            phone.setStopped(false)
            val target = state(display)
            val baseline = target.getInt("count")
            val old = observe(phone)
            observe(phone)
            val stale = phone.action("点击", params(old, target))
            assertEquals("未派发", execution(stale))
            assertEquals("已取得", details(stale).getJSONObject("观察").getString("状态"))
            assertFalse(details(stale).getJSONObject("通道").getBoolean("用户停止"))
            val click = params(stale, target)
            val done = phone.action("点击", click)
            assertEquals("已派发", execution(done))
            assertEquals(baseline + 1, state(display).getInt("count"))
            val duplicate = phone.action("点击", click)
            assertTrue(details(duplicate).getBoolean("本次未重放"))
            assertEquals("已派发", execution(duplicate))
            assertEquals(baseline + 1, state(display).getInt("count"))

            val missing = phone.action("打开应用", JSONObject().put("动作编号", UUID.randomUUID().toString())
                .put("包名", "io.bbui.test.missing.application"))
            assertEquals("未派发", execution(missing))
            assertEquals("已取得", details(missing).getJSONObject("观察").getString("状态"))
            assertEquals("可用", details(missing).getJSONObject("通道").getString("控制"))
            val next = phone.action("点击", params(observe(phone), target))
            assertEquals("已派发", execution(next))
            assertEquals(baseline + 2, state(display).getInt("count"))

            phone.setStopped(true)
            assertEquals("未派发", execution(phone.action("点击", params(observe(phone), target))))
            assertEquals(baseline + 2, state(display).getInt("count"))
            phone.setStopped(false)
            phone.quarantineInput("test transport outcome uncertain")
            val readable = observe(phone)
            assertEquals("已取得", details(readable).getJSONObject("观察").getString("状态"))
            assertFalse(details(readable).getJSONObject("通道").getBoolean("用户停止"))
            assertEquals("未派发", execution(phone.action("点击", params(readable, target))))
            phone.setStopped(false)
            assertEquals("未派发", execution(phone.action("点击", params(observe(phone), target))))
            assertEquals(baseline + 2, state(display).getInt("count"))
            File(context.filesDir, "gate-evidence").apply { mkdirs() }.resolve("tool-facts.json")
                .writeText(JSONObject().put("passed", true).put("displayId", display).put("counterDelta", 2).toString())
        } finally { phone.close() }
    }

    @Test fun cloneSelectorIsReturnedAsAnObservablePage() {
        val phone = device()
        try {
            val display = details(phone.connect()).getInt("显示屏编号")
            phone.setStopped(false)
            val inventory = phone.action("列出应用", JSONObject().put("关键词", "com.sankuai.meituan"))
            assertTrue("Connected phone must have Meituan for the clone regression", details(inventory).getJSONArray("应用").length() > 0)
            val launched = phone.action("打开应用", JSONObject().put("动作编号", UUID.randomUUID().toString())
                .put("包名", "com.sankuai.meituan").put("执行后等待毫秒", 1000))
            assertEquals("已派发", execution(launched))
            assertEquals(display, details(launched).getInt("显示屏编号"))
            val current = observe(phone)
            val facts = details(current)
            assertEquals("已取得", facts.getJSONObject("观察").getString("状态"))
            assertEquals("可用", facts.getJSONObject("通道").getString("控制"))
            assertFalse(facts.getJSONObject("通道").getBoolean("用户停止"))
            val pkg = facts.getJSONObject("屏幕").getString("package")
            assertTrue("Unexpected foreground: $pkg", pkg in setOf("com.vivo.doubleinstance", "com.sankuai.meituan"))
            val evidence = File(context.filesDir, "gate-evidence").apply { mkdirs() }
            evidence.resolve("clone-tool-facts.json").writeText(facts.toString(2))
            evidence.resolve("clone-tool-screen.png").writeBytes(Base64.decode(current.getJSONArray("content")
                .getJSONObject(1).getString("data"), Base64.DEFAULT))
            if (pkg == "com.vivo.doubleinstance") {
                // Continue with a normal model-visible operation, without choosing any account.
                val back = phone.action("按键", JSONObject().put("动作编号", UUID.randomUUID().toString())
                    .put("截图编号", facts.getString("截图编号")).put("键名", "返回").put("执行后等待毫秒", 500))
                assertEquals("已派发", execution(back))
                val resumed = observe(phone)
                assertEquals("io.bbui.assistant", details(resumed).getJSONObject("屏幕").getString("package"))
                val target = state(display)
                val continued = phone.action("点击", params(resumed, target))
                assertEquals("已派发", execution(continued))
                assertEquals(target.getInt("count") + 1, state(display).getInt("count"))
                evidence.resolve("clone-continued.json").writeText(JSONObject().put("passed", true)
                    .put("selectorReturned", true).put("backAndCounterClickWorked", true).toString())
            }
        } finally { phone.close() }
    }

    @Test fun failedPostActionObservationDoesNotEraseDispatchReceipt() {
        val phone = ShizukuPhoneDevice(context, useTestTarget = true)
        try {
            val finish = ShizukuPhoneDevice::class.java.getDeclaredMethod("finishResult", String::class.java,
                Int::class.javaPrimitiveType, String::class.java, JSONObject::class.java).apply { isAccessible = true }
            val result = finish.invoke(phone, "已派发", 0, null, JSONObject().put("动作编号", "offline-receipt")) as JSONObject
            assertEquals("已派发", execution(result))
            assertEquals("失败", details(result).getJSONObject("观察").getString("状态"))
            assertEquals("offline-receipt", details(result).getString("动作编号"))
        } finally { phone.close() }
    }

    @Test fun stoppedDoubleClickRetainsTheCompletedFirstClick() {
        val phone = device()
        var watcher: Thread? = null
        try {
            val display = details(phone.connect()).getInt("显示屏编号")
            phone.setStopped(false)
            val target = state(display)
            val baseline = target.getInt("count")
            val view = observe(phone)
            watcher = Thread {
                val until = System.currentTimeMillis() + 10000
                while (!Thread.currentThread().isInterrupted && System.currentTimeMillis() < until) {
                    if (runCatching { state(display).getInt("count") }.getOrDefault(baseline) > baseline) {
                        phone.setStopped(true); break
                    }
                    try { Thread.sleep(2) } catch (_: InterruptedException) { break }
                }
            }.apply { start() }
            val result = phone.action("双击", params(view, target))
            assertEquals("部分派发", execution(result))
            assertEquals(1, details(result).getInt("已完成点击次数"))
            assertEquals(baseline + 1, state(display).getInt("count"))
            assertTrue(details(result).getJSONObject("通道").getBoolean("用户停止"))
            assertEquals("已取得", details(result).getJSONObject("观察").getString("状态"))
        } finally { watcher?.interrupt(); watcher?.join(2000); phone.close() }
    }
}
