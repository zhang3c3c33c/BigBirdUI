package io.bbui.assistant

import android.content.Intent
import android.app.Activity
import android.view.WindowManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean

/** Runs bundled Pi against the local deterministic vision/tool provider; no credentials or phone input. */
@RunWith(AndroidJUnit4::class)
class RuntimeGateTest {
    @Test(timeout = 150000) fun embeddedPiCompletesTwoImageToolRounds() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val finished = CountDownLatch(1)
        val result = AtomicReference<JSONObject?>()
        val diagnostic = AtomicReference("No runtime events received")
        val restored = AtomicBoolean(false)
        val readyAfterHistory = AtomicBoolean(false)
        val timestamped = AtomicBoolean(false)
        val intentReceived = AtomicBoolean(false)
        val taskCompleted = AtomicBoolean(false)
        val trace = RuntimeGateTrace(File(context.filesDir, "gate-evidence"), "runtime-gate-events.jsonl")
        var activity: Activity? = null
        var coordinator: AppCoordinator? = null
        try {
            trace.record(JSONObject().put("type", "test_started"))
            // Some OEMs freeze instrumented background processes despite the
            // runner remaining connected. Keep our test application visible.
            activity = instrumentation.startActivitySync(Intent().setClassName(context, "io.bbui.assistant.TestTargetActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as Activity
            instrumentation.runOnMainSync {
                activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                coordinator = AppCoordinator(context) { event ->
                    trace.record(event)
                    if (event.optString("type") == "history_snapshot") {
                        restored.set(event.optJSONArray("messages")?.length() == 0 && event.optString("sessionId").isNotBlank())
                        timestamped.set(event.optLong("runtimeReceivedAtMs") > 0L)
                    }
                    if (event.optString("type") == "runtime_ready") readyAfterHistory.set(restored.get())
                    if (event.optString("type") == "tool_execution_start" && event.optString("toolName") == "phone_action") {
                        intentReceived.set(event.optJSONObject("args")?.optString("意图")?.isNotBlank() == true)
                    }
                    if (event.optString("type") == "tool_execution_end" && event.optString("toolName") == "task_state" && !event.optBoolean("isError")) {
                        val task = event.optJSONObject("result")?.optJSONObject("details")?.optJSONObject("bbuiTask")
                        taskCompleted.set(task?.optString("status") == "completed" && task.optString("completionEvidence").isNotBlank())
                    }
                    if (event.optString("type") == "runtime_gate_result") {
                        result.set(JSONObject(event.toString()))
                        finished.countDown()
                    } else if (event.optString("type") == "status") {
                        diagnostic.set(event.optString("message").take(2048))
                        if (event.optString("status") == "error") finished.countDown()
                    }
                }
                coordinator?.runRuntimeGate(JSONObject())
            }
            assertTrue("Runtime gate timed out: ${diagnostic.get()}", finished.await(120, TimeUnit.SECONDS))
            val observed = result.get()
            assertNotNull("Runtime gate failed: ${diagnostic.get()}", observed)
            val report = requireNotNull(observed)
            assertTrue("Runtime gate reported failure: $report", report.getBoolean("passed"))
            assertTrue("Expected at least two tool calls", report.getInt("toolCalls") >= 2)
            assertTrue("Expected both images in model input", report.getInt("imagesSeen") >= 2)
            assertTrue("Pi history must precede runtime_ready", readyAfterHistory.get())
            assertTrue("History receipt must be timestamped", timestamped.get())
            assertTrue("Phone tool events must retain model-authored intent", intentReceived.get())
            assertTrue("Bundled Pi must execute the task-state tool without routing it to phone input", taskCompleted.get())
            val evidence = File(context.filesDir, "gate-evidence").apply { mkdirs() }
            File(evidence, "runtime-gate.json").writeText(report.toString(2))
        } finally {
            instrumentation.runOnMainSync {
                coordinator?.close()
                activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                activity?.finish()
            }
            trace.record(JSONObject().put("type", "test_finished"))
            trace.close()
        }
    }
}
