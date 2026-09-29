package io.bbui.assistant

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ViewStateStoreTest {
    private fun draft(text: String = "草稿😀", top: Double = 456.0) = JSONObject().put("text", text).put("scrollTop", top)
    private fun state() = ConversationStore().apply {
        for (id in listOf("a", "b")) sessions[id] = JSONObject().put("id", id).put("title", "会话$id")
        selected = "a"
    }
    @Test fun legacyDraftsMigrateWithoutLosingTextOrReadingPosition() {
        val legacy = JSONObject().put("selected", "a").put("sessions", JSONArray().put(JSONObject().put("id", "a")))
            .put("drafts", JSONObject().put("a", draft()))
        val restored = ConversationStore().also { it.restore(legacy) }
        assertEquals("草稿😀", restored.drafts["a"]!!.getString("text"))
        assertEquals(456.0, restored.drafts["a"]!!.getDouble("scrollTop"), 0.0)
        assertEquals(0, restored.viewStates.clock)
        restored.viewStates.set("a", draft(top = 600.0))
        assertEquals(1, restored.viewStates.clock)
        val roundTrip = ConversationStore().also { it.restore(restored.save()) }
        assertEquals(1, roundTrip.viewStates.revision("a"))
        assertEquals(600.0, roundTrip.drafts["a"]!!.getDouble("scrollTop"), 0.0)
    }
    @Test fun sidecarWinsOnlyWhenNewerAndClockUsesBothFilesIncludingIgnoredSessions() {
        val state = state(); state.viewStates.set("a", draft("旧"))
        val main = state.save()
        state.viewStates.set("a", draft("新", 900.0)); state.viewStates.set("b", draft("另一会话", 80.0))
        val sidecar = state.viewStates.snapshot()
        sidecar.getJSONObject("entries").put("deleted", JSONObject().put("revision", 50).put("value", draft("不得复活")))
        val restored = ConversationStore().also { it.restore(main) }
        restored.viewStates.mergeSidecar(sidecar, restored.sessions.keys)
        assertEquals("新", restored.drafts["a"]!!.getString("text"))
        assertEquals("另一会话", restored.drafts["b"]!!.getString("text"))
        assertFalse("deleted" in restored.drafts)
        assertEquals(50, restored.viewStates.clock)
        restored.viewStates.set("a", draft("继续输入")); assertEquals(51, restored.viewStates.revision("a"))
        val newestMain = restored.save()
        val newer = ConversationStore().also { it.restore(newestMain) }
        newer.viewStates.mergeSidecar(sidecar, newer.sessions.keys)
        assertEquals("继续输入", newer.drafts["a"]!!.getString("text"))
    }
    @Test fun sendClearCannotBeOverwrittenByOlderSidecarAndFailureRestoresWithNewRevision() {
        val state = state(); state.viewStates.set("a", draft())
        val oldSidecar = state.viewStates.snapshot(); val prior = state.drafts["a"]!!
        state.submit("send", "a", "用户指令")
        val cleared = state.viewStates.revision("a")
        val restart = ConversationStore().also { it.restore(state.save()) }
        restart.viewStates.mergeSidecar(oldSidecar, restart.sessions.keys)
        assertFalse("a" in restart.drafts)
        assertTrue(state.viewStates.restoreIfUnchanged("a", prior, cleared))
        assertTrue(state.viewStates.revision("a") > cleared)
        val restored = ConversationStore().also { it.restore(restart.save()) }
        restored.viewStates.mergeSidecar(state.viewStates.snapshot(), restored.sessions.keys)
        assertEquals("草稿😀", restored.drafts["a"]!!.getString("text"))
    }
    @Test fun failedSubmissionNeverRestoresOverNewerInputEvenIfItWasClearedAgain() {
        val store = ViewStateStore(); store.set("a", draft("初始")); val prior = store.values["a"]!!
        store.clear("a"); val cleared = store.revision("a")
        store.set("a", draft("保存期间输入"))
        assertFalse(store.restoreIfUnchanged("a", prior, cleared))
        assertEquals("保存期间输入", store.values["a"]!!.getString("text"))
        store.clear("a")
        assertFalse(store.restoreIfUnchanged("a", prior, cleared)); assertFalse("a" in store.values)
    }
    @Test fun deleteTombstonePreventsOldMainDraftThenCanBePrunedAfterMainDeletionCommits() {
        val state = state(); state.viewStates.set("a", draft()); val oldMain = state.save(); val oldSidecar = state.viewStates.snapshot()
        state.remove("a")
        val tombstone = state.viewStates.snapshot()
        val oldRestart = ConversationStore().also { it.restore(oldMain) }
        oldRestart.viewStates.mergeSidecar(tombstone, oldRestart.sessions.keys)
        assertFalse("a" in oldRestart.drafts)
        val deletionCommitted = state.save()
        state.viewStates.forgetDeleted(setOf("a"), state.sessions.keys)
        assertFalse(state.viewStates.snapshot().getJSONObject("entries").has("a"))
        val restart = ConversationStore().also { it.restore(deletionCommitted) }
        restart.viewStates.mergeSidecar(oldSidecar, restart.sessions.keys)
        assertFalse("a" in restart.drafts); assertFalse("a" in restart.sessions)
    }
    @Test fun excludedPendingClaimsRestoreDraftVersionsInSnapshotIncludingConsecutiveClears() {
        val state = state(); state.viewStates.set("a", draft("尚未提交"))
        val prior = state.drafts["a"]!!; val priorRevision = state.viewStates.revision("a")
        state.submit("first", "a", "第一项")
        val first = ViewStateStore.PendingClear("a", prior, priorRevision, state.viewStates.revision("a"))
        state.submit("second", "a", "第二项")
        val second = ViewStateStore.PendingClear("a", null, first.clearedRevision, state.viewStates.revision("a"))
        val snapshot = state.save(setOf("first", "second"))
        ViewStateStore.restoreExcludedClears(snapshot, listOf(first, second))
        val restarted = ConversationStore().also { it.restore(snapshot) }
        assertTrue(restarted.queue.isEmpty())
        assertEquals("尚未提交", restarted.drafts["a"]!!.getString("text"))
        assertEquals(priorRevision, restarted.viewStates.revision("a"))
        assertTrue(restarted.viewStates.clock >= second.clearedRevision)
        // The first save includes its own committed clear but excludes the later pending clear.
        val firstIncluded = state.save(setOf("second"))
        ViewStateStore.restoreExcludedClears(firstIncluded, listOf(second))
        assertFalse(firstIncluded.getJSONObject("drafts").has("a"))
        assertEquals(first.clearedRevision, firstIncluded.getJSONObject("viewStateRevisions").getLong("a"))
        // User input after both clears must survive unchanged in unrelated/close snapshots.
        state.viewStates.set("a", draft("后来的输入"))
        val later = state.save(setOf("first", "second")); ViewStateStore.restoreExcludedClears(later, listOf(first, second))
        assertEquals("后来的输入", later.getJSONObject("drafts").getJSONObject("a").getString("text"))
        state.remove("a")
        val deleted = state.save(setOf("first", "second")); ViewStateStore.restoreExcludedClears(deleted, listOf(first, second))
        assertFalse(deleted.getJSONObject("drafts").has("a"))
    }
    @Test fun twoFailedSubmissionsRestoreOriginalDraftWithoutResurrectingSuccessfulClears() {
        val state = state(); state.viewStates.set("a", draft("保留我"))
        val prior = state.drafts["a"]!!; val revision = state.viewStates.revision("a")
        state.submit("first", "a", "第一项")
        val first = ViewStateStore.PendingClear("a", prior, revision, state.viewStates.revision("a"))
        state.submit("second", "a", "第二项")
        val second = ViewStateStore.PendingClear("a", null, first.clearedRevision, state.viewStates.revision("a"))
        val pending = mutableMapOf("first" to first, "second" to second)
        val failedFirst = ViewStateStore.retireFailedClear(pending, "first")!!
        assertFalse(state.viewStates.restoreIfUnchanged("a", failedFirst.prior!!, failedFirst.clearedRevision))
        val failedSecond = ViewStateStore.retireFailedClear(pending, "second")!!
        assertTrue(state.viewStates.restoreIfUnchanged("a", failedSecond.prior!!, failedSecond.clearedRevision))
        assertEquals("保留我", state.drafts["a"]!!.getString("text"))
    }
    @Test fun snapshotsAreDetachedAndUnknownFieldsCannotChooseFilesOrCarryProviderData() {
        val state = ViewStateStore()
        state.set("a", draft().put("path", "/outside/private.json").put("messages", JSONArray().put("secret")))
        val captured = state.snapshot()
        state.set("a", draft("后来的输入"))
        val value = captured.getJSONObject("entries").getJSONObject("a").getJSONObject("value")
        assertEquals("草稿😀", value.getString("text")); assertFalse(value.has("path")); assertFalse(value.has("messages"))
        assertEquals(2, value.length())
    }
    @Test fun repeatedScrollKeepsOnlyLatestValueAndUnchangedStateDoesNotScheduleWrites() {
        val state = ViewStateStore()
        for (position in 1..1000) state.set("a", draft(top = position.toDouble()))
        assertFalse(state.set("a", draft(top = 1000.0)))
        assertEquals(1000, state.clock)
        assertEquals(1, state.snapshot().getJSONObject("entries").length())
        assertTrue(state.snapshot().toString().toByteArray(Charsets.UTF_8).size < 250)
    }
    @Test fun scrollPersistenceBytesDoNotGrowWithThousandLongHistoryMessages() {
        val short = state(); val long = state()
        val messages = JSONArray()
        repeat(1000) { index -> messages.put(JSONObject().put("role", "assistant").put("timestamp", index + 1)
            .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "长回复".repeat(1000) + index)))) }
        long.chat("a").accept(JSONObject().put("type", "history_snapshot").put("sessionId", "a").put("messages", messages))
        short.viewStates.set("a", draft()); long.viewStates.set("a", draft())
        val shortBytes = short.viewStates.snapshot().toString().toByteArray(Charsets.UTF_8)
        val longBytes = long.viewStates.snapshot().toString().toByteArray(Charsets.UTF_8)
        assertArrayEquals(shortBytes, longBytes)
        assertTrue(longBytes.size < 250)
        assertEquals(1000, long.chat("a").snapshot(Int.MAX_VALUE).getJSONArray("messages").length())
        assertTrue(long.save().toString().length > 3000000)
    }
}
