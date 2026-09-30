package io.bbui.assistant

import org.json.JSONArray
import org.json.JSONObject

/** Only explicit endpoint metadata and saved per-model settings describe capabilities. */
object ModelSettings {
    val levels = listOf("off", "minimal", "low", "medium", "high", "xhigh", "max")
    fun thinkingLevels(model: JSONObject): JSONArray {
        if (!model.optBoolean("reasoning")) return JSONArray()
        return model.optJSONArray("thinkingLevels") ?: JSONArray()
    }
    fun fromEndpoint(row: JSONObject, api: String? = null, baseUrl: String? = null): JSONObject {
        val result = JSONObject()
        val modalities = row.optJSONArray("input_modalities") ?: row.optJSONObject("architecture")?.optJSONArray("input_modalities")
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
        fun present(key: String): Any? = row.opt(key).takeUnless { it == JSONObject.NULL }
        length("contextWindow", present("context_window") ?: present("max_input_tokens") ?: row.opt("context_length"))
        length("maxTokens", present("max_output_tokens") ?: present("max_tokens") ?: row.optJSONObject("top_provider")?.opt("max_completion_tokens"))
        val effort = capabilities?.optJSONObject("effort")
        if (result.optBoolean("reasoning") && effort?.optBoolean("supported") == true) result.put("thinkingLevels", JSONArray(
            levels.filter { effort.optJSONObject(it)?.optBoolean("supported") == true }.map { JSONObject().put("id", it).put("label", it) }))
        val explicitEffort = row.optJSONObject("effort")
        explicitEffort?.optJSONArray("supported_levels")?.let { values ->
            val supported = (0 until values.length()).mapNotNull { values.opt(it) as? String }.filter { it in levels }.distinct()
            if (supported.isNotEmpty() && reasoning != false) result.put("reasoning", true)
            if (result.optBoolean("reasoning")) {
                result.put("thinkingLevels", JSONArray(supported.map { JSONObject().put("id", it).put("label", if (it == "off") "关闭" else it) }))
                val default = explicitEffort.optString("default_level")
                if (default in supported) result.put("defaultThinkingLevel", default)
                // Native DeepSeek disables thinking separately from its effort values.
                val nativeDeepSeek = runCatching {
                    val endpoint = java.net.URI(baseUrl?.trim())
                    api == "openai-completions" && endpoint.scheme.equals("https", true) && endpoint.host.equals("api.deepseek.com", true)
                        && endpoint.port in listOf(-1, 443) && endpoint.path.trimEnd('/') in listOf("", "/v1")
                        && endpoint.userInfo == null && endpoint.rawQuery == null && endpoint.rawFragment == null
                }.getOrDefault(false)
                if (nativeDeepSeek && supported.isNotEmpty() && "off" !in supported) result.put("thinkingLevels", JSONArray(
                    (listOf("off") + supported).map { JSONObject().put("id", it).put("label", if (it == "off") "关闭" else it) }))
            }
        }
        return result
    }
    fun fillMissing(saved: JSONObject, discovered: JSONObject) {
        discovered.keys().forEach { key -> if (key != "defaultThinkingLevel" && (!saved.has(key) || saved.isNull(key))) saved.put(key, discovered.get(key)) }
        if (!saved.has("defaultThinkingLevel") || saved.isNull("defaultThinkingLevel")) {
            val default = discovered.optString("defaultThinkingLevel")
            val effectiveLevels = saved.optJSONArray("thinkingLevels")
            if (effectiveLevels != null && (0 until effectiveLevels.length()).any { effectiveLevels.optJSONObject(it)?.optString("id") == default }) {
                saved.put("defaultThinkingLevel", default)
            }
        }
    }
}
