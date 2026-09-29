package io.bbui.assistant

import org.json.JSONObject

/** Whitelists bounded UI text; never stores tool image/content payloads in event history. */
object EventPresentation {
    private const val MAX_TEXT = 2048
    private val dataUrl = Regex("data:[^\\s,]{1,100};base64,[A-Za-z0-9+/=_-]+")
    private val bearer = Regex("(?i)Bearer\\s+[A-Za-z0-9._~+/-]+=*")
    private val apiKey = Regex("(?i)(api[ _-]?key(?:\\s+provided)?[\\s\"']*[:=][\\s\"']*)[^\\s\"',}]+")
    private val providerKey = Regex("sk-[A-Za-z0-9_-]{8,}")

    private fun text(value: String, limit: Int = MAX_TEXT): String {
        // Bound work as well as retained text. Images are absent from the whitelist.
        val bounded = value.take(MAX_TEXT * 4)
        return providerKey.replace(apiKey.replace(bearer.replace(dataUrl.replace(bounded, "[图片已省略]"), "Bearer [已隐藏]"), "$1[已隐藏]"), "[已隐藏]").take(limit)
    }

    fun compact(event: JSONObject): JSONObject {
        val type = text(event.optString("type", "event"), 80)
        val completedMessage = if (type == "message_end") event.optJSONObject("message") else null
        val assistantError = completedMessage != null && completedMessage.optString("role") == "assistant" && completedMessage.optString("stopReason") == "error"
        val status = if (assistantError) "error" else text(event.optString("status"), 80)
        val message = when (type) {
            "runtime_gate_result" -> "运行时测试${if (event.optBoolean("passed")) "通过" else "未通过"}：工具 ${event.optInt("toolCalls")} 次，图片 ${event.optInt("imagesSeen")} 张"
            "phone_action_result" -> "手机操作：${event.optString("operation")}"
            "message_update" -> (event.optJSONObject("assistantMessageEvent")?.opt("delta") as? String).orEmpty()
            "message_end" -> if (assistantError) "模型请求失败：${(completedMessage?.opt("errorMessage") as? String).orEmpty().ifBlank { "服务未返回错误详情" }}" else "回复结束"
            "response" -> event.optString("error").ifBlank { "${event.optString("command")}：${if (event.optBoolean("success")) "完成" else "失败"}" }
            else -> (event.opt("message") as? String).orEmpty().ifBlank { status.ifBlank { type } }
        }
        return JSONObject().put("type", type).put("status", status).put("message", text(message))
    }

    fun line(event: JSONObject): String = "[${event.optString("type", "event")}] ${event.optString("message")}\n"
}
