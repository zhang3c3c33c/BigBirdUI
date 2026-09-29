package io.bbui.assistant

import android.app.Activity
import android.content.Intent
import android.view.WindowManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.bbui.runtime.EmbeddedPiRuntime
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Real bundled Node and upstream storage, isolated from personal memory and model APIs. */
@RunWith(AndroidJUnit4::class)
class MemoryRuntimeTest {
    @Test(timeout = 120000) fun managementPersistsAcrossRuntimeRestartAndRejectsStaleRevision() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val host = instrumentation.startActivitySync(Intent().setClassName(context, "io.bbui.assistant.TestTargetActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as Activity
        instrumentation.runOnMainSync { host.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        var runtime: EmbeddedPiRuntime? = null
        val responses = LinkedBlockingQueue<JSONObject>()
        val failure = AtomicReference("")
        fun start() {
            val ready = CountDownLatch(1)
            instrumentation.runOnMainSync {
                runtime = EmbeddedPiRuntime(context).also { instance ->
                    instance.start(JSONObject().put("catalogOnly", true).put("mockPhone", true), { event ->
                        if (event.optString("type") == "runtime_ready") ready.countDown()
                        if (event.optString("command") == "bbui_memory") responses.offer(event)
                    }, { error -> failure.set(error); ready.countDown() })
                }
            }
            assertTrue("catalog readiness timeout", ready.await(40, TimeUnit.SECONDS))
            assertEquals("", failure.get())
        }
        fun command(action: String, revision: String? = null, content: String? = null): JSONObject {
            val id = UUID.randomUUID().toString()
            val command = JSONObject().put("type", "bbui_memory").put("id", id).put("action", action)
            revision?.let { command.put("revision", it) }; content?.let { command.put("content", it) }
            runtime!!.send(command)
            val response = responses.poll(30, TimeUnit.SECONDS)
            assertNotNull("memory RPC response missing; ${failure.get()}", response)
            assertEquals(id, response!!.getString("id"))
            return response
        }
        try {
            start()
            val original = command("read").getJSONObject("data")
            val text = "# Memory\n\n- synthetic fixture 中文 😀 ${UUID.randomUUID()}\n"
            val written = command("write", original.getString("revision"), text)
            assertTrue(written.optBoolean("success"))
            assertEquals(text, written.getJSONObject("data").getString("content"))
            assertFalse(command("write", original.getString("revision"), "must not overwrite").optBoolean("success"))
            instrumentation.runOnMainSync { runtime!!.close() }
            start()
            val restored = command("read").getJSONObject("data")
            assertEquals(text, restored.getString("content"))
            val deleted = command("delete", restored.getString("revision"))
            assertTrue(deleted.optBoolean("success"))
            assertEquals("", deleted.getJSONObject("data").getString("content"))
        } finally {
            instrumentation.runOnMainSync { runtime?.close(); host.finish() }
        }
    }
}
