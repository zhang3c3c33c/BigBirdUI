package io.bbui.assistant

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ChatStoreTest {
    @Test fun nonGuiCardsRetainIntentAndExcludeProviderBodies() {
        val names = mapOf("contacts" to "管理联系人", "sms" to "查询短信", "call_log" to "查询通话记录", "media" to "检索媒体", "clock" to "设置闹钟与倒计时")
        for ((name, title) in names) for (intent in listOf("", "查看指定测试对象")) {
            val store = ChatStore(); begin(store); start(store)
            val args = JSONObject().put("operation", "details").put("intent", intent).put("params", JSONObject().put("query", "private-provider-input"))
            val call = JSONObject().put("type", "toolCall").put("id", name).put("name", "system_$name").put("arguments", args)
            finalMessage(store, JSONArray().put(call), "toolUse")
            store.accept(event("tool_execution_end").put("toolCallId", name).put("result", JSONObject().put("details", JSONObject()
                .put("data", JSONObject().put("body", "private-provider-body"))
                .put("bbuiTool", JSONObject().put("summary", "已返回结果")))))
            assertEquals(intent.ifBlank { title }, rows(store).getJSONObject(1).getJSONArray("parts").getJSONObject(0).getString("title"))
            assertFalse(store.snapshot().toString().contains("private-provider"))
        }
    }
    @Test fun calendarCardsKeepIntentWithoutCopyingCalendarContents() {
        for (intent in listOf("", "安排下周的项目会议")) {
            val store = ChatStore(); begin(store); start(store)
            val args = JSONObject().put("operation", "create").put("intent", intent)
                .put("params", JSONObject().put("description", "private-meeting-description"))
            val call = JSONObject().put("type", "toolCall").put("id", "calendar").put("name", "system_calendar").put("arguments", args)
            finalMessage(store, JSONArray().put(call), "toolUse")
            store.accept(event("tool_execution_end").put("toolCallId", "calendar").put("result", JSONObject().put("details", JSONObject()
                .put("data", JSONObject().put("title", "private-meeting-title"))
                .put("bbuiTool", JSONObject().put("title", "管理日程").put("summary", "create：已返回结果"))
                .put("执行", JSONObject().put("状态", "已派发")))))
            val part = rows(store).getJSONObject(1).getJSONArray("parts").getJSONObject(0)
            assertEquals(intent.ifBlank { "管理日程" }, part.getString("title"))
            assertFalse(store.snapshot().toString().contains("private-meeting"))
            assertEquals("已派发", part.getString("executionState"))
        }
    }
    @Test fun skillReadSnapshotsKeepOnlyStatusAcrossResultsAndHistory() {
        for (failed in listOf(false, true)) {
            val call = JSONObject().put("type", "toolCall").put("id", "skill").put("name", "read")
                .put("arguments", JSONObject().put("path", "/private/SKILL.md"))
            val result = JSONObject().put("role", "toolResult").put("toolCallId", "skill").put("toolName", "read").put("isError", failed)
                .put("content", JSONArray().put(textBlock("private-document")))
                .put("details", JSONObject().put("错误", "/private/path").put("bbuiTool", JSONObject().put("summary", "private-document")))
            val store = ChatStore(); begin(store); start(store)
            finalMessage(store, JSONArray().put(call), "toolUse")
            store.accept(event("tool_execution_end").put("toolCallId", "skill").put("isError", failed).put("result", result))
            assertFalse(store.snapshot().toString().contains("private"))
            assertTrue(store.snapshot().toString().contains("读取手机操作指南"))
            val restored = ChatStore()
            restored.accept(event("history_snapshot", run = "").put("messages", JSONArray().put(JSONObject().put("role", "assistant").put("content", JSONArray().put(call))).put(result)))
            assertFalse(restored.snapshot().toString().contains("private"))
            val part = rows(restored).getJSONObject(0).getJSONArray("parts").getJSONObject(0)
            assertEquals(if (failed) "error" else "complete", part.getString("state"))
        }
    }
    @Test fun dispatchAndObservationFactsSurviveNormalResultsAndCacheWithoutBecomingSuccess() {
        for ((execution, observation) in listOf("未派发" to "已取得", "部分派发" to "失败",
            "未知" to "未请求", "已派发" to "失败", "无需派发" to "已取得")) {
            val store = ChatStore(); begin(store); start(store)
            val tool = JSONObject().put("type", "toolCall").put("id", "facts").put("name", "phone_action")
                .put("arguments", JSONObject().put("操作", "打开应用").put("意图", "打开美团"))
            finalMessage(store, JSONArray().put(tool), "toolUse")
            val details = JSONObject().put("执行", JSONObject().put("状态", execution))
                .put("观察", JSONObject().put("状态", observation).put("截图", "private-image"))
                .put("原始诊断", "private-diagnostic")
            store.accept(event("tool_execution_end").put("toolCallId", "facts")
                .put("result", JSONObject().put("details", details)))
            val part = rows(store).getJSONObject(1).getJSONArray("parts").getJSONObject(0)
            assertEquals("complete", part.getString("state")) // The call returned; this is not action success.
            assertEquals(execution, part.getString("executionState"))
            assertEquals(observation, part.getString("observationState"))
            assertEquals("打开美团", part.getString("title"))
            assertFalse(part.getString("summary").contains("完成"))
            assertFalse(store.snapshot().toString().contains("private-"))
            val restored = ChatStore(); restored.restore(store.snapshot())
            val cached = rows(restored).getJSONObject(1).getJSONArray("parts").getJSONObject(0)
            assertEquals(execution, cached.getString("executionState"))
            assertEquals(observation, cached.getString("observationState"))
        }
    }

    @Test fun historyToolResultRestoresFactsAndIgnoresUnknownStatePayloads() {
        val store = ChatStore(); begin(store)
        val call = JSONObject().put("type", "toolCall").put("id", "history-facts").put("name", "phone_action")
        val result = JSONObject().put("role", "toolResult").put("toolCallId", "history-facts")
            .put("details", JSONObject().put("执行", JSONObject().put("状态", "已派发"))
                .put("观察", JSONObject().put("状态", "失败")))
        store.accept(event("history_snapshot").put("messages", JSONArray()
            .put(JSONObject().put("role", "assistant").put("content", JSONArray().put(call))).put(result)))
        val part = rows(store).getJSONObject(0).getJSONArray("parts").getJSONObject(0)
        assertEquals("已派发", part.getString("executionState"))
        assertEquals("失败", part.getString("observationState"))
        store.accept(event("tool_execution_end").put("toolCallId", "history-facts")
            .put("result", JSONObject().put("details", JSONObject()
                .put("执行", JSONObject().put("状态", "private-invalid-state")))))
        assertFalse(store.snapshot().toString().contains("private-invalid-state"))
    }

    private fun event(type: String, run: String = "1") = JSONObject().put("type", type).put("runId", run)
    private fun begin(store: ChatStore) = store.accept(event("run_started").put("prompt", "请查看手机"))
    private fun start(store: ChatStore) = store.accept(event("message_start").put("message", JSONObject().put("role", "assistant").put("content", JSONArray())))
    private fun delta(store: ChatStore, kind: String, text: String, index: Int = 0, run: String = "1") = store.accept(
        event("message_update", run).put("assistantMessageEvent", JSONObject().put("type", kind).put("contentIndex", index).put("delta", text)))
    private fun textBlock(text: String) = JSONObject().put("type", "text").put("text", text)
    private fun finalMessage(store: ChatStore, parts: JSONArray, reason: String = "stop") = store.accept(event("message_end")
        .put("message", JSONObject().put("role", "assistant").put("content", parts).put("stopReason", reason)))
    private fun rows(store: ChatStore) = store.snapshot().getJSONArray("messages")

    @Test fun arbitraryUnicodeAndLongMarkdownFragmentsFormOneAuthoritativeMessage() {
        val store = ChatStore(); begin(store); start(store)
        val text = "你好🌍\n\n## 标题\n```kotlin\nval a = 1\n```\n" + "持续回复".repeat(6000)
        // Include UTF-16 surrogate boundaries to exercise the transport-independent accumulator.
        text.forEach { delta(store, "text_delta", it.toString()) }
        val id = rows(store).getJSONObject(1).getString("id")
        finalMessage(store, JSONArray().put(textBlock(text)))
        assertEquals(2, rows(store).length())
        assertEquals(id, rows(store).getJSONObject(1).getString("id"))
        assertEquals(text, rows(store).getJSONObject(1).getJSONArray("parts").getJSONObject(0).getString("text"))
        assertFalse(store.snapshot().toString().contains("message_update"))
    }

    @Test fun reasoningToolArgumentsAndProseNeverMerge() {
        var clock = 100L
        val store = ChatStore { clock }; begin(store); start(store)
        delta(store, "thinking_delta", "先确认屏幕", 0)
        clock = 300
        delta(store, "text_delta", "我来查看。", 1)
        store.accept(event("message_update").put("assistantMessageEvent", JSONObject().put("type", "toolcall_start")
            .put("contentIndex", 2).put("id", "call-1").put("toolName", "phone_action")))
        delta(store, "toolcall_delta", "{\"privateArgument\":\"secret\"}", 2)
        val call = JSONObject().put("type", "toolCall").put("id", "call-1").put("name", "phone_action")
            .put("arguments", JSONObject().put("操作", "查看"))
        finalMessage(store, JSONArray().put(JSONObject().put("type", "thinking").put("thinking", "先确认屏幕"))
            .put(textBlock("我来查看。")).put(call), "toolUse")
        store.accept(event("tool_execution_start").put("toolCallId", "call-1").put("toolName", "phone_action"))
        store.accept(event("tool_execution_end").put("toolCallId", "call-1").put("isError", true)
            .put("result", JSONObject().put("details", JSONObject().put("错误", "连接已断开"))))
        val parts = rows(store).getJSONObject(1).getJSONArray("parts")
        assertEquals(listOf("reasoning", "text", "tool"), (0..2).map { parts.getJSONObject(it).getString("type") })
        assertEquals("error", parts.getJSONObject(2).getString("state"))
        assertFalse(store.snapshot().toString().contains("privateArgument"))
        assertTrue(parts.getJSONObject(0).getLong("durationMs") >= 200)
    }

    @Test fun stoppingRetainsPartialAndDiscardsRetiredRun() {
        val store = ChatStore(); begin(store); start(store); delta(store, "text_delta", "已找到")
        store.accept(event("run_stopped", "2"))
        assertFalse(delta(store, "text_delta", "不能出现", run = "1"))
        assertEquals("stopped", rows(store).getJSONObject(1).getString("status"))
        assertEquals("已找到", rows(store).getJSONObject(1).getJSONArray("parts").getJSONObject(0).getString("text"))
        assertFalse(store.snapshot().getBoolean("isRunning"))
    }

    @Test fun authoritativeHistoryRestoresAllMessagesAndKeepsOnlyOnePendingUser() {
        val store = ChatStore(); begin(store)
        val history = JSONArray()
        repeat(105) { history.put(JSONObject().put("role", if (it % 2 == 0) "user" else "assistant").put("content", JSONArray().put(textBlock("历史$it")))) }
        store.accept(event("history_snapshot").put("sessionId", "session-a").put("messages", history))
        assertEquals(40, rows(store).length())
        assertTrue(store.snapshot().getBoolean("hasOlder"))
        assertEquals(106, store.snapshot(200).getJSONArray("messages").length())
        store.accept(event("message_start").put("message", JSONObject().put("role", "user").put("content", "请查看手机")))
        assertEquals(106, store.snapshot(200).getJSONArray("messages").length())
    }

    @Test fun cacheRestoreDoesNotResumeAndIdsRemainUnique() {
        val original = ChatStore(); begin(original); start(original); delta(original, "text_delta", "部分答案")
        val restored = ChatStore(); restored.restore(original.snapshot(Int.MAX_VALUE))
        assertFalse(restored.snapshot().getBoolean("isRunning"))
        assertEquals("stopped", rows(restored).getJSONObject(1).getString("status"))
        begin(restored); start(restored)
        val rows = rows(restored)
        assertEquals(rows.length(), (0 until rows.length()).map { rows.getJSONObject(it).getString("id") }.toSet().size)
    }

    @Test fun toolResultsDoNotCopyImagesOrCredentialsIntoProjection() {
        val store = ChatStore(); begin(store); start(store)
        val tool = JSONObject().put("type", "toolCall").put("id", "x").put("name", "phone_action")
            .put("arguments", JSONObject().put("操作", "查看"))
        finalMessage(store, JSONArray().put(tool), "toolUse")
        store.accept(event("tool_execution_end").put("toolCallId", "x").put("result", JSONObject()
            .put("content", JSONArray().put(JSONObject().put("type", "image").put("data", "A".repeat(1000000))))))
        start(store); delta(store, "text_delta", "API key provided: sk-abcdef123456789")
        val projection = store.snapshot().toString()
        assertFalse(projection.contains("abcdef123456789"))
        assertFalse(projection.contains("AAAA"))
        assertTrue(projection.length < 3000)
    }

    @Test fun modelFailureKeepsBodyAndExplicitError() {
        val store = ChatStore(); begin(store); start(store); delta(store, "text_delta", "部分答案")
        store.accept(event("message_end").put("message", JSONObject().put("role", "assistant").put("stopReason", "error")
            .put("errorMessage", "请求断开").put("content", JSONArray().put(textBlock("部分答案")))))
        store.accept(event("status").put("status", "error").put("message", "模型请求失败"))
        store.accept(event("agent_settled"))
        assertEquals("error", rows(store).getJSONObject(1).getString("status"))
        assertEquals("部分答案", rows(store).getJSONObject(1).getJSONArray("parts").getJSONObject(0).getString("text"))
    }

    @Test fun mockGateNeverPollutesRealConversation() {
        val store = ChatStore(); begin(store)
        val before = rows(store).toString()
        assertFalse(store.accept(event("history_snapshot").put("gate", true).put("messages", JSONArray())))
        assertEquals(before, rows(store).toString())
    }

    @Test fun actualPiFixtureProducesOrderedReasoningToolsAndFinalText() {
        val fixture = JSONArray(javaClass.getResource("/chat-events.json")!!.readText())
        val store = ChatStore(); store.accept(event("run_started"))
        val expected = mutableListOf<String>()
        for (i in 0 until fixture.length()) {
            val e = fixture.getJSONObject(i)
            store.accept(JSONObject(e.toString()).put("runId", "1"))
            val message = e.optJSONObject("message")
            if (e.optString("type") == "message_end" && message?.optString("role") == "assistant") {
                val content = message.getJSONArray("content")
                for (j in 0 until content.length()) if (content.getJSONObject(j).optString("type") == "text") expected.add(content.getJSONObject(j).getString("text"))
            }
        }
        store.accept(event("agent_settled"))
        val messages = store.snapshot(Int.MAX_VALUE).getJSONArray("messages")
        val parts = (0 until messages.length()).map { messages.getJSONObject(it) }.filter { it.getString("role") == "assistant" }
            .flatMap { m -> val p = m.getJSONArray("parts"); (0 until p.length()).map { p.getJSONObject(it) } }
        assertEquals(expected, parts.filter { it.getString("type") == "text" }.map { it.getString("text") })
        assertTrue(parts.any { it.getString("type") == "reasoning" })
        assertTrue(parts.count { it.getString("type") == "tool" && it.getString("state") == "complete" } >= 2)
        val phoneParts = parts.filter { it.optString("toolName") == "phone_action" }
        assertEquals(1, phoneParts.count { it.getString("state") == "error" })
        assertEquals(1, phoneParts.count { it.getString("state") == "complete" })
        val taskParts = parts.filter { it.optString("toolName") == "task_state" }
        assertEquals(listOf("更新任务记录", "更新任务记录", "读取任务记录"), taskParts.map { it.getString("title") })
        assertTrue(taskParts.all { it.getString("state") == "complete" })
        assertEquals("waiting_user", store.snapshot().getJSONObject("task").getString("status"))
        assertEquals("waiting_user", store.snapshot().getJSONObject("taskDisplay").getString("state"))
        assertFalse(store.snapshot().toString().contains("\"arguments\""))
    }

    @Test fun restartMergesConfirmedHistoryWithoutLosingUnflushedPartialOrCompactedRows() {
        val store = ChatStore(); begin(store)
        store.accept(event("message_start").put("message", JSONObject().put("role", "user").put("timestamp", 100).put("content", "请查看手机")))
        store.accept(event("message_start").put("message", JSONObject().put("role", "assistant").put("timestamp", 101).put("content", JSONArray())))
        delta(store, "text_delta", "已看到部分结果")
        store.accept(event("run_stopped", "2"))
        store.accept(event("run_started", "3").put("prompt", "继续检查"))
        val user = JSONObject().put("role", "user").put("timestamp", 100).put("content", "请查看手机")
        store.accept(event("history_snapshot", "3").put("sessionId", "session").put("messages", JSONArray().put(user)))
        assertEquals(3, rows(store).length())
        assertEquals("已看到部分结果", rows(store).getJSONObject(1).getJSONArray("parts").getJSONObject(0).getString("text"))
        val aborted = JSONObject().put("role", "assistant").put("timestamp", 101).put("stopReason", "aborted")
            .put("content", JSONArray().put(textBlock("已看到部分结果")))
        store.accept(event("history_snapshot", "3").put("sessionId", "session").put("messages", JSONArray().put(user).put(aborted)))
        assertEquals(3, rows(store).length())
        assertEquals("stopped", rows(store).getJSONObject(1).getString("status"))
        store.accept(event("history_snapshot", "3").put("sessionId", "session").put("messages", JSONArray()))
        assertEquals(3, rows(store).length())
    }

    @Test fun interruptedHistoryToolNeverBecomesSuccessfulOnNextTurn() {
        val store = ChatStore(); begin(store)
        val call = JSONObject().put("type", "toolCall").put("id", "unfinished").put("name", "phone_action")
        val history = JSONArray().put(JSONObject().put("role", "assistant").put("timestamp", 100)
            .put("content", JSONArray().put(call)).put("stopReason", "toolUse"))
        store.accept(event("history_snapshot").put("messages", history))
        store.accept(event("agent_settled"))
        assertEquals("stopped", rows(store).getJSONObject(0).getJSONArray("parts").getJSONObject(0).getString("state"))
    }

    @Test fun emptyErrorTerminalPreservesPreviouslyReceivedText() {
        val store = ChatStore(); begin(store); start(store); delta(store, "text_delta", "已经收到")
        store.accept(event("status").put("status", "error").put("message", "模型请求失败"))
        finalMessage(store, JSONArray(), "error")
        assertEquals(2, rows(store).length())
        assertEquals("已经收到", rows(store).getJSONObject(1).getJSONArray("parts").getJSONObject(0).getString("text"))
    }

    @Test fun stopBeforeUserAcknowledgementStillDeduplicatesPersistedPrompt() {
        val store = ChatStore { 100 }; begin(store)
        store.accept(event("run_stopped", "2"))
        store.accept(event("run_started", "3").put("prompt", "下一步"))
        val user = JSONObject().put("role", "user").put("timestamp", 101).put("content", "请查看手机")
        store.accept(event("history_snapshot", "3").put("messages", JSONArray().put(user)))
        assertEquals(2, rows(store).length())
        assertEquals("user:101", rows(store).getJSONObject(0).getString("sourceKey"))
    }

    @Test fun toolIntentIsDisplayOnlyAndSurvivesHistoryRestoreWithFailureState() {
        val store = ChatStore(); begin(store); start(store)
        val args = JSONObject().put("操作", "点击").put("意图", "点击打开月度账单")
            .put("参数", JSONObject().put("位置", JSONArray().put(100).put(200)).put("内容", "private-input"))
        val tool = JSONObject().put("type", "toolCall").put("id", "intent-call").put("name", "phone_action").put("arguments", args)
        val message = JSONObject().put("role", "assistant").put("timestamp", 10).put("content", JSONArray().put(tool)).put("stopReason", "toolUse")
        store.accept(event("message_end").put("message", message))
        store.accept(event("tool_execution_start").put("toolCallId", "intent-call").put("args", args))
        val part = rows(store).getJSONObject(1).getJSONArray("parts").getJSONObject(0)
        assertEquals("点击打开月度账单", part.getString("title"))
        assertEquals("running", part.getString("state"))
        assertFalse(store.snapshot().toString().contains("private-input"))
        val restored = ChatStore(); begin(restored)
        restored.accept(event("history_snapshot").put("messages", JSONArray().put(message).put(JSONObject()
            .put("role", "toolResult").put("toolCallId", "intent-call").put("isError", true))))
        val result = rows(restored).getJSONObject(0).getJSONArray("parts").getJSONObject(0)
        assertEquals("点击打开月度账单", result.getString("title"))
        assertEquals("error", result.getString("state"))
    }

    @Test fun legacyToolTitlesRemainCompatibleAndIntentIsBoundedSanitizedText() {
        val store = ChatStore(); begin(store); start(store)
        fun tool(id: String, args: JSONObject) = JSONObject().put("type", "toolCall").put("id", id).put("name", "phone_action").put("arguments", args)
        finalMessage(store, JSONArray()
            .put(tool("legacy", JSONObject().put("操作", "查看")))
            .put(tool("new", JSONObject().put("操作", "滑动").put("意图", "下滑列表\n 查看更多")))
            .put(tool("private", JSONObject().put("操作", "输入内容").put("意图", "搜索 API key: sk-abcdef123456789 " + "🙂".repeat(100)))))
        val parts = rows(store).getJSONObject(1).getJSONArray("parts")
        assertEquals("查看屏幕", parts.getJSONObject(0).getString("title"))
        assertEquals("下滑列表 查看更多", parts.getJSONObject(1).getString("title"))
        val title = parts.getJSONObject(2).getString("title")
        assertTrue(title.length <= 80)
        assertFalse(Character.isHighSurrogate(title.last()))
        assertFalse(title.contains("abcdef"))
    }
    @Test fun hostContinuationProjectsSameActionLiveHistoryAndCacheWithoutChangingOtherText() {
        val prompt = ContinuationPrompt.create(JSONObject().put("text", "私有原目标"))
        val store = ChatStore { 10L }
        store.accept(event("run_started").put("prompt", prompt))
        assertEquals("继续任务", rows(store).getJSONObject(0).getJSONArray("parts").getJSONObject(0).getString("text"))
        // Abort before Pi acknowledges the synthetic user row, then restore full Pi history.
        store.accept(event("run_stopped", "2"))
        val cached = ChatStore(); cached.restore(store.snapshot())
        cached.accept(JSONObject().put("type", "history_snapshot").put("messages", JSONArray().put(
            JSONObject().put("role", "user").put("timestamp", 20).put("content", prompt))))
        assertEquals(1, rows(cached).length())
        assertEquals("继续任务", rows(cached).getJSONObject(0).getJSONArray("parts").getJSONObject(0).getString("text"))
        assertFalse(cached.snapshot().toString().contains("私有原目标"))
        val ordinary = ChatStore()
        ordinary.accept(JSONObject().put("type", "history_snapshot").put("messages", JSONArray()
            .put(JSONObject().put("role", "user").put("content", "继续任务，以及我的其他要求"))
            .put(JSONObject().put("role", "assistant").put("content", prompt))))
        assertEquals("继续任务，以及我的其他要求", rows(ordinary).getJSONObject(0).getJSONArray("parts").getJSONObject(0).getString("text"))
        assertEquals(prompt, rows(ordinary).getJSONObject(1).getJSONArray("parts").getJSONObject(0).getString("text"))
    }
}
