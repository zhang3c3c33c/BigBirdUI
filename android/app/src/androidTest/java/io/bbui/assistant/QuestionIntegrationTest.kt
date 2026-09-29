package io.bbui.assistant

import android.app.Activity
import android.content.ContextWrapper
import android.view.WindowManager
import java.io.File
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** Real Pi loop, native waiting tool and queue; only the loopback synthetic provider is used. */
@RunWith(AndroidJUnit4::class)
class QuestionIntegrationTest {
    @Test(timeout = 240000) fun questionWaitsAcrossSessionSwitchAndStopRejectsLateAnswer() = runScenario("question")
    @Test(timeout = 240000) fun packagedSkillAndTextQuestionSurviveSwitchAndRejectLateAnswers() = runScenario("skill-question")

    private fun runScenario(scenario: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        // Check the production state before binding the shared runtime service.
        // The controller below uses an isolated store and cannot prove production is idle.
        val productionFile = File(context.noBackupFilesDir, "conversations.json")
        if (productionFile.exists()) {
            val production = JSONObject(productionFile.readText())
            assertNull("Never interrupt a real user task", production.optJSONObject("running"))
            assertEquals("Never execute or alter real queued tasks", 0, production.optJSONArray("queue")?.length() ?: 0)
            assertNotEquals("Never replace an active user question", "pending", production.optJSONObject("pendingQuestion")?.optString("status"))
        }
        val host = instrumentation.startActivitySync(Intent().setClassName(context, "io.bbui.assistant.TestTargetActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as Activity
        val directory = File(context.cacheDir, "question-state-${UUID.randomUUID()}").apply { mkdirs() }
        val isolated = object : ContextWrapper(context) { override fun getNoBackupFilesDir(): File = directory }
        lateinit var controller: SessionController
        lateinit var coordinator: AppCoordinator
        instrumentation.runOnMainSync {
            host.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            coordinator = AppCoordinator(context) { controller.accept(it) }
            controller = SessionController(isolated, coordinator) {}
            controller.testConfig = coordinator.localSessionTestConfig(scenario)
            controller.refresh()
        }
        fun snapshot(): JSONObject {
            val state = AtomicReference<JSONObject>()
            instrumentation.runOnMainSync { state.set(controller.state.snapshot()) }; return state.get()
        }
        fun command(type: String, id: String = "", value: JSONObject = JSONObject()) = instrumentation.runOnMainSync {
            controller.command(value.put("type", type).put("sessionId", id))
        }
        fun awaitState(label: String, check: (JSONObject) -> Boolean): JSONObject {
            val deadline = System.currentTimeMillis() + 60000
            var state = snapshot()
            while (!check(state) && System.currentTimeMillis() < deadline) { Thread.sleep(50); state = snapshot() }
            assertTrue("$label: ${state.optString("sessionError")}", check(state)); return state
        }
        awaitState("catalog") { it.optBoolean("sessionsReady") && it.optString("sessionId").isNotBlank() }
        val original = snapshot()
        assertTrue("Never interrupt a user task", original.optString("runningSessionId").isBlank())
        assertEquals("Never modify a user's queued tasks", 0, original.getJSONArray("queue").length())
        val created = mutableListOf<String>()
        try {
            instrumentation.runOnMainSync { controller.testConfig = coordinator.localSessionTestConfig(scenario) }
            command("pauseQueue")
            fun create(): String {
                val before = snapshot().getString("sessionId"); command("newSession")
                return awaitState("new fixture session") { it.optString("sessionId").isNotBlank() && it.optString("sessionId") != before }
                    .getString("sessionId").also { created.add(it) }
            }
            val a = create(); val b = create()
            fun submit(id: String, text: String) = command("send", id, JSONObject().put("text", text).put("submissionId", UUID.randomUUID().toString()))
            submit(a, "本地问题等待测试"); command("resumeQueue")
            val waiting = awaitState("question pending") { it.optJSONObject("pendingQuestion")?.optString("status") == "pending" }
            val question = waiting.getJSONObject("pendingQuestion")
            val q = question.getJSONArray("questions").getJSONObject(0)
            if (scenario == "skill-question") {
                assertEquals(0, q.getJSONArray("options").length())
                val displayState = AtomicReference<String>()
                instrumentation.runOnMainSync { displayState.set(controller.state.chat(a).snapshot().getJSONArray("messages").toString()) }
                val displayed = displayState.get()
                assertTrue(displayed.contains("读取手机操作指南"))
                assertFalse(displayed.contains("SKILL.md"))
                assertFalse(displayed.contains("/data/user/"))
            }
            val answers = JSONArray().put(JSONObject().put("questionId", q.getString("id"))
                .put("selected", if (scenario == "skill-question") JSONArray() else JSONArray().put(q.getJSONArray("options").getJSONObject(0).getString("label"))).put("text", "测试补充 😀"))
            fun reply(request: JSONObject) = JSONObject().put("requestId", request.getString("requestId"))
                .put("runId", request.getString("runId")).put("answers", answers)
            command("questionDraft", a, reply(question))
            command("selectSession", b)
            assertEquals(a, snapshot().getJSONObject("pendingQuestion").getString("sessionId"))
            assertEquals(answers.toString(), snapshot().getJSONObject("pendingQuestion").getJSONArray("draft").toString())
            submit(b, "回答后执行的模拟任务")
            Thread.sleep(400)
            assertEquals(a, snapshot().getString("runningSessionId"))
            assertEquals(1, snapshot().getJSONArray("queue").length())
            assertFalse(snapshot().getJSONObject("control").getBoolean("canSteer"))
            command("answerQuestion", b, reply(question))
            assertFalse(snapshot().getJSONObject("questionResult").getBoolean("accepted"))
            assertEquals("pending", snapshot().getJSONObject("pendingQuestion").getString("status"))
            instrumentation.runOnMainSync { controller.testConfig = coordinator.localSessionTestConfig() }
            command("answerQuestion", a, reply(question))
            assertTrue(snapshot().getJSONObject("questionResult").getBoolean("accepted"))
            command("answerQuestion", a, reply(question))
            assertFalse(snapshot().getJSONObject("questionResult").getBoolean("accepted"))
            awaitState("queue completes after answer") { it.getJSONArray("queue").length() == 0 && it.optString("runningSessionId").isBlank() && !coordinator.isBusy() }
            command("selectSession", a)
            awaitState("question result in history") { it.getJSONArray("messages").toString().contains("已收到测试答案") }
            assertEquals("answered", snapshot().getJSONArray("questionHistory").getJSONObject(0).getString("status"))
            instrumentation.runOnMainSync { controller.testConfig = coordinator.localSessionTestConfig(scenario) }
            submit(a, "本地问题中断测试")
            val second = awaitState("second question") {
                it.optJSONObject("pendingQuestion")?.let { p -> p.optString("status") == "pending" && p.optString("requestId") != question.getString("requestId") } == true
            }.getJSONObject("pendingQuestion")
            command("stop")
            awaitState("stopped question") { it.optJSONObject("pendingQuestion")?.optString("status") == "interrupted" && !coordinator.isBusy() }
            command("answerQuestion", a, reply(second))
            assertFalse(snapshot().getJSONObject("questionResult").getBoolean("accepted"))
            assertTrue(snapshot().getBoolean("queuePaused"))
            submit(b, "本地权限中断测试"); command("resumeQueue")
            val third = awaitState("permission question") {
                it.optJSONObject("pendingQuestion")?.let { p -> p.optString("status") == "pending" && p.optString("requestId") != second.getString("requestId") } == true
            }.getJSONObject("pendingQuestion")
            instrumentation.runOnMainSync {
                controller.testConfig = null
                controller.permissionChanged(ShizukuAvailability.STOPPED)
            }
            awaitState("permission interrupts question") { it.optJSONObject("pendingQuestion")?.optString("status") == "interrupted" && !coordinator.isBusy() }
            command("answerQuestion", b, reply(third))
            assertFalse(snapshot().getJSONObject("questionResult").getBoolean("accepted"))
            instrumentation.runOnMainSync { controller.permissionChanged(ShizukuAvailability.READY) }
            assertTrue(snapshot().getBoolean("queuePaused"))
            assertTrue(snapshot().optString("runningSessionId").isBlank())
        } finally {
            command("stop")
            awaitState("cleanup stop") { !coordinator.isBusy() }
            instrumentation.runOnMainSync { controller.testConfig = null }
            for (id in created) {
                command("deleteSession", id)
                awaitState("remove fixture session") { value -> (0 until value.getJSONArray("sessions").length()).none { value.getJSONArray("sessions").getJSONObject(it).optString("id") == id } }
            }
            command("selectSession", original.getString("sessionId"))
            if (!original.optBoolean("queuePaused")) command("resumeQueue")
            instrumentation.runOnMainSync { controller.close(); coordinator.close(); host.finish() }
        }
    }
}
