package io.bbui.assistant

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ModelSettingsTest {
    @Test fun metadataMatchesSharedEndpointFixtures() {
        val cases = JSONArray(javaClass.getResource("/model-metadata.json")!!.readText())
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val expected = case.getJSONObject("expected"); val actual = ModelSettings.fromEndpoint(case.getJSONObject("row"))
            assertEquals(expected.keys().asSequence().toSet(), actual.keys().asSequence().toSet())
            expected.keys().forEach { key -> assertEquals(expected.get(key).toString(), actual.get(key).toString()) }
        }
    }
    @Test fun savedSettingsWinAndSessionControlsNeedNoManualLevelList() {
        val saved = JSONObject().put("input", JSONArray(listOf("text"))).put("contextWindow", 32000).put("reasoning", true)
        ModelSettings.fillMissing(saved, JSONObject().put("input", JSONArray(listOf("text", "image"))).put("contextWindow", 999999).put("maxTokens", 4000))
        assertEquals("[\"text\"]", saved.getJSONArray("input").toString())
        assertEquals(32000, saved.getInt("contextWindow")); assertEquals(4000, saved.getInt("maxTokens"))
        assertEquals(5, ModelSettings.thinkingLevels(saved).length())
        assertEquals(0, ModelSettings.thinkingLevels(JSONObject()).length())
    }
    @Test fun presetChangesConnectionOnlyAndClearsKeyForDifferentEndpoint() {
        val preset = JSONObject().put("name", "示例").put("provider", "bbui").put("api", "openai-completions").put("baseUrl", "https://new.invalid/v1")
        val connection = JSONObject().put("name", "我的连接").put("baseUrl", "https://old.invalid").put("apiKey", "fixture").put("models", JSONArray().put(JSONObject().put("id", "custom").put("reasoning", false)))
        ProviderPresets.apply(connection, preset, listOf(preset))
        assertEquals("", connection.getString("apiKey")); assertEquals("我的连接", connection.getString("name"))
        assertFalse(connection.getJSONArray("models").getJSONObject(0).getBoolean("reasoning"))
        ProviderPresets.apply(connection, null, listOf(preset))
        assertEquals("https://new.invalid/v1", connection.getString("baseUrl"))
    }
}
