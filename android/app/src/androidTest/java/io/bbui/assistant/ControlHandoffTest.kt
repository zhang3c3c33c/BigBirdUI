package io.bbui.assistant

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Exercises the actual service/Pi boundary using its loopback model and simulated phone. */
@RunWith(AndroidJUnit4::class)
class ControlHandoffTest : ForegroundDeviceTest() {
    @Test(timeout = 300000) fun handoffPreservesOwnerAndDoesNotReleaseUnrelatedWaitingWork() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val bound = CountDownLatch(1)
        val reference = AtomicReference<AssistantService>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                reference.set((binder as AssistantService.LocalBinder).service)
                bound.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName) { }
        }
        assertTrue(context.bindService(Intent(context, AssistantService::class.java), connection, Context.BIND_AUTO_CREATE))
        try {
            assertTrue(bound.await(10, TimeUnit.SECONDS))
            val service = reference.get()
            fun snapshot(): JSONObject {
                val result = AtomicReference<JSONObject>()
                instrumentation.runOnMainSync { result.set(service.sessionSnapshot()) }
                return result.get()
            }
            fun command(type: String, sessionId: String = "", extra: JSONObject = JSONObject()) {
                instrumentation.runOnMainSync {
                    service.sessionCommand(extra.put("type", type).put("sessionId", sessionId))
                }
            }
            fun controlCommand(type: String, controlId: String = snapshot().getJSONObject("control").getString("id")) {
                command(type, extra = JSONObject().put("controlId", controlId))
            }
            fun awaitState(description: String, check: (JSONObject) -> Boolean): JSONObject {
                val deadline = System.currentTimeMillis() + 70000
                var value = snapshot()
                while (!check(value) && System.currentTimeMillis() < deadline) {
                    Thread.sleep(40)
                    value = snapshot()
                }
                assertTrue("$description; control=${value.optJSONObject("control")}; error=${value.optString("sessionError")}", check(value))
                return value
            }
            fun queueIds(value: JSONObject): List<String> = value.getJSONArray("queue").let { rows ->
                (0 until rows.length()).map { rows.getJSONObject(it).getString("id") }
            }
            fun pauseReasons(value: JSONObject): List<String> = value.getJSONArray("queuePauseReasons").let { rows ->
                (0 until rows.length()).map { rows.getString(it) }
            }
            awaitState("catalog ready") { it.optBoolean("sessionsReady") && it.optString("sessionId").isNotBlank() }
            val initial = snapshot()
            // Never discard a user's paused continuation, manual control, or waiting work.
            assumeTrue("Requires an idle phone without a retained task", initial.optString("runningSessionId").isBlank() &&
                !initial.getJSONObject("control").optBoolean("canResume") &&
                initial.getJSONObject("control").optString("mode") !in setOf("manual", "taking_over", "resuming"))
            assumeTrue("Preserve the user's queue", initial.getJSONArray("queue").length() == 0)
            val original = initial.getString("sessionId")
            val created = mutableListOf<String>()
            val starts = mutableListOf<String>()
            val observations = mutableListOf<Int>()
            var interruptNextObservation = ""
            val eventListener: (JSONObject) -> Unit = { event ->
                when (event.optString("type")) {
                    "run_started" -> starts.add(service.sessionSnapshot().optString("runningSessionId"))
                    "phone_action_result" -> {
                        observations.add(starts.size)
                        val action = interruptNextObservation
                        if (action.isNotBlank()) {
                            interruptNextObservation = ""
                            val request = JSONObject().put("type", action)
                            if (action == "takeOver") request.put("controlId", service.sessionSnapshot().getJSONObject("control").getString("id"))
                            service.sessionCommand(request)
                        }
                    }
                }
            }
            fun startsNow(): List<String> {
                val value = AtomicReference<List<String>>()
                instrumentation.runOnMainSync { value.set(starts.toList()) }
                return value.get()
            }
            fun create(label: String): String {
                val previous = snapshot().getString("sessionId")
                command("newSession")
                val id = awaitState("create $label") { it.optString("sessionId").isNotBlank() && it.optString("sessionId") != previous }.getString("sessionId")
                created.add(id)
                command("renameSession", id, JSONObject().put("title", label))
                awaitState("rename $label") { value -> value.getJSONArray("sessions").let { rows ->
                    (0 until rows.length()).any { rows.getJSONObject(it).optString("id") == id && rows.getJSONObject(it).optString("title") == label }
                } }
                return id
            }
            fun submit(id: String, text: String): String {
                val key = UUID.randomUUID().toString()
                command("send", id, JSONObject().put("text", text).put("submissionId", key))
                return key
            }
            try {
                instrumentation.runOnMainSync {
                    service.useLocalSessionTestModel(true)
                    service.subscribe(eventListener)
                }
                command("pauseQueue")
                val a = create("本地交接测试 A")
                val b = create("本地交接测试 B")
                awaitState("catalog is idle") { !service.coordinator.isBusy() }

                // Viewing/controlling an idle screen must not manufacture an AI request.
                controlCommand("takeOver")
                val idleManual = awaitState("idle manual control") { it.getJSONObject("control").optString("mode") == "manual" }
                assertFalse(idleManual.getJSONObject("control").getBoolean("canResume"))
                controlCommand("endManual")
                awaitState("end idle manual control") { it.getJSONObject("control").optString("mode") != "manual" }
                assertEquals(emptyList<String>(), startsNow())

                val originalPrompt = "交接测试 A：查看模拟画面后报告结果"
                submit(a, originalPrompt)
                val waitingB = submit(b, "交接测试 B：稍后查看模拟画面")
                command("selectSession", b)
                instrumentation.runOnMainSync { interruptNextObservation = "takeOver" }
                command("resumeQueue")
                val manual = awaitState("take over A after its first observation") {
                    it.getJSONObject("control").optString("mode") == "manual"
                }
                val manualControl = manual.getJSONObject("control")
                val handoffId = manualControl.getString("id")
                assertEquals(b, manual.getString("sessionId"))
                assertEquals(a, manualControl.getString("sessionId"))
                assertTrue(manualControl.getBoolean("canResume"))
                assertEquals(listOf(a), startsNow())
                assertEquals(listOf(waitingB), queueIds(manual))
                assertFalse(service.coordinator.isBusy())

                command("resumeQueue")
                // Let any mistakenly scheduled async drain run before asserting ownership.
                Thread.sleep(400)
                assertEquals("manual", snapshot().getJSONObject("control").getString("mode"))
                assertEquals(listOf(a), startsNow())
                assertEquals(listOf(waitingB), queueIds(snapshot()))
                val submittedDuringManual = submit(b, "人工操作期间的新任务仍应等待")
                Thread.sleep(400)
                assertEquals("manual", snapshot().getJSONObject("control").getString("mode"))
                assertEquals(listOf(a), startsNow())
                assertEquals(listOf(waitingB, submittedDuringManual), queueIds(snapshot()))
                command("cancelQueued", extra = JSONObject().put("submissionId", submittedDuringManual))
                command("pauseQueue")
                assertTrue(pauseReasons(snapshot()).contains("user"))
                controlCommand("resumeTask", "retired-control-id")
                assertEquals("manual", snapshot().getJSONObject("control").getString("mode"))

                // The same displayed control id may arrive twice from rapid taps / bridge delivery.
                controlCommand("resumeTask", handoffId)
                controlCommand("resumeTask", handoffId)
                awaitState("one continuation run") { startsNow().size == 2 }
                controlCommand("takeOver", handoffId)
                awaitState("continued A settles without releasing B") {
                    startsNow().size == 2 && it.optString("runningSessionId").isBlank() && !service.coordinator.isBusy()
                }
                assertEquals(listOf(a, a), startsNow())
                assertEquals(listOf(waitingB), queueIds(snapshot()))
                assertTrue(pauseReasons(snapshot()).contains("user"))
                instrumentation.runOnMainSync {
                    assertTrue("Continuation must observe again", observations.contains(2))
                }
                command("selectSession", a)
                awaitState("restored original conversation") { it.getJSONArray("messages").toString().contains(originalPrompt) }
                val messages = snapshot().getJSONArray("messages")
                val originalOccurrences = (0 until messages.length()).count {
                    val message = messages.getJSONObject(it)
                    val parts = message.optJSONArray("parts") ?: JSONArray()
                    val text = (0 until parts.length()).joinToString("") { index -> parts.getJSONObject(index).optString("text") }
                    message.optString("role") == "user" && text == originalPrompt
                }
                assertEquals("Resume must not replay the original user submission", 1, originalOccurrences)

                // STOP while B runs retains the subsequent submission and cannot dispatch it.
                val waitingA = submit(a, "此任务应保留等待，不自动执行")
                instrumentation.runOnMainSync { interruptNextObservation = "stop" }
                command("resumeQueue")
                val stopped = awaitState("STOP settles B") {
                    startsNow().size == 3 && it.getJSONObject("control").optString("mode") == "stopped" && !service.coordinator.isBusy()
                }
                assertEquals(listOf(a, a, b), startsNow())
                assertEquals(b, stopped.getJSONObject("control").getString("sessionId"))
                assertTrue(stopped.getJSONObject("control").getBoolean("canResume"))
                assertTrue(stopped.getBoolean("queuePaused"))
                assertEquals(listOf(waitingA), queueIds(stopped))
                Thread.sleep(400)
                assertEquals(listOf(a, a, b), startsNow())
                val report = File(context.filesDir, "gate-evidence").apply { mkdirs() }
                File(report, "control-handoff.json").writeText(JSONObject().put("passed", true)
                    .put("executionOrder", JSONArray(listOf("A", "A continuation", "B")))
                    .put("idleHandoffDoesNotCallModel", true).put("browsingDoesNotChangeOwner", true)
                    .put("globalQueueResumeCannotStealManualControl", true).put("userPauseSurvivesHandoff", true)
                    .put("staleAndRepeatedControlSuppressed", true).put("continuationObservesAgain", true)
                    .put("originalSubmissionNotReplayed", true).put("stopPreservesWaitingWork", true).toString(2))
            } finally {
                instrumentation.runOnMainSync {
                    interruptNextObservation = ""
                    service.unsubscribe(eventListener)
                    service.sessionCommand(JSONObject().put("type", "stop"))
                }
                awaitState("cleanup stop settles") { !service.coordinator.isBusy() }
                // Keep the loopback model selected until all test queue entries are removed.
                for (id in created) {
                    command("deleteSession", id)
                    awaitState("remove temporary session") { value -> value.getJSONArray("sessions").let { rows ->
                        (0 until rows.length()).none { rows.getJSONObject(it).optString("id") == id }
                    } }
                }
                command("selectSession", original)
                instrumentation.runOnMainSync { service.useLocalSessionTestModel(false) }
                if (initial.optBoolean("queuePaused")) command("pauseQueue") else command("resumeQueue")
            }
        } finally {
            context.unbindService(connection)
        }
    }
}


