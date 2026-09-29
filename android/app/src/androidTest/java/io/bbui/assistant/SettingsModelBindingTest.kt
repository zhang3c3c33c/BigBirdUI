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
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** No model requests or phone actions: every submitted item remains paused and is cancelled. */
@RunWith(AndroidJUnit4::class)
class SettingsModelBindingTest : ForegroundDeviceTest() {
    @Test(timeout = 180000) fun selectionsPersistAndWaitingTasksKeepTheirAcceptedConfiguration() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val bound = CountDownLatch(1)
        val reference = AtomicReference<AssistantService>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                reference.set((binder as AssistantService.LocalBinder).service); bound.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName) { }
        }
        assertTrue(context.bindService(Intent(context, AssistantService::class.java), connection, Context.BIND_AUTO_CREATE))
        try {
            assertTrue(bound.await(10, TimeUnit.SECONDS))
            val service = reference.get()
            val controller = AssistantService::class.java.getDeclaredField("sessions").apply { isAccessible = true }.get(service) as SessionController
            val store = SettingsStore(context)
            fun snapshot(): JSONObject {
                val result = AtomicReference<JSONObject>()
                instrumentation.runOnMainSync { result.set(service.sessionSnapshot()) }
                return result.get()
            }
            fun command(type: String, id: String = "", extra: JSONObject = JSONObject()) = instrumentation.runOnMainSync {
                service.sessionCommand(extra.put("type", type).put("sessionId", id))
            }
            fun awaitState(description: String, check: (JSONObject) -> Boolean): JSONObject {
                val deadline = System.currentTimeMillis() + 45000
                var value = snapshot()
                while (!check(value) && System.currentTimeMillis() < deadline) { Thread.sleep(40); value = snapshot() }
                assertTrue(description, check(value)); return value
            }
            awaitState("catalog ready") { it.optBoolean("sessionsReady") && it.optString("sessionId").isNotBlank() }
            val initial = snapshot()
            assumeTrue("Requires idle with no continuation", initial.optString("runningSessionId").isBlank() &&
                initial.getJSONObject("control").optString("mode") !in setOf("manual", "taking_over", "resuming") &&
                !initial.getJSONObject("control").optBoolean("canResume"))
            assumeTrue("Preserve existing queue", initial.getJSONArray("queue").length() == 0)
            val originalDefault = store.registry().getJSONObject("defaultSelection").toString()
            val originalPauses = initial.getJSONArray("queuePauseReasons").let { a -> (0 until a.length()).map { a.getString(it) } }
            val created = mutableListOf<String>()
            val submissions = mutableListOf<String>()
            var testConnection = ""
            val starts = mutableListOf<String>()
            val listener: (JSONObject) -> Unit = { if (it.optString("runningSessionId").isNotBlank()) starts.add(it.getString("runningSessionId")) }
            try {
                instrumentation.runOnMainSync { service.subscribeChat(listener) }
                command("pauseQueue")
                val fixture = JSONObject().put("name", "本地配置绑定测试").put("provider", "bbui-settings-test")
                    .put("api", "openai-completions").put("baseUrl", "http://127.0.0.1:1/v1").put("apiKey", "dummy-never-sent")
                    .put("models", JSONArray().put(JSONObject().put("id", "model-a").put("name", "模型 A").put("input", JSONArray().put("text")))
                        .put(JSONObject().put("id", "model-b").put("name", "模型 B").put("input", JSONArray().put("text"))))
                testConnection = store.saveConnection(fixture)
                command("settingsChanged")
                fun create(): String {
                    val before = snapshot().getString("sessionId")
                    command("newSession")
                    return awaitState("create temporary session") { it.optString("sessionId").isNotBlank() && it.getString("sessionId") != before }
                        .getString("sessionId").also { created.add(it) }
                }
                val a = create(); val b = create()
                fun select(id: String, model: String): JSONObject {
                    val requestId = UUID.randomUUID().toString()
                    command("selectModel", id, JSONObject().put("requestId", requestId).put("connectionId", testConnection).put("modelId", model))
                    return awaitState("selection acknowledged") { it.optJSONObject("modelSelectionResult")?.optString("id") == requestId }
                        .getJSONObject("modelSelectionResult")
                }
                assertTrue(select(a, "model-a").getBoolean("accepted"))
                assertTrue(select(b, "model-b").getBoolean("accepted"))
                command("selectSession", a)
                assertEquals("model-a", snapshot().getJSONObject("modelSelection").getString("modelId"))
                command("selectSession", b)
                assertEquals("model-b", snapshot().getJSONObject("modelSelection").getString("modelId"))
                fun submit(id: String): String = UUID.randomUUID().toString().also {
                    submissions.add(it); command("send", id, JSONObject().put("submissionId", it).put("text", "仅测试配置绑定，禁止执行"))
                }
                val first = submit(a)
                awaitState("first task queued") { it.getJSONArray("queue").length() == 1 }
                fun binding(key: String): JSONObject {
                    val result = AtomicReference<JSONObject>()
                    instrumentation.runOnMainSync { result.set(JSONObject(controller.state.queue.first { it.getString("id") == key }.getJSONObject("modelBinding").toString())) }
                    return result.get()
                }
                val firstBinding = binding(first)
                assertEquals("model-a", firstBinding.getString("modelId"))
                assertFalse(firstBinding.has("apiKey")); assertFalse(firstBinding.has("baseUrl"))
                val oldRevision = firstBinding.getInt("revision")
                fixture.put("id", testConnection).put("baseUrl", "http://127.0.0.1:2/v1").put("apiKey", "dummy-next-revision")
                store.saveConnection(fixture); command("settingsChanged")
                assertEquals(oldRevision, binding(first).getInt("revision"))
                assertEquals("http://127.0.0.1:1/v1", store.resolve(binding(first)).getString("baseUrl"))
                val second = submit(b)
                awaitState("second task queued") { it.getJSONArray("queue").length() == 2 }
                assertTrue(binding(second).getInt("revision") > oldRevision)
                assertEquals("model-b", binding(second).getString("modelId"))
                assertEquals("http://127.0.0.1:2/v1", store.resolve(binding(second)).getString("baseUrl"))
                assertTrue(snapshot().getBoolean("queuePaused"))
                val sanitized = snapshot().toString()
                assertFalse(sanitized.contains("dummy-never-sent")); assertFalse(sanitized.contains("dummy-next-revision"))
                assertFalse(sanitized.contains("127.0.0.1"))
                store.deleteConnection(testConnection); command("settingsChanged")
                assertTrue("deleted connection cannot resolve an old version", runCatching { store.resolve(firstBinding) }.isFailure)
                assertFalse(select(a, "model-a").getBoolean("accepted"))
                val rejected = submit(a)
                val rejection = awaitState("deleted connection rejection acknowledged") { it.optJSONObject("submissionResult")?.optString("id") == rejected }.getJSONObject("submissionResult")
                assertFalse(rejection.getBoolean("accepted"))
                assertEquals("failed binding is not silently queued using defaults", 2, snapshot().getJSONArray("queue").length())
                assertTrue(rejection.optString("error").isNotBlank())
                assertEquals("no task may start during this test", emptyList<String>(), starts)
                assertEquals(originalDefault, store.registry().getJSONObject("defaultSelection").toString())
            } finally {
                command("pauseQueue")
                for (submission in submissions) command("cancelQueued", extra = JSONObject().put("submissionId", submission))
                for (id in created) {
                    command("deleteSession", id)
                    awaitState("delete temporary session") { value -> value.getJSONArray("sessions").let { a -> (0 until a.length()).none { a.getJSONObject(it).getString("id") == id } } }
                }
                if (testConnection.isNotBlank()) store.deleteConnection(testConnection)
                command("selectSession", initial.getString("sessionId"))
                // Restore exact reason sources without releasing any queue or issuing a prompt.
                instrumentation.runOnMainSync {
                    service.unsubscribeChat(listener)
                    controller.state.pauseReasons.clear(); controller.state.pauseReasons.addAll(originalPauses)
                    controller.state.error = initial.optString("sessionError")
                    service.sessionCommand(JSONObject().put("type", "settingsChanged"))
                }
                assertEquals(originalDefault, store.registry().getJSONObject("defaultSelection").toString())
            }
        } finally { context.unbindService(connection) }
    }
}
