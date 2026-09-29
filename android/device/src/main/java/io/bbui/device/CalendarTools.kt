package io.bbui.device

import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.ZoneId

/** Testable calendar contract. The provider alone expands and validates RRULEs. */
internal interface CalendarBackend {
    fun calendars(p: JSONObject): JSONObject
    fun instances(p: JSONObject): JSONObject
    fun event(id: Long): JSONObject?
    fun reminders(id: Long): JSONArray
    fun apply(id: Long?, values: Map<String, Any?>, reminders: List<Int>?, delete: Boolean, beforeDispatch: () -> Unit): Long
}

internal object CalendarValues {
    fun textOrNull(p: JSONObject?, key: String): String? = if (p == null || p.isNull(key)) null else p.getString(key).ifBlank { null }
    fun reminderMethod(calendar: JSONObject, count: Int, needsMethod: Boolean): Int {
        require(calendar.getInt("accessLevel") >= 500) { "指定日历不可写" }
        val maximum = if (calendar.isNull("maxReminders")) -1 else calendar.getInt("maxReminders")
        require(maximum < 0 || count <= maximum) { "此日历最多支持${maximum}个提醒" }
        if (!needsMethod) return 0
        val allowed = (textOrNull(calendar, "allowedReminders") ?: "").split(',').mapNotNull { it.trim().toIntOrNull() }
        return when { 1 in allowed -> 1; 0 in allowed -> 0; else -> error("此日历不支持本地提醒或默认提醒方式") }
    }
    private val strings = mapOf("title" to "title", "description" to "description", "location" to "eventLocation")
    fun reminders(p: JSONObject): List<Int>? = if (!p.has("reminderMinutes")) null else {
        val rows = p.getJSONArray("reminderMinutes")
        require(rows.length() <= 100) { "单个事件最多提交100个提醒" }
        (0 until rows.length()).map { rows.getInt(it).also { n -> require(n >= 0) { "提醒提前分钟不能为负数" } } }.distinct()
    }
    fun durationMillis(value: String): Long {
        val seconds = Regex("^P(\\d+)S$").matchEntire(value)?.groupValues?.get(1)?.toLong()
        return if (seconds != null) Math.multiplyExact(seconds, 1000) else Duration.parse(value).toMillis()
    }
    fun build(p: JSONObject, previous: JSONObject? = null): Map<String, Any?> {
        val creating = previous == null
        if (creating) for (key in listOf("calendarId", "title", "startMs", "endMs", "timeZone")) require(p.has(key) && !p.isNull(key)) { "缺少$key" }
        val result = linkedMapOf<String, Any?>()
        for ((key, column) in strings) if (p.has(key)) {
            val text = p.getString(key); require(text.codePointCount(0, text.length) <= if (key == "description") 12000 else 2000) { "${key}文本过长" }
            if (key == "title") require(text.isNotBlank()) { "标题不能为空" }; result[column] = text
        }
        if (p.has("calendarId")) result["calendar_id"] = p.getLong("calendarId").also { require(it > 0) }
        val changingTime = creating || listOf("startMs", "endMs", "timeZone", "allDay", "rrule").any(p::has)
        if (changingTime) {
            require(previous == null || listOf("rrule", "rdate", "exrule", "exdate", "duration", "timeZone").none { previous.optBoolean(it + "Truncated") }) { "原事件重复或时间字段超出读取上限，未修改其时间规则" }
            require(textOrNull(previous, "rdate") == null || p.has("rrule")) { "此事件使用RDATE；修改时间时请明确rrule（null取消重复），避免改变原重复语义" }
            val start = if (p.has("startMs")) p.getLong("startMs") else previous!!.getLong("startMs")
            val oldEnd = previous?.let { if (!it.isNull("endMs")) it.getLong("endMs") else Math.addExact(it.getLong("startMs"), durationMillis(it.getString("duration"))) }
            val end = if (p.has("endMs")) p.getLong("endMs") else oldEnd!!
            require(end > start) { "endMs必须晚于startMs" }
            val zone = if (p.has("timeZone")) p.getString("timeZone") else previous!!.getString("timeZone")
            ZoneId.of(zone)
            val allDay = if (p.has("allDay")) p.getBoolean("allDay") else previous?.optBoolean("allDay") ?: false
            if (allDay) require(zone == "UTC" && start % 86400000L == 0L && end % 86400000L == 0L) { "全天事件使用UTC午夜时间戳，endMs为不包含的结束日期" }
            val rule = if (p.has("rrule")) textOrNull(p, "rrule") else textOrNull(previous, "rrule")
            require(rule == null || rule.codePointCount(0, rule.length) <= 4000) { "RRULE过长" }
            result["dtstart"] = start; result["eventTimezone"] = zone; result["allDay"] = if (allDay) 1 else 0
            result["rrule"] = rule
            if (rule != null) {
                val duration = Math.subtractExact(end, start)
                require(duration % 1000 == 0L) { "重复事件的持续时间须为整秒" }
                result["duration"] = if (allDay) "P${duration / 86400000}D" else "PT${duration / 1000}S"
                result["dtend"] = null
            } else { result["dtend"] = end; result["duration"] = null }
            if (p.has("rrule") && rule == null) { result["rdate"] = null; result["exrule"] = null; result["exdate"] = null }
        }
        reminders(p)?.let { result["hasAlarm"] = if (it.isEmpty()) 0 else 1 }
        if (creating && !p.has("reminderMinutes")) result["hasAlarm"] = 0
        return result
    }
}

