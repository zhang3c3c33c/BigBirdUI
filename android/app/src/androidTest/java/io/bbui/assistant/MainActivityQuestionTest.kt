package io.bbui.assistant

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Real WebView -> MainActivity -> AssistantService -> question broker.
 * Uses only the local synthetic Pi provider; refuses to touch active user work. */
@RunWith(AndroidJUnit4::class)
class MainActivityQuestionTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test(timeout = 240000) fun draftSubmitAndCancelTraverseActualActivityRoute() {
        val context = instrumentation.targetContext
        val persisted = File(context.noBackupFilesDir, "conversations.json")
        if (persisted.exists()) {
            val state = JSONObject(persisted.readText())
            assertNull("Never interrupt a user task", state.optJSONObject("running"))
            assertTrue("User queue must be paused before isolation", (state.optJSONArray("queue")?.length() ?: 0) == 0 || state.optBoolean("paused"))
            assertNotEquals("Never answer a user question", "pending", state.optJSONObject("pendingQuestion")?.optString("status"))
            assertFalse(state.optString("controlMode") in setOf("running", "manual", "taking_over", "resuming"))
        }
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val serviceField = MainActivity::class.java.getDeclaredField("service").apply { isAccessible = true }
        val chatField = MainActivity::class.java.getDeclaredField("chat").apply { isAccessible = true }
        var service: AssistantService? = null
        var web: ChatWebView? = null
        var fixtureSession: String? = null
        var isolation: AutoCloseable? = null
        var userController: SessionController? = null
        var userState: String? = null
        var failure: Throwable? = null
        fun preserveFailure(error: Throwable, phase: String) {
            android.util.Log.e("BBUI.QuestionRouteTest", phase, error)
            instrumentation.sendStatus(0, android.os.Bundle().apply { putString("stream", "$phase\n${error.stackTraceToString()}\n") })
            if (failure == null) failure = error else if (failure !== error) failure!!.addSuppressed(error)
        }
        fun snapshot(): JSONObject {
            val result = AtomicReference<JSONObject>()
            instrumentation.runOnMainSync { result.set(requireNotNull(service).sessionSnapshot()) }
            return result.get()
        }
        fun command(type: String, fields: JSONObject = JSONObject()) {
            val raw = fields.put("type", type).toString()
            // Dispatch once. Chromium may lose an evaluate callback around page
            // recreation even though native received the command; the native
            // state assertions below are the acknowledgement, never JS return.
            dispatch(requireNotNull(web), "window.BBUI.postMessage(${JSONObject.quote(raw)});")
        }
        try {
            await("activity bound") {
                instrumentation.runOnMainSync { service = serviceField.get(activity) as? AssistantService; web = chatField.get(activity) as? ChatWebView }
                service != null && web != null
            }
            await("catalog ready") { snapshot().optBoolean("sessionsReady") && !requireNotNull(service).coordinator.isBusy() }
            await("page ready") { evaluate(requireNotNull(web), "!!document.querySelector('textarea') && !!window.BBUI") == "true" }
            // Only the controller is swapped. The original object (including a
            // stopped task's continuation and question draft) remains untouched.
            instrumentation.runOnMainSync {
                val controller = AssistantService::class.java.getDeclaredField("sessions").apply { isAccessible = true }.get(service) as SessionController
                userController = controller
                userState = preservedStateHash(controller.state)
                isolation = requireNotNull(service).isolateQuestionTestSessions()
            }
            await("isolated catalog ready") { snapshot().optBoolean("sessionsReady") && !requireNotNull(service).coordinator.isBusy() }
            val before = snapshot()
            command("pauseQueue")
            command("newSession")
            await("new isolated fixture session") { snapshot().optString("sessionId").let { it.isNotBlank() && it != before.optString("sessionId") } }
            fixtureSession = snapshot().getString("sessionId")
            fun send() = command("send", JSONObject().put("sessionId", fixtureSession).put("submissionId", UUID.randomUUID().toString()).put("text", "本地MainActivity提问路由验收"))
            send(); command("resumeQueue")
            await("pending question") { snapshot().optJSONObject("pendingQuestion")?.optString("status") == "pending" }
            val first = JSONObject(snapshot().getJSONObject("pendingQuestion").toString())
            await("text answer field") { evaluate(requireNotNull(web), "!!document.querySelector('.question-card textarea')") == "true" }
            dispatch(requireNotNull(web), "(() => { const e = document.querySelector('.question-card textarea'); Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value').set.call(e, '测试补充 😀'); e.dispatchEvent(new Event('input', { bubbles: true })); })()")
            await("draft reached service through activity") {
                snapshot().optJSONObject("pendingQuestion")?.optJSONArray("draft")?.optJSONObject(0)?.optString("text") == "测试补充 😀"
            }
            val nativeDraft = JSONArray(snapshot().getJSONObject("pendingQuestion").getJSONArray("draft").toString())
            // Actual page recreation must restore the service's saved answer, not local React state.
            val previousDocument = evaluate(requireNotNull(web), "performance.timeOrigin")
            assertNotNull("Current WebView document identity", previousDocument.toDoubleOrNull())
            instrumentation.runOnMainSync { requireNotNull(web).reload() }
            await("draft restored in a new document after page reload") {
                evaluate(requireNotNull(web), "performance.timeOrigin !== $previousDocument && !!window.BBUI && document.querySelector('.question-card textarea')?.value === '测试补充 😀'") == "true"
            }
            command("answerQuestion", JSONObject().put("sessionId", fixtureSession).put("runId", "stale-run")
                .put("requestId", first.getString("requestId")).put("answers", nativeDraft))
            await("invalid identity acknowledgement") { snapshot().optJSONObject("questionResult")?.optString("runId") == "stale-run" }
            assertFalse(snapshot().getJSONObject("questionResult").getBoolean("accepted"))
            assertEquals("pending", snapshot().getJSONObject("pendingQuestion").getString("status"))
            await("submit button ready") { evaluate(requireNotNull(web), "(() => { const b = document.querySelector('.question-card button[type=submit]'); return !!b && !b.disabled; })()") == "true" }
            dispatch(requireNotNull(web), "document.querySelector('.question-card button[type=submit]').click();")
            await("answer acknowledgement through activity") {
                snapshot().optJSONObject("questionResult")?.let { it.optString("requestId") == first.getString("requestId") && it.optBoolean("accepted") } == true
            }
            await("local model received answer") { snapshot().getJSONArray("messages").toString().contains("已收到测试答案") && !requireNotNull(service).coordinator.isBusy() }
            assertEquals("answered", snapshot().getJSONObject("pendingQuestion").getString("status"))
            assertEquals(nativeDraft.toString(), snapshot().getJSONObject("pendingQuestion").getJSONArray("answers").toString())

            send()
            await("second pending question") { snapshot().optJSONObject("pendingQuestion")?.let { it.optString("status") == "pending" && it.optString("requestId") != first.getString("requestId") } == true }
            val second = snapshot().getJSONObject("pendingQuestion").getString("requestId")
            await("cancel button ready") { evaluate(requireNotNull(web), "Array.from(document.querySelectorAll('.question-card button')).some(b => b.textContent === '取消回答' && !b.disabled)") == "true" }
            dispatch(requireNotNull(web), "Array.from(document.querySelectorAll('.question-card button')).find(b => b.textContent === '取消回答' && !b.disabled).click();")
            await("cancel acknowledgement through activity") {
                val state = snapshot()
                state.optJSONObject("questionResult")?.let { it.optString("requestId") == second && it.optBoolean("accepted") } == true &&
                    state.optJSONObject("pendingQuestion")?.optString("status") == "cancelled" && !requireNotNull(service).coordinator.isBusy()
            }
        } catch (error: Throwable) {
            preserveFailure(error, "Question route primary failure")
        } finally {
            // Cleanup only begins after creating our fixture; an idle guard failure
            // must never send STOP to a production task.
            try {
                if (isolation != null && service != null) {
                    // Cleanup must work even if a failed WebView is why the test failed.
                    instrumentation.runOnMainSync { requireNotNull(service).sessionCommand(JSONObject().put("type", "stop")) }
                    await("fixture stopped") { !requireNotNull(service).coordinator.isBusy() }
                    if (fixtureSession != null) {
                        instrumentation.runOnMainSync { requireNotNull(service).sessionCommand(JSONObject().put("type", "deleteSession").put("sessionId", fixtureSession)) }
                        await("fixture deleted") {
                            val sessions = snapshot().getJSONArray("sessions")
                            (0 until sessions.length()).none { sessions.getJSONObject(it).getString("id") == fixtureSession }
                        }
                    }
                }
            } catch (error: Throwable) {
                preserveFailure(error, "Question route fixture cleanup failure")
            } finally {
                try {
                    if (isolation != null) {
                        // delete removes the row before its follow-up refresh has
                        // completed. A missing row alone is not an idle executor.
                        var idleSince = 0L
                        await("fixture catalog drained before restoring user state") {
                            val idle = AtomicReference(false)
                            instrumentation.runOnMainSync {
                                val current = AssistantService::class.java.getDeclaredField("sessions").apply { isAccessible = true }.get(service) as SessionController
                                idle.set(!requireNotNull(service).coordinator.isBusy() && current.state.running == null)
                            }
                            if (!idle.get()) idleSince = 0L else if (idleSince == 0L) idleSince = System.nanoTime()
                            idleSince != 0L && System.nanoTime() - idleSince >= TimeUnit.MILLISECONDS.toNanos(250)
                        }
                    }
                    instrumentation.runOnMainSync {
                        isolation?.close()
                        if (isolation != null) {
                            val controller = AssistantService::class.java.getDeclaredField("sessions").apply { isAccessible = true }.get(service) as SessionController
                            assertSame("Original user controller restored", userController, controller)
                            assertEquals("User continuation, drafts, selection and queue remain intact", userState, preservedStateHash(controller.state))
                        }
                    }
                } catch (error: Throwable) {
                    preserveFailure(error, "Question route user-state restoration failure")
                } finally {
                    try { instrumentation.runOnMainSync { activity.finish() } }
                    catch (error: Throwable) { preserveFailure(error, "Question route activity cleanup failure") }
                }
            }
        }
        failure?.let { throw it }
    }

    // ConversationStore.save includes ChatStore.snapshot timing, which changes
    // merely by reading state. Compare user-owned state, never print private data.
    private fun preservedStateHash(state: ConversationStore): String {
        val saved = state.save()
        val stable = JSONObject()
        for (key in listOf("selected", "paused", "pauseReasons", "controlMode", "continuation", "pendingQuestion", "questionHistory",
            "queue", "running", "drafts", "pinnedSessions", "modelSelections", "modelChoices", "submissions", "interrupted")) {
            stable.put(key, saved.opt(key) ?: JSONObject.NULL)
        }
        return java.security.MessageDigest.getInstance("SHA-256").digest(stable.toString().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun evaluate(web: ChatWebView, script: String): String {
        val latch = CountDownLatch(1); val value = AtomicReference("")
        instrumentation.runOnMainSync { web.evaluateJavascript(script) { value.set(it); latch.countDown() } }
        return if (latch.await(1500, TimeUnit.MILLISECONDS)) value.get() else "<context-transition>"
    }
    private fun dispatch(web: ChatWebView, script: String) {
        instrumentation.runOnMainSync { web.evaluateJavascript(script, null) }
    }
    private fun await(label: String, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 60000
        while (System.currentTimeMillis() < deadline) { if (predicate()) return; Thread.sleep(75) }
        fail("Timed out: $label")
    }
}
