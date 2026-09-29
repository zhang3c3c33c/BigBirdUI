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

/** Real Pi steer consumption; all inference is loopback and phone actions are mocked. */
@RunWith(AndroidJUnit4::class)
class QueuedSteeringDeviceTest : ForegroundDeviceTest() {
    @Test(timeout = 180000) fun queuedMessageTransfersToCurrentRunOnlyOnceWithoutClearingNewDraft() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val bound = CountDownLatch(1)
        val reference = AtomicReference<AssistantService>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                reference.set((binder as AssistantService.LocalBinder).service); bound.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName) {}
        }
        context.bindService(Intent(context, AssistantService::class.java), connection, Context.BIND_AUTO_CREATE)
        assertTrue(bound.await(10, TimeUnit.SECONDS))
        val service = reference.get()
        fun snapshot(): JSONObject = AtomicReference<JSONObject>().also { result ->
            instrumentation.runOnMainSync { result.set(service.sessionSnapshot()) }
        }.get()
        fun await(label: String, check: () -> Boolean) {
            val end = System.currentTimeMillis() + 45000
            while (!check() && System.currentTimeMillis() < end) Thread.sleep(40)
            assertTrue(label, check())
        }
        fun command(type: String, fields: JSONObject = JSONObject()) = instrumentation.runOnMainSync {
            service.sessionCommand(fields.put("type", type))
        }
        await("production idle") { snapshot().optBoolean("sessionsReady") && !service.coordinator.isBusy() }
        val before = snapshot()
        assertEquals("", before.optString("runningSessionId"))
        assertTrue(before.getJSONArray("queue").length() == 0 || before.optBoolean("queuePaused"))
        assertFalse(before.getJSONObject("control").optString("mode") in setOf("running", "manual", "taking_over", "resuming"))
        assertNotEquals("pending", before.optJSONObject("pendingQuestion")?.optString("status"))
        val home = File(context.noBackupFilesDir, "pi-agent")
        val settings = listOf("models.json", "settings.json").associateWith { File(home, it).takeIf(File::exists)?.readText() }
        val firstRequest = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val requests = AtomicInteger()
        val occurrences = AtomicInteger()
        val steerText = "补充夹具：请把答案改为两个词"
        val server = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(request: IHTTPSession): Response {
                request.headers["content-type"] = "application/json; charset=utf-8"
                val bodies = mutableMapOf<String, String>(); request.parseBody(bodies)
                val body = JSONObject(bodies["postData"].orEmpty())
                val messages = body.getJSONArray("messages")
                val matches = (0 until messages.length()).map { messages.getJSONObject(it) }
                    .count { it.optString("role") == "user" && it.toString().contains(steerText) }
                occurrences.set(maxOf(occurrences.get(), matches))
                if (requests.incrementAndGet() == 1) {
                    firstRequest.countDown(); releaseFirst.await(20, TimeUnit.SECONDS)
                }
                fun event(delta: JSONObject, finish: String?) = JSONObject().put("choices", JSONArray().put(JSONObject()
                    .put("index", 0).put("delta", delta).put("finish_reason", finish ?: JSONObject.NULL))).toString()
                return newFixedLengthResponse(Response.Status.OK, "text/event-stream",
                    "data: ${event(JSONObject().put("content", "本地模拟回复"), null)}\n\ndata: ${event(JSONObject(), "stop")}\n\ndata: [DONE]\n\n")
            }
        }
        val controllerField = AssistantService::class.java.getDeclaredField("sessions").apply { isAccessible = true }
        var controller: SessionController? = null
        var fixture = ""
        var scope: AutoCloseable? = null
        fun send(text: String): String {
            val id = UUID.randomUUID().toString()
            command("send", JSONObject().put("sessionId", fixture).put("submissionId", id).put("text", text))
            await("durable submission") { snapshot().optJSONObject("submissionResult")?.optString("id") == id }
            assertTrue(snapshot().getJSONObject("submissionResult").getBoolean("accepted"))
            return id
        }
        try {
            server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true)
            instrumentation.runOnMainSync {
                scope = service.isolateQuestionTestSessions()
                controller = controllerField.get(service) as SessionController
                controller!!.testConfig = JSONObject().put("provider", "fixture").put("api", "openai-completions")
                    .put("model", "steering-fixture").put("baseUrl", "http://127.0.0.1:${server.listeningPort}/v1")
                    .put("apiKey", "synthetic-steering-key").put("mockPhone", true)
            }
            await("isolated catalog") { snapshot().optBoolean("sessionsReady") && !service.coordinator.isBusy() }
            val oldId = snapshot().optString("sessionId")
            command("newSession")
            await("fixture created") { snapshot().optString("sessionId").let { it.isNotBlank() && it != oldId } }
            fixture = snapshot().getString("sessionId")
            command("renameSession", JSONObject().put("sessionId", fixture).put("title", "Queued steering fixture"))
            await("manual title suppresses auxiliary requests") { !service.coordinator.isBusy() }
            send("主任务夹具：只回复一句话")
            assertTrue("first request running", firstRequest.await(45, TimeUnit.SECONDS))
            val queued = send(steerText)
            assertEquals(1, snapshot().getJSONArray("queue").length())
            command("viewState", JSONObject().put("sessionId", fixture).put("text", "后续草稿保持不变"))
            val control = snapshot().getJSONObject("control")
            assertTrue(control.optBoolean("canSteer"))
            val runId = control.getString("runId")
            val transfer = JSONObject().put("sessionId", fixture).put("submissionId", queued)
                .put("controlId", control.getString("id")).put("runId", runId)
            command("steerQueued", JSONObject(transfer.toString()))
            command("steerQueued", JSONObject(transfer.toString())) // Duplicate cannot dispatch a second steer.
            await("Pi acknowledged transferred item") {
                val ok = AtomicReference(false)
                instrumentation.runOnMainSync { ok.set(controller!!.state.running?.optJSONArray("steering")?.length() == 1) }
                ok.get()
            }
            assertEquals(0, snapshot().getJSONArray("queue").length())
            assertEquals(0, snapshot().getJSONArray("interruptedTasks").length())
            assertEquals("后续草稿保持不变", snapshot().getJSONObject("viewState").getString("text"))
            assertEquals(runId, snapshot().getJSONObject("control").getString("runId"))
            releaseFirst.countDown()
            await("single run settled") { snapshot().optString("runningSessionId").isBlank() && !service.coordinator.isBusy() }
            Thread.sleep(350)
            assertEquals("The original run consumes steering exactly once", 2, requests.get())
            assertEquals(1, occurrences.get())
            assertEquals(0, snapshot().getJSONArray("queue").length())
            val finalRun = AtomicReference("")
            instrumentation.runOnMainSync { finalRun.set(controller!!.state.chat(fixture).currentRunId()) }
            assertEquals("No FIFO follow-up run", runId, finalRun.get())
            assertEquals("后续草稿保持不变", snapshot().getJSONObject("viewState").getString("text"))
        } finally {
            releaseFirst.countDown()
            try {
                if (scope != null) {
                    instrumentation.runOnMainSync { controller!!.stop() }
                    await("fixture stopped") { !service.coordinator.isBusy() && snapshot().optString("runningSessionId").isBlank() }
                    if (fixture.isNotBlank()) {
                        command("deleteSession", JSONObject().put("sessionId", fixture))
                        await("fixture deleted") {
                            val rows = snapshot().getJSONArray("sessions")
                            (0 until rows.length()).none { rows.getJSONObject(it).optString("id") == fixture } && !service.coordinator.isBusy()
                        }
                    }
                    instrumentation.runOnMainSync { scope!!.close() }
                    assertEquals(before.optString("sessionId"), snapshot().optString("sessionId"))
                }
            } finally {
                server.stop(); context.unbindService(connection)
                settings.forEach { (name, text) -> assertEquals(text, File(home, name).takeIf(File::exists)?.readText()) }
            }
        }
    }
}
