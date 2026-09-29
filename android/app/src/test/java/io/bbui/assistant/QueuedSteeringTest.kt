package io.bbui.assistant

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class QueuedSteeringTest {
    private fun state() = ConversationStore().apply {
        for (id in listOf("a", "b")) sessions[id] = JSONObject().put("id", id).put("title", id)
        selected = "a"; transition("running")
        running = JSONObject().put("id", "task").put("sessionId", "a").put("runId", "7").put("started", true)
        submit("first", "b", "另一会话排队")
        submit("queued", "a", "给当前任务的补充")
        submit("last", "a", "稍后单独执行")
        drafts["a"] = JSONObject().put("text", "用户正在写的新草稿")
    }
    private fun command(state: ConversationStore) = JSONObject().put("sessionId", "a").put("submissionId", "queued")
        .put("controlId", state.controlId).put("runId", "7")

    @Test fun claimIsBoundToOriginalQueueItemAndDoesNotTouchDraftOrQueueOrder() {
        val state = state(); val command = command(state)
        val claim = QueuedSteering.claim(state, command, emptySet())!!
        assertEquals(listOf("first", "last"), state.queue.map { it.getString("id") })
        assertEquals("给当前任务的补充", claim.item.getString("text"))
        assertEquals("steering", claim.item.getString("interruptedReason"))
        assertEquals("unconfirmed", claim.item.getString("steeringStatus"))
        assertEquals("用户正在写的新草稿", state.drafts["a"]!!.getString("text"))
        assertNull(QueuedSteering.claim(state, command, emptySet()))
        QueuedSteering.resolve(state, claim, SteeringOutcome.ACCEPTED)
        assertTrue(state.interrupted.isEmpty())
        assertEquals("给当前任务的补充", state.running!!.getJSONArray("steering").getString(0))
        QueuedSteering.resolve(state, claim, SteeringOutcome.ACCEPTED)
        assertEquals(1, state.running!!.getJSONArray("steering").length())
    }

    @Test fun staleControlRunCrossSessionPendingSaveAndQuestionsCannotClaim() {
        val state = state()
        assertNull(QueuedSteering.claim(state, command(state).put("controlId", "old"), emptySet()))
        assertNull(QueuedSteering.claim(state, command(state).put("runId", "6"), emptySet()))
        assertNull(QueuedSteering.claim(state, command(state).put("sessionId", "b"), emptySet()))
        assertNull(QueuedSteering.claim(state, command(state), setOf("queued")))
        state.pendingQuestion = JSONObject().put("status", "pending")
        assertNull(QueuedSteering.claim(state, command(state), emptySet()))
        assertEquals(3, state.queue.size); assertTrue(state.interrupted.isEmpty())
    }

    @Test fun failedClaimSaveRollsBackBeforeNextSnapshotAndNeverDispatches() {
        val state = state(); var writeDone: ((Boolean) -> Unit)? = null; var dispatched = false
        var claim: QueuedSteering.Claim? = null
        var bytes = ByteArray(0)
        val persistence = SessionPersistenceQueue({ state.save().toString().toByteArray() }) { snapshot, done -> bytes = snapshot; writeDone = done }
        persistence.save(prepare = { claim = QueuedSteering.claim(state, command(state), emptySet()) },
            success = { dispatched = true }, failure = { QueuedSteering.rollback(state, claim!!) })
        assertEquals(2, JSONObject(String(bytes)).getJSONArray("queue").length())
        persistence.save()
        writeDone!!(false)
        assertFalse(dispatched)
        assertEquals(listOf("first", "queued", "last"), state.queue.map { it.getString("id") })
        assertEquals(3, JSONObject(String(bytes)).getJSONArray("queue").length())
        assertEquals(0, JSONObject(String(bytes)).getJSONArray("interrupted").length())
    }

    @Test fun stopAfterDurableClaimKeepsNotSentAndRestartCannotReplay() {
        val state = state(); val claim = QueuedSteering.claim(state, command(state), emptySet())!!
        state.retainTask(); state.running = null; state.transition("stopped")
        assertFalse(QueuedSteering.active(state, claim))
        QueuedSteering.resolve(state, claim, SteeringOutcome.NOT_SENT)
        val restored = ConversationStore().also { it.restore(state.save()) }
        assertEquals(listOf("first", "last"), restored.queue.map { it.getString("id") })
        assertEquals("not_sent", restored.interrupted.single().getString("steeringStatus"))
        assertEquals("给当前任务的补充", restored.interrupted.single().getString("text"))
    }

    @Test fun rollbackKeepsFifoWhenEarlierQueuedItemIsCancelledDuringSave() {
        val state = state(); var writeDone: ((Boolean) -> Unit)? = null
        var claim: QueuedSteering.Claim? = null
        val persistence = SessionPersistenceQueue({ state.save().toString().toByteArray() }) { _, done -> writeDone = done }
        persistence.save(prepare = { claim = QueuedSteering.claim(state, command(state), emptySet()) },
            failure = { QueuedSteering.rollback(state, claim!!) })
        state.queue.removeAll { it.getString("id") == "first" }
        writeDone!!(false)
        assertEquals(listOf("queued", "last"), state.queue.map { it.getString("id") })
        assertTrue(state.interrupted.isEmpty())
    }

    @Test fun lateAckPreservesAcceptedFactAndCannotSteerReplacementRun() {
        val state = state(); val original = state.running!!
        val claim = QueuedSteering.claim(state, command(state), emptySet())!!
        QueuedSteering.resolve(state, claim, SteeringOutcome.UNCONFIRMED)
        state.retainTask(); state.transition("running")
        state.running = JSONObject().put("id", "replacement").put("sessionId", "a").put("runId", "8")
        QueuedSteering.resolve(state, claim, SteeringOutcome.ACCEPTED)
        assertEquals("accepted", state.interrupted.single().getString("steeringStatus"))
        assertEquals(1, original.getJSONArray("steering").length())
        assertEquals(1, state.continuation!!.getJSONArray("steering").length())
        assertFalse(state.running!!.has("steering"))
        QueuedSteering.resolve(state, claim, SteeringOutcome.UNCONFIRMED)
        assertEquals("accepted", state.interrupted.single().getString("steeringStatus"))
        assertEquals(listOf("first", "last"), state.queue.map { it.getString("id") })
    }

    @Test fun deletionDuringClaimDoesNotRestoreOrRenameAnUnrelatedSession() {
        val state = state(); val claim = QueuedSteering.claim(state, command(state), emptySet())!!
        state.remove("a")
        QueuedSteering.rollback(state, claim)
        QueuedSteering.resolve(state, claim, SteeringOutcome.ACCEPTED)
        assertTrue(state.interrupted.isEmpty())
        assertEquals(listOf("first"), state.queue.map { it.getString("id") })
    }

    @Test fun pendingQuestionAfterDispatchDoesNotTurnActiveAckIntoInterruptedWork() {
        val state = state(); val claim = QueuedSteering.claim(state, command(state), emptySet())!!
        state.pendingQuestion = JSONObject().put("status", "pending")
        assertFalse(QueuedSteering.active(state, claim)) // Cannot dispatch another steer now.
        QueuedSteering.resolve(state, claim, SteeringOutcome.ACCEPTED)
        assertTrue(state.interrupted.isEmpty())
        assertEquals(1, state.running!!.getJSONArray("steering").length())
    }

    @Test fun timeoutNotifiesOnceButLateAckStillPreservesKnownAcceptance() {
        val events = mutableListOf<SteeringOutcome>()
        val reply = SteeringReply(Any(), events::add)
        reply.uncertain(); reply.uncertain()
        reply.finish(SteeringOutcome.ACCEPTED)
        reply.uncertain(); reply.finish(SteeringOutcome.UNCONFIRMED)
        assertEquals(listOf(SteeringOutcome.UNCONFIRMED, SteeringOutcome.ACCEPTED), events)
    }

    @Test fun retiringRuntimeKeepsUncertaintyAndIgnoresNoLongerReadableAcknowledgements() {
        val events = mutableListOf<SteeringOutcome>()
        val reply = SteeringReply(Any(), events::add)
        reply.uncertain(); reply.finish(SteeringOutcome.UNCONFIRMED)
        reply.finish(SteeringOutcome.ACCEPTED)
        assertEquals(listOf(SteeringOutcome.UNCONFIRMED), events)
    }
}
