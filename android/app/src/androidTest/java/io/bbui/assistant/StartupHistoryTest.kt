package io.bbui.assistant

import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean

/** Read-only recovery of the installed user's selected history; never submits a task. */
@RunWith(AndroidJUnit4::class)
class StartupHistoryTest {
    @Test(timeout = 90000) fun restoresSelectedHistoryAndRemainsResponsive() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val serviceField = MainActivity::class.java.getDeclaredField("service").apply { isAccessible = true }
        val sessionsField = AssistantService::class.java.getDeclaredField("sessions").apply { isAccessible = true }
        val loadedField = SessionController::class.java.getDeclaredField("loaded").apply { isAccessible = true }
        val ready = AtomicBoolean(false)
        val end = SystemClock.uptimeMillis() + 55000
        try {
            while (!ready.get() && SystemClock.uptimeMillis() < end) {
                instrumentation.runOnMainSync {
                    val service = serviceField.get(activity) as? AssistantService
                    val controller = service?.let { sessionsField.get(it) as SessionController }
                    if (controller != null) ready.set(controller.state.ready &&
                        (loadedField.get(controller) as Set<*>).contains(controller.state.selected))
                }
                SystemClock.sleep(100)
            }
            assertTrue("The selected Pi history must actually finish loading", ready.get())
            // The reported crash happened seconds after opening; keep the real screen alive.
            repeat(20) {
                SystemClock.sleep(1000)
                instrumentation.runOnMainSync { assertFalse(activity.isFinishing); assertFalse(activity.isDestroyed) }
            }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
}
