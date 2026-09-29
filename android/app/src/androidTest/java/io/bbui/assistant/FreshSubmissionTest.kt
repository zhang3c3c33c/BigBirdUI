package io.bbui.assistant

import android.content.Intent
import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean

/** Real MainActivity bridge and local mock Pi. Production queued work is preserved
 * in its original controller and is never drained, cleared, answered or resumed. */
@RunWith(AndroidJUnit4::class)
class FreshSubmissionTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test(timeout = 300000) fun freshSendClearsOnlyHistoricalAutomaticPausesAndNewStopWins() {
        val context = instrumentation.targetContext
        val productionFile = File(context.noBackupFilesDir, "conversations.json")
        if (productionFile.exists()) {
            val saved = JSONObject(productionFile.readText())
            assertNull("Do not interrupt a user execution", saved.optJSONObject("running"))
            assertTrue("Production queue must remain paused", (saved.optJSONArray("queue")?.length() ?: 0) == 0 || saved.optBoolean("paused"))
            assertNotEquals("pending", saved.optJSONObject("pendingQuestion")?.optString("status"))
            assertFalse(saved.optString("controlMode") in setOf("running", "manual", "taking_over", "resuming"))
        }
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val serviceField = MainActivity::class.java.getDeclaredField("service").apply { isAccessible = true }
        val webField = MainActivity::class.java.getDeclaredField("chat").apply { isAccessible = true }
        val controllerField = AssistantService::class.java.getDeclaredField("sessions").apply { isAccessible = true }
        var service: AssistantService? = null
        var web: ChatWebView? = null
        var isolated: SessionController? = null
        var original: SessionController? = null
        var originalHash: String? = null
        var scope: AutoCloseable? = null
        var fixtureId: String? = null
        var failure: Throwable? = null
        var diskBlock: CountDownLatch? = null
        fun failed(error: Throwable, phase: String) {
            android.util.Log.e("BBUI.FreshSubmissionTest", phase, error)
            instrumentation.sendStatus(0, android.os.Bundle().apply { putString("stream", "$phase\n${error.stackTraceToString()}\n") })
            if (failure == null) failure = error else if (failure !== error) failure!!.addSuppressed(error)
        }
        fun snapshot(): JSONObject = AtomicReference<JSONObject>().also { result ->
            instrumentation.runOnMainSync { result.set(requireNotNull(service).sessionSnapshot()) }
        }.get()
        fun command(type: String, fields: JSONObject = JSONObject()) {
            val payload = JSONObject.quote(fields.put("type", type).toString())
            // One dispatch only. Native state is the acknowledgement, not the
            // optional WebView evaluateJavascript callback.
            instrumentation.runOnMainSync { requireNotNull(web).evaluateJavascript("window.BBUI.postMessage($payload);", null) }
        }
        fun controllerState(action: (ConversationStore) -> Unit) = instrumentation.runOnMainSync { action(requireNotNull(isolated).state) }
        fun currentRun(): String = AtomicReference("").also { result -> controllerState { result.set(it.chat(requireNotNull(fixtureId)).currentRunId()) } }.get()
        fun send(submission: String = UUID.randomUUID().toString(), session: String = requireNotNull(fixtureId)): String {
            command("send", JSONObject().put("submissionId", submission).put("sessionId", session).put("text", "仅本地模拟：新提交队列验收"))
            return submission
        }
        fun ack(id: String, accepted: Boolean = true) {
            await("submission acknowledgement") { snapshot().optJSONObject("submissionResult")?.optString("id") == id }
            assertEquals("Submission acceptance", accepted, snapshot().getJSONObject("submissionResult").getBoolean("accepted"))
        }
        fun reset(reasons: Set<String>) {
            assertFalse("Never reset active fixture work", requireNotNull(service).coordinator.isBusy())
            controllerState { state ->
                check(state.running == null)
                state.queue.clear(); state.continuation = null; state.pendingQuestion = null
                state.pauseReasons.clear(); state.pauseReasons.addAll(reasons)
                state.transition("stopped"); state.submissionResult = null
            }
        }
        fun held(id: String, reason: String, runBefore: String) {
            ack(id)
            // Drain is posted after durable acceptance; cross several main-loop
            // turns to detect an unintended follow-up start.
            Thread.sleep(350)
            assertTrue(snapshot().getBoolean("queuePaused"))
            assertTrue(snapshot().getJSONArray("queuePauseReasons").toString().contains(reason))
            assertTrue(snapshot().optString("runningSessionId").isBlank())
            assertEquals("Held submission must not start Pi", runBefore, currentRun())
            assertFalse(requireNotNull(service).coordinator.isBusy())
        }
        fun diskFence() {
            // A disk executor fence alone misses writes scheduled by the preceding
            // write's main-thread completion callback. Wait for the whole pump.
            val persistenceField = SessionController::class.java.getDeclaredField("persistence").apply { isAccessible = true }
            val deferredField = SessionController::class.java.getDeclaredField("deferredPersist").apply { isAccessible = true }
            val writingField = SessionPersistenceQueue::class.java.getDeclaredField("writing").apply { isAccessible = true }
            val requestsField = SessionPersistenceQueue::class.java.getDeclaredField("requests").apply { isAccessible = true }
            await("isolated persistence pump idle") {
                val idle = AtomicBoolean(false)
                instrumentation.runOnMainSync {
                    val pump = persistenceField.get(isolated)
                    idle.set(!deferredField.getBoolean(isolated) && !writingField.getBoolean(pump) &&
                        (requestsField.get(pump) as java.util.ArrayDeque<*>).isEmpty())
                }
                idle.get()
            }
            val done = CountDownLatch(1)
            val disk = SessionController::class.java.getDeclaredField("disk").apply { isAccessible = true }.get(isolated) as ExecutorService
            disk.execute { done.countDown() }
            assertTrue("Isolated persistence drained", done.await(10, TimeUnit.SECONDS))
        }
        fun verifySaveFailure(secondSave: Boolean) {
            reset(setOf("environment"))
            diskFence()
            val id = UUID.randomUUID().toString()
            val priorRun = currentRun()
            val field = SessionController::class.java.getDeclaredField("file").apply { isAccessible = true }
            val originalFile = field.get(isolated) as AtomicFile
            val fired = AtomicBoolean(false)
            val fault = object : AtomicFile(originalFile.baseFile) {
                override fun startWrite(): FileOutputStream {
                    val matchingPhase = AtomicReference(false)
                    instrumentation.runOnMainSync {
                        val state = requireNotNull(isolated).state
                        matchingPhase.set(state.queue.any { it.optString("id") == id } && (!secondSave || !state.paused))
                    }
                    if (matchingPhase.get() && fired.compareAndSet(false, true)) throw IOException("Synthetic isolated ${if (secondSave) "second" else "first"} save failure")
                    return super.startWrite()
                }
            }
            instrumentation.runOnMainSync { field.set(isolated, fault) }
            try {
                send(id)
                // Second-stage failure is allowed to accept the already durable
                // queue item, but it must remain paused and never run implicitly.
                ack(id, accepted = secondSave)
                assertTrue("The intended storage phase failed", fired.get())
                diskFence()
                val reopened = ConversationStore().also { it.restore(JSONObject(originalFile.baseFile.readText())) }
                val restoredIds = reopened.queue.map { it.getString("id") }
                if (secondSave) {
                    assertTrue("Accepted durable task survives failure", id in restoredIds)
                    assertTrue("Second-save failure keeps queue paused", snapshot().getBoolean("queuePaused"))
                } else {
                    assertFalse("Rejected task must not reappear from an older disk snapshot", id in restoredIds)
                    assertFalse("Rejected submission ID must not appear accepted after restart", id in reopened.submissions)
                }
                assertTrue(reopened.paused)
                assertNull(reopened.running)
                assertEquals("Save failure must not start Pi", priorRun, currentRun())
            } finally {
                diskFence()
                instrumentation.runOnMainSync { field.set(isolated, originalFile) }
            }
        }
        try {
            await("activity binding") {
                instrumentation.runOnMainSync { service = serviceField.get(activity) as? AssistantService; web = webField.get(activity) as? ChatWebView }
                service != null && web != null
            }
            await("production catalog idle") { snapshot().optBoolean("sessionsReady") && !requireNotNull(service).coordinator.isBusy() }
            await("WebView ready") { evaluate(requireNotNull(web), "!!window.BBUI && !!document.querySelector('textarea')") == "true" }
            instrumentation.runOnMainSync {
                original = controllerField.get(service) as SessionController
                originalHash = userStateHash(requireNotNull(original).state)
                scope = requireNotNull(service).isolateQuestionTestSessions()
                isolated = controllerField.get(service) as SessionController
                requireNotNull(service).useLocalSessionTestModel(true)
            }
            await("isolated catalog idle") { snapshot().optBoolean("sessionsReady") && !requireNotNull(service).coordinator.isBusy() }
            val selected = snapshot().optString("sessionId")
            command("newSession")
            await("new Pi fixture") { snapshot().optString("sessionId").let { it.isNotBlank() && it != selected } }
            fixtureId = snapshot().getString("sessionId")
            // Avoid an unrelated first-send rename/catalog request in the timing tests.
            command("renameSession", JSONObject().put("sessionId", fixtureId).put("title", "Fresh submission fixture"))
            await("fixture renamed") {
                val items = snapshot().getJSONArray("sessions")
                (0 until items.length()).any { items.getJSONObject(it).let { row -> row.optString("id") == fixtureId && row.optString("title") == "Fresh submission fixture" } }
            }
            awaitIdle(requireNotNull(service), requireNotNull(isolated))

            val automatic = setOf("stop", "environment", "lock", "error", "restart", "shizuku")
            reset(automatic)
            val first = send()
            ack(first)
            await("fresh task starts and completes without resumeQueue") {
                snapshot().getJSONArray("messages").toString().contains("模拟测试完成") && snapshot().optString("runningSessionId").isBlank() && !requireNotNull(service).coordinator.isBusy()
            }
            awaitIdle(requireNotNull(service), requireNotNull(isolated))
            assertFalse(snapshot().getBoolean("queuePaused"))
            assertEquals(0, snapshot().getJSONArray("queue").length())

            val beforeClose = snapshot()
            val runBeforeClose = currentRun()
            val generationField = AppCoordinator::class.java.getDeclaredField("runGeneration").apply { isAccessible = true }
            val generationBeforeClose = generationField.getLong(requireNotNull(service).coordinator)
            val device = AppCoordinator::class.java.getDeclaredField("phone").apply { isAccessible = true }
                .get(requireNotNull(service).coordinator) as io.bbui.device.ShizukuPhoneDevice
            val epochBeforeClose = device.connectionEpoch()
            // Closing the environment is a native preview command, deliberately
            // absent from the WebView bridge. Exercise its actual service route.
            instrumentation.runOnMainSync {
                requireNotNull(service).sessionCommand(JSONObject().put("type", "closeEnvironment")
                    .put("controlId", beforeClose.getJSONObject("control").getString("id"))
                    .put("environmentId", beforeClose.getJSONObject("environment").getString("id")))
            }
            await("idle environment released") {
                device.connectionEpoch() > epochBeforeClose && snapshot().getJSONObject("environment").optString("state") == "absent" &&
                    !requireNotNull(service).coordinator.isReleasingEnvironment()
            }
            awaitIdle(requireNotNull(service), requireNotNull(isolated))
            assertFalse("Idle close must not pause the next task", snapshot().getBoolean("queuePaused"))
            assertEquals(0, snapshot().getJSONArray("queuePauseReasons").length())
            assertEquals(runBeforeClose, currentRun())
            assertEquals(generationBeforeClose, generationField.getLong(requireNotNull(service).coordinator))
            assertEquals(beforeClose.getJSONArray("messages").toString(), snapshot().getJSONArray("messages").toString())

            // Re-delivering an already completed submission is not a new task.
            reset(setOf("stop"))
            val beforeDuplicate = currentRun()
            send(first)
            await("duplicate acknowledged") { snapshot().optJSONObject("submissionResult")?.optString("id") == first }
            Thread.sleep(350)
            assertEquals(0, snapshot().getJSONArray("queue").length())
            assertTrue(snapshot().getBoolean("queuePaused"))
            assertEquals(beforeDuplicate, currentRun())

            reset(setOf("stop"))
            command("pauseQueue")
            await("explicit user pause") { snapshot().getJSONArray("queuePauseReasons").toString().contains("user") }
            val beforeExplicit = currentRun(); held(send(), "user", beforeExplicit)

            reset(setOf("future-unknown-reason"))
            val beforeUnknown = currentRun(); held(send(), "future-unknown-reason", beforeUnknown)

            reset(setOf("stop"))
            val oldId = UUID.randomUUID().toString()
            controllerState { state -> state.queue.add(JSONObject().put("id", oldId).put("sessionId", fixtureId).put("text", "旧模拟等待项").put("submittedAt", 1)) }
            val beforeOld = currentRun(); held(send(), "stop", beforeOld)
            assertEquals(oldId, snapshot().getJSONArray("queue").getJSONObject(0).getString("id"))
            assertEquals(2, snapshot().getJSONArray("queue").length())

            reset(setOf("stop"))
            controllerState { state -> state.continuation = JSONObject().put("id", "retained-fixture").put("sessionId", fixtureId).put("text", "保留的模拟任务") }
            val beforeContinuation = currentRun(); held(send(), "stop", beforeContinuation)
            controllerState { assertEquals("retained-fixture", it.continuation?.optString("id")) }

            reset(setOf("stop")); controllerState { it.transition("manual") }
            val beforeManual = currentRun(); held(send(), "stop", beforeManual)

            reset(setOf("stop"))
            controllerState { state -> state.pendingQuestion = JSONObject().put("requestId", "synthetic-pending")
                .put("sessionId", fixtureId).put("runId", "synthetic-run").put("toolCallId", "synthetic-call").put("status", "pending")
                .put("questions", JSONArray().put(JSONObject().put("id", "q1").put("header", "测试").put("question", "仅测试未回答状态")
                    .put("options", JSONArray()).put("multiSelect", false))) }
            val beforeQuestion = currentRun(); held(send(), "stop", beforeQuestion)

            reset(setOf("stop"))
            val rejected = send(session = "missing-fixture-session")
            ack(rejected, accepted = false)
            assertTrue(snapshot().getBoolean("queuePaused")); assertEquals(0, snapshot().getJSONArray("queue").length())

            verifySaveFailure(secondSave = false)
            verifySaveFailure(secondSave = true)

            // Freeze only the isolated controller's persistence worker. The new
            // STOP is observed before send's durable callback can clear pauses.
            reset(setOf("environment"))
            val disk = SessionController::class.java.getDeclaredField("disk").apply { isAccessible = true }.get(isolated) as ExecutorService
            val entered = CountDownLatch(1); val release = CountDownLatch(1); diskBlock = release
            disk.execute { entered.countDown(); release.await(120, TimeUnit.SECONDS) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val messagesBeforeStop = snapshot().getJSONArray("messages").toString()
            val raced = send()
            await("submission reached native before persistence") { snapshot().getJSONArray("queue").toString().contains(raced) }
            command("stop")
            await("new STOP observed") { snapshot().getJSONArray("queuePauseReasons").toString().contains("stop") }
            awaitIdle(requireNotNull(service), requireNotNull(isolated))
            // STOP invalidates the generation even without a prompt. Compare
            // against its retired run ID, and independently reject new messages.
            val stoppedRun = currentRun()
            release.countDown(); diskBlock = null
            held(raced, "stop", stoppedRun)
            assertEquals(messagesBeforeStop, snapshot().getJSONArray("messages").toString())
        } catch (error: Throwable) {
            failed(error, "Fresh submission primary failure")
        } finally {
            diskBlock?.countDown()
            try {
                if (scope != null && service != null) {
                    instrumentation.runOnMainSync { requireNotNull(service).sessionCommand(JSONObject().put("type", "stop")) }
                    await("fixture stopped") { !requireNotNull(service).coordinator.isBusy() }
                    // Only synthetic held items exist in the isolated controller.
                    controllerState { it.queue.clear(); it.continuation = null; it.pendingQuestion = null }
                    if (fixtureId != null) {
                        instrumentation.runOnMainSync { requireNotNull(service).sessionCommand(JSONObject().put("type", "deleteSession").put("sessionId", fixtureId)) }
                        await("fixture Pi session deleted") {
                            val list = snapshot().getJSONArray("sessions")
                            (0 until list.length()).none { list.getJSONObject(it).getString("id") == fixtureId }
                        }
                    }
                }
            } catch (error: Throwable) { failed(error, "Fresh submission cleanup failure") }
            finally {
                try {
                    if (scope != null) awaitIdle(requireNotNull(service), requireNotNull(isolated))
                    instrumentation.runOnMainSync {
                        scope?.close()
                        if (scope != null) {
                            val restored = controllerField.get(service) as SessionController
                            assertSame(original, restored)
                            assertEquals("User paused queue and all task data preserved", originalHash, userStateHash(restored.state))
                        }
                    }
                } catch (error: Throwable) { failed(error, "Fresh submission restoration failure") }
                finally { instrumentation.runOnMainSync { activity.finish() } }
            }
        }
        failure?.let { throw it }
    }

