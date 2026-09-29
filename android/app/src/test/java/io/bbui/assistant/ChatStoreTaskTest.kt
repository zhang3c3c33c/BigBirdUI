package io.bbui.assistant

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ChatStoreTaskTest {
    private fun event(type: String, run: String = "1") = JSONObject().put("type", type).put("runId", run)
    private fun task(status: String, id: String = "task-1") = JSONObject().put("version", 1).put("id", id)
        .put("goal", "打开默认美团，查看订单").put("status", status).put("summary", "已看到分身选择页")
        .put("constraints", JSONArray().put(JSONObject().put("text", "默认那个就行").put("source", "user")))
        .put("unknowns", JSONArray().put(JSONObject().put("text", "订单是否显示").put("resolveBy", "observe")))
    private fun begin(store: ChatStore, run: String = "1") = store.accept(event("run_started", run).put("prompt", "查看订单"))
    private fun call(id: String, action: String = "update") = JSONObject().put("type", "toolCall").put("id", id)
        .put("name", "task_state").put("arguments", JSONObject().put("action", action).put("task", task("completed")))
    private fun result(id: String, value: JSONObject?, failed: Boolean = false) = JSONObject().put("role", "toolResult")
        .put("toolCallId", id).put("toolName", "task_state").put("isError", failed)
        .put("details", JSONObject().put("bbuiTask", value ?: JSONObject.NULL))
    private fun update(store: ChatStore, value: JSONObject?, id: String = "record", action: String = "update", failed: Boolean = false, run: String = "1") {
        store.accept(event("message_start", run).put("message", JSONObject().put("role", "assistant")
            .put("content", JSONArray().put(call(id, action)))))
        store.accept(event("message_end", run).put("message", result(id, value, failed)))
    }
    private fun state(store: ChatStore) = store.snapshot().getJSONObject("taskDisplay").getString("state")
    private fun settle(store: ChatStore, run: String = "1") = store.accept(event("agent_settled", run))
    private fun history(vararg items: JSONObject) = JSONObject().put("type", "history_snapshot").put("sessionId", "session")
        .put("messages", JSONArray(items.toList()))
    private fun assistant(id: String, action: String = "update") = JSONObject().put("role", "assistant")
        .put("timestamp", 2).put("content", JSONArray().put(call(id, action)))
    private fun user(text: String, time: Long) = JSONObject().put("role", "user").put("timestamp", time).put("content", text)

    @Test fun onlySuccessfulToolDetailsPublishTaskAndArgumentsNeverClaimCompletion() {
        val store = ChatStore(); begin(store)
        store.accept(event("message_start").put("message", assistant("proposal")))
        assertTrue(store.snapshot().isNull("task"))
        store.accept(event("message_end").put("message", result("proposal", task("completed"), true)))
        assertTrue(store.snapshot().isNull("task"))
        update(store, task("waiting_user"), id = "actual")
        assertEquals("waiting_user", store.snapshot().getJSONObject("task").getString("status"))
        assertEquals("running", state(store))
        settle(store)
        assertEquals("waiting_user", state(store))
        val parts = store.snapshot().getJSONArray("messages").getJSONObject(2).getJSONArray("parts")
        assertEquals("更新任务记录", parts.getJSONObject(0).getString("title"))
        assertFalse(parts.toString().contains("打开默认美团"))
    }

    @Test fun newRunAndReadDoNotInheritOldCompletionAndReadDoesNotClearNewUpdate() {
        val store = ChatStore(); begin(store); update(store, task("completed")); settle(store)
        assertEquals("completed", state(store))
        begin(store, "2")
        update(store, task("completed"), "read-old", "read", run = "2"); settle(store, "2")
        assertEquals("unconfirmed", state(store))
        assertEquals("completed", store.snapshot().getJSONObject("task").getString("status"))
        begin(store, "3")
        update(store, task("blocked", "task-2"), "new", run = "3")
        update(store, task("blocked", "task-2"), "read-new", "read", run = "3"); settle(store, "3")
        assertEquals("blocked", state(store))
        assertEquals("task-2", store.snapshot().getJSONObject("task").getString("id"))
    }

    @Test fun coldHistoryAssociatesTaskUpdateWithLatestUserAndSubsequentPhoneActions() {
        val store = ChatStore()
        val first = user("第一个任务", 1)
        val finished = result("saved", task("completed"))
        store.accept(history(first, assistant("saved"), finished))
        assertEquals("completed", state(store))
        store.accept(history(first, assistant("saved"), finished, user("新任务", 3)))
        assertEquals("unconfirmed", state(store))
        assertEquals("completed", store.snapshot().getJSONObject("task").getString("status"))
        val phone = JSONObject().put("role", "toolResult").put("toolName", "phone_action").put("toolCallId", "phone")
        store.accept(history(first, assistant("saved"), finished, phone))
        assertEquals("unconfirmed", state(store))
        val unfinishedPhone = JSONObject().put("role", "assistant").put("timestamp", 4).put("content", JSONArray()
            .put(JSONObject().put("type", "toolCall").put("name", "phone_action").put("id", "pending-phone")))
        store.accept(history(first, assistant("saved"), finished, unfinishedPhone))
        assertEquals("unconfirmed", state(store))
    }

    @Test fun startupHistoryRestoresRecordWithoutMarkingNewRunComplete() {
        val store = ChatStore(); begin(store)
        store.accept(history(user("旧任务", 1), assistant("saved"), result("saved", task("completed"))).put("runId", "1"))
        settle(store)
        assertEquals("unconfirmed", state(store))
        assertEquals("completed", store.snapshot().getJSONObject("task").getString("status"))
    }

    @Test fun unfinishedPhoneFromEarlierUserTurnDoesNotInvalidateCurrentCompletion() {
        val store = ChatStore()
        val unfinished = JSONObject().put("role", "assistant").put("timestamp", 2).put("content", JSONArray()
            .put(JSONObject().put("type", "toolCall").put("name", "phone_action").put("id", "old-pending-phone")))
        store.accept(history(user("先前中断的任务", 1), unfinished, user("新的任务", 3),
            assistant("current-task").put("timestamp", 4), result("current-task", task("completed", "current"))))
        assertEquals("completed", state(store))
        assertEquals("current", store.snapshot().getJSONObject("task").getString("id"))
        // Re-reading history must not depend on whether an old projected part was settled.
        store.accept(history(user("先前中断的任务", 1), unfinished, user("新的任务", 3),
            assistant("current-task").put("timestamp", 4), result("current-task", task("completed", "current"))))
        assertEquals("completed", state(store))
    }

    @Test fun authoritativeHistoryCanReplaceCachedTaskAndNewLiveTaskResistsOldStartupHistory() {
        val original = ChatStore(); begin(original); update(original, task("completed")); settle(original)
        val restored = ChatStore(); restored.restore(original.snapshot())
        restored.accept(history(user("新任务", 1), assistant("new"), result("new", task("waiting_user", "new-task"))))
        assertEquals("waiting_user", state(restored))
        assertEquals("new-task", restored.snapshot().getJSONObject("task").getString("id"))
        begin(restored, "2"); update(restored, task("active", "live-task"), "live", run = "2")
        restored.accept(history(user("旧任务", 1), assistant("old"), result("old", task("completed"))).put("runId", "2"))
        assertEquals("live-task", restored.snapshot().getJSONObject("task").getString("id"))
    }

    @Test fun cacheRestorePreservesRecordedStateButNeverResumesRunningWork() {
        for (status in listOf("completed", "waiting_user", "blocked")) {
            val original = ChatStore(); begin(original); update(original, task(status)); settle(original)
            val restored = ChatStore(); restored.restore(original.snapshot())
            assertEquals(status, state(restored))
            assertFalse(restored.snapshot().getBoolean("isRunning"))
            assertEquals(original.snapshot().getJSONObject("task").toString(), restored.snapshot().getJSONObject("task").toString())
        }
        val running = ChatStore(); begin(running); update(running, task("completed"))
        val restored = ChatStore(); restored.restore(running.snapshot())
        assertEquals("stopped", state(restored))
        assertFalse(restored.snapshot().getBoolean("isRunning"))
    }

    @Test fun stopAndErrorOverrideModelRecordAndLateRunCannotReplaceIt() {
        val store = ChatStore(); begin(store); update(store, task("completed"))
        store.accept(event("run_stopped", "2")); settle(store, "2")
        assertEquals("stopped", state(store))
        assertFalse(store.accept(event("message_end", "1").put("message", result("record", task("waiting_user")))))
        assertEquals("completed", store.snapshot().getJSONObject("task").getString("status"))
        begin(store, "3"); update(store, task("completed"), "new", run = "3")
        store.accept(event("status", "3").put("status", "error").put("message", "网络断开")); settle(store, "3")
        assertEquals("error", state(store))
        val restored = ChatStore(); restored.restore(store.snapshot()); assertEquals("error", state(restored))
    }

    @Test fun phoneActionAfterCompletionRequiresAnotherTaskUpdate() {
        val store = ChatStore(); begin(store); update(store, task("completed"))
        store.accept(event("tool_execution_start").put("toolCallId", "phone").put("toolName", "phone_action")
            .put("args", JSONObject().put("操作", "查看")))
        settle(store)
        assertEquals("unconfirmed", state(store))
        val active = ChatStore(); begin(active); update(active, task("active"))
        active.accept(event("tool_execution_start").put("toolCallId", "phone").put("toolName", "phone_action")
            .put("args", JSONObject().put("操作", "查看")))
        settle(active)
        assertEquals("active", state(active))
        assertTrue(active.snapshot().getBoolean("taskUpdatedThisRun"))
    }

    @Test fun absentActiveAndInvalidRecordsDoNotInventTaskCompletion() {
        val empty = ChatStore(); begin(empty); settle(empty)
        assertEquals("unconfirmed", state(empty))
        val active = ChatStore(); begin(active); update(active, task("active")); settle(active)
        assertEquals("active", state(active))
        val invalid = ChatStore(); begin(invalid); update(invalid, task("made-up")); settle(invalid)
        assertTrue(invalid.snapshot().isNull("task")); assertEquals("unconfirmed", state(invalid))
    }

    @Test fun projectionAllowlistsTaskFieldsAndNullClearsRecord() {
        val store = ChatStore(); begin(store)
        update(store, task("active").put("rawScreenshot", "private-image").put("summary", "api_key: sk-abcdef12345678"))
        assertFalse(store.snapshot().toString().contains("private-image"))
        assertFalse(store.snapshot().toString().contains("abcdef12345678"))
        update(store, null, "clear", "read")
        assertTrue(store.snapshot().isNull("task"))
        assertFalse(store.snapshot().getBoolean("taskUpdatedThisRun"))
    }
}
