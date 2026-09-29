package io.bbui.assistant

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.bbui.device.DevicePermission
import io.bbui.device.ShizukuPhoneDevice
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Launch-only compatibility checks: no messages, searches, carts or orders are submitted. */
@RunWith(AndroidJUnit4::class)
class ApplicationCompatibilityTest {
    @Test fun installedTargetsCanLaunchOnVirtualDisplay() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val deadline = System.currentTimeMillis() + 10000
        while (!DevicePermission.available() && System.currentTimeMillis() < deadline) Thread.sleep(100)
        assertTrue("BBUI requires Shizuku authorization", DevicePermission.granted())
        val reports = JSONArray()
        val directory = File(context.filesDir, "gate-evidence").apply { mkdirs() }
        val watchdog = Thread({
            try {
                while (!Thread.currentThread().isInterrupted) {
                    Thread.sleep(45000)
                    File(directory, "compatibility-threads.txt").writeText(Thread.getAllStackTraces()
                        .entries.joinToString("\n\n") { (thread, stack) ->
                            "${thread.name} ${thread.state}\n${stack.joinToString("\n")}" })
                }
            } catch (_: InterruptedException) { }
        }, "compatibility-watchdog").apply { isDaemon = true; start() }
        try {
        for (target in listOf("com.tencent.mm", "tv.danmaku.bili", "com.jingdong.app.mall", "com.taobao.taobao")) {
            val device = ShizukuPhoneDevice(context, useTestTarget = true)
            val report = JSONObject().put("package", target)
            try {
                device.connect()
                device.setStopped(false)
                val observation = device.action("查看", JSONObject().put("执行后等待毫秒", 0))
                val result = device.action("打开应用", JSONObject().put("执行后等待毫秒", 3000)
                    .put("截图编号", observation.getJSONObject("details").getString("截图编号"))
                    .put("动作编号", UUID.randomUUID().toString()).put("包名", target))
                val facts = result.getJSONObject("details")
                report.put("passed", facts.optBoolean("目标应用在前台"))
                    .put("foreground", facts.optJSONObject("屏幕")?.optString("package"))
                    .put("execution", facts.optJSONObject("执行"))
                    .put("displayId", facts.getInt("显示屏编号"))
            } catch (error: Exception) {
                report.put("passed", false).put("error", error.message ?: error.javaClass.simpleName)
            } finally { device.close() }
            reports.put(report)
            File(directory, "application-compatibility.json").writeText(reports.toString(2))
        }
        } finally { watchdog.interrupt() }
        assertTrue("Some apps could not remain on the virtual display: $reports",
            (0 until reports.length()).all { reports.getJSONObject(it).optBoolean("passed") })
    }
}
