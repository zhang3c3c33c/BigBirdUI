package io.bbui.assistant

import android.os.SystemClock
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import io.bbui.device.DevicePermission
import io.bbui.device.IDeviceService
import io.bbui.device.ShizukuPhoneDevice
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Exercise public production actions; reflection is only used to read raw diagnostics.
 * Every mutation targets debug test editors, with readback before another input. */
@RunWith(AndroidJUnit4::class)
class ScrcpyPasteIsolationTest : ForegroundDeviceTest() {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val evidence get() = File(context.filesDir, "paste-isolation").apply { mkdirs() }

    @Test fun directedPasteWithMainDisplayActivity() {
        val deadline = SystemClock.elapsedRealtime() + 10000
        while (!DevicePermission.available() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(100)
        assertTrue(DevicePermission.granted())
        val device = ShizukuPhoneDevice(context, useTestTarget = true)
        val report = JSONObject().put("backend", "Shizuku UID 2000 + bundled scrcpy 4.1")
            .put("productionActions", true)
        val cases = JSONArray()
        report.put("cases", cases)
        File(evidence, "shell.txt").writeText("")
        try {
            val display = device.connect().getJSONObject("details").getInt("显示屏编号")
            assertTrue(display > 0)
            report.put("displayId", display).put("initialVirtual", state(display))
            assertEquals(480, state(display).getInt("densityDpi"))
            assertEquals(1080, state(display).getInt("width"))
            assertEquals(2160, state(display).getInt("height"))
            val service = field(device, "service") as IDeviceService
            device.setStopped(false)
            fun observe() = device.action("查看", JSONObject().put("执行后等待毫秒", 150))
            fun params(view: JSONObject) = JSONObject().put("动作编号", UUID.randomUUID().toString())
                .put("截图编号", view.getJSONObject("details").getString("截图编号")).put("执行后等待毫秒", 300)
            fun action(operation: String, arguments: JSONObject = JSONObject()): JSONObject {
                val request = params(observe())
                arguments.keys().forEach { key -> request.put(key, arguments.get(key)) }
                return device.action(operation, request)
            }
            fun execution(result: JSONObject) = result.getJSONObject("details").getJSONObject("执行").getString("状态")

            fun focusVirtual() {
                val target = state(display)
                assertEquals("已派发", execution(action("点击", JSONObject().put("位置",
                    JSONArray().put(target.getInt("inputX")).put(target.getInt("inputY"))))))
                Thread.sleep(700)
            }
            fun probe(name: String, text: String) {
                val before = state(display)
                val mainBefore = optionalState(0)
                val inspection = JSONObject(service.inspect())
                val result = JSONObject().put("name", name).put("insert", text)
                    .put("virtualBefore", before).put("mainBefore", mainBefore)
                    .put("inspectionBefore", inspection)
                cases.put(result)
                val started = SystemClock.elapsedRealtime()
                try {
                    val request = params(observe()).put("内容", text)
                    val response = device.action("输入内容", request)
                    result.put("dispatched", execution(response) == "已派发").put("toolDetails", response.getJSONObject("details"))
                    if (name == "main-editor-focused-no-retap") {
                        val duplicate = device.action("输入内容", request)
                        assertTrue(duplicate.getJSONObject("details").getBoolean("本次未重放"))
                        result.put("duplicateNotReplayed", true)
                    }
                } catch (error: Exception) {
                    result.put("dispatched", false).put("error", (error.cause ?: error).toString())
                }
                val previous = before.getString("text")
                fun insertedOnce(actual: String): Boolean = (0..previous.length).any { offset ->
                    actual == previous.substring(0, offset) + text + previous.substring(offset)
                }
                val until = SystemClock.elapsedRealtime() + 3500
                while (!insertedOnce(state(display).getString("text")) && SystemClock.elapsedRealtime() < until) Thread.sleep(100)
                val after = state(display)
                val mainAfter = optionalState(0)
                result.put("elapsedMs", SystemClock.elapsedRealtime() - started)
                    .put("virtualAfter", after).put("mainAfter", mainAfter)
                    .put("insertedExactlyOnce", insertedOnce(after.getString("text")))
                    .put("appendedAtEnd", after.getString("text") == previous + text)
                    .put("mainUnchanged", mainBefore?.optString("text") == mainAfter?.optString("text"))
                    .put("inspectionAfter", JSONObject(service.inspect()))
                val observation = device.action("查看", JSONObject().put("执行后等待毫秒", 100).put("屏幕会话", "virtual"))
                File(evidence, "$name.png").writeBytes(Base64.decode(observation.getJSONArray("content")
                    .getJSONObject(1).getString("data"), Base64.DEFAULT))
                File(evidence, "results.json").writeText(report.toString(2))
            }

            focusVirtual()
            probe("virtual-focused", "虚拟屏甲🙂")

            shell("am start -W --display 0 -f 0x10000000 -a android.settings.SETTINGS")
            Thread.sleep(700)
            probe("main-settings-no-retap", "设置乙🚀")

            shell("am start -W --display 0 -f 0x18000000 -n io.bbui.assistant/.TestTargetActivity")
            val mainDeadline = SystemClock.elapsedRealtime() + 5000
            while (optionalState(0)?.optBoolean("windowFocused") != true && SystemClock.elapsedRealtime() < mainDeadline) Thread.sleep(100)
            val main = state(0)
            report.put("mainSetup", main)
            assertTrue("Main test window did not gain focus", main.getBoolean("windowFocused"))
            // Focus our own debug editor directly; launch animations may swallow a setup tap.
            instrumentation.runOnMainSync {
                val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .single { it is TestTargetActivity && it.display?.displayId == 0 }
                val matches = ArrayList<android.view.View>()
                activity.window.decorView.findViewsWithText(matches, "bbui_test_input", android.view.View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION)
                val editor = matches.single() as android.widget.EditText
                editor.requestFocus()
                (activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                    .showSoftInput(editor, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            }
            Thread.sleep(800)
            report.put("mainBeforeTyping", state(0)).put("mainSetupInspection", JSONObject(service.inspect()))
            assertTrue("Main editor did not gain focus", state(0).getBoolean("inputFocused"))
            shell("input -d 0 text MAINBEFORE")
            // WeType consumes physical key events into its candidate window until Enter.
            shell("input -d 0 keyevent 66")
            Thread.sleep(700)
            assertEquals("MAINBEFORE", state(0).getString("text"))
            probe("main-editor-focused-no-retap", "主屏输入丙中文")

            shell("input -d 0 text AFTER")
            shell("input -d 0 keyevent 66")
            Thread.sleep(300)
            report.put("mainAfterTypingAgain", state(0))
            val selected = action("全选")
            val deleted = action("删除内容", JSONObject().put("次数", 1))
            report.put("selectDelete", JSONObject().put("selectExecution", execution(selected))
                .put("deleteExecution", execution(deleted)).put("virtualAfter", state(display)).put("mainAfter", state(0)))
            assertEquals("已派发", execution(selected))
            assertEquals("已派发", execution(deleted))
            assertEquals("", state(display).getString("text"))
            assertEquals("MAINBEFOREAFTER", state(0).getString("text"))
            focusVirtual()
            probe("virtual-refocused", "重新聚焦丁")
            assertEquals("MAINBEFOREAFTER", report.getJSONObject("mainAfterTypingAgain").getString("text"))
            for (index in 0 until cases.length()) {
                val result = cases.getJSONObject(index)
                assertTrue(result.getString("name"), result.getBoolean("dispatched") &&
                    result.getBoolean("insertedExactlyOnce") && result.getBoolean("mainUnchanged"))
            }
            val old = observe()
            observe()
            val unchanged = state(display).getString("text")
            assertEquals("未派发", execution(device.action("输入内容", params(old).put("内容", "STALE"))))
            val current = observe()
            device.setStopped(true)
            assertEquals("未派发", execution(device.action("输入内容", params(current).put("内容", "STOPPED"))))
            assertEquals(unchanged, state(display).getString("text"))
            report.put("staleAndStoppedInputRejected", true)
        } finally {
            File(evidence, "results.json").writeText(report.toString(2))
            device.setStopped(true)
            device.close()
        }
    }

    private fun field(target: Any, name: String): Any? = target.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(target)
    private fun optionalState(display: Int): JSONObject? = runCatching {
        JSONObject(File(context.noBackupFilesDir, "test-target-$display.json").readText())
    }.getOrNull()
    private fun state(display: Int): JSONObject = checkNotNull(optionalState(display)) { "Missing editor state for $display" }
    private fun shell(command: String): String {
        val output = android.os.ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }
        File(evidence, "shell.txt").appendText("$command\n$output\n")
        return output
    }
}
