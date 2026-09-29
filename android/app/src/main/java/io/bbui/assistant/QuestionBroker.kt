package io.bbui.assistant

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.CountDownLatch

/** One live question belongs to one execution. Persistence never resurrects this latch. */
internal class QuestionBroker(private val event: (JSONObject) -> Unit) {
    class Pending(val value: JSONObject) {
        val done = CountDownLatch(1)
        @Volatile var response: JSONObject? = null
        fun await(): JSONObject { done.await(); return checkNotNull(response) }
    }
    private var pending: Pending? = null

    @Synchronized fun open(body: JSONObject, context: JSONObject): Pending {
        check(pending == null) { "已有问题等待回答" }
        val questions = body.getJSONArray("questions")
        require(questions.length() in 1..4) { "问题数量必须为 1–4" }
        require(body.getString("toolCallId").length in 1..200)
        val ids = mutableSetOf<String>()
        for (i in 0 until questions.length()) {
            val q = questions.getJSONObject(i)
            require(q.getString("id").length in 1..100 && ids.add(q.getString("id")))
            require(q.getString("question").length in 1..4000 && q.getString("header").length in 1..100)
            val options = q.getJSONArray("options")
            require(options.length() in 2..4 || (options.length() == 0 && !q.getBoolean("multiSelect")))
            val labels = mutableSetOf<String>()
            for (j in 0 until options.length()) {
                val option = options.getJSONObject(j)
                require(option.getString("label").length in 1..500 && labels.add(option.getString("label")))
                require(option.optString("description").length <= 2000)
            }
        }
        val value = JSONObject().put("requestId", UUID.randomUUID().toString()).put("toolCallId", body.getString("toolCallId"))
            .put("sessionId", context.getString("sessionId")).put("runId", context.getString("runId"))
            .put("questions", JSONArray(questions.toString())).put("status", "pending").put("draft", JSONArray())
        val request = Pending(value)
        pending = request
        event(JSONObject(value.toString()).put("type", "question_requested"))
        return request
    }

    @Synchronized fun answer(command: JSONObject): Boolean {
        val request = pending ?: return false
        if (listOf("sessionId", "runId", "requestId").any { command.optString(it) != request.value.optString(it) }) return false
        val cancelled = command.optBoolean("cancelled")
        val answers = if (cancelled) JSONArray() else validateAnswers(request.value.getJSONArray("questions"), command.getJSONArray("answers"))
        finish(request, JSONObject().put("requestId", request.value.getString("requestId")).put("answers", answers).put("cancelled", cancelled), if (cancelled) "cancelled" else "answered")
        return true
    }
    @Synchronized fun cancel() { pending?.let { finish(it, JSONObject().put("requestId", it.value.getString("requestId")).put("answers", JSONArray()).put("cancelled", true), "interrupted") } }
    private fun finish(request: Pending, response: JSONObject, status: String) {
        pending = null
        request.response = response
        event(JSONObject(request.value.toString()).put("type", "question_resolved").put("status", status).put("answers", response.getJSONArray("answers")))
        request.done.countDown()
    }
    companion object {
        fun validateAnswers(questions: JSONArray, answers: JSONArray, complete: Boolean = true): JSONArray {
            require(answers.length() == questions.length()) { "请回答所有问题" }
            val result = JSONArray()
            val byId = (0 until answers.length()).map { answers.getJSONObject(it) }.associateBy { it.getString("questionId") }
            require(byId.size == questions.length())
            for (i in 0 until questions.length()) {
                val q = questions.getJSONObject(i)
                val a = requireNotNull(byId[q.getString("id")])
                val selected = a.getJSONArray("selected")
                val text = a.getString("text")
                require(text.length <= 10000)
                val allowed = q.getJSONArray("options").let { options -> (0 until options.length()).map { options.getJSONObject(it).getString("label") }.toSet() }
                val chosen = (0 until selected.length()).map { selected.getString(it) }
                require(chosen.distinct().size == chosen.size && chosen.all { it in allowed })
                require(q.optBoolean("multiSelect") || chosen.size <= 1)
                require(!complete || chosen.isNotEmpty() || text.isNotBlank()) { "请填写答案" }
                result.put(JSONObject().put("questionId", q.getString("id")).put("selected", JSONArray(chosen)).put("text", text))
            }
            return result
        }
    }
}
