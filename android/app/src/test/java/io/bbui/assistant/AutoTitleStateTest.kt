package io.bbui.assistant

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AutoTitleStateTest {
    @Test fun onlyNewSessionsReceiveOneAttemptAndRestartDoesNotRetry() {
        val titles = AutoTitleState()
        assertFalse(titles.claim("legacy", "one"))
        titles.created("a")
        assertTrue(titles.claim("a", "one"))
        assertFalse(titles.claim("a", "two"))
        val restored = AutoTitleState().also { it.restore(titles.snapshot()) }
        assertFalse(restored.claim("a", "three"))
        assertFalse(AutoTitleState().also { it.restore(null) }.claim("a", "old"))
    }

    @Test fun manualRenameIncludingSameTitleRevokesLateResponse() {
        val titles = AutoTitleState().apply { created("a"); claim("a", "one") }
        titles.revoke("a")
        assertFalse(titles.current("a", "one"))
        titles.rollback("a", "one")
        assertFalse(titles.claim("a", "two"))
        assertTrue(titles.isManual("a"))
    }

    @Test fun deletingOrSwitchingSessionsCannotRedirectTitle() {
        val state = ConversationStore()
        for (id in listOf("a", "b")) {
            state.sessions[id] = JSONObject().put("id", id).put("title", "新会话")
            state.autoTitles.created(id)
        }
        assertTrue(state.autoTitles.claim("a", "one"))
        state.selected = "b"
        assertTrue(state.autoTitles.current("a", "one"))
        assertFalse(state.autoTitles.current("b", "one"))
        state.remove("a")
        assertFalse(state.autoTitles.current("a", "one"))
    }

    @Test fun failedSubmissionCanRetryButUnrelatedSaveCannotConsumeItsAttempt() {
        val titles = AutoTitleState().apply { created("a"); claim("a", "pending") }
        val unrelated = AutoTitleState().also { it.restore(titles.snapshot(setOf("pending"))) }
        assertTrue(unrelated.claim("a", "after-restart"))
        titles.rollback("a", "pending")
        assertTrue(titles.claim("a", "retry"))
        assertFalse(titles.current("a", "pending"))
    }

    @Test fun manualDefaultTitleIsNotReplacedBySubmissions() {
        val state = ConversationStore()
        state.sessions["a"] = JSONObject().put("id", "a").put("title", "新会话")
        state.autoTitles.created("a"); state.autoTitles.revoke("a")
        state.submit("one", "a", "不应成为标题")
        assertEquals("新会话", state.sessions["a"]!!.getString("title"))
        assertFalse(state.autoTitles.claim("a", "one"))
        val restored = ConversationStore().also { it.restore(state.save()) }
        assertTrue(restored.autoTitles.isManual("a"))
        assertTrue(restored.paused)
    }
}
