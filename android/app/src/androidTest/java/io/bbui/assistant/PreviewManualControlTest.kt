package io.bbui.assistant

import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.bbui.core.PreviewViewport
import io.bbui.device.ShizukuPhoneDevice
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** Reproduces error-without-continuation using real manual touch on the debug counter only. */
@RunWith(AndroidJUnit4::class)
class PreviewManualControlTest {
    @Test(timeout = 180000) fun idleErrorCanTakeOverAndTheIdleBorderFitsLiveVideo() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val ref = AtomicReference<AssistantService>()
        val field = MainActivity::class.java.getDeclaredField("service").apply { isAccessible = true }
        var restore: (() -> Unit)? = null
        try {
            await("binding") { instrumentation.runOnMainSync { ref.set(field.get(activity) as? AssistantService) }; ref.get() != null }
            val service = ref.get()
            val controller = AssistantService::class.java.getDeclaredField("sessions").apply { isAccessible = true }.get(service) as SessionController
            fun mode(): String { val result = AtomicReference<String>(); instrumentation.runOnMainSync { result.set(controller.state.controlMode) }; return result.get() }
            await("catalog ready") { !service.coordinator.isBusy() }
            instrumentation.runOnMainSync {
                val state = controller.state
                assumeTrue(state.running == null && state.continuation == null && state.queue.isEmpty() &&
                    state.controlMode !in setOf("manual", "taking_over", "resuming", "running") &&
                    service.coordinator.environmentSnapshot().getString("state") == "absent")
                val previousMode = state.controlMode; val previousId = state.controlId; val error = state.error
                val reasons = state.pauseReasons.toList()
                restore = { state.controlMode = previousMode; state.controlId = previousId; state.error = error
                    state.pauseReasons.clear(); state.pauseReasons.addAll(reasons); controller.settingsChanged() }
                state.transition("error"); state.error = "local regression fixture"
                controller.settingsChanged()
                MainActivity::class.java.getDeclaredMethod("showPreview", Boolean::class.javaPrimitiveType)
                    .apply { isAccessible = true }.invoke(activity, true)
            }
            // Wait for the service snapshot to reach the native control button.
            SystemClock.sleep(150)
            instrumentation.runOnMainSync {
                val button = descendants(activity.window.decorView).filterIsInstance<Button>().single { it.text == "我来操作" }
                assertTrue(button.isEnabled); button.performClick()
            }
            await("manual ownership after error") { mode() == "manual" }
            val device = AppCoordinator::class.java.getDeclaredField("phone").apply { isAccessible = true }.get(service.coordinator) as ShizukuPhoneDevice
            val details = device.action("查看", JSONObject()).getJSONObject("details")
            val display = details.getInt("显示屏编号")
            assertTrue(display > 0)
            instrumentation.uiAutomation.executeShellCommand("am start --display $display -n io.bbui.assistant/.TestTargetActivity").use {
                android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().use { reader -> reader.readText() }
            }
            val target = File(context.noBackupFilesDir, "test-target-$display.json")
            await("debug target") { target.exists() && runCatching { JSONObject(target.readText()).optInt("incrementX") > 0 }.getOrDefault(false) }
            val initial = JSONObject(target.readText())
            val point = FloatArray(2)
            instrumentation.runOnMainSync {
                val views = descendants(activity.window.decorView)
                assertFalse(views.filterIsInstance<ExecutionEdgeView>().single().isShown)
                val surface = views.filterIsInstance<SurfaceView>().single()
                val viewport = PreviewViewport.fit(surface.width, surface.height, details.getInt("宽"), details.getInt("高"))!!
                val location = IntArray(2).also { surface.getLocationOnScreen(it) }
                point[0] = location[0] + viewport.left + initial.getInt("incrementX") * viewport.width.toFloat() / details.getInt("宽")
                point[1] = location[1] + viewport.top + initial.getInt("incrementY") * viewport.height.toFloat() / details.getInt("高")
            }
            val down = SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val touch = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, point[0], point[1], 0)
                try { instrumentation.sendPointerSync(touch) } finally { touch.recycle() }
            }
            await("actual manual tap arrived exactly once") { JSONObject(target.readText()).getInt("count") == initial.getInt("count") + 1 }
            instrumentation.runOnMainSync {
                descendants(activity.window.decorView).filterIsInstance<Button>().single { it.text == "结束操作" }.performClick()
            }
            await("idle ownership") { mode() == "idle" }
            SystemClock.sleep(200)
            instrumentation.runOnMainSync {
                val views = descendants(activity.window.decorView)
                val edge = views.filterIsInstance<ExecutionEdgeView>().single()
                assertTrue("Idle still belongs to the host", edge.isShown)
                assertTrue(views.filterIsInstance<android.widget.TextView>().any { it.text == "闲置" })
                val expected = PreviewViewport.fit(edge.width, edge.height, details.getInt("宽"), details.getInt("高"))!!
                assertEquals(expected.left.toFloat(), edge.pictureBounds().left, 0f)
                assertEquals(expected.width.toFloat(), edge.pictureBounds().width(), 0f)
            }
            instrumentation.uiAutomation.takeScreenshot()?.let { image ->
                val directory = File(context.filesDir, "gate-evidence").apply { mkdirs() }
                File(directory, "preview-control-live.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
            }
        } finally {
            val service = ref.get()
            if (service != null && restore != null) {
                instrumentation.runOnMainSync {
                    service.sessionCommand(JSONObject().put("type", "closeEnvironment")
                        .put("controlId", service.sessionSnapshot().getJSONObject("control").getString("id"))
                        .put("environmentId", service.coordinator.environmentSnapshot().getString("id")))
                }
                await("cleanup") { service.coordinator.environmentSnapshot().getString("state") == "absent" && !service.coordinator.isBusy() }
                instrumentation.runOnMainSync { restore?.invoke() }
            }
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun await(label: String, test: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 50000
        while (!test() && SystemClock.uptimeMillis() < end) SystemClock.sleep(50)
        assertTrue(label, test())
    }
}
