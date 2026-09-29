package io.bbui.assistant

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class EventPresentationTest {
    @Test fun imagePayloadIsNotRetainedInHistory() {
        val largeImage = "A".repeat(1_000_000)
        val original = JSONObject().put("type", "tool_execution_end")
            .put("result", JSONObject().put("content", JSONArray().put(JSONObject().put("type", "image").put("data", largeImage))))
        val compact = EventPresentation.compact(original)
        assertEquals(setOf("type", "status", "message"), compact.keys().asSequence().toSet())
        assertTrue(compact.toString().length < 256)
        assertFalse(compact.toString().contains(largeImage))
    }
    @Test fun diagnosticsAreBoundedAndRemoveCredentialsAndDataUrls() {
        val event = JSONObject().put("type", "error").put("message",
            "Authorization: Bearer SECRET-token apiKey=SECRET-key image=data:image/png;base64,AAAA " + "x".repeat(10000))
        val message = EventPresentation.compact(event).getString("message")
        assertFalse(message.contains("SECRET"))
        assertFalse(message.contains("base64"))
        assertTrue(message.length <= 2048)
    }
    @Test fun gateCountsAreVisibleWithoutCopyingPayload() {
        val event = JSONObject().put("type", "runtime_gate_result").put("passed", true).put("toolCalls", 2).put("imagesSeen", 2)
        val line = EventPresentation.line(EventPresentation.compact(event))
        assertTrue(line.contains("通过"))
        assertTrue(line.contains("工具 2 次，图片 2 张"))
    }
    @Test fun assistantDeltaIsShownAndToolDetailsAreExcluded() {
        val event = JSONObject().put("type", "message_update")
            .put("assistantMessageEvent", JSONObject().put("delta", "已找到商品"))
            .put("details", JSONObject().put("private", "not retained"))
        val compact = EventPresentation.compact(event)
        assertEquals("已找到商品", compact.getString("message"))
        assertFalse(compact.has("details"))
    }
    @Test fun structuredMessageWithImageDoesNotBecomeDisplayText() {
        val event = JSONObject().put("type", "message_start").put("message",
            JSONObject().put("content", JSONArray().put(JSONObject().put("type", "image").put("data", "A".repeat(1000000)))))
        assertEquals("message_start", EventPresentation.compact(event).getString("message"))
    }
    @Test fun failedAssistantMessageShowsProviderErrorWithoutCopyingImagesOrKeys() {
        val event = JSONObject().put("type", "message_end").put("message", JSONObject()
            .put("role", "assistant").put("stopReason", "error")
            .put("errorMessage", "401 Incorrect API key provided: sk-secret123456789. Check configuration.")
            .put("content", JSONArray().put(JSONObject().put("type", "image").put("data", "A".repeat(1000000)))))
        val compact = EventPresentation.compact(event)
        assertEquals("error", compact.getString("status"))
        assertTrue(compact.getString("message").contains("401"))
        assertTrue(compact.getString("message").contains("模型请求失败"))
        assertFalse(compact.toString().contains("secret123456789"))
        assertFalse(compact.has("content"))
        assertTrue(compact.toString().length < 512)
    }
    @Test fun missingProviderErrorStillExplainsFailure() {
        val event = JSONObject().put("type", "message_end").put("message", JSONObject()
            .put("role", "assistant").put("stopReason", "error"))
        val compact = EventPresentation.compact(event)
        assertEquals("error", compact.getString("status"))
        assertTrue(compact.getString("message").contains("服务未返回错误详情"))
    }
}
