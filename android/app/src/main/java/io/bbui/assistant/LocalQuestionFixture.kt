package io.bbui.assistant

import org.json.JSONArray
import org.json.JSONObject

/** Test provider oracle: only the latest matching successful tool result proves the answer arrived. */
internal object LocalQuestionFixture {
    fun receivedAnswer(messages: JSONArray, textOnly: Boolean = false): Boolean {
        val latestUser = (messages.length() - 1 downTo 0).firstOrNull { messages.optJSONObject(it)?.optString("role") == "user" } ?: -1
        val tool = (messages.length() - 1 downTo latestUser + 1).mapNotNull { messages.optJSONObject(it) }
            .firstOrNull { it.optString("role") == "tool" && it.optString("tool_call_id") == "question-fixture" } ?: return false
        if (tool.optBoolean("isError") || tool.has("error")) return false
        val content = when (val raw = tool.opt("content")) {
            is String -> raw
            is JSONArray -> (0 until raw.length()).mapNotNull { i -> raw.optJSONObject(i)?.takeIf { it.optString("type") == "text" }?.optString("text") }.joinToString("\n")
            else -> return false
        }
        val expected = if (textOnly) "收件人: \"测试补充 😀\" (other)" else "选择: 甲, \"测试补充 😀\" (other)"
        return content.trim() == expected
    }

    fun skillPath(messages: JSONArray): String {
        val system = (0 until messages.length()).mapNotNull { messages.optJSONObject(it) }
            .filter { it.optString("role") in setOf("system", "developer") }.joinToString("\n") { it.optString("content") }
        require(!system.contains("expert coding assistant")) { "Coding identity remains in the phone prompt" }
        return Regex("<location>([^<]*phone-operation/SKILL\\.md)</location>").find(system)?.groupValues?.get(1)
            ?: error("Packaged phone skill is missing from the system prompt")
    }

    fun receivedSkill(messages: JSONArray): Boolean {
        val latestUser = (messages.length() - 1 downTo 0).firstOrNull { messages.optJSONObject(it)?.optString("role") == "user" } ?: -1
        val latest = (messages.length() - 1 downTo latestUser + 1).mapNotNull { messages.optJSONObject(it) }
            .firstOrNull { it.optString("role") == "tool" && it.optString("tool_call_id") == "skill-fixture" } ?: return false
        return !latest.optBoolean("isError") && !latest.has("error") && latest.optString("content").contains("name: phone-operation")
    }
}