internal class CalendarTools(private val backend: CalendarBackend, private val checkActive: () -> Unit, private val beforeMutation: () -> Unit) {
    fun execute(operation: String, p: JSONObject): JSONObject {
        checkActive()
        when (operation) {
            "calendars" -> return backend.calendars(p).put("deviceTimeMs", System.currentTimeMillis()).put("deviceTimeZone", ZoneId.systemDefault().id).put("capabilities", JSONObject().put("provider", "CalendarContract").put("scopes", JSONArray().put("series")).put("operations", JSONArray(listOf("calendars", "list", "details", "create", "update", "delete"))))
            "list" -> { require(p.getLong("endMs") > p.getLong("startMs")) { "需要有效查询时间区间" }; return backend.instances(p) }
            "details" -> {
                val event = details(p.getLong("eventId"))
                if (p.has("calendarId")) require(event.getLong("calendarId") == p.getLong("calendarId")) { "事件不属于指定日历" }
                return JSONObject().put("event", event)
            }
        }
        require(operation in setOf("create", "update", "delete")) { "不支持的日程操作" }
        if (operation != "create") require(p.has("scope") && p.getString("scope") == "series") { "更新或删除需明确scope=series，仅支持整个事件或重复系列" }
        val id = if (operation == "create") null else p.getLong("eventId").also { require(it > 0) }
        val old = id?.let { checkNotNull(backend.event(it)) { "事件不存在" } }
        if (old != null && p.has("calendarId")) require(old.getLong("calendarId") == p.getLong("calendarId")) { "事件不属于指定日历" }
        require(old == null || old.isNull("originalId")) { "这是单次例外事件；首轮仅支持完整事件或系列" }
        val values = if (operation == "delete") emptyMap() else CalendarValues.build(p, old)
        val reminders = if (operation == "create") CalendarValues.reminders(p) ?: emptyList() else CalendarValues.reminders(p)
        checkActive()
        val eventId = backend.apply(id, values, reminders, operation == "delete", beforeMutation)
        val result = JSONObject().put("eventId", eventId).put("scope", "series").put("dispatched", true)
        try {
            checkActive()
            if (operation == "delete") {
                val after = backend.event(eventId)
                result.put("deleted", after == null || after.optBoolean("deleted"))
                check(result.getBoolean("deleted")) { "删除后仍可读取事件" }
            } else result.put("event", details(eventId))
            result.put("verification", "observed")
        } catch (error: Exception) { result.put("verification", "failed").put("verificationError", error.message ?: "回读失败") }
        return result
    }
    private fun details(id: Long): JSONObject {
        require(id > 0)
        return checkNotNull(backend.event(id)) { "事件不存在" }.put("reminderMinutes", backend.reminders(id))
    }
}
