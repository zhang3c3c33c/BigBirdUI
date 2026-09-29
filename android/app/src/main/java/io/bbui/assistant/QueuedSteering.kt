package io.bbui.assistant

import org.json.JSONArray
import org.json.JSONObject

/** Transfers one durable queue item to an explicitly chosen run, without replay on uncertainty. */
internal object QueuedSteering {
    data class Claim(val item: JSONObject, val predecessors: Set<String>, val successors: Set<String>,
        val run: JSONObject, val controlId: String, val runId: String)

    fun claim(state: ConversationStore, command: JSONObject, pending: Set<String>): Claim? {
        val id = command.optString("submissionId")
        val sessionId = command.optString("sessionId")
        val run = state.running ?: return null
        val controlId = command.optString("controlId")
        val runId = command.optString("runId")
        if (id.isBlank() || id in pending || controlId.isBlank() || runId.isBlank() ||
            state.controlId != controlId || run.optString("runId") != runId || run.optString("sessionId") != sessionId ||
            !state.controlSnapshot().optBoolean("canSteer")) return null
        val index = state.queue.indexOfFirst { it.optString("id") == id && it.optString("sessionId") == sessionId }
        if (index < 0) return null
        val predecessors = state.queue.take(index).mapTo(mutableSetOf()) { it.getString("id") }
        val successors = state.queue.drop(index + 1).mapTo(mutableSetOf()) { it.getString("id") }
        val item = state.queue.removeAt(index)
        val receipt = JSONObject(item.toString()).put("interruptedReason", "steering").put("steeringStatus", "unconfirmed")
        state.interrupted.add(receipt)
        return Claim(receipt, predecessors, successors, run, controlId, runId)
    }

    fun active(state: ConversationStore, claim: Claim): Boolean = state.running === claim.run &&
        state.controlId == claim.controlId && state.running?.optString("runId") == claim.runId &&
        state.controlSnapshot().optBoolean("canSteer")

    private fun sameRun(state: ConversationStore, claim: Claim): Boolean = state.running === claim.run &&
        state.controlId == claim.controlId && state.running?.optString("runId") == claim.runId && state.controlMode == "running"

    fun rollback(state: ConversationStore, claim: Claim) {
        if (!state.interrupted.remove(claim.item) || !state.sessions.containsKey(claim.item.optString("sessionId"))) return
        val item = JSONObject(claim.item.toString()).apply { remove("interruptedReason"); remove("steeringStatus") }
        val next = state.queue.indexOfFirst { it.optString("id") in claim.successors }
        val previous = state.queue.indexOfLast { it.optString("id") in claim.predecessors }
        state.queue.add(if (next >= 0) next else previous + 1, item)
    }

    fun resolve(state: ConversationStore, claim: Claim, outcome: SteeringOutcome) {
        if (claim.item !in state.interrupted) return
        val status = when (outcome) {
            SteeringOutcome.ACCEPTED -> "accepted"
            SteeringOutcome.NOT_SENT -> "not_sent"
            SteeringOutcome.UNCONFIRMED -> "unconfirmed"
        }
        if (claim.item.optString("steeringStatus") == "accepted") return
        claim.item.put("steeringStatus", status)
        if (outcome != SteeringOutcome.ACCEPTED) return
        val steering = claim.run.optJSONArray("steering") ?: JSONArray().also { claim.run.put("steering", it) }
        steering.put(claim.item.getString("text"))
        state.continuation?.takeIf { it.optString("id") == claim.run.optString("id") && it.optString("runId") == claim.runId }
            ?.put("steering", JSONArray(steering.toString()))
        if (sameRun(state, claim)) state.interrupted.remove(claim.item)
    }
}

internal enum class SteeringOutcome { ACCEPTED, NOT_SENT, UNCONFIRMED }

/** Timeout is a notice, not retirement: a later ACK can still settle the original request. */
internal class SteeringReply(val owner: Any, private val done: (SteeringOutcome) -> Unit) {
    private var notified = false
    private var settled = false
    fun uncertain() {
        if (!settled && !notified) { notified = true; done(SteeringOutcome.UNCONFIRMED) }
    }
    fun finish(outcome: SteeringOutcome) {
        if (settled) return
        settled = true
        if (outcome != SteeringOutcome.UNCONFIRMED || !notified) done(outcome)
    }
}
