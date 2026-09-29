package io.bbui.assistant

import android.content.Intent
import android.view.WindowManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** A second gate on the same coordinator must start a fresh libnode process. */
@RunWith(AndroidJUnit4::class)
class RuntimeRestartTest {
    @Test(timeout = 180000) fun secondGateWaitsForOldAgentProcessDeath() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val finished = AtomicReference(CountDownLatch(1))
        val result = AtomicReference<JSONObject?>()
        val diagnostic = AtomicReference("No runtime events")
        val reports = JSONArray()
        val trace = RuntimeGateTrace(File(context.filesDir, "gate-evidence"), "runtime-restart-events.jsonl")
        var activity: MainActivity? = null
        var coordinator: AppCoordinator? = null
        try {
            trace.record(JSONObject().put("type", "test_started"))
            activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            instrumentation.runOnMainSync {
                activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                coordinator = AppCoordinator(context) { event ->
                    trace.record(event)
                    when (event.optString("type")) {
                        "runtime_gate_result" -> {
                            result.set(JSONObject(event.toString()))
                            finished.get().countDown()
                        }
                        "status" -> {
                            diagnostic.set(event.optString("message"))
                            if (event.optString("status") == "error") finished.get().countDown()
                        }
                    }
                }
            }
            repeat(2) { round ->
                result.set(null)
                val latch = CountDownLatch(1)
                finished.set(latch)
                instrumentation.runOnMainSync { coordinator!!.runRuntimeGate(JSONObject()) }
                assertTrue("Gate ${round + 1} timed out: ${diagnostic.get()}", latch.await(75, TimeUnit.SECONDS))
                val report = result.get()
                assertNotNull("Gate ${round + 1} failed: ${diagnostic.get()}", report)
                val observed = requireNotNull(report)
                assertTrue("Gate ${round + 1} reported failure: $observed", observed.getBoolean("passed"))
                assertTrue(observed.getInt("toolCalls") >= 2)
                assertTrue(observed.getInt("imagesSeen") >= 2)
                reports.put(observed)
            }
            val evidence = File(context.filesDir, "gate-evidence").apply { mkdirs() }
            File(evidence, "runtime-restart.json").writeText(JSONObject()
                .put("firstPassed", true).put("secondPassed", true).put("rounds", reports).toString(2))
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
