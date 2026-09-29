package io.bbui.assistant

import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicReference

/** Browsing only: never connects, takes over, closes an existing environment or calls a model. */
@RunWith(AndroidJUnit4::class)
class EnvironmentUiTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test(timeout = 45000) fun previewAndCancelledCloseDoNotCreateOrReleaseEnvironment() {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        var dialogOpen = false
        var menuOpen = false
        try {
            val serviceField = MainActivity::class.java.getDeclaredField("service").apply { isAccessible = true }
            val serviceRef = AtomicReference<AssistantService>()
            val deadline = SystemClock.uptimeMillis() + 10000
            while (serviceRef.get() == null && SystemClock.uptimeMillis() < deadline) {
                instrumentation.runOnMainSync { serviceRef.set(serviceField.get(activity) as? AssistantService) }
                if (serviceRef.get() == null) SystemClock.sleep(50)
            }
            assumeTrue("The initial setup must be complete", serviceRef.get() != null)
            val service = serviceRef.get()
            val controllerField = AssistantService::class.java.getDeclaredField("sessions").apply { isAccessible = true }
            val original = AtomicReference<JSONObject>()
            instrumentation.runOnMainSync {
                val state = (controllerField.get(service) as SessionController).state
                assumeTrue("Do not touch an existing task or retained environment",
                    state.running == null && state.continuation == null && state.queue.isEmpty() &&
                        state.controlMode !in setOf("manual", "taking_over", "resuming", "running") &&
                        service.coordinator.environmentSnapshot().optString("state") == "absent")
                original.set(state.snapshot())
            }
            val before = service.coordinator.environmentSnapshot().toString()
            val showPreview = MainActivity::class.java.getDeclaredMethod("showPreview", Boolean::class.javaPrimitiveType)
                .apply { isAccessible = true }
            instrumentation.runOnMainSync { showPreview.invoke(activity, true) }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                assertFalse("An absent environment must not show a retained frame", descendants(activity.window.decorView).filterIsInstance<SurfaceView>().single().isShown)
                assertTrue(descendants(activity.window.decorView).filterIsInstance<TextView>().any { it.text == "执行画面 · 未连接" })
            }
            // Let the Surface callbacks and any mistakenly dispatched connect reach the worker.
            val observeUntil = SystemClock.uptimeMillis() + 1500
            while (SystemClock.uptimeMillis() < observeUntil) {
                assertEquals("Viewing an empty preview must not allocate a display", before, service.coordinator.environmentSnapshot().toString())
                SystemClock.sleep(50)
            }
            instrumentation.runOnMainSync {
                val menu = descendants(activity.window.decorView).filterIsInstance<Button>()
                    .single { it.contentDescription == "执行画面菜单" }
                assertTrue(menu.isShown)
                menu.performClick()
            }
            menuOpen = true
            clickText("关闭执行环境")
            menuOpen = false
            dialogOpen = true
            assertTrue(awaitText("关闭执行环境？") != null)
            assertTrue(awaitText("当前执行画面将结束，正在执行的任务会停止，队列会暂停。聊天记录和待续任务将保留。") != null)
            clickText("取消")
            dialogOpen = false
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val now = (controllerField.get(service) as SessionController).state.snapshot()
                assertEquals("Cancel must leave task control unchanged", original.get().getJSONObject("control").toString(), now.getJSONObject("control").toString())
                assertEquals("Cancel must leave queue pause state unchanged", original.get().getBoolean("queuePaused"), now.getBoolean("queuePaused"))
                assertEquals("Cancel must leave queue pause reasons unchanged", original.get().getJSONArray("queuePauseReasons").toString(), now.getJSONArray("queuePauseReasons").toString())
                assertEquals("Cancel must neither create nor release the display", before, service.coordinator.environmentSnapshot().toString())
                val title = descendants(activity.window.decorView).filterIsInstance<TextView>().single { it.text == "执行画面 · 未连接" }
                assertEquals(android.view.Gravity.CENTER, title.gravity)
                val back = descendants(activity.window.decorView).filterIsInstance<Button>().single { it.contentDescription == "返回对话" }
                val menu = descendants(activity.window.decorView).filterIsInstance<Button>().single { it.contentDescription == "执行画面菜单" }
                assertEquals(back.width, menu.width)
                assertEquals((title.parent as View).width / 2f, title.x + title.width / 2f, 1f)
                back.performClick()
                assertFalse(title.isShown)
                assertEquals(original.get().getJSONObject("control").toString(), (controllerField.get(service) as SessionController).state.controlSnapshot().toString())
            }
        } finally {
            if (dialogOpen || menuOpen) instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    private fun awaitText(text: String): AccessibilityNodeInfo? {
        val deadline = SystemClock.uptimeMillis() + 5000
        while (SystemClock.uptimeMillis() < deadline) {
            val match = instrumentation.uiAutomation.rootInActiveWindow
                ?.findAccessibilityNodeInfosByText(text)?.firstOrNull { it.text?.toString() == text }
            if (match != null) return match
            SystemClock.sleep(50)
        }
        return null
    }

    private fun clickText(text: String) {
        var node = requireNotNull(awaitText(text)) { "Missing UI control: $text" }
        while (!node.isClickable && node.parent != null) node = node.parent
        assertTrue("Unable to activate $text", node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }
}
