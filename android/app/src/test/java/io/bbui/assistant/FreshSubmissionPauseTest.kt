package io.bbui.assistant

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Durable-send policy tests: no executor or phone is invoked. */
class FreshSubmissionPauseTest {
    private fun store() = ConversationStore().apply {
        sessions["a"] = JSONObject().put("id", "a").put("title", "新会话")
        selected = "a"; ready = true
    }
    @Test fun newSubmissionCanReleaseOnlyHistoricalAutomaticPausesAfterItsFirstSave() {
        for (reason in listOf("stop", "environment", "lock", "error", "restart", "shizuku")) {
            val state = store().apply { pauseReasons.add(reason); transition("stopped") }
            val ticket = state.prepareFreshSubmission("new")
            assertNotNull(reason, ticket)
            // Merely checking eligibility or queuing the message does not discard the old protection.
            assertTrue(state.paused)
            assertTrue(state.submit("new", "a", "新请求")); assertTrue(state.paused)
            val firstSave = state.save()
            assertEquals(reason, firstSave.getJSONArray("pauseReasons").getString(0))
            assertEquals("new", firstSave.getJSONArray("queue").getJSONObject(0).getString("id"))
            assertTrue(state.releaseFreshSubmissionPause(ticket))
            assertFalse(state.paused); assertEquals("idle", state.controlMode)
            assertNull(state.running); assertNull(state.continuation)
            assertEquals("new", state.queue.single().getString("id"))
            assertFalse(state.releaseFreshSubmissionPause(ticket))
        }
    }
    @Test fun consecutiveFreshSendsStayFifoWhileFirstWaitsForPauseReleaseSave() {
        val state = store().apply { pauseReasons.add("environment") }
        val first = state.prepareFreshSubmission("first")!!
        state.submit("first", "a", "第一项")
        val pending = mutableSetOf("first")
        assertNull(state.prepareFreshSubmission("second"))
        state.submit("second", "a", "第二项"); pending.add("second")
        // First save finishes and releases its own historical pause. Its second save remains pending.
        assertTrue(state.releaseFreshSubmissionPause(first))
        assertFalse(state.paused)
        // The second submission's save may finish first, but it cannot overtake the queue head.
        pending.remove("second")
        assertNull(state.nextReadySubmission(pending))
        assertEquals(listOf("first", "second"), state.queue.map { it.getString("id") })
        pending.remove("first")
        assertEquals("first", state.nextReadySubmission(pending)!!.getString("id"))
        state.running = state.queue.removeAt(0)
        assertEquals("first", state.running!!.getString("id"))
        assertEquals("second", state.queue.single().getString("id"))
    }
    @Test fun explicitOrUnknownPausesAreNeverReleasedBySending() {
        for (reason in listOf("user", "future-reason", "", "manual")) {
            val state = store().apply { pauseReasons.add("stop"); pauseReasons.add(reason) }
            assertNull(state.prepareFreshSubmission("new"))
            state.submit("new", "a", "新请求")
            assertFalse(state.releaseFreshSubmissionPause(null))
            assertEquals(setOf("stop", reason), state.pauseReasons)
        }
    }
    @Test fun existingWorkQuestionsAndManualControlPreventNewSendFromResumingAnything() {
        val blockers: List<(ConversationStore) -> Unit> = listOf(
            { it.submit("old", "a", "旧待办") },
            { it.running = JSONObject().put("id", "old").put("sessionId", "a") },
            { it.continuation = JSONObject().put("id", "old").put("sessionId", "a") },
            { it.pendingQuestion = JSONObject().put("status", "pending") },
            { it.transition("manual") }, { it.transition("taking_over") }, { it.transition("resuming") }
        )
        for (block in blockers) {
            val state = store().apply { pauseReasons.add("environment") }; block(state)
            val before = state.save().toString()
            assertTrue(state.hasProtectedWork()); assertNull(state.prepareFreshSubmission("new"))
            assertEquals(before, state.save().toString())
        }
    }
    @Test fun duplicateIdsCannotObtainTicketOrClearAnExistingPause() {
        val state = store()
        state.submit("duplicate", "a", "旧请求"); state.queue.clear()
        state.pauseReasons.add("stop")
        assertNull(state.prepareFreshSubmission("duplicate"))
        assertFalse(state.submit("duplicate", "a", "重发"))
        assertTrue(state.paused); assertTrue(state.queue.isEmpty())
    }
    @Test fun sameReasonRepeatedDuringSaveInvalidatesTicketEvenWhenSetIsUnchanged() {
        for (reason in listOf("stop", "environment", "error")) {
            val state = store().apply { pauseReasons.add(reason) }
            val ticket = state.prepareFreshSubmission("new")
            state.submit("new", "a", "新请求")
            state.pauseReasons.add(reason)
            assertFalse(state.releaseFreshSubmissionPause(ticket))
            assertEquals(setOf(reason), state.pauseReasons)
        }
    }
    @Test fun laterStopPauseTakeoverOrQuestionWinsDuringFirstSave() {
        val changes: List<(ConversationStore) -> Unit> = listOf(
            { it.pauseReasons.add("user") },
            { it.transition("stopped") },
            { it.transition("manual") },
            { it.pendingQuestion = JSONObject().put("status", "pending") },
            { it.continuation = JSONObject().put("id", "old") },
            { it.queue.clear() }
        )
        for (change in changes) {
            val state = store().apply { pauseReasons.add("stop") }
            val ticket = state.prepareFreshSubmission("new")
            state.submit("new", "a", "新请求"); change(state)
            assertFalse(state.releaseFreshSubmissionPause(ticket)); assertTrue(state.paused)
        }
    }
    @Test fun failedSecondSaveRestoresOriginalPauseAndRetainsAnyNewProtection() {
        val state = store().apply { pauseReasons.add("environment"); pauseReasons.add("restart") }
        val ticket = state.prepareFreshSubmission("new")!!
        state.submit("new", "a", "新请求")
        assertTrue(state.releaseFreshSubmissionPause(ticket))
        // During the second save a new user pause arrives, then disk reports failure.
        state.pauseReasons.add("user"); state.pauseReasons.add("error")
        state.restoreFreshSubmissionPause(ticket)
        // The first save already committed the item: failure to release its pause keeps it queued.
        assertEquals(setOf("environment", "restart", "user", "error"), state.pauseReasons)
        assertNull(state.running); assertEquals("new", state.queue.single().getString("id"))
        assertTrue("new" in state.submissions)
    }
    @Test fun secondSaveDoesNotEraseANewStopAndRebootNeverStartsQueuedSubmission() {
        val state = store().apply { pauseReasons.add("stop") }
        val ticket = state.prepareFreshSubmission("new")
        state.submit("new", "a", "新请求"); assertTrue(state.releaseFreshSubmissionPause(ticket))
        state.pauseReasons.add("stop"); state.transition("stopped")
        assertTrue(state.paused)
        val restored = ConversationStore().also { it.restore(state.save()) }
        assertNull(restored.running); assertTrue(restored.paused)
        assertEquals("new", restored.queue.single().getString("id"))
    }
    @Test fun idleResourcesDoNotProtectOldWorkButManualAndCancelledQuestionAreDistinct() {
        val state = store().apply { pauseReasons.add("environment"); transition("error") }
        assertFalse(state.hasProtectedWork())
        state.pendingQuestion = JSONObject().put("status", "cancelled")
        assertFalse(state.hasProtectedWork())
        state.transition("manual"); assertTrue(state.hasProtectedWork())
    }
}
