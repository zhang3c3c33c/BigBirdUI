package io.bbui.assistant

import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Private temporary stores exercise the actual Android Keystore; never touch user configuration. */
@RunWith(AndroidJUnit4::class)
class SettingsStoreTest {
    private fun withStore(test: (SettingsStore, File) -> Unit) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(base.cacheDir, "settings-test-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) { override fun getNoBackupFilesDir(): File = directory }
        try { test(SettingsStore(context), directory) } finally { directory.deleteRecursively() }
    }
    private fun config() = JSONObject().put("provider", "deepseek").put("model", "deepseek-flash")
        .put("api", "openai-completions").put("baseUrl", "https://fixture.invalid/v1").put("apiKey", "fixture-secret-never-send")
        .put("unrelated", "preserve")

    @Test fun searchKeysAreEncryptedIndependentAndOnlySelectedProviderEntersRuntime() = withStore { store, directory ->
        store.save(config())
        store.saveSearchSettings(JSONObject().put("provider", "bocha").put("keys", JSONObject()
            .put("bocha", "bocha-fixture-secret").put("baidu", "baidu-fixture-secret")))
        assertFalse(File(directory, "search.enc").readText().contains("fixture-secret"))
        assertEquals("fixture-secret-never-send", store.load().getString("apiKey"))
        assertEquals("bocha-fixture-secret", store.toolsConfig().getJSONObject("search").getString("apiKey"))
        assertFalse(store.toolsConfig().toString().contains("baidu-fixture-secret"))
        store.save(config().put("apiKey", "updated-model-key"))
        store.registry()
        val search = store.searchSettings().put("provider", "baidu")
        store.saveSearchSettings(search)
        assertEquals("baidu-fixture-secret", store.toolsConfig().getJSONObject("search").getString("apiKey"))
        assertFalse(store.registry().toString().contains("baidu-fixture-secret"))
        assertEquals("updated-model-key", store.load().getString("apiKey"))
        assertTrue(runCatching { store.saveSearchSettings(search.put("provider", "unknown")) }.isFailure)
        assertEquals("baidu", store.searchSettings().getString("provider"))
    }

    @Test fun migrationKeepsLegacyCredentialsAndDoesNotExposeThem() = withStore { store, directory ->
        store.save(config())
        val registry = store.registry()
        assertEquals(1, registry.getJSONArray("connections").length())
        val selection = registry.getJSONObject("defaultSelection")
        assertEquals("", selection.getString("thinkingLevel"))
        assertEquals("fixture-secret-never-send", store.load().getString("apiKey"))
        assertEquals("preserve", store.load().getString("unrelated"))
        assertEquals(selection.getString("connectionId"), store.registry().getJSONObject("defaultSelection").getString("connectionId"))
        assertFalse(store.modelOptions().toString().contains("fixture-secret"))
        assertFalse(store.modelOptions().toString().contains("fixture.invalid"))
        assertFalse(File(directory, "model.enc").readText().contains("fixture-secret"))
        assertTrue(store.modelMetadata("deepseek", "openai-completions", "deepseek-flash").getBoolean("known"))
        assertFalse(store.modelMetadata("custom", "openai-responses", "future-model").getBoolean("known"))
        assertEquals(0, store.modelMetadata("custom", "openai-responses", "future-model").getJSONArray("thinkingLevels").length())
    }
    @Test fun queueBindingRetainsRevisionAndDeletionFailsClosed() = withStore { store, _ ->
        store.save(config())
        val original = store.registry()
        val binding = store.binding(original.getJSONObject("defaultSelection"))
        val edited = original.getJSONArray("connections").getJSONObject(0).put("baseUrl", "https://changed.invalid/v1").put("apiKey", "new-secret")
        store.saveConnection(edited)
        assertEquals("https://fixture.invalid/v1", store.resolve(binding).getString("baseUrl"))
        assertEquals("fixture-secret-never-send", store.resolve(binding).getString("apiKey"))
        assertEquals("https://changed.invalid/v1", store.load().getString("baseUrl"))
        assertFalse(binding.toString().contains("secret"))
        store.deleteConnection(binding.getString("connectionId"))
        assertTrue(runCatching { store.resolve(binding) }.isFailure)
        assertEquals(0, store.registry().getJSONObject("versions").length())
    }
    @Test fun legacySavePreservesOtherConnectionsAndCustomInputDeclaration() = withStore { store, _ ->
        store.save(config()); store.registry()
        val id = store.saveConnection(JSONObject().put("name", "Custom").put("provider", "").put("api", "openai-responses")
            .put("baseUrl", "https://custom.invalid/v1").put("apiKey", "custom-secret")
            .put("models", JSONArray().put(JSONObject().put("id", "custom-vision").put("input", JSONArray(listOf("text", "image"))).put("reasoning", true))))
        store.save(config().put("apiKey", "updated"))
        assertEquals(2, store.registry().getJSONArray("connections").length())
        val selection = JSONObject().put("connectionId", id).put("modelId", "custom-vision").put("thinkingLevel", "")
        val custom = store.resolve(store.binding(selection))
        assertEquals("image", custom.getJSONArray("input").getString(1))
        assertTrue(custom.getString("provider").startsWith("bbui-"))
        assertTrue(custom.getBoolean("reasoning"))
    }
}
