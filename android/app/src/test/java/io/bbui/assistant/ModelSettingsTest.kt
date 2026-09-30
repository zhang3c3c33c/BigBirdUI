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
            val connection = case.optJSONObject("connection")
            val expected = case.getJSONObject("expected"); val actual = ModelSettings.fromEndpoint(case.getJSONObject("row"), connection?.optString("api"), connection?.optString("baseUrl"))
            assertEquals(expected.keys().asSequence().toSet(), actual.keys().asSequence().toSet())
            expected.keys().forEach { key -> assertEquals(expected.get(key).toString(), actual.get(key).toString()) }
        }
    }
    @Test fun onlyNativeDeepSeekEndpointAddsSeparateOffControl() {
        val row = JSONObject("""{"effort":{"supported_levels":["low","high","max"],"default_level":"high"}}""")
        for (base in listOf("https://api.deepseek.com", "https://api.deepseek.com/v1/")) {
            val model = ModelSettings.fromEndpoint(row, "openai-completions", base)
            assertEquals("off", model.getJSONArray("thinkingLevels").getJSONObject(0).getString("id"))
            assertEquals(4, model.getJSONArray("thinkingLevels").length())
        }
        for (base in listOf("https://api.deepseek.com.evil/v1", "https://api.deepseek.com@evil/v1", "https://user@api.deepseek.com/v1", "https://api.deepseek.com/proxy", "https://custom.invalid", "http://api.deepseek.com", "https://api.deepseek.com:444", "https://api.deepseek.com?x=1")) {
            val model = ModelSettings.fromEndpoint(row, "openai-completions", base)
            assertEquals("low", model.getJSONArray("thinkingLevels").getJSONObject(0).getString("id"))
            assertEquals(3, model.getJSONArray("thinkingLevels").length())
        }
        assertEquals(3, ModelSettings.fromEndpoint(row, "openai-responses", "https://api.deepseek.com").getJSONArray("thinkingLevels").length())
        assertFalse(ModelSettings.fromEndpoint(JSONObject().put("reasoning", true), "openai-completions", "https://api.deepseek.com").has("thinkingLevels"))
    }
    @Test fun savedSettingsWinAndUnknownLevelsAreNotInvented() {
        val saved = JSONObject().put("input", JSONArray(listOf("text"))).put("contextWindow", 32000).put("reasoning", true)
        ModelSettings.fillMissing(saved, JSONObject().put("input", JSONArray(listOf("text", "image"))).put("contextWindow", 999999).put("maxTokens", 4000))
        assertEquals("[\"text\"]", saved.getJSONArray("input").toString())
        assertEquals(32000, saved.getInt("contextWindow")); assertEquals(4000, saved.getInt("maxTokens"))
        assertEquals(0, ModelSettings.thinkingLevels(saved).length())
        assertEquals(0, ModelSettings.thinkingLevels(JSONObject()).length())
    }
    @Test fun explicitLevelsAndDefaultSurviveRefresh() {
        val saved = JSONObject().put("reasoning", true).put("thinkingLevels", JSONArray().put(JSONObject().put("id", "max").put("label", "max"))).put("defaultThinkingLevel", "max")
        val remote = ModelSettings.fromEndpoint(JSONObject("""{"effort":{"supported_levels":["low","high","max"],"default_level":"high"}}"""))
        ModelSettings.fillMissing(saved, remote)
        assertEquals("max", saved.getString("defaultThinkingLevel"))
        assertEquals("max", ModelSettings.thinkingLevels(saved).getJSONObject(0).getString("id"))
        assertEquals(1, ModelSettings.thinkingLevels(saved).length())
        saved.remove("defaultThinkingLevel")
        ModelSettings.fillMissing(saved, remote)
        assertFalse(saved.has("defaultThinkingLevel"))
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
