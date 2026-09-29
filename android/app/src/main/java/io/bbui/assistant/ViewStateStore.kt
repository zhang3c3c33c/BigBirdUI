package io.bbui.assistant

import org.json.JSONObject

/** Latest per-session view state only; no messages, actions or execution credentials. */
internal class ViewStateStore {
    val values = linkedMapOf<String, JSONObject>()
    private val revisions = linkedMapOf<String, Long>()
    var clock = 0L
        private set
    fun revision(id: String): Long = revisions[id] ?: 0L
    private fun normalize(value: JSONObject): JSONObject {
        val top = value.optDouble("scrollTop", 0.0).let { if (it.isFinite()) it.coerceAtLeast(0.0) else 0.0 }
        return JSONObject().put("text", value.optString("text")).put("scrollTop", top)
    }
    fun set(id: String, value: JSONObject): Boolean {
        val normalized = normalize(value)
        if (values[id]?.let { it.optString("text") == normalized.getString("text") && it.optDouble("scrollTop", 0.0) == normalized.getDouble("scrollTop") } == true) return false
        values[id] = normalized; revisions[id] = ++clock
        return true
    }
    fun clear(id: String) { values.remove(id); revisions[id] = ++clock }
    fun restoreIfUnchanged(id: String, prior: JSONObject, expectedRevision: Long): Boolean {
        if (revision(id) != expectedRevision || id in values) return false
        return set(id, prior)
    }
    data class PendingClear(val sessionId: String, val prior: JSONObject?, val priorRevision: Long, val clearedRevision: Long)
    companion object {
        fun retireFailedClear(pending: MutableMap<String, PendingClear>, submissionId: String): PendingClear? {
            val failed = pending.remove(submissionId) ?: return null
            for ((id, clear) in pending.toMap()) {
                if (clear.sessionId == failed.sessionId && clear.priorRevision == failed.clearedRevision)
                    pending[id] = clear.copy(prior = failed.prior, priorRevision = failed.priorRevision)
            }
            return failed
        }
        /** Undo only excluded submissions' draft clears in a copy, never later user edits. */
        fun restoreExcludedClears(snapshot: JSONObject, clears: Collection<PendingClear>) {
            if (clears.isEmpty()) return
            val drafts = snapshot.getJSONObject("drafts")
            val revisions = snapshot.getJSONObject("viewStateRevisions")
            val sessions = snapshot.getJSONArray("sessions")
            val existing = (0 until sessions.length()).mapTo(mutableSetOf()) { sessions.getJSONObject(it).getString("id") }
            for ((id, pending) in clears.groupBy { it.sessionId }) {
                if (id !in existing) continue
                val byRevision = pending.associateBy { it.clearedRevision }
                var revision = revisions.optLong(id, 0)
                while (true) {
                    val clear = byRevision[revision] ?: break
                    if (clear.prior == null) drafts.remove(id) else drafts.put(id, JSONObject(clear.prior.toString()))
                    revisions.put(id, clear.priorRevision)
                    revision = clear.priorRevision
                }
            }
        }
    }
    fun revisionsSnapshot(): JSONObject = JSONObject(revisions as Map<*, *>)
    fun snapshot(): JSONObject {
        val entries = JSONObject()
        for (id in revisions.keys + values.keys) entries.put(id, JSONObject().put("revision", revision(id))
            .put("value", values[id]?.let(::normalize) ?: JSONObject.NULL))
        return JSONObject().put("version", 1).put("clock", clock).put("entries", entries)
    }
    fun restoreMain(drafts: JSONObject?, savedRevisions: JSONObject?, savedClock: Long) {
        values.clear(); revisions.clear(); clock = savedClock.coerceAtLeast(0)
        drafts?.keys()?.forEach { id -> drafts.optJSONObject(id)?.let { values[id] = normalize(it) } }
        savedRevisions?.keys()?.forEach { id ->
            val value = savedRevisions.optLong(id, 0).coerceAtLeast(0)
            revisions[id] = value; clock = maxOf(clock, value)
        }
        for (id in values.keys) revisions.putIfAbsent(id, 0)
    }
    fun mergeSidecar(saved: JSONObject, sessionIds: Set<String>) {
        if (saved.optInt("version") != 1) return
        clock = maxOf(clock, saved.optLong("clock", 0).coerceAtLeast(0))
        val entries = saved.optJSONObject("entries") ?: return
        entries.keys().forEach { id ->
            val entry = entries.optJSONObject(id) ?: return@forEach
            val incoming = entry.optLong("revision", 0).coerceAtLeast(0)
            clock = maxOf(clock, incoming)
            if (id !in sessionIds || incoming <= revision(id)) return@forEach
            if (entry.isNull("value")) values.remove(id)
            else values[id] = normalize(entry.optJSONObject("value") ?: return@forEach)
            revisions[id] = incoming
        }
    }
    /** Called only after the main file durably removed these session IDs. */
    fun forgetDeleted(ids: Set<String>, currentSessionIds: Set<String>) {
        for (id in ids) if (id !in currentSessionIds) { values.remove(id); revisions.remove(id) }
    }
}
