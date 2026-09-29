package io.bbui.device

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CalendarToolsTest {
    private fun create() = JSONObject().put("calendarId", 1).put("title", "中文, 日程\n第二行").put("description", "逗号,换行\n保留😀")
        .put("startMs", 1800000000000L).put("endMs", 1800003600000L).put("timeZone", "Asia/Shanghai")
    private fun existing() = JSONObject().put("id", 7).put("calendarId", 1).put("title", "已有").put("startMs", 1800000000000L)
        .put("endMs", JSONObject.NULL).put("duration", "P3600S").put("timeZone", "Asia/Shanghai").put("allDay", false).put("rrule", "FREQ=DAILY").put("originalId", JSONObject.NULL)
    @Test fun providerValuesPreserveTextAndRepresentRecurringDurationWithoutDtend() {
        val p = create().put("rrule", "FREQ=WEEKLY;BYDAY=MO,WE").put("reminderMinutes", JSONArray().put(10).put(5))
        val values = CalendarValues.build(p)
        assertEquals("中文, 日程\n第二行", values["title"])
        assertEquals("逗号,换行\n保留😀", values["description"])
        assertEquals("PT3600S", values["duration"]); assertNull(values["dtend"])
        assertEquals("FREQ=WEEKLY;BYDAY=MO,WE", values["rrule"])
        assertEquals(listOf(10, 5), CalendarValues.reminders(p))
    }
    @Test fun partialUpdatePreservesUnspecifiedRecurrenceAndRemindersAndNullCancelsAllRepeatFields() {
        val title = CalendarValues.build(JSONObject().put("title", "新标题"), existing())
        assertEquals(mapOf("title" to "新标题"), title)
        assertNull(CalendarValues.reminders(JSONObject()))
        val values = CalendarValues.build(JSONObject().put("rrule", JSONObject.NULL).put("reminderMinutes", JSONArray()), existing().put("rdate", "20270101T000000Z"))
        assertEquals(1800003600000L, values["dtend"])
        for (key in listOf("rrule", "duration", "rdate", "exrule", "exdate")) { assertTrue(values.containsKey(key)); assertNull(values[key]) }
        assertEquals(0, values["hasAlarm"])
        val nonRepeating = existing().put("rrule", JSONObject.NULL).put("rdate", JSONObject.NULL).put("endMs", 1800003600000L).put("duration", JSONObject.NULL)
        assertNull(CalendarValues.build(JSONObject().put("startMs", 1800002000000L), nonRepeating)["rrule"])
    }
    @Test fun changingStartPreservesOmittedEndAndAllDayUsesUtcExclusiveEnd() {
        val moved = CalendarValues.build(JSONObject().put("startMs", 1800001000000L), existing())
        assertEquals("PT2600S", moved["duration"])
        assertThrows(IllegalArgumentException::class.java) { CalendarValues.build(JSONObject().put("startMs", 1800001000000L), existing().put("rruleTruncated", true)) }
        val day = create().put("startMs", 172800000L).put("endMs", 259200000L).put("allDay", true).put("timeZone", "UTC").put("rrule", "FREQ=DAILY")
        assertEquals("P1D", CalendarValues.build(day)["duration"])
        assertThrows(IllegalArgumentException::class.java) { CalendarValues.build(day.put("timeZone", "Asia/Shanghai")) }
    }
    private class Fake : CalendarBackend {
        var current: JSONObject? = JSONObject().put("id", 7).put("calendarId", 1).put("originalId", JSONObject.NULL)
        var applies = 0; var failAfterApply = false; var receivedReminders: List<Int>? = null
        override fun calendars(p: JSONObject) = JSONObject().put("items", JSONArray()).put("nextOffset", JSONObject.NULL)
        override fun instances(p: JSONObject) = calendars(p)
        override fun event(id: Long): JSONObject? { if (applies > 0 && failAfterApply) error("Provider回读断开"); return current }
        override fun reminders(id: Long) = JSONArray().put(5)
        override fun apply(id: Long?, values: Map<String, Any?>, reminders: List<Int>?, delete: Boolean, beforeDispatch: () -> Unit): Long {
            beforeDispatch()
            applies++; receivedReminders = reminders; if (delete) current = null; return id ?: 7
        }
    }
    @Test fun stopAndIdentityOrScopeRejectionNeverDispatch() {
        val backend = Fake(); var claims = 0
        val tools = CalendarTools(backend, {}, { claims++ })
        for (params in listOf(JSONObject().put("eventId", 7), JSONObject().put("eventId", 7).put("scope", "single"), JSONObject().put("eventId", 7).put("calendarId", 2).put("scope", "series"))) {
            assertThrows(IllegalArgumentException::class.java) { tools.execute("delete", params) }
        }
        backend.current!!.put("originalId", 99)
        assertThrows(IllegalArgumentException::class.java) { tools.execute("delete", JSONObject().put("eventId", 7).put("scope", "series")) }
        assertThrows(IllegalStateException::class.java) { CalendarTools(backend, { error("STOP") }, { claims++ }).execute("create", create()) }
        assertEquals(0, claims); assertEquals(0, backend.applies)
    }
    @Test fun committedMutationReadbackFailurePreservesReceiptAndDoesNotRetry() {
        val backend = Fake().apply { failAfterApply = true }
        val result = CalendarTools(backend, {}, {}).execute("create", create())
        assertTrue(result.getBoolean("dispatched")); assertEquals(7L, result.getLong("eventId"))
        assertEquals("failed", result.getString("verification")); assertEquals(1, backend.applies)
        assertEquals(emptyList<Int>(), backend.receivedReminders)
    }
    @Test fun detailsEnvelopeAndDeleteReadbackAreStructured() {
        val backend = Fake(); val tools = CalendarTools(backend, {}, {})
        assertEquals(7, tools.execute("details", JSONObject().put("eventId", 7)).getJSONObject("event").getInt("id"))
        assertEquals(7, tools.execute("details", JSONObject().put("eventId", 7).put("calendarId", 1)).getJSONObject("event").getInt("id"))
        assertThrows(IllegalArgumentException::class.java) { tools.execute("details", JSONObject().put("eventId", 7).put("calendarId", 2)) }
        assertEquals(0, backend.applies)
        val result = tools.execute("delete", JSONObject().put("eventId", 7).put("scope", "series"))
        assertTrue(result.getBoolean("deleted")); assertEquals("observed", result.getString("verification"))
        assertTrue(tools.execute("calendars", JSONObject()).has("deviceTimeZone"))
    }
    @Test fun capabilityValidationUsesWritableCountAndProviderReminderMethods() {
        val calendar = JSONObject().put("accessLevel", 700).put("maxReminders", 5).put("allowedReminders", "0,1")
        assertEquals(1, CalendarValues.reminderMethod(calendar, 2, true))
        assertEquals(0, CalendarValues.reminderMethod(calendar.put("allowedReminders", "0"), 2, true))
        assertThrows(IllegalStateException::class.java) { CalendarValues.reminderMethod(calendar.put("allowedReminders", "2,3"), 2, true) }
        assertThrows(IllegalArgumentException::class.java) { CalendarValues.reminderMethod(calendar, 6, false) }
        assertThrows(IllegalArgumentException::class.java) { CalendarValues.reminderMethod(calendar.put("accessLevel", 200), 0, false) }
    }
}
