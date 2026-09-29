package io.bbui.assistant

import org.json.JSONObject

/** New sessions get one naming attempt; missing legacy metadata is deliberately ineligible. */
class AutoTitleState {
    private val attempts = linkedMapOf<String, String>()
    fun created(sessionId: String) { attempts[sessionId] = "" }
    fun claim(sessionId: String, submissionId: String): Boolean {
        if (attempts[sessionId] != "") return false
        attempts[sessionId] = submissionId
        return true
    }
    fun current(sessionId: String, submissionId: String): Boolean = attempts[sessionId] == submissionId
    fun revoke(sessionId: String) { attempts[sessionId] = "manual" }
    fun isManual(sessionId: String): Boolean = attempts[sessionId] == "manual"
    fun rollback(sessionId: String, submissionId: String) {
        if (current(sessionId, submissionId)) attempts[sessionId] = ""
    }
    fun remove(sessionId: String) { attempts.remove(sessionId) }
    fun snapshot(excluded: Set<String> = emptySet()): JSONObject = JSONObject(attempts.mapValues {
        if (it.value in excluded) "" else it.value
    })
    fun restore(value: JSONObject?) {
        attempts.clear()
        value?.keys()?.forEach { attempts[it] = value.getString(it) }
    }
}
