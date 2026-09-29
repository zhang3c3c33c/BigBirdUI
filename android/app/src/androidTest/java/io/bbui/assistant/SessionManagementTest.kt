package io.bbui.assistant

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Uses the production service, real Pi sessions and local mock provider. No device input or external API. */
@RunWith(AndroidJUnit4::class)
class SessionManagementTest : ForegroundDeviceTest() {
    @Test(timeout = 240000) fun sidebarSessionsAndCrossSessionQueueUseOneExecutor() {
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
        context.bindService(Intent(context, AssistantService::class.java), connection, Context.BIND_AUTO_CREATE)
        assertTrue(bound.await(10, TimeUnit.SECONDS))
        val service = reference.get()
        fun snapshot(): JSONObject { val result = AtomicReference<JSONObject>(); instrumentation.runOnMainSync { result.set(service.sessionSnapshot()) }; return result.get() }
        fun command(type: String, id: String = "", extra: JSONObject = JSONObject()) = instrumentation.runOnMainSync {
            service.sessionCommand(extra.put("type", type).put("sessionId", id))
        }
        fun awaitState(description: String, check: (JSONObject) -> Boolean): JSONObject {
            val deadline = System.currentTimeMillis() + 70000
            var value = snapshot()
            while (!check(value) && System.currentTimeMillis() < deadline) { Thread.sleep(50); value = snapshot() }
            assertTrue("$description; error=${value.optString("sessionError")}; owner=${value.optString("runningSessionId")}", check(value))
            return value
        }
        awaitState("catalog ready") { it.optBoolean("sessionsReady") && it.optString("sessionId").isNotBlank() }
        val initial = snapshot()
        assertTrue("Do not interrupt a user's task", initial.optString("runningSessionId").isBlank())
        assertEquals("Do not alter a user's queue", 0, initial.getJSONArray("queue").length())
        val original = initial.getString("sessionId")
        val created = mutableListOf<String>()
        val owners = mutableListOf<String>()
        val listener: (JSONObject) -> Unit = { value ->
            val id = value.optString("runningSessionId")
            if (id.isNotBlank() && owners.lastOrNull() != id) owners.add(id)
        }
        try {
            instrumentation.runOnMainSync { service.useLocalSessionTestModel(true); service.subscribeChat(listener) }
            command("pauseQueue")
            fun create(label: String): String {
                val before = snapshot().getString("sessionId")
                command("newSession")
                val id = awaitState("create $label") { it.optString("sessionId") != before && it.optString("sessionId").isNotBlank() }.getString("sessionId")
                created.add(id)
                command("renameSession", id, JSONObject().put("title", label))
                awaitState("rename $label") { value -> (0 until value.getJSONArray("sessions").length()).any { value.getJSONArray("sessions").getJSONObject(it).optString("title") == label } }
                return id
            }
            val a = create("本地队列测试 A")
            val b = create("本地队列测试 B")
            fun submit(id: String, text: String, key: String = UUID.randomUUID().toString()): String {
                command("send", id, JSONObject().put("text", text).put("submissionId", key)); return key
            }
            val first = submit(a, "只查看模拟画面 A1")
            submit(a, "只查看模拟画面 A1", first)
            submit(b, "只查看模拟画面 B1")
            submit(a, "只查看模拟画面 A2")
            val cancelled = submit(b, "这个任务应取消")
            command("cancelQueued", extra = JSONObject().put("submissionId", cancelled))
            assertEquals(3, snapshot().getJSONArray("queue").length())
            command("resumeQueue")
            awaitState("A starts") { it.optString("runningSessionId") == a }
            command("selectSession", b)
            assertEquals(b, snapshot().getString("sessionId"))
            assertEquals(a, snapshot().getString("runningSessionId"))
            assertEquals(0, snapshot().getJSONArray("messages").length())
            awaitState("queue completes") { it.getJSONArray("queue").length() == 0 && it.optString("runningSessionId").isBlank() }
            assertEquals(listOf(a, b, a), owners)
            command("selectSession", a)
            awaitState("A history") { it.toString().contains("只查看模拟画面 A2") }
            assertFalse(snapshot().getJSONArray("messages").toString().contains("只查看模拟画面 B1"))
            command("selectSession", b)
            assertFalse(snapshot().getJSONArray("messages").toString().contains("只查看模拟画面 A1"))
            // Kill only our idle isolated Pi process, then recover the catalogue without a prompt.
            val manager = context.getSystemService(android.app.ActivityManager::class.java)
            fun agentPid() = manager.runningAppProcesses.firstOrNull { it.processName == "${context.packageName}:agent" }?.pid
            val previousPid = checkNotNull(agentPid())
            android.os.Process.killProcess(previousPid)
            awaitState("Pi failure pauses queue") { it.optBoolean("queuePaused") }
            command("refreshSessions")
            awaitState("catalog recovers after Pi exit") {
                agentPid() != null && agentPid() != previousPid && !service.coordinator.isBusy() && it.optString("sessionError").isBlank()
            }
            assertTrue(snapshot().getJSONArray("messages").toString().contains("只查看模拟画面 B1"))
            // A user STOP pauses waiting work even when nothing is currently generating.
            command("pauseQueue"); submit(a, "停止后不应自动执行")
            instrumentation.runOnMainSync { service.coordinator.stop() }
            assertTrue(snapshot().getBoolean("queuePaused"))
            val report = File(context.filesDir, "gate-evidence").apply { mkdirs() }
            File(report, "session-management.json").writeText(JSONObject().put("passed", true)
                .put("order", org.json.JSONArray(listOf("A", "B", "A"))).put("duplicateSuppressed", true)
                .put("cancelledBeforeExecution", true).put("historyIsolated", true).put("stopPausesQueue", true)
                .put("catalogRecoveredAfterPiExit", true).toString(2))
        } finally {
            instrumentation.runOnMainSync { service.coordinator.stop(); service.unsubscribeChat(listener); service.useLocalSessionTestModel(false) }
            awaitState("stop settles") { !service.coordinator.isBusy() }
            for (id in created) {
                command("deleteSession", id)
                awaitState("remove test session") { value -> (0 until value.getJSONArray("sessions").length()).none { value.getJSONArray("sessions").getJSONObject(it).optString("id") == id } }
            }
            command("selectSession", original)
            if (!initial.optBoolean("queuePaused")) command("resumeQueue")
            context.unbindService(connection)
        }
    }
}
