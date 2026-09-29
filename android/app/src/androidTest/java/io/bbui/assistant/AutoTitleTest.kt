package io.bbui.assistant

import android.content.*
import android.os.IBinder
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Real Android Node + durable submission route, loopback model only and no phone actions. */
@RunWith(AndroidJUnit4::class)
class AutoTitleTest : ForegroundDeviceTest() {
    @Test(timeout = 180000) fun titlesAreBackgroundAndLateResponsesCannotOverrideManualNames() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val bound = CountDownLatch(1)
        val serviceRef = AtomicReference<AssistantService>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                serviceRef.set((binder as AssistantService.LocalBinder).service); bound.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName) {}
        }
        context.bindService(Intent(context, AssistantService::class.java), connection, Context.BIND_AUTO_CREATE)
        assertTrue(bound.await(10, TimeUnit.SECONDS))
        val service = serviceRef.get()
        fun snapshot(): JSONObject = AtomicReference<JSONObject>().also { value ->
            instrumentation.runOnMainSync { value.set(service.sessionSnapshot()) }
        }.get()
        fun await(label: String, condition: () -> Boolean) {
            val end = System.currentTimeMillis() + 45000
            while (!condition() && System.currentTimeMillis() < end) Thread.sleep(50)
            assertTrue(label, condition())
        }
        await("catalog idle") { snapshot().optBoolean("sessionsReady") && !service.coordinator.isBusy() }
        val before = snapshot()
        assertEquals("", before.optString("runningSessionId"))
        assertTrue(before.getJSONArray("queue").length() == 0 || before.optBoolean("queuePaused"))
        assertFalse(before.getJSONObject("control").optString("mode") in setOf("running", "manual", "taking_over", "resuming"))
        assertNotEquals("pending", before.optJSONObject("pendingQuestion")?.optString("status"))
        val home = File(context.noBackupFilesDir, "pi-agent")
        val settings = listOf("settings.json", "models.json").associateWith { File(home, it).takeIf(File::exists)?.readText() }
        val titleRequests = AtomicInteger()
        val taskRequests = AtomicInteger()
        val secondTitle = CountDownLatch(1)
        val releaseTitle = CountDownLatch(1)
        val requestErrors = AtomicReference<String>()
        val server = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(request: IHTTPSession): Response {
                request.headers["content-type"] = "application/json; charset=utf-8"
                val body = mutableMapOf<String, String>(); request.parseBody(body)
                val value = JSONObject(body["postData"].orEmpty())
                val naming = value.getJSONArray("messages").toString().contains("为会话起一个简短")
                if (value.optString("model") != "title-fixture") requestErrors.set("Unexpected model")
                if (naming) {
                    if (titleRequests.incrementAndGet() == 2) {
                        secondTitle.countDown(); releaseTitle.await(15, TimeUnit.SECONDS)
                    }
                    val content = value.getJSONArray("messages").toString()
                    if (content.contains("image_url") || content.contains("tool_calls")) requestErrors.set("Title leaked non-text history")
                } else taskRequests.incrementAndGet()
                fun event(delta: JSONObject, reason: String?) = JSONObject().put("choices", JSONArray().put(JSONObject()
                    .put("index", 0).put("delta", delta).put("finish_reason", reason ?: JSONObject.NULL))).toString()
                val text = if (naming) "北京旅行攻略与景点收藏" else "本地任务已结束"
                return newFixedLengthResponse(Response.Status.OK, "text/event-stream",
                    "data: ${event(JSONObject().put("content", text), null)}\n\ndata: ${event(JSONObject(), "stop")}\n\ndata: [DONE]\n\n")
            }
        }
        var scope: AutoCloseable? = null
        val fixtures = mutableListOf<String>()
        val field = AssistantService::class.java.getDeclaredField("sessions").apply { isAccessible = true }
        var controller: SessionController? = null
        fun command(type: String, data: JSONObject = JSONObject()) = instrumentation.runOnMainSync {
            service.sessionCommand(data.put("type", type))
        }
        fun newSession(): String {
            val prior = snapshot().optString("sessionId")
            command("newSession")
            await("new fixture") { snapshot().optString("sessionId").let { it.isNotBlank() && it != prior } }
            return snapshot().getString("sessionId").also { fixtures.add(it) }
        }
        fun title(id: String): String {
            val rows = snapshot().getJSONArray("sessions")
            return (0 until rows.length()).map { rows.getJSONObject(it) }.firstOrNull { it.optString("id") == id }?.optString("title").orEmpty()
        }
        fun send(id: String) {
            val submission = UUID.randomUUID().toString()
            command("send", JSONObject().put("sessionId", id).put("submissionId", submission).put("text", "帮我整理北京旅行攻略并收藏三个景点"))
            await("durable acknowledgement") { snapshot().optJSONObject("submissionResult")?.optString("id") == submission }
            assertTrue(snapshot().getJSONObject("submissionResult").getBoolean("accepted"))
        }
        try {
            server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true)
            instrumentation.runOnMainSync {
                scope = service.isolateQuestionTestSessions()
                controller = field.get(service) as SessionController
                controller!!.testConfig = JSONObject().put("provider", "fixture").put("api", "openai-completions")
                    .put("model", "title-fixture").put("baseUrl", "http://127.0.0.1:${server.listeningPort}/v1")
                    .put("apiKey", "synthetic-title-key").put("mockPhone", true)
            }
            await("isolated catalog") { snapshot().optBoolean("sessionsReady") && !service.coordinator.isBusy() }
            val first = newSession(); send(first)
            try { await("generated title") { title(first) == "北京旅行攻略与景点收藏" } }
            catch (error: AssertionError) {
                val debug = AtomicReference("")
                instrumentation.runOnMainSync {
                    val fieldRequests = AppCoordinator::class.java.getDeclaredField("titleRequests").apply { isAccessible = true }
                    debug.set("pendingTitles=${(fieldRequests.get(service.coordinator) as Map<*, *>).size}, ready=${controller!!.state.ready}, mode=${controller!!.state.controlMode}, queued=${controller!!.state.queue.size}, stateError=${controller!!.state.error}")
                }
                throw AssertionError("generated title: titleRequests=${titleRequests.get()}, taskRequests=${taskRequests.get()}, ${debug.get()}", error)
            }
            await("task settled") { snapshot().optString("runningSessionId").isBlank() && !service.coordinator.isBusy() }
            assertFalse(snapshot().optBoolean("queuePaused"))
            assertEquals(1, titleRequests.get())
            val second = newSession(); send(second)
            assertTrue("second title in flight", secondTitle.await(45, TimeUnit.SECONDS))
            await("task can settle while title is blocked") { snapshot().optString("runningSessionId").isBlank() && !service.coordinator.isBusy() }
            val manual = title(second) // Same visible text still establishes user ownership.
            command("renameSession", JSONObject().put("sessionId", second).put("title", manual))
            await("manual rename stored") { !service.coordinator.isBusy() }
            command("selectSession", JSONObject().put("sessionId", first))
            releaseTitle.countDown()
            Thread.sleep(600)
            assertEquals(manual, title(second)); assertEquals("北京旅行攻略与景点收藏", title(first))
            send(second)
            await("follow-up settled") { snapshot().optString("runningSessionId").isBlank() && !service.coordinator.isBusy() && snapshot().getJSONArray("queue").length() == 0 }
            assertEquals(2, titleRequests.get()); assertNull(requestErrors.get())
            settings.forEach { (name, contents) -> assertEquals(contents, File(home, name).takeIf(File::exists)?.readText()) }
        } finally {
            releaseTitle.countDown()
            try {
                if (scope != null) {
                    instrumentation.runOnMainSync { controller!!.stop() }
                    await("fixture stopped") { !service.coordinator.isBusy() && snapshot().optString("runningSessionId").isBlank() }
                    for (id in fixtures) {
                        command("deleteSession", JSONObject().put("sessionId", id))
                        await("fixture deleted") { title(id).isBlank() && !service.coordinator.isBusy() }
                    }
                    instrumentation.runOnMainSync { scope!!.close() }
                    assertEquals(before.optString("sessionId"), snapshot().optString("sessionId"))
                }
            } finally {
                server.stop(); context.unbindService(connection)
                settings.forEach { (name, contents) -> assertEquals(contents, File(home, name).takeIf(File::exists)?.readText()) }
            }
        }
    }
}
