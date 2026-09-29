package io.bbui.assistant

import org.json.JSONArray
import org.json.JSONObject

/** Host control envelope stays in Pi history; the conversation projects only its user action. */
internal object ContinuationPrompt {
    private const val marker = "[[BBUI_HOST_RESUME_V1]]\n"
    private const val instruction = "用户已明确交还控制权，请继续当前未完成任务。先查看当前手机画面，再根据原会话与当前状态决定下一步；禁止重放旧动作，历史截图编号已失效。"
    fun create(task: JSONObject): String = marker + JSONObject().put("instruction", instruction)
        .put("task", task.optString("text")).put("steering", task.optJSONArray("steering") ?: JSONArray())
        .put("lastDispatch", task.optJSONObject("lastDispatch") ?: JSONObject()).toString()
    fun project(text: String): String {
        if (!text.startsWith(marker)) return text
        val payload = runCatching { JSONObject(text.removePrefix(marker)) }.getOrNull() ?: return text
        return if (payload.optString("instruction") == instruction && payload.has("task") &&
            payload.optJSONArray("steering") != null && payload.optJSONObject("lastDispatch") != null) "继续任务" else text
    }
}
