package io.bbui.assistant

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SessionPersistenceQueueTest {
    private data class Write(val bytes: ByteArray, val done: (Boolean) -> Unit)
    private class Disk {
        val writes = java.util.ArrayDeque<Write>()
        var saved = JSONObject()
        fun write(bytes: ByteArray, done: (Boolean) -> Unit) { writes.add(Write(bytes, done)) }
        fun finish(ok: Boolean) { val item = writes.removeFirst(); if (ok) saved = JSONObject(String(item.bytes, Charsets.UTF_8)); item.done(ok) }
    }
    private fun state() = ConversationStore().apply {
        sessions["a"] = JSONObject().put("id", "a").put("title", "新会话"); selected = "a"
    }
    private fun JSONObject.queueIds() = getJSONArray("queue").let { rows -> (0 until rows.length()).map { rows.getJSONObject(it).getString("id") } }
    @Test fun firstSuccessfulConcurrentSubmissionClaimsTitleAfterPriorWriteRollback() {
        val state = state().apply { autoTitles.created("a") }
        val disk = Disk(); val attempts = mutableListOf<String>(); val pending = mutableSetOf<String>()
        val persistence = SessionPersistenceQueue({ id -> state.save(pending.filterTo(mutableSetOf()) { it != id }).toString().toByteArray() }, disk::write)
        fun submit(id: String) {
            state.submit(id, "a", id); pending.add(id)
            var claimed = false
            persistence.save(id, prepare = { claimed = state.autoTitles.claim("a", id) }, success = {
                pending.remove(id)
                if (claimed) attempts.add(id)
            }, failure = {
                pending.remove(id); state.queue.removeAll { it.optString("id") == id }; state.submissions.remove(id)
                state.autoTitles.rollback("a", id)
            })
        }
        submit("first"); submit("second")
        disk.finish(false); disk.finish(true)
        assertEquals(listOf("second"), attempts)
        val restored = ConversationStore().also { it.restore(disk.saved) }
        assertTrue(restored.autoTitles.current("a", "second"))
        assertFalse(restored.autoTitles.claim("a", "third"))
    }
    @Test fun failedFirstSaveRollsBackBeforeConcurrentSubmissionSnapshotAndCannotReturnAfterRestart() {
        val state = state(); val pending = mutableSetOf<String>(); val disk = Disk(); val acks = linkedMapOf<String, Boolean>()
        val persistence = SessionPersistenceQueue({ id -> state.save(pending.filterTo(mutableSetOf()) { it != id }).toString().toByteArray() }, disk::write)
        fun submit(id: String) {
            state.submit(id, "a", id); pending.add(id)
            persistence.save(id, success = { pending.remove(id); acks[id] = true }, failure = {
                pending.remove(id); state.queue.removeAll { it.optString("id") == id }; state.submissions.remove(id)
                state.pauseReasons.add("error"); acks[id] = false
            })
        }
        submit("first"); submit("second")
        assertEquals(1, disk.writes.size)
        disk.finish(false)
        assertEquals(false, acks["first"])
        assertEquals(listOf("second"), JSONObject(String(disk.writes.first.bytes)).queueIds())
        disk.finish(true)
        val restored = ConversationStore().also { it.restore(disk.saved) }
        assertEquals(listOf("second"), restored.queue.map { it.getString("id") })
        assertFalse("first" in restored.submissions); assertTrue(restored.paused); assertNull(restored.running)
    }
    @Test fun unrelatedSavesExcludeSubmissionsWhoseFirstWriteHasNotSucceeded() {
        val state = state(); val pending = mutableSetOf<String>(); val disk = Disk()
        val persistence = SessionPersistenceQueue({ id -> state.save(pending.filterTo(mutableSetOf()) { it != id }).toString().toByteArray() }, disk::write)
        persistence.save() // Existing save in flight.
        state.submit("new", "a", "未提交"); pending.add("new")
        persistence.save() // UI draft or unrelated status save was queued before the send save.
        persistence.save("new", failure = { pending.remove("new"); state.queue.clear(); state.submissions.remove("new") })
        disk.finish(true); assertTrue(JSONObject(String(disk.writes.first.bytes)).queueIds().isEmpty())
        disk.finish(true); disk.finish(false)
        val restored = ConversationStore().also { it.restore(disk.saved) }
        assertTrue(restored.queue.isEmpty()); assertFalse("new" in restored.submissions)
    }
    @Test fun failedPauseReleaseKeepsDurableSubmissionAndUnrelatedWritesRetainOriginalPause() {
        val state = state().apply { pauseReasons.add("environment") }; val pending = mutableSetOf("new"); val durable = mutableSetOf<String>()
        val ticket = state.prepareFreshSubmission("new")!!; state.submit("new", "a", "新请求")
        var release: ConversationStore.FreshSubmissionPause? = null; var accepted: Boolean? = null
        val disk = Disk()
        val persistence = SessionPersistenceQueue({ id ->
            val held = release?.takeIf { it.submissionId != id }?.reasons ?: emptySet()
            state.save(pending.filterTo(mutableSetOf()) { it !in durable && it != id }, held).toString().toByteArray()
        }, disk::write)
        persistence.save("new", success = {
            durable.add("new"); assertTrue(state.releaseFreshSubmissionPause(ticket)); release = ticket
            persistence.save("new", success = { error("This write should fail") }, failure = {
                state.pauseReasons.add("error"); state.restoreFreshSubmissionPause(ticket); release = null
                pending.remove("new"); durable.remove("new"); accepted = true
            })
        })
        persistence.save() // Interleaving normal save must not silently commit the pending release.
        disk.finish(true)
        assertTrue(JSONObject(String(disk.writes.first.bytes)).getBoolean("paused"))
        disk.finish(true)
        assertFalse(JSONObject(String(disk.writes.first.bytes)).getBoolean("paused"))
        disk.finish(false)
        assertEquals(true, accepted); assertEquals(listOf("new"), state.queue.map { it.getString("id") })
        assertEquals(setOf("environment", "error"), state.pauseReasons)
        val restored = ConversationStore().also { it.restore(disk.saved) }
        assertTrue("environment" in restored.pauseReasons); assertTrue(restored.paused)
        assertEquals(listOf("new"), restored.queue.map { it.getString("id") }); assertNull(restored.running)
    }
    @Test fun closeRetiresInflightCallbacksAndQueuedWritesWithoutOverwritingFinalSnapshot() {
        val disk = Disk(); var value = 1; var callbacks = 0
        val persistence = SessionPersistenceQueue({ JSONObject().put("value", value).toString().toByteArray() }, disk::write)
        persistence.save(success = { callbacks++ }); value = 2; persistence.save(success = { callbacks++ })
        value = 3
        val finalSnapshot = JSONObject().put("value", value).toString().toByteArray()
        persistence.close()
        // SessionController appends this final snapshot directly before shutting down its disk executor.
        disk.write(finalSnapshot) { }
        disk.finish(true); assertEquals(0, callbacks); assertEquals(1, disk.writes.size)
        disk.finish(true); assertEquals(3, disk.saved.getInt("value"))
        persistence.save(); assertTrue(disk.writes.isEmpty())
    }
    @Test fun writerDispatchExceptionsAndAsyncFailuresDoNotWedgeSubsequentSaves() {
        var throws = true; var failures = 0; var successes = 0; val disk = Disk()
        val persistence = SessionPersistenceQueue({ JSONObject().toString().toByteArray() }) { bytes, done ->
            if (throws) { throws = false; error("executor rejected") }
            disk.write(bytes, done)
        }
        persistence.save(failure = { failures++ })
        persistence.save(failure = { failures++ })
        persistence.save(success = { successes++ })
        assertEquals(1, failures); disk.finish(false); assertEquals(2, failures)
        disk.finish(true); assertEquals(1, successes)
    }
}
