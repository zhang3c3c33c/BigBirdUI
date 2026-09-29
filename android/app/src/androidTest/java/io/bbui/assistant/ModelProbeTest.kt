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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Exercises the APK's real isolated Pi adapters against loopback only. */
@RunWith(AndroidJUnit4::class)
class ModelProbeTest : ForegroundDeviceTest() {
    @Test(timeout = 100000) fun syntheticProbeDoesNotCreateSessionsOrChangeModelSettings() {
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
        fun snapshot(): JSONObject {
            val result = AtomicReference<JSONObject>()
            instrumentation.runOnMainSync { result.set(service.sessionSnapshot()) }; return result.get()
        }
        val deadline = System.currentTimeMillis() + 45000
        while ((!snapshot().optBoolean("sessionsReady") || service.coordinator.isBusy()) && System.currentTimeMillis() < deadline) Thread.sleep(50)
        val before = snapshot()
        assertTrue(before.optBoolean("sessionsReady")); assertFalse(service.coordinator.isBusy())
        assertEquals("", before.optString("runningSessionId")); assertEquals(0, before.getJSONArray("queue").length())
        assertFalse(before.getJSONObject("control").optBoolean("canResume"))
        val home = File(context.noBackupFilesDir, "pi-agent")
        val settings = listOf("settings.json", "models.json").associateWith { File(home, it).takeIf(File::exists)?.readText() }
        val sessions = File(home, "sessions").walkTopDown().filter { it.isFile }.map { it.relativeTo(home).path }.toSet()
        val count = AtomicInteger()
        val server = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(request: IHTTPSession): Response {
                val body = mutableMapOf<String, String>(); request.parseBody(body)
                val value = JSONObject(body["postData"].orEmpty())
                if (value.optString("model") != "fixture" || request.uri != "/v1/chat/completions") return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "invalid fixture")
                val tool = count.incrementAndGet() == 3
                val delta = if (tool) JSONObject().put("tool_calls", JSONArray().put(JSONObject().put("index", 0).put("id", "fixture-call").put("type", "function")
                    .put("function", JSONObject().put("name", "connection_test").put("arguments", "{}")))) else JSONObject().put("content", "OK")
                fun event(payload: JSONObject, finish: String?) = JSONObject().put("choices", JSONArray().put(JSONObject().put("index", 0).put("delta", payload).put("finish_reason", finish ?: JSONObject.NULL))).toString()
                return newFixedLengthResponse(Response.Status.OK, "text/event-stream", "data: ${event(delta, null)}\n\ndata: ${event(JSONObject(), if (tool) "tool_calls" else "stop")}\n\ndata: [DONE]\n\n")
            }
        }
        try {
            server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true)
            val completed = CountDownLatch(1); val result = AtomicReference<JSONObject>()
            instrumentation.runOnMainSync {
                service.coordinator.probeModel(JSONObject().put("provider", "fixture").put("api", "openai-completions").put("model", "fixture")
                    .put("baseUrl", "http://127.0.0.1:${server.listeningPort}/v1").put("apiKey", "synthetic-key")
                    .put("input", JSONArray(listOf("text", "image")))) { result.set(it); completed.countDown() }
            }
            assertTrue("probe completed", completed.await(50, TimeUnit.SECONDS))
            assertTrue(result.get().optString("message"), result.get().getBoolean("ok")); assertEquals(4, count.get())
            settings.forEach { (name, contents) -> assertEquals(contents, File(home, name).takeIf(File::exists)?.readText()) }
            assertEquals(sessions, File(home, "sessions").walkTopDown().filter { it.isFile }.map { it.relativeTo(home).path }.toSet())
            assertEquals(before.getString("sessionId"), snapshot().getString("sessionId"))
            assertEquals("", snapshot().optString("runningSessionId"))
            assertFalse(File(home, "runtime-config.json").takeIf(File::exists)?.readText()?.contains("synthetic-key") == true)
            // A cancelled probe after an earlier conversation must not mark that chat stopped.
            val controller = AssistantService::class.java.getDeclaredField("sessions").apply { isAccessible = true }.get(service) as SessionController
            val originalPauses = controller.state.pauseReasons.toList()
            val originalMode = controller.state.controlMode
            val cancelResult = AtomicReference<JSONObject>(); val cancelled = CountDownLatch(1)
            val previousMessages = snapshot().getJSONArray("messages").toString()
            instrumentation.runOnMainSync {
                AppCoordinator::class.java.getDeclaredField("runSessionId").apply { isAccessible = true }.set(service.coordinator, before.getString("sessionId"))
                service.coordinator.probeModel(JSONObject().put("provider", "fixture").put("api", "openai-completions").put("model", "fixture")
                    .put("baseUrl", "http://127.0.0.1:${server.listeningPort}/v1").put("apiKey", "cancelled-synthetic-key")) { cancelResult.set(it); cancelled.countDown() }
                service.coordinator.stop()
            }
            assertTrue(cancelled.await(10, TimeUnit.SECONDS)); assertFalse(cancelResult.get().getBoolean("ok"))
            val stopDeadline = System.currentTimeMillis() + 10000
            while (service.coordinator.isBusy() && System.currentTimeMillis() < stopDeadline) Thread.sleep(50)
            assertFalse(service.coordinator.isBusy())
            assertEquals(previousMessages, snapshot().getJSONArray("messages").toString())
            assertFalse(File(home, "runtime-config.json").takeIf(File::exists)?.readText()?.contains("synthetic-key") == true)
            instrumentation.runOnMainSync {
                controller.state.pauseReasons.clear(); controller.state.pauseReasons.addAll(originalPauses)
                controller.state.transition(originalMode)
                service.sessionCommand(JSONObject().put("type", "settingsChanged"))
            }
        } finally {
            server.stop(); context.unbindService(connection)
        }
    }
}
