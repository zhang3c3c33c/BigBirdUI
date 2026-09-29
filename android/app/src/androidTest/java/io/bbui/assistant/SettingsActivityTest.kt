package io.bbui.assistant

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Spinner
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Settings never submit a task; fixtures use a reserved invalid host and are removed afterwards. */
@RunWith(AndroidJUnit4::class)
class SettingsActivityTest : ForegroundDeviceTest() {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun views(activity: SettingsActivity) = descendants(activity.window.decorView)
    private fun launch(): SettingsActivity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, SettingsActivity::class.java)
        .putExtra("page", "models").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as SettingsActivity

    @Test fun searchProviderDraftsStaySeparateAndCredentialsAreNotSavedInViewState() {
        val activity = launch()
        val before = SettingsStore(instrumentation.targetContext).searchSettings().toString()
        try {
            instrumentation.runOnMainSync {
                activity.open("search")
                views(activity).filterIsInstance<Spinner>().single().setSelection(0)
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                views(activity).filterIsInstance<EditText>().single { it.contentDescription == "API Key" }.setText("bocha-unsaved-fixture")
                views(activity).filterIsInstance<Spinner>().single().setSelection(1)
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                views(activity).filterIsInstance<EditText>().single { it.contentDescription == "API Key" }.setText("baidu-unsaved-fixture")
                views(activity).filterIsInstance<Spinner>().single().setSelection(0)
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val key = views(activity).filterIsInstance<EditText>().single { it.contentDescription == "API Key" }
                assertEquals("bocha-unsaved-fixture", key.text.toString())
                assertFalse(key.isSaveEnabled)
                val state = Bundle(); instrumentation.callActivityOnSaveInstanceState(activity, state)
                assertFalse(state.toString().contains("unsaved-fixture"))
            }
            assertEquals(before, SettingsStore(instrumentation.targetContext).searchSettings().toString())
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    @Test fun editorSavesAndRenamesWithoutLeakingCredentialIntoState() {
        val context = instrumentation.targetContext
        val store = SettingsStore(context)
        val name = "settings-test-${UUID.randomUUID()}"
        val key = "synthetic-key-${UUID.randomUUID()}"
        val activity = launch()
        var created = ""
        try {
            instrumentation.runOnMainSync {
                activity.editConnection(JSONObject().put("name", name).put("provider", "").put("api", "openai-completions")
                    .put("baseUrl", "https://example.invalid/v1").put("apiKey", key)
                    .put("models", JSONArray().put(JSONObject().put("id", "test-model").put("name", "Test model").put("input", JSONArray().put("text")))))
                val secret = views(activity).filterIsInstance<EditText>().single { it.contentDescription == "API Key" }
                assertFalse(secret.isSaveEnabled)
                views(activity).filterIsInstance<Button>().single { it.text == "保存连接" }.performClick()
            }
            created = store.registry().getJSONArray("connections").let { rows ->
                (0 until rows.length()).map { rows.getJSONObject(it) }.single { it.optString("name") == name }.getString("id")
            }
            val saved = store.registry().getJSONArray("connections").let { rows -> (0 until rows.length()).map { rows.getJSONObject(it) }.single { it.optString("id") == created } }
            assertEquals(key, saved.getString("apiKey"))
            assertTrue(saved.getString("provider").isNotBlank())
            assertFalse(store.modelOptions().toString().contains(key))
            instrumentation.runOnMainSync {
                activity.editConnection(saved)
                views(activity).filterIsInstance<EditText>().single { it.contentDescription == "连接名称" }.setText("$name-renamed")
                views(activity).filterIsInstance<Button>().single { it.text == "保存连接" }.performClick()
            }
            val renamed = store.registry().getJSONArray("connections").let { rows -> (0 until rows.length()).map { rows.getJSONObject(it) }.single { it.optString("id") == created } }
            assertEquals("$name-renamed", renamed.getString("name"))
            instrumentation.runOnMainSync {
                activity.editConnection(renamed)
                val state = Bundle()
                instrumentation.callActivityOnSaveInstanceState(activity, state)
                assertFalse("Saved state must not contain a key", state.toString().contains(key))
                assertFalse(state.getString("draft").orEmpty().contains(key))
                assertFalse(state.getString("original").orEmpty().contains(key))
            }
        } finally {
            instrumentation.runOnMainSync {
                val rows = store.registry().getJSONArray("connections")
                (0 until rows.length()).map { rows.getJSONObject(it) }.filter { it.optString("name") in setOf(name, "$name-renamed") }
                    .forEach { store.deleteConnection(it.getString("id")) }
                activity.changed(); activity.finish()
            }
        }
    }

    @Test fun editingModelIdKeepsExplicitCapabilitiesWithoutCatalogGuessing() {
        val activity = launch()
        try {
            instrumentation.runOnMainSync {
                activity.editConnection(JSONObject().put("name", "temporary editor").put("provider", "deepseek").put("api", "openai-completions")
                    .put("baseUrl", "https://example.invalid/v1").put("apiKey", "not-persisted")
                    .put("models", JSONArray().put(JSONObject().put("id", "deepseek-chat").put("name", "Capability fixture")
                        .put("input", JSONArray(listOf("text", "image"))).put("contextWindow", 32000))))
                views(activity).filterIsInstance<Button>().single { it.text == "Capability fixture" }.performClick()
                views(activity).filterIsInstance<EditText>().single { it.contentDescription == "模型 ID" }.setText("unknown-model-fixture")
                assertEquals(1, views(activity).filterIsInstance<android.widget.Spinner>().single { it.contentDescription == "图片输入" }.selectedItemPosition)
                assertEquals("32000", views(activity).filterIsInstance<EditText>().single { it.contentDescription == "上下文长度" }.text.toString())
                assertFalse(views(activity).filterIsInstance<TextView>().any { it.text.toString().startsWith("Pi 模型元数据") })
            }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    @Test fun activityRecreationPreservesUnsavedDraftWithoutPersistingIt() {
        var activity = launch()
        try {
            instrumentation.runOnMainSync {
                activity.editConnection(JSONObject().put("name", "rotation fixture").put("provider", "fixture").put("api", "openai-completions")
                    .put("baseUrl", "https://example.invalid/v1").put("apiKey", "unsaved-fixture-key").put("models", JSONArray()))
                activity.recreate()
            }
            val old = activity
            val deadline = System.currentTimeMillis() + 10000
            while (activity === old && System.currentTimeMillis() < deadline) {
                instrumentation.waitForIdleSync()
                instrumentation.runOnMainSync {
                    ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                        .filterIsInstance<SettingsActivity>().firstOrNull { it !== old }?.let { activity = it }
                }
                if (activity === old) Thread.sleep(50)
            }
            assertNotSame("settings Activity was recreated", old, activity)
            instrumentation.runOnMainSync {
                val fields = views(activity).filterIsInstance<EditText>()
                assertEquals("rotation fixture", fields.single { it.contentDescription == "连接名称" }.text.toString())
                assertEquals("unsaved-fixture-key", fields.single { it.contentDescription == "API Key" }.text.toString())
                val bundle = Bundle(); instrumentation.callActivityOnSaveInstanceState(activity, bundle)
                assertFalse(bundle.toString().contains("unsaved-fixture-key"))
            }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
}
