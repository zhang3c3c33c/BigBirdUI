package io.bbui.assistant

import org.json.JSONArray
import org.json.JSONObject

/** Only explicit endpoint metadata and saved per-model settings describe capabilities. */
object ModelSettings {
    val levels = listOf("off", "minimal", "low", "medium", "high", "xhigh", "max")
    fun thinkingLevels(model: JSONObject): JSONArray {
        if (!model.optBoolean("reasoning")) return JSONArray()
        return model.optJSONArray("thinkingLevels") ?: JSONArray(levels.take(5).map { JSONObject().put("id", it).put("label", it) })
    }
    fun fromEndpoint(row: JSONObject): JSONObject {
        val result = JSONObject()
        val modalities = row.optJSONObject("architecture")?.optJSONArray("input_modalities")
        if (modalities != null && modalities.length() > 0) result.put("input", JSONArray().put("text").apply {
            if ((0 until modalities.length()).any { modalities.optString(it) == "image" }) put("image")
        })
        val capabilities = row.optJSONObject("capabilities")
        val image = capabilities?.optJSONObject("image_input")?.opt("supported")
        if (image is Boolean) result.put("input", JSONArray().put("text").apply { if (image) put("image") })
        val reasoning = capabilities?.optJSONObject("thinking")?.opt("supported")
        if (reasoning is Boolean) result.put("reasoning", reasoning)
        else row.optJSONArray("supported_parameters")?.let { params ->
            if ((0 until params.length()).any { params.optString(it) in setOf("reasoning", "include_reasoning") }) result.put("reasoning", true)
        }
        if (reasoning == true && capabilities?.optJSONObject("thinking")?.optJSONObject("types")?.optJSONObject("adaptive")?.optBoolean("supported") == true) result.put("thinkingMode", "adaptive")
        fun length(key: String, value: Any?) {
            if (value is Number && value.toDouble() == value.toLong().toDouble() && value.toLong() in 1..Int.MAX_VALUE.toLong()) result.put(key, value.toLong())
        }
        length("contextWindow", row.opt("max_input_tokens").takeUnless { it == JSONObject.NULL } ?: row.opt("context_length"))
        length("maxTokens", row.opt("max_tokens").takeUnless { it == JSONObject.NULL } ?: row.optJSONObject("top_provider")?.opt("max_completion_tokens"))
        val effort = capabilities?.optJSONObject("effort")
        if (result.optBoolean("reasoning") && effort?.optBoolean("supported") == true) result.put("thinkingLevels", JSONArray(
            levels.filter { effort.optJSONObject(it)?.optBoolean("supported") == true }.map { JSONObject().put("id", it).put("label", it) }))
        return result
    }
    fun fillMissing(saved: JSONObject, discovered: JSONObject) {
        discovered.keys().forEach { key -> if (!saved.has(key) || saved.isNull(key)) saved.put(key, discovered.get(key)) }
    }
}
