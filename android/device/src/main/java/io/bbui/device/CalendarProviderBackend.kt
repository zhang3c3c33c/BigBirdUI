package io.bbui.device

import android.content.ContentProviderClient
import android.content.ContentProviderOperation
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Binder
import android.os.IBinder
import android.os.Process
import android.provider.CalendarContract
import org.json.JSONArray
import org.json.JSONObject

/** Shell attribution with explicit current-user provider acquisition; no activity or virtual display. */
internal class CalendarProviderBackend private constructor(
    private val client: ContentProviderClient,
    private val release: () -> Unit,
    private val checkActive: () -> Unit
) : CalendarBackend, AutoCloseable {
    private val resolver = ContentResolver.wrap(client)
    override fun close() { runCatching(release) }
    companion object {
        fun open(context: Context, user: Int, checkActive: () -> Unit): CalendarProviderBackend {
            val connection = ShellProvider.open(context, user, CalendarContract.AUTHORITY)
            return CalendarProviderBackend(connection.client, { connection.close() }, checkActive)
        }
    }
    private fun query(uri: Uri, columns: Map<String, String>, selection: String? = null, args: Array<String>? = null,
        sort: String = "_id ASC", p: JSONObject = JSONObject()): JSONObject {
        checkActive()
        val offset = p.optInt("offset", 0).also { require(it >= 0) }
        val limit = p.optInt("limit", 50).coerceIn(1, 100)
        val items = JSONArray()
        var pageChars = 0
        var pageBytes = 0
        val cursor = checkNotNull(resolver.query(uri, columns.keys.toTypedArray(), selection, args, sort)) { "Calendar Provider未返回结果" }
        cursor.use {
            var index = 0
            while (it.moveToNext()) {
                checkActive()
                if (index++ < offset) continue
                if (items.length() == limit) return JSONObject().put("items", items).put("nextOffset", offset + items.length())
                val row = JSONObject()
                columns.entries.forEachIndexed { i, entry ->
                    if (it.isNull(i)) row.put(entry.value, JSONObject.NULL)
                    else when (it.getType(i)) {
                        android.database.Cursor.FIELD_TYPE_INTEGER -> row.put(entry.value, it.getLong(i))
                        else -> {
                            val text = it.getString(i)
                            val bound = if (entry.value == "description") 12000 else 4000
                            val codePoints = text.codePointCount(0, text.length)
                            row.put(entry.value, text.substring(0, text.offsetByCodePoints(0, minOf(codePoints, bound))))
                            if (codePoints > bound) row.put(entry.value + "Truncated", true)
                        }
                    }
                }
                val encoded = row.toString()
                val bytes = encoded.toByteArray(Charsets.UTF_8).size
                if (pageChars + encoded.length > 48000 || pageBytes + bytes > 96000) {
                    check(items.length() > 0) { "单项日程记录超过传输限制" }
                    return JSONObject().put("items", items).put("nextOffset", offset + items.length())
                }
                items.put(row); pageChars += encoded.length; pageBytes += bytes
            }
        }
        return JSONObject().put("items", items).put("nextOffset", JSONObject.NULL)
    }
    override fun calendars(p: JSONObject): JSONObject {
        val page = query(CalendarContract.Calendars.CONTENT_URI, linkedMapOf("_id" to "id", "calendar_displayName" to "name",
            "account_type" to "accountType", "calendar_access_level" to "accessLevel", "maxReminders" to "maxReminders", "allowedReminders" to "allowedReminders"), p = p)
        val items = page.getJSONArray("items")
        for (i in 0 until items.length()) items.getJSONObject(i).apply { put("writable", getInt("accessLevel") >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR) }
        return page
    }
    override fun instances(p: JSONObject): JSONObject {
        val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(builder, p.getLong("startMs")); ContentUris.appendId(builder, p.getLong("endMs"))
        val selection = mutableListOf("deleted=0"); val args = mutableListOf<String>()
        if (p.has("calendarId")) { selection += "calendar_id=?"; args += p.getLong("calendarId").also { require(it > 0) }.toString() }
        val text = p.optString("query")
        if (text.isNotEmpty()) {
            require(text.codePointCount(0, text.length) <= 2000)
            selection += "(title LIKE ? ESCAPE '\\' OR description LIKE ? ESCAPE '\\' OR eventLocation LIKE ? ESCAPE '\\')"
            val escaped = "%" + text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
            repeat(3) { args += escaped }
        }
        val page = query(builder.build(), linkedMapOf("_id" to "id", "event_id" to "eventId", "calendar_id" to "calendarId", "title" to "title",
            "eventLocation" to "location", "begin" to "beginMs", "end" to "endMs", "allDay" to "allDay", "eventTimezone" to "timeZone"),
            selection.joinToString(" AND "), args.toTypedArray(), "begin ASC, event_id ASC, _id ASC", p)
        val rows = page.getJSONArray("items"); for (i in 0 until rows.length()) rows.getJSONObject(i).apply { put("allDay", optInt("allDay") != 0) }
        return page
    }
    override fun event(id: Long): JSONObject? {
        val page = query(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id), linkedMapOf("_id" to "id", "calendar_id" to "calendarId",
            "title" to "title", "description" to "description", "eventLocation" to "location", "dtstart" to "startMs", "dtend" to "endMs",
            "duration" to "duration", "eventTimezone" to "timeZone", "allDay" to "allDay", "rrule" to "rrule", "rdate" to "rdate", "exdate" to "exdate", "exrule" to "exrule", "original_id" to "originalId", "deleted" to "deleted"), p = JSONObject().put("limit", 1))
        return page.getJSONArray("items").optJSONObject(0)?.apply { put("allDay", optInt("allDay") != 0); put("deleted", optInt("deleted") != 0) }
    }
    override fun reminders(id: Long): JSONArray {
        val rows = query(CalendarContract.Reminders.CONTENT_URI, linkedMapOf("minutes" to "minutes"), "event_id=?", arrayOf(id.toString()), "minutes ASC, _id ASC", JSONObject().put("limit", 100)).getJSONArray("items")
        return JSONArray().apply { for (i in 0 until rows.length()) put(rows.getJSONObject(i).getInt("minutes")) }
    }
    override fun apply(id: Long?, values: Map<String, Any?>, reminders: List<Int>?, delete: Boolean, beforeDispatch: () -> Unit): Long {
        checkActive()
        val calendarId = (values["calendar_id"] as? Long) ?: checkNotNull(id?.let(::event)) { "事件不存在" }.getLong("calendarId")
        val calendar = query(ContentUris.withAppendedId(CalendarContract.Calendars.CONTENT_URI, calendarId), linkedMapOf(
            "calendar_access_level" to "accessLevel", "maxReminders" to "maxReminders", "allowedReminders" to "allowedReminders"), p = JSONObject().put("limit", 1))
            .getJSONArray("items").optJSONObject(0) ?: error("指定日历不存在")
        require(calendar.getInt("accessLevel") >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR) { "指定日历不可写" }
        var reminderMethod = CalendarContract.Reminders.METHOD_DEFAULT
        if (!delete) {
            val count = reminders?.size ?: if (id == null) 0 else this.reminders(id).length()
            reminderMethod = CalendarValues.reminderMethod(calendar, count, !reminders.isNullOrEmpty())
        }
        val operations = arrayListOf<ContentProviderOperation>()
        val eventUri = if (id == null) CalendarContract.Events.CONTENT_URI else ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id)
        val event = when { delete -> ContentProviderOperation.newDelete(eventUri); id == null -> ContentProviderOperation.newInsert(eventUri); else -> ContentProviderOperation.newUpdate(eventUri) }
        if (!delete) event.withValues(ContentValues().apply { for ((key, value) in values) when (value) {
            null -> putNull(key); is String -> put(key, value); is Long -> put(key, value); is Int -> put(key, value); else -> error("不支持的日程字段类型")
        } })
        if (id != null) event.withExpectedCount(1)
        operations += event.build()
        if (!delete && reminders != null) {
            if (id != null) operations += ContentProviderOperation.newDelete(CalendarContract.Reminders.CONTENT_URI).withSelection("event_id=?", arrayOf(id.toString())).build()
            for (minutes in reminders) {
                val reminder = ContentProviderOperation.newInsert(CalendarContract.Reminders.CONTENT_URI).withValue("minutes", minutes).withValue("method", reminderMethod)
                if (id == null) reminder.withValueBackReference("event_id", 0) else reminder.withValue("event_id", id)
                operations += reminder.build()
            }
        }
        checkActive()
        beforeDispatch()
        val results = try { resolver.applyBatch(CalendarContract.AUTHORITY, operations) }
        catch (error: android.content.OperationApplicationException) { throw SystemRejected("Calendar Provider拒绝本次批量操作：${error.message}") }
        catch (error: SecurityException) { throw SystemRejected("Calendar Provider拒绝权限：${error.message}") }
        catch (error: IllegalArgumentException) { throw SystemRejected("Calendar Provider拒绝参数：${error.message}") }
        return id ?: ContentUris.parseId(checkNotNull(results.first().uri) { "Provider未返回新事件编号" })
    }
}