    private fun awaitIdle(service: AssistantService, controller: SessionController) {
        var idleSince = 0L
        await("catalog and executor fully idle") {
            val idle = AtomicReference(false)
            instrumentation.runOnMainSync { idle.set(!service.coordinator.isBusy() && controller.state.running == null) }
            if (!idle.get()) idleSince = 0L else if (idleSince == 0L) idleSince = System.nanoTime()
            idleSince != 0L && System.nanoTime() - idleSince >= TimeUnit.MILLISECONDS.toNanos(250)
        }
    }
    private fun userStateHash(state: ConversationStore): String {
        val saved = state.save(); val stable = JSONObject()
        for (key in listOf("selected", "paused", "pauseReasons", "controlMode", "continuation", "pendingQuestion", "questionHistory",
            "queue", "running", "drafts", "pinnedSessions", "modelSelections", "modelChoices", "submissions", "interrupted")) stable.put(key, saved.opt(key) ?: JSONObject.NULL)
        return java.security.MessageDigest.getInstance("SHA-256").digest(stable.toString().toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
    private fun evaluate(web: ChatWebView, script: String): String {
        val result = AtomicReference(""); val latch = CountDownLatch(1)
        instrumentation.runOnMainSync { web.evaluateJavascript(script) { result.set(it); latch.countDown() } }
        return if (latch.await(1500, TimeUnit.MILLISECONDS)) result.get() else "<context-transition>"
    }
    private fun await(label: String, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 60000
        while (System.currentTimeMillis() < deadline) { if (predicate()) return; Thread.sleep(75) }
        fail("Timed out: $label")
    }
}
