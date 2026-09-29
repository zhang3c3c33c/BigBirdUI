package io.bbui.assistant

import android.app.Activity
import android.content.Intent
import android.view.WindowManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.bbui.device.ShizukuPhoneDevice
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Real Shizuku provider calls. Mutations are confined to the event created by this test. */
@RunWith(AndroidJUnit4::class)
class CalendarIntegrationTest {
    @Test(timeout = 180000) fun isolatedEventRoundTripRecurrenceRemindersDedupAndStop() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val stateFile = File(context.noBackupFilesDir, "conversations.json")
        if (stateFile.exists()) {
            val state = JSONObject(stateFile.readText())
            assertNull("Never interrupt a real user task", state.optJSONObject("running"))
            assertEquals("Never alter real queued tasks", 0, state.optJSONArray("queue")?.length() ?: 0)
            assertNotEquals("Never replace a pending user question", "pending", state.optJSONObject("pendingQuestion")?.optString("status"))
        }
        val host = instrumentation.startActivitySync(Intent(context, TestTargetActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as Activity
        instrumentation.runOnMainSync { host.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        val device = ShizukuPhoneDevice(context)
        val marker = "BBUI临时日程验收-${UUID.randomUUID()}"
        var calendarId = -1L
        var createdId = -1L
        var deleted = false
        fun call(operation: String, params: JSONObject = JSONObject(), actionId: String = UUID.randomUUID().toString()): JSONObject {
            val result = device.systemAction("calendar", operation, params, actionId)
            val details = result.getJSONObject("details")
            // Remember even a partially verified create receipt so finally can inspect its identity.
            if (operation == "create") {
                val receiptId = details.optJSONObject("receipt")?.optLong("eventId", -1) ?: -1
                val dataId = details.optJSONObject("data")?.optLong("eventId", -1) ?: -1
                if (createdId <= 0 && (receiptId > 0 || dataId > 0)) createdId = maxOf(receiptId, dataId)
            }
            assertFalse("calendar.$operation failed: ${details.optString("错误")}", result.optBoolean("isError"))
            return details
        }
        fun target() = JSONObject().put("calendarId", calendarId).put("eventId", createdId)
        fun details() = call("details", target()).getJSONObject("data").getJSONObject("event")
        fun minutes(event: JSONObject): List<Int> = event.getJSONArray("reminderMinutes").let { values ->
            (0 until values.length()).map { values.getInt(it) }.sorted()
        }
        val day = 24 * 60 * 60 * 1000L
        val start = (System.currentTimeMillis() / day + 10) * day + 12 * 60 * 60 * 1000L
        fun occurrences(): List<JSONObject> {
            val collected = mutableListOf<JSONObject>()
            var offset = 0
            do {
                val page = call("list", JSONObject().put("calendarId", calendarId).put("startMs", start - day)
                    .put("endMs", start + 5 * day).put("query", marker).put("offset", offset).put("limit", 100)).getJSONObject("data")
                val items = page.getJSONArray("items")
                for (i in 0 until items.length()) collected.add(items.getJSONObject(i))
                if (page.isNull("nextOffset")) break
                val next = page.getInt("nextOffset")
                assertTrue("Pagination must advance", next > offset)
                offset = next
                assertTrue("Unexpected unbounded fixture result", offset < 1000)
            } while (true)
            return collected.sortedBy { it.getLong("beginMs") }
        }
        try {
            device.bindSystem(); device.setStopped(false)
            val calendars = mutableListOf<JSONObject>()
            var offset = 0
            do {
                val page = call("calendars", JSONObject().put("offset", offset).put("limit", 100)).getJSONObject("data")
                val items = page.getJSONArray("items")
                for (i in 0 until items.length()) calendars.add(items.getJSONObject(i))
                if (page.isNull("nextOffset")) break
                val next = page.getInt("nextOffset"); assertTrue(next > offset); offset = next
                assertTrue(offset < 1000)
            } while (true)
            val selected = calendars.firstOrNull { calendar ->
                calendar.optBoolean("writable") && calendar.optString("accountType").equals("LOCAL", ignoreCase = true) &&
                    !Regex("birthday|anniversary|holiday|生日|纪念|节日", RegexOption.IGNORE_CASE).containsMatchIn(calendar.optString("name"))
            }
            assertNotNull("No ordinary writable LOCAL calendar; do not use a user's remote or birthday calendar", selected)
            calendarId = selected!!.getLong("id")
            val title = "$marker，中文😀"
            val description = "第一行，中文\n第二行, comma 😀"
            val params = JSONObject().put("calendarId", calendarId).put("title", title).put("description", description)
                .put("location", "测试地点，楼上\n第二间").put("startMs", start).put("endMs", start + 60 * 60 * 1000L)
                .put("timeZone", "UTC").put("allDay", false).put("rrule", "FREQ=DAILY;COUNT=3")
                .put("reminderMinutes", JSONArray().put(15))
            val actionId = UUID.randomUUID().toString()
            val created = call("create", params, actionId).getJSONObject("data").getJSONObject("event")
            if (createdId <= 0) createdId = created.getLong("id")
            assertTrue(createdId > 0)
            val observed = details()
            assertEquals(title, observed.getString("title")); assertEquals(description, observed.getString("description"))
            assertEquals(params.getString("location"), observed.getString("location"))
            assertEquals(listOf(15), minutes(observed))
            assertEquals("FREQ=DAILY;COUNT=3", observed.getString("rrule"))
            val instances = occurrences()
            assertEquals(3, instances.size)
            assertTrue("Every fixture occurrence must belong to the first event", instances.all { it.getLong("eventId") == createdId })
            assertEquals(listOf(start, start + day, start + 2 * day), instances.map { it.getLong("beginMs") })
            assertTrue(instances.all { it.getLong("endMs") - it.getLong("beginMs") == 60 * 60 * 1000L })
            val duplicate = call("create", params, actionId)
            assertTrue("A repeated action must not recreate the event", duplicate.getBoolean("本次未重放"))
            assertEquals("Deduplication must retain the original event receipt", createdId, duplicate.getJSONObject("receipt").getLong("eventId"))
            val afterDuplicate = occurrences()
            assertEquals(3, afterDuplicate.size)
            assertTrue("No duplicate fixture event may be created", afterDuplicate.all { it.getLong("eventId") == createdId })
            call("update", target().put("scope", "series").put("title", "$marker，已修改")
                .put("description", "修改后\n仍有逗号,😀").put("reminderMinutes", JSONArray().put(30)))
            val updated = details()
            assertEquals("$marker，已修改", updated.getString("title"))
            assertEquals("修改后\n仍有逗号,😀", updated.getString("description"))
            assertEquals(listOf(30), minutes(updated))
            call("update", target().put("scope", "series").put("rrule", JSONObject.NULL).put("reminderMinutes", JSONArray()))
            val cleared = details()
            assertTrue("Recurrence must be cleared", cleared.isNull("rrule") || cleared.optString("rrule").isBlank())
            assertEquals(emptyList<Int>(), minutes(cleared)); assertEquals(1, occurrences().size)
            assertEquals(0, device.action("列出屏幕", JSONObject()).getJSONObject("details").getJSONArray("屏幕").length())
            device.setStopped(true)
            assertThrows(IllegalStateException::class.java) { device.systemAction("calendar", "delete", target().put("scope", "series"), UUID.randomUUID().toString()) }
            device.setStopped(false)
            assertEquals(createdId, details().getLong("id"))
            call("delete", target().put("scope", "series"))
            deleted = true
            assertEquals(0, occurrences().size)
        } finally {
            try {
                if (calendarId > 0) {
                    device.setStopped(false)
                    // Also remove same-marker duplicate fixture rows if deduplication itself failed.
                    val ids = occurrences().map { it.getLong("eventId") }.toMutableSet()
                    if (createdId > 0 && !deleted) ids.add(createdId)
                    for (id in ids) {
                        val exact = JSONObject().put("calendarId", calendarId).put("eventId", id)
                        val retained = call("details", exact).getJSONObject("data").getJSONObject("event")
                        assertEquals("Cleanup must target the chosen calendar", calendarId, retained.getLong("calendarId"))
                        assertTrue("Cleanup must target our unique event", retained.getString("title").startsWith(marker))
                        call("delete", exact.put("scope", "series"))
                    }
                    assertEquals(0, occurrences().size)
                }
            } finally {
                device.close()
                instrumentation.runOnMainSync { host.finish() }
            }
        }
    }
}
