package io.bbui.assistant

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PreviewExecutionTest {
    private fun store() = ConversationStore().apply {
        for (id in listOf("a", "b")) sessions[id] = JSONObject().put("id", id).put("title", id)
        selected = "b"
        running = JSONObject().put("sessionId", "a")
    }
    private fun event(type: String, run: String = "1") = JSONObject().put("type", type).put("runId", run).put("sessionId", "a")
    private fun tool(type: String, id: String, intent: String = "点击打开订单") = event(type).put("toolCallId", id)
        .put("toolName", "phone_action").put("args", JSONObject().put("操作", "点击").put("意图", intent)
            .put("参数", JSONObject().put("private", "not-for-header")))

    @Test fun previewUsesExecutionOwnerAndOnlyActuallyStartedTools() {
        val state = store()
        state.accept(event("run_started"))
        state.accept(event("message_start").put("message", JSONObject().put("role", "assistant")
            .put("content", JSONArray().put(JSONObject().put("type", "toolCall").put("id", "pending").put("name", "phone_action")
                .put("arguments", JSONObject().put("意图", "尚未派发的意图"))))))
        assertFalse(state.snapshot().getJSONObject("execution").has("intent"))
        state.accept(tool("tool_execution_start", "tap"))
        val active = state.snapshot()
        assertEquals("b", active.getString("sessionId"))
        assertEquals("点击打开订单", active.getJSONObject("execution").getString("intent"))
        assertFalse(active.getJSONObject("execution").toString().contains("not-for-header"))
        state.accept(tool("tool_execution_end", "tap").put("isError", true).put("result", JSONObject()))
        assertFalse(state.snapshot().getJSONObject("execution").has("intent"))
        state.accept(tool("tool_execution_start", "swipe", "下滑列表"))
        assertEquals("下滑列表", state.snapshot().getJSONObject("execution").getString("intent"))
        state.accept(event("run_stopped", "2"))
        state.accept(tool("tool_execution_start", "late"))
        assertFalse(state.snapshot().getJSONObject("execution").has("intent"))
    }
}
