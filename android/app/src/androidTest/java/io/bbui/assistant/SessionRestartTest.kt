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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Two phases with an external force-stop between them. Only disposable paused work is created. */
@RunWith(AndroidJUnit4::class)
class SessionRestartTest : ForegroundDeviceTest() {
    @Test(timeout = 120000) fun pausedWorkSurvivesProcessRestart() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val latch = CountDownLatch(1)
        val reference = AtomicReference<AssistantService>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                reference.set((binder as AssistantService.LocalBinder).service); latch.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName) { }
        }
        context.bindService(Intent(context, AssistantService::class.java), connection, Context.BIND_AUTO_CREATE)
        assertTrue(latch.await(10, TimeUnit.SECONDS))
        val service = reference.get()
        fun snapshot(): JSONObject { val value = AtomicReference<JSONObject>(); instrumentation.runOnMainSync { value.set(service.sessionSnapshot()) }; return value.get() }
        fun command(type: String, id: String = "", extra: JSONObject = JSONObject()) = instrumentation.runOnMainSync {
            service.sessionCommand(extra.put("type", type).put("sessionId", id))
        }
        fun awaitState(check: (JSONObject) -> Boolean): JSONObject {
            val deadline = System.currentTimeMillis() + 60000
            var value = snapshot()
            while (!check(value) && System.currentTimeMillis() < deadline) { Thread.sleep(50); value = snapshot() }
            assertTrue("Session state did not settle: ${value.optString("sessionError")}", check(value)); return value
        }
        val marker = File(context.filesDir, "gate-evidence/session-restart-fixture.json")
        try {
            val initial = awaitState { it.optBoolean("sessionsReady") && it.optString("sessionId").isNotBlank() }
            if (InstrumentationRegistry.getArguments().getString("phase") == "prepare") {
                assertEquals(0, initial.getJSONArray("queue").length())
                assertTrue(initial.optString("runningSessionId").isBlank())
                command("pauseQueue")
                val id = if (marker.exists()) JSONObject(marker.readText()).getString("id") else {
                    command("newSession")
                    val created = awaitState { value -> value.optString("sessionId") != initial.optString("sessionId") &&
                        (0 until value.getJSONArray("sessions").length()).any { value.getJSONArray("sessions").getJSONObject(it).getString("id") == value.getString("sessionId") }
                    }.getString("sessionId")
                    marker.parentFile!!.mkdirs()
                    marker.writeText(JSONObject().put("original", initial.getString("sessionId"))
                        .put("paused", initial.optBoolean("queuePaused")).put("id", created).toString())
                    created
                }
                command("selectSession", id)
                val submissionId = java.util.UUID.randomUUID().toString()
                marker.writeText(JSONObject(marker.readText()).put("submissionId", submissionId).toString())
                command("send", id, JSONObject().put("submissionId", submissionId).put("text", "进程重启后只能等待，不能自动执行"))
                command("viewState", id, JSONObject().put("text", "保留草稿🙂").put("scrollTop", 150))
                command("loadOlder", id)
                // Wait for the actual atomic file, not just the in-memory projection.
                val file = File(context.noBackupFilesDir, "conversations.json")
                val deadline = System.currentTimeMillis() + 10000
                while ((!file.readText().contains("保留草稿") || !file.readText().contains(submissionId)) && System.currentTimeMillis() < deadline) Thread.sleep(50)
                assertTrue(file.readText().contains("保留草稿"))
                assertEquals(1, JSONObject(file.readText()).getJSONArray("queue").length())
            } else {
                val saved = JSONObject(marker.readText())
                val id = saved.getString("id")
                assertEquals(id, initial.getString("sessionId"))
                assertTrue(initial.getBoolean("queuePaused"))
                assertTrue(initial.optString("runningSessionId").isBlank())
                assertEquals(1, initial.getJSONArray("queue").length())
                assertEquals(saved.getString("submissionId"), initial.getJSONArray("queue").getJSONObject(0).getString("id"))
                assertEquals("保留草稿🙂", initial.getJSONObject("viewState").getString("text"))
                assertEquals(0, initial.getJSONArray("messages").length())
                command("deleteSession", id)
                awaitState { it.getJSONArray("queue").length() == 0 && it.getString("sessionId") != id }
                command("selectSession", saved.getString("original"))
                if (!saved.getBoolean("paused")) command("resumeQueue")
                File(marker.parentFile, "session-restart-result.json").writeText("{\"passed\":true,\"queuePaused\":true,\"draftRestored\":true,\"noReplay\":true}")
                assertTrue(marker.delete())
            }
        } finally { context.unbindService(connection) }
    }
}
