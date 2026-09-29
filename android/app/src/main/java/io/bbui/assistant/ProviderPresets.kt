package io.bbui.assistant

import org.json.JSONArray
import org.json.JSONObject

object ProviderPresets {
    fun parse(json: String): List<JSONObject> = JSONArray(json).let { rows -> (0 until rows.length()).map { rows.getJSONObject(it) } }
    fun apply(connection: JSONObject, preset: JSONObject?, presets: List<JSONObject>) {
        if (preset == null) { connection.put("provider", "bbui"); return }
        if (connection.optString("baseUrl").trim().trimEnd('/') != preset.getString("baseUrl").trimEnd('/')) connection.put("apiKey", "")
        if (connection.optString("name").isBlank() || presets.any { it.getString("name") == connection.optString("name") }) connection.put("name", preset.getString("name"))
        for (key in listOf("provider", "api", "baseUrl")) connection.put(key, preset.getString(key))
    }
}
