package io.bbui.assistant

import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.bbui.device.DevicePermission
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicReference

/** Service scheduling + real scrcpy, with no model request and no user-application input. */
@RunWith(AndroidJUnit4::class)
class EnvironmentServiceTest {
    @Test(timeout = 240000) fun explicitCloseAndBackgroundIdleReleaseThroughService() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val serviceField = MainActivity::class.java.getDeclaredField("service").apply { isAccessible = true }
        val serviceRef = AtomicReference<AssistantService>()
        var restore: (() -> Unit)? = null
        try {
            await("service binding") {
                instrumentation.runOnMainSync { serviceRef.set(serviceField.get(activity) as? AssistantService) }
                serviceRef.get() != null
            }
            val service = serviceRef.get()
            val controller = AssistantService::class.java.getDeclaredField("sessions").apply { isAccessible = true }
                .get(service) as SessionController
            await("catalog idle") {
                val ready = AtomicReference(false)
                instrumentation.runOnMainSync { ready.set(controller.state.ready && !service.coordinator.isBusy()) }
                ready.get()
            }
            instrumentation.runOnMainSync {
                val state = controller.state
                assumeTrue("Requires idle service; preserve all existing user work", state.running == null &&
                    state.continuation == null && state.queue.isEmpty() && state.controlMode !in setOf("manual", "taking_over", "resuming", "running") &&
                    service.coordinator.environmentSnapshot().getString("state") == "absent")
                assertTrue(DevicePermission.granted())
                val reasons = state.pauseReasons.toList()
                val mode = state.controlMode; val controlId = state.controlId; val error = state.error
                restore = {
                    state.pauseReasons.clear(); state.pauseReasons.addAll(reasons)
                    state.controlMode = mode; state.controlId = controlId; state.error = error
                    // Persist the restored controls through the existing native state path.
                    controller.settingsChanged()
                }
                service.coordinator.connect()
            }
            await("first environment ready") { service.coordinator.environmentSnapshot().optString("state") == "ready" && !service.coordinator.isBusy() }
            val first = service.coordinator.environmentSnapshot().getString("id")
            instrumentation.runOnMainSync {
                service.sessionCommand(JSONObject().put("type", "closeEnvironment").put("controlId", controller.state.controlId)
                    .put("environmentId", "stale-environment"))
                assertEquals("ready", service.coordinator.environmentSnapshot().getString("state"))
                assertEquals(first, service.coordinator.environmentSnapshot().getString("id"))
                service.sessionCommand(JSONObject().put("type", "closeEnvironment").put("controlId", controller.state.controlId)
                    .put("environmentId", first))
                assertTrue(controller.state.paused)
            }
            await("explicit close completes") { service.coordinator.environmentSnapshot().optString("state") == "absent" && !service.coordinator.isBusy() }
            SystemClock.sleep(300)
            assertEquals("absent", service.coordinator.environmentSnapshot().getString("state"))
            instrumentation.runOnMainSync { service.coordinator.connect() }
            await("explicit reconnect") { service.coordinator.environmentSnapshot().optString("state") == "ready" && !service.coordinator.isBusy() }
            assertNotEquals(first, service.coordinator.environmentSnapshot().getString("id"))

            // The real Activity transition exercises the service's foreground tracking. Advance
            // only the idle clock, without adding a production timeout override or waiting 10 min.
            instrumentation.runOnMainSync { assertTrue(activity.moveTaskToBack(true)) }
            await("application background") {
                val background = AtomicReference(false)
                instrumentation.runOnMainSync {
                    val foreground = AssistantService::class.java.getDeclaredField("mainForeground").apply { isAccessible = true }.getBoolean(service)
                    val resumed = AssistantService::class.java.getDeclaredField("resumedActivities").apply { isAccessible = true }.get(service) as Set<*>
                    background.set(!foreground && resumed.isEmpty())
                }
                background.get()
            }
            instrumentation.runOnMainSync {
                val policy = AssistantService::class.java.getDeclaredField("idlePolicy").apply { isAccessible = true }.get(service)
                EnvironmentIdlePolicy::class.java.getDeclaredField("idleSince").apply { isAccessible = true }
                    .set(policy, SystemClock.elapsedRealtime() - 600001L)
                AssistantService::class.java.getDeclaredMethod("updateEnvironmentRetention").apply { isAccessible = true }.invoke(service)
            }
            await("background idle release") { service.coordinator.environmentSnapshot().optString("state") == "absent" && !service.coordinator.isBusy() }
            instrumentation.runOnMainSync {
                assertTrue(controller.state.paused)
                assertNull(controller.state.running)
                assertTrue(controller.state.queue.isEmpty())
            }
        } finally {
            val service = serviceRef.get()
            if (service != null && restore != null) {
                instrumentation.runOnMainSync { service.coordinator.releasePhone() }
                await("test environment cleanup") { service.coordinator.environmentSnapshot().optString("state") == "absent" && !service.coordinator.isBusy() }
                instrumentation.runOnMainSync { restore?.invoke() }
            }
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    private fun await(description: String, predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 65000
        while (!predicate() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
        assertTrue(description, predicate())
    }
}
