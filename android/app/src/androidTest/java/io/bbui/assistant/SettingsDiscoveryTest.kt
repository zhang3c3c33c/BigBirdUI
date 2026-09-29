package io.bbui.assistant

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class SettingsDiscoveryTest : ForegroundDeviceTest() {
    @Test(timeout = 60000) fun discoverySurvivesRecreationSearchesAndAddsWithoutDuplicates() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val count = AtomicInteger()
        val server = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(request: IHTTPSession): Response {
                count.incrementAndGet()
                if (request.method != Method.GET || request.uri != "/v1/models" || request.headers["authorization"] != "Bearer discovery-fixture")
                    return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "invalid fixture")
                return newFixedLengthResponse("""{"data":[{"id":"model-a"},{"id":"model-b","display_name":"Model B"},{"id":"model-b"}]}""")
            }
        }
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true)
        var activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, SettingsActivity::class.java)
            .putExtra("page", "models").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as SettingsActivity
        fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
        fun views() = descendants(activity.window.decorView)
        try {
            instrumentation.runOnMainSync {
                activity.editConnection(JSONObject().put("name", "Discovery fixture").put("provider", "fixture").put("api", "openai-responses")
                    .put("baseUrl", "http://127.0.0.1:${server.listeningPort}/v1").put("apiKey", "discovery-fixture")
                    .put("models", JSONArray().put(JSONObject().put("id", "model-a").put("name", "Existing A"))))
                views().filterIsInstance<Button>().single { it.text == "从供应商获取模型" }.performClick()
                activity.recreate()
            }
            val old = activity
            val deadline = System.currentTimeMillis() + 15000
            var ready = false
            while (!ready && System.currentTimeMillis() < deadline) {
                instrumentation.runOnMainSync {
                    ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<SettingsActivity>()
                        .firstOrNull { it !== old }?.let { activity = it }
                    ready = activity !== old && views().filterIsInstance<ListView>().any { it.adapter?.count == 2 }
                }
                if (!ready) Thread.sleep(50)
            }
            assertTrue("list restored after recreation", ready); assertEquals(1, count.get())
            instrumentation.runOnMainSync {
                val search = views().filterIsInstance<EditText>().single { it.contentDescription == "搜索模型" }
                search.setText("model-b")
                val list = views().filterIsInstance<ListView>().single()
                assertEquals(1, list.adapter.count)
                list.performItemClick(list.adapter.getView(0, null, list), 0, 0)
                search.setText("")
                assertEquals(2, list.adapter.count)
                assertTrue(list.isItemChecked(0)); assertTrue(list.isItemChecked(1))
                views().filterIsInstance<Button>().single { it.text == "添加 1 个模型" }.performClick()
                val buttons = views().filterIsInstance<Button>()
                assertEquals(1, buttons.count { it.text == "Existing A" }); assertEquals(1, buttons.count { it.text == "Model B" })
                assertTrue(buttons.any { it.text == "保存连接" })
            }
        } finally { instrumentation.runOnMainSync { activity.finish() }; server.stop() }
    }
}
