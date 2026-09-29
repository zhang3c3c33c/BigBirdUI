package io.bbui.assistant

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class QuestionBrokerTest {
    @Test fun textOnlyQuestionsRequireNonBlankExplicitAnswersAndRejectSelection() {
        val b = body(); b.getJSONArray("questions").getJSONObject(0).put("options", JSONArray())
        val broker = QuestionBroker {}; val p = broker.open(b, context())
        for (raw in listOf("""[{"questionId":"q1","selected":[],"text":"  "}]""", """[{"questionId":"q1","selected":["甲"],"text":"张三"}]""")) {
            assertThrows(IllegalArgumentException::class.java) { broker.answer(JSONObject(p.value.toString()).put("answers", JSONArray(raw))) }
            assertEquals(1L, p.done.count)
        }
        assertTrue(broker.answer(JSONObject(p.value.toString()).put("answers", JSONArray("""[{"questionId":"q1","selected":[],"text":"张三😀"}]"""))))
        assertEquals("张三😀", p.await().getJSONArray("answers").getJSONObject(0).getString("text"))
        b.getJSONArray("questions").getJSONObject(0).put("multiSelect", true)
        assertThrows(IllegalArgumentException::class.java) { broker.open(b, context()) }
        b.getJSONArray("questions").getJSONObject(0).put("multiSelect", false).put("options", JSONArray("""[{"label":"甲"}]"""))
        assertThrows(IllegalArgumentException::class.java) { broker.open(b, context()) }
    }
    private fun body() = JSONObject("""{"toolCallId":"call","questions":[{"id":"q1","header":"选择","question":"请选择","options":[{"label":"甲"},{"label":"乙"}],"multiSelect":false}]}""")
    private fun context() = JSONObject().put("sessionId", "a").put("runId", "7")
    private fun answer(p: QuestionBroker.Pending) = JSONObject(p.value.toString()).put("answers", JSONArray("""[{"questionId":"q1","selected":["甲"],"text":""}]"""))
    @Test fun answerIsBoundToOneRunAndAcceptedOnce() {
        val events = mutableListOf<JSONObject>(); val broker = QuestionBroker { events.add(it) }
        val p = broker.open(body(), context())
        assertFalse(broker.answer(answer(p).put("runId", "6")))
        assertFalse(broker.answer(answer(p).put("sessionId", "b")))
        assertEquals(1L, p.done.count)
        assertTrue(broker.answer(answer(p)))
        assertFalse(broker.answer(answer(p)))
        assertFalse(p.await().getBoolean("cancelled"))
        assertEquals(listOf("question_requested", "question_resolved"), events.map { it.getString("type") })
    }
    @Test fun invalidSelectionKeepsWaitingAndStopReleasesWithoutAnswer() {
        val broker = QuestionBroker {}; val p = broker.open(body(), context())
        assertThrows(IllegalArgumentException::class.java) { broker.answer(answer(p).put("answers", JSONArray("""[{"questionId":"q1","selected":["不存在"],"text":""}]"""))) }
        assertEquals(1L, p.done.count)
        broker.cancel()
        assertTrue(p.await().getBoolean("cancelled")); assertFalse(broker.answer(answer(p)))
    }
    @Test fun multipleQuestionsAndFreeTextArePreserved() {
        val b = body(); b.getJSONArray("questions").put(JSONObject("""{"id":"q2","header":"多选","question":"请选择多个","options":[{"label":"一"},{"label":"二"}],"multiSelect":true}"""))
        val broker = QuestionBroker {}; val p = broker.open(b, context())
        val a = JSONArray("""[{"questionId":"q1","selected":[],"text":"中文😀"},{"questionId":"q2","selected":["一","二"],"text":"补充"}]""")
        assertTrue(broker.answer(JSONObject(p.value.toString()).put("answers", a)))
        assertEquals("中文😀", p.await().getJSONArray("answers").getJSONObject(0).getString("text"))
    }
    @Test fun restartRetainsDraftButNeverResurrectsQuestion() {
        val state = ConversationStore(); state.sessions["a"] = JSONObject().put("id", "a"); state.selected = "a"
        state.running = JSONObject().put("sessionId", "a").put("runId", "7").put("started", true)
        val broker = QuestionBroker { state.accept(it) }; val p = broker.open(body(), context())
        state.pendingQuestion!!.put("draft", JSONArray("""[{"questionId":"q1","selected":[],"text":"草稿"}]"""))
        assertFalse(state.controlSnapshot().getBoolean("canSteer"))
        val recovered = ConversationStore(); recovered.restore(state.save())
        assertEquals("interrupted", recovered.pendingQuestion!!.getString("status"))
        assertEquals("草稿", recovered.pendingQuestion!!.getJSONArray("draft").getJSONObject(0).getString("text"))
        assertNull(recovered.running); assertTrue(recovered.paused)
        broker.cancel()
    }
}
