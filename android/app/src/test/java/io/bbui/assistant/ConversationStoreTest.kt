package io.bbui.assistant

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ConversationStoreTest {
    @Test fun pinsPersistAcrossRefreshAndRestartWithoutChangingExecution() {
        val state = store()
        state.sessions["a"]!!.put("modified", 10)
        state.sessions["b"]!!.put("modified", 1)
        state.running = JSONObject().put("sessionId", "a")
        state.setPinned("b", true); state.setPinned("b", true)
        assertEquals("b", state.snapshot().getJSONArray("sessions").getJSONObject(0).getString("id"))
        assertEquals("a", state.running!!.getString("sessionId")); assertEquals("a", state.selected)
        // Pi catalogue refresh replaces metadata; pins belong to the native store.
        state.sessions["b"] = JSONObject().put("id", "b").put("title", "改名后").put("modified", 1)
        state.running = null
        val restored = ConversationStore().also { it.restore(state.save()) }
        assertEquals(setOf("b"), restored.pinnedSessions)
        assertTrue(restored.snapshot().getJSONArray("sessions").getJSONObject(0).getBoolean("pinned"))
        restored.setPinned("b", false)
        assertEquals("a", restored.snapshot().getJSONArray("sessions").getJSONObject(0).getString("id"))
        restored.setPinned("b", true); restored.remove("b")
        assertTrue(restored.pinnedSessions.isEmpty())
        restored.setPinned("missing", true); assertTrue(restored.pinnedSessions.isEmpty())
    }
    private fun store() = ConversationStore().apply {
        for (id in listOf("a", "b")) sessions[id] = JSONObject().put("id", id).put("title", "新会话").put("modified", 0)
        selected = "a"; ready = true
    }
    @Test fun viewingDoesNotMoveExecutionAndSubmissionsStayOutOfContext() {
        val state = store()
        assertTrue(state.submit("one", "a", "任务 A"))
        assertFalse(state.submit("one", "a", "任务 A"))
        assertTrue(state.submit("two", "b", "任务 B"))
        assertEquals(0, state.chat("a").snapshot().getJSONArray("messages").length())
        state.running = state.queue.removeAt(0)
        state.selected = "b"
        state.accept(JSONObject().put("type", "run_started").put("sessionId", "a").put("runId", "1").put("prompt", "任务 A"))
        assertEquals("a", state.snapshot().getString("runningSessionId"))
        assertEquals("b", state.snapshot().getString("sessionId"))
        assertEquals(0, state.snapshot().getJSONArray("messages").length())
        assertEquals("two", state.queue.single().getString("id"))
        assertEquals(1, state.chat("a").snapshot().getJSONArray("messages").length())
    }
    @Test fun stopAlwaysPausesEvenBetweenDurableClaimAndAgentStart() {
        val state = store()
        state.submit("one", "a", "任务 A"); state.submit("two", "b", "任务 B")
        state.running = state.queue.removeAt(0)
        state.accept(JSONObject().put("type", "run_stopped").put("sessionId", ""))
        assertTrue(state.paused); assertNull(state.running)
        assertEquals("two", state.queue.single().getString("id"))
    }
    @Test fun restartKeepsWaitingItemsAndNeverRequeuesClaimedWork() {
        val state = store()
        state.submit("one", "a", "任务 A"); state.submit("two", "b", "任务 B")
        state.running = state.queue.removeAt(0)
        state.drafts["b"] = JSONObject().put("text", "未发送草稿").put("scrollTop", 120)
        state.limits["b"] = 120
        val restored = ConversationStore().also { it.restore(state.save()) }
        assertTrue(restored.paused); assertNull(restored.running)
        assertEquals("two", restored.queue.single().getString("id"))
        assertFalse(restored.submit("one", "a", "任务 A"))
        assertEquals("未发送草稿", restored.drafts["b"]!!.getString("text"))
        assertEquals(120, restored.limits["b"])
        assertEquals("任务 A", restored.interrupted.single().getString("text"))
        restored.remove("b")
        assertTrue(restored.queue.isEmpty()); assertFalse(restored.drafts.containsKey("b"))
        assertEquals("a", restored.selected)
    }
    @Test fun errorPausesButNormalRoundEndDoesNotClaimBusinessSuccess() {
        val state = store()
        state.running = JSONObject().put("sessionId", "a")
        state.accept(JSONObject().put("type", "agent_settled").put("sessionId", "a"))
        assertFalse(state.paused); assertNull(state.running)
        state.running = JSONObject().put("sessionId", "b")
        state.accept(JSONObject().put("type", "status").put("status", "error").put("sessionId", "b"))
        assertTrue(state.paused); assertNull(state.running)
    }
    @Test fun channelFailurePausesQueueButBusinessAndObservationErrorsDoNot() {
        val state = store()
        fun channel(control: String) = JSONObject().put("type", "phone_action_result").put("sessionId", "a")
            .put("details", JSONObject().put("通道", JSONObject().put("控制", control))
                .put("执行", JSONObject().put("状态", "未派发")).put("观察", JSONObject().put("状态", "失败")))
        state.accept(channel("可用")); assertFalse(state.paused)
        state.accept(channel("不可用")); assertTrue(state.paused)
        state.accept(JSONObject().put("type", "agent_settled").put("sessionId", "a")); assertTrue(state.paused)
    }
    @Test fun lazyDisplayQueryThenGuiAndCompletionNeverRetainAnInterruptedTask() {
        val state = store()
        state.submit("first", "a", "查找应用并操作"); state.submit("next", "b", "后续任务")
        state.running = state.queue.removeAt(0)
        fun event(type: String) = JSONObject().put("type", type).put("sessionId", "a").put("runId", "1")
        state.accept(event("run_started"))
        fun result(control: String, execution: String) = event("phone_action_result")
            .put("details", JSONObject().put("通道", JSONObject().put("控制", control))
                .put("执行", JSONObject().put("状态", execution)))
        state.accept(result("未创建", "无需派发"))
        assertEquals("running", state.controlMode); assertFalse(state.paused)
        assertNull(state.continuation); assertEquals("", state.error)
        state.accept(result("可用", "已派发"))
        state.accept(event("agent_settled"))
        assertEquals("idle", state.controlMode); assertFalse(state.paused)
        assertNull(state.running); assertNull(state.continuation)
        assertFalse(state.controlSnapshot().getBoolean("canResume"))
        assertEquals("next", state.queue.single().getString("id"))
        val restored = ConversationStore().also { it.restore(state.save()) }
        assertFalse(restored.controlSnapshot().getBoolean("canResume"))
        assertEquals("next", restored.queue.single().getString("id"))
        assertTrue(restored.paused) // A restart still holds waiting work.
    }
    @Test fun explicitFaultBeforeDisplayCreationIsNotClearedBySuccessOrCompletion() {
        val state = store()
        state.running = JSONObject().put("sessionId", "a").put("runId", "1")
        state.transition("running")
        fun event(type: String) = JSONObject().put("type", type).put("sessionId", "a").put("runId", "1")
        state.accept(event("phone_action_result").put("details", JSONObject().put("通道",
            JSONObject().put("控制", "未创建").put("输入故障", "系统调用结果未确认"))))
        assertEquals("error", state.controlMode); assertTrue(state.paused)
        state.accept(event("phone_action_result").put("details", JSONObject().put("通道", JSONObject().put("控制", "可用"))))
        state.accept(event("agent_settled"))
        assertEquals("error", state.controlMode); assertTrue(state.paused)
        assertTrue(state.controlSnapshot().getBoolean("canResume"))
    }
    @Test fun removingSelectedSessionChoosesItsNeighbor() {
        val state = store()
        state.sessions["a"]!!.put("modified", 3)
        state.sessions["b"]!!.put("modified", 2)
        state.sessions["c"] = JSONObject().put("id", "c").put("title", "旧会话").put("modified", 1)
        state.selected = "b"; state.remove("b")
        assertEquals("c", state.selected)
    }
    @Test fun handoffRetainsOwnerWithoutPausingWaitingQueue() {
        val state = store()
        state.submit("a-task", "a", "原任务")
        state.running = state.queue.removeAt(0)
        state.accept(JSONObject().put("type", "run_started").put("sessionId", "a").put("runId", "1"))
        state.pauseReasons.add("user")
        state.retainTask(); state.transition("taking_over")
        state.selected = "b"
        state.accept(JSONObject().put("type", "run_stopped").put("sessionId", "a").put("reason", "takeover").put("runId", "2"))
        assertNull(state.running)
        assertEquals(setOf("user"), state.pauseReasons)
        state.accept(JSONObject().put("type", "handoff_ready"))
        assertEquals("a", state.controlSnapshot().getString("sessionId"))
        assertTrue(state.controlSnapshot().getBoolean("canResume"))
        assertEquals("原任务", state.continuation!!.getString("text"))
        val before = state.controlId
        state.transition("resuming")
        assertNotEquals(before, state.controlId)
        assertFalse(state.controlSnapshot().getBoolean("canResume"))
        assertEquals(setOf("user"), state.pauseReasons)
    }
    @Test fun emptyRestartDoesNotRequireQueueResume() {
        val state = store()
        val restored = ConversationStore().also { it.restore(state.save()) }
        assertFalse(restored.paused)
        restored.submit("fresh", "a", "新任务")
        assertFalse(restored.paused)
    }
    @Test fun interruptedRestartRetainsContinuationAndNeverAutoRequeues() {
        val state = store()
        state.submit("a-task", "a", "原任务")
        state.running = state.queue.removeAt(0).put("started", true)
        val restored = ConversationStore().also { it.restore(state.save()) }
        assertEquals("a", restored.controlSnapshot().getString("sessionId"))
        assertTrue(restored.controlSnapshot().getBoolean("canResume"))
        assertTrue("restart" in restored.pauseReasons)
        assertTrue(restored.queue.isEmpty())
    }
    @Test fun staleCompletionCannotClearNewRunOrContinuation() {
        val state = store()
        state.running = JSONObject().put("sessionId", "a").put("runId", "4")
        state.transition("running")
        state.accept(JSONObject().put("type", "agent_settled").put("sessionId", "a").put("runId", "2"))
        assertNotNull(state.running)
        assertEquals("running", state.controlMode)
    }
    @Test fun errorOffersExplicitContinuationButKeepsQueueHold() {
        val state = store()
        state.running = JSONObject().put("sessionId", "a").put("text", "任务")
        state.accept(JSONObject().put("type", "status").put("status", "error").put("sessionId", "a"))
        assertTrue(state.controlSnapshot().getBoolean("canResume"))
        assertEquals(setOf("error"), state.pauseReasons)
    }
    @Test fun emptyLegacyPauseDoesNotBlockNewWorkButExplicitPausePersists() {
        val legacy = store().save().put("paused", true)
        legacy.remove("pauseReasons")
        assertFalse(ConversationStore().also { it.restore(legacy) }.paused)
        val current = store().apply { pauseReasons.add("user") }
        assertTrue(ConversationStore().also { it.restore(current.save()) }.paused)
    }
    @Test fun resumedClaimStartsWithFreshProcessGeneration() {
        val state = store()
        state.running = JSONObject().put("id", "old").put("sessionId", "a").put("runId", "12").put("started", true)
        val restored = ConversationStore().also { it.restore(state.save()) }
        val claim = JSONObject(restored.continuation.toString()).put("started", false)
        claim.remove("runId")
        restored.running = claim; restored.transition("resuming")
        restored.accept(JSONObject().put("type", "run_started").put("sessionId", "a").put("runId", "1"))
        assertEquals("running", restored.controlMode)
        assertEquals("1", restored.running!!.getString("runId"))
    }
}
