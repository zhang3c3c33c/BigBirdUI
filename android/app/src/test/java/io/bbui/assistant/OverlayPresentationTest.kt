package io.bbui.assistant

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OverlayPresentationTest {
    private fun state() = ConversationStore().apply {
        for (id in listOf("a", "b")) sessions[id] = JSONObject().put("id", id).put("title", id)
        selected = "b"
    }
    private fun start(state: ConversationStore, session: String = "a", run: String = "1", submission: String = "task") {
        state.running = JSONObject().put("id", submission).put("sessionId", session).put("runId", run)
        state.accept(JSONObject().put("type", "run_started").put("sessionId", session).put("runId", run))
    }
    private fun tool(state: ConversationStore, session: String = "a", run: String = "1", intent: String = "搜索附近餐厅") {
        state.accept(JSONObject().put("type", "tool_execution_start").put("sessionId", session).put("runId", run)
            .put("toolCallId", "tool-$session-$run").put("toolName", "phone_action")
            .put("args", JSONObject().put("操作", "点击").put("意图", intent)))
    }
    @Test fun runningOwnerAndLiveToolDoNotFollowTheBrowsedConversation() {
        val state = state(); start(state); tool(state)
        startHistoricalChat(state)
        val presentation = OverlayPresentation()
        val model = presentation.project(state, 0)!!
        assertEquals("a", model.sessionId); assertEquals("搜索附近餐厅", model.label); assertTrue(model.canStop)
        state.selected = "a"
        assertEquals(model, presentation.project(state, 50, refreshTool = false))
        state.selected = "b"
        assertEquals(model, presentation.project(state, 100, refreshTool = false))
    }
    private fun startHistoricalChat(state: ConversationStore) {
        state.chat("b").accept(JSONObject().put("type", "run_started").put("runId", "99"))
        state.chat("b").accept(JSONObject().put("type", "tool_execution_start").put("runId", "99")
            .put("toolCallId", "old").put("toolName", "phone_action").put("args", JSONObject().put("意图", "不能显示这个意图")))
    }
    @Test fun tokenUpdatesReuseCurrentToolButToolEndAndNewRunRemoveOldIntent() {
        val state = state(); start(state); tool(state)
        val presentation = OverlayPresentation()
        val model = presentation.project(state, 0)
        repeat(100) { assertEquals(model, presentation.project(state, it.toLong(), refreshTool = false)) }
        state.accept(JSONObject().put("type", "tool_execution_end").put("sessionId", "a").put("runId", "1")
            .put("toolCallId", "tool-a-1").put("result", JSONObject()))
        assertEquals("处理中", presentation.project(state, 200)!!.label)
        start(state, run = "2", submission = "next")
        assertEquals("处理中", presentation.project(state, 201, refreshTool = false)!!.label)
    }
    @Test fun onlyCurrentPendingQuestionRemainsVisibleUntilItResolves() {
        val state = state(); start(state)
        state.pendingQuestion = JSONObject().put("status", "pending").put("sessionId", "a").put("runId", "1")
            .put("toolCallId", "question").put("requestId", "request")
        val presentation = OverlayPresentation()
        val model = presentation.project(state, 0)!!
        assertEquals("需要你回答", model.label); assertEquals("request", model.requestId)
        assertEquals(model, presentation.project(state, 60000))
        state.pendingQuestion!!.put("runId", "old")
        assertEquals("处理中", presentation.project(state, 60001)!!.label)
        state.pendingQuestion!!.put("runId", "1").put("status", "answered")
        assertNull(presentation.project(state, 60002)!!.requestId)
    }
    @Test fun normalEndIsBriefReplyEndNotBusinessSuccessAndDuplicatesDoNotExtendIt() {
        val state = state(); start(state)
        val presentation = OverlayPresentation(); presentation.project(state, 0)
        state.accept(JSONObject().put("type", "agent_settled").put("sessionId", "a").put("runId", "1"))
        val ended = presentation.project(state, 100)!!
        assertEquals("本轮回复结束", ended.label); assertFalse(ended.canStop); assertEquals("a", ended.sessionId)
        assertEquals(3100L, presentation.nextUpdateAtMs)
        assertEquals(ended, presentation.project(state, 3099))
        assertEquals(3100L, presentation.nextUpdateAtMs)
        assertNull(presentation.project(state, 3100)); assertNull(presentation.project(state, 9999))
    }
    @Test fun stopErrorAndNewExecutionHaveIndependentTerminalLifetimes() {
        val state = state(); start(state)
        val presentation = OverlayPresentation(); presentation.project(state, 0)
        state.retainTask(); state.running = null; state.transition("stopped")
        assertEquals("stopped", presentation.project(state, 100)!!.kind)
        state.transition("stopped") // A duplicate STOP changes control ID, not the terminal deadline.
        assertEquals(3100L, presentation.nextUpdateAtMs)
        assertEquals("stopped", presentation.project(state, 500)!!.kind)
        assertEquals(3100L, presentation.nextUpdateAtMs)
        start(state, "b", "2", "next")
        assertEquals("b", presentation.project(state, 501)!!.sessionId)
        state.transition("error"); state.retainTask(); state.running = null
        assertEquals("error", presentation.project(state, 600)!!.kind)
        assertEquals(3600L, presentation.nextUpdateAtMs)
        assertNull(presentation.project(state, 3600))
    }
    @Test fun manualUsesEnvironmentOwnerAndHidesWhenUserEndsWithoutATask() {
        val state = state(); state.transition("manual")
        val presentation = OverlayPresentation()
        val manual = presentation.project(state, 0, environmentSessionId = "a")!!
        assertEquals("你正在操作", manual.label); assertEquals("a", manual.sessionId); assertTrue(manual.canStop)
        assertEquals(manual, presentation.project(state, 60000, environmentSessionId = "a"))
        state.transition("idle")
        assertNull(presentation.project(state, 60001, environmentSessionId = "a"))
    }
    @Test fun restoringIdleResourceOrOldContinuationDoesNotReplayATerminalNotice() {
        val state = state(); val presentation = OverlayPresentation()
        assertNull(presentation.project(state, 0, environmentSessionId = "a"))
        state.continuation = JSONObject().put("id", "old").put("sessionId", "a").put("runId", "old")
        state.transition("stopped")
        assertNull(presentation.project(state, 1, environmentSessionId = "a"))
        start(state); presentation.project(state, 2)
        assertNull(presentation.project(state(), 3))
    }
    @Test fun visibilityGatesOnlyPresentationAndCannotPauseOrResumeState() {
        val allowed = OverlayPresentation.Visibility(true, true, true, false, false)
        assertTrue(allowed.allows())
        assertFalse(allowed.copy(enabled = false).allows()); assertFalse(allowed.copy(permission = false).allows())
        assertFalse(allowed.copy(shizukuReady = false).allows()); assertFalse(allowed.copy(foreground = true).allows())
        assertFalse(allowed.copy(locked = true).allows())
        val state = state(); start(state)
        val before = state.running!!.toString(); val controlId = state.controlId
        val presentation = OverlayPresentation()
        repeat(5) { presentation.project(state, it.toLong()) }
        assertEquals(before, state.running!!.toString()); assertEquals(controlId, state.controlId)
        assertTrue(state.queue.isEmpty()); assertTrue(state.pauseReasons.isEmpty())
    }
}
