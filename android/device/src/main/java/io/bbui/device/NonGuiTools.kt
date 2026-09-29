package io.bbui.device

import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.ContactsContract
import org.json.JSONArray
import org.json.JSONObject

/** Structured current-user providers. No SQL, authority, projection or commands supplied by callers. */
internal class NonGuiTools(private val context: Context, private val user: Int, private val active: () -> Unit,
    private val beforeMutation: () -> Unit, private val command: (List<String>) -> String) {
    fun execute(group: String, op: String, p: JSONObject): JSONObject {
        active()
        if (group == "clock") return clock(op, p)
        val authority = when (group) { "contacts" -> "com.android.contacts"; "sms" -> "sms"; "call_log" -> "call_log"; "media" -> "media"; else -> error("不支持的系统能力") }
        return ShellProvider.open(context, user, authority).use { provider ->
            val access = Access(provider, active)
            when (group) { "contacts" -> contacts(access, op, p); else -> information(access, group, op, p) }
        }
    }
    private class Access(val provider: ShellProvider, val active: () -> Unit) {
        fun query(uri: Uri, columns: Map<String, String>, p: JSONObject = JSONObject(), selection: String? = null,
            args: List<String> = emptyList(), sort: String = "_id ASC", budgetChars: Int = 48000, rowMapper: ((JSONObject) -> Unit)? = null): JSONObject {
            active()
            val offset = p.optInt("offset", 0).also { require(it >= 0) }; val limit = p.optInt("limit", 50).also { require(it in 1..100) }
            val items = JSONArray(); var chars = 0; var bytes = 0
            val cursor = checkNotNull(provider.resolver.query(uri, columns.keys.toTypedArray(), selection, args.toTypedArray(), sort)) { "Provider 未返回结果" }
            cursor.use {
                var skipped = 0
                while (it.moveToNext()) {
                    active(); if (skipped++ < offset) continue
                    if (items.length() == limit) return JSONObject().put("items", items).put("nextOffset", offset + items.length())
                    val row = JSONObject()
                    columns.entries.forEachIndexed { i, entry ->
                        if (it.isNull(i)) row.put(entry.value, JSONObject.NULL)
                        else if (it.getType(i) == Cursor.FIELD_TYPE_INTEGER) row.put(entry.value, it.getLong(i))
                        else {
                            val text = it.getString(i); val bound = if (entry.value == "snippet") 160 else 2000
                            val count = text.codePointCount(0, text.length)
                            row.put(entry.value, text.substring(0, text.offsetByCodePoints(0, minOf(count, bound))))
                            if (count > bound) row.put(entry.value + "Truncated", true)
                        }
                    }
                    rowMapper?.invoke(row)
                    val encoded = row.toString(); val size = encoded.toByteArray(Charsets.UTF_8).size
                    if (chars + encoded.length > budgetChars || bytes + size > budgetChars * 2) {
                        check(items.length() > 0) { "单项结果超过传输上限" }
                        return JSONObject().put("items", items).put("nextOffset", offset + items.length())
                    }
                    items.put(row); chars += encoded.length; bytes += size
                }
            }
            return JSONObject().put("items", items).put("nextOffset", JSONObject.NULL)
        }
        fun one(uri: Uri, columns: Map<String, String>, selection: String? = null, args: List<String> = emptyList()): JSONObject =
            query(uri, columns, JSONObject().put("limit", 1), selection, args).getJSONArray("items").optJSONObject(0) ?: error("目标不存在或当前用户无权访问")
    }
    private fun contacts(a: Access, op: String, p: JSONObject): JSONObject {
        val contacts = ContactsContract.Contacts.CONTENT_URI; val raw = ContactsContract.RawContacts.CONTENT_URI; val data = ContactsContract.Data.CONTENT_URI
        val contactColumns = linkedMapOf("_id" to "contactId", "display_name" to "name", "lookup" to "lookupKey", "has_phone_number" to "hasPhoneNumber")
        fun detail(id: Long): JSONObject {
            val contact = a.one(ContentUris.withAppendedId(contacts, id), contactColumns)
            val rows = a.query(raw, linkedMapOf("_id" to "rawContactId", "contact_id" to "contactId", "account_type" to "accountType", "account_name" to "accountName"),
                JSONObject().put("offset", p.optInt("rawOffset", 0)).put("limit", p.optInt("rawLimit", 50)),
                selection = "contact_id=? AND deleted=0", args = listOf(id.toString()), budgetChars = 19000)
            contact.put("rawContacts", rows.getJSONArray("items")).put("rawContactsNextOffset", rows.get("nextOffset"))
            val fields = a.query(data, linkedMapOf("_id" to "dataId", "raw_contact_id" to "rawContactId", "mimetype" to "mimeType", "data1" to "value"),
                p, "contact_id=? AND mimetype IN (?,?,?)", listOf(id.toString(), ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE, ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE), budgetChars = 19000)
            return JSONObject().put("contact", contact).put("items", fields.getJSONArray("items")).put("nextOffset", fields.get("nextOffset"))
        }
        if (op == "list") {
            val query = if (p.has("query")) NonGuiValues.text(p, "query", 2000) else ""
            val uri = if (query.isEmpty()) contacts else ContactsContract.Contacts.CONTENT_FILTER_URI.buildUpon().appendPath(query).build()
            return a.query(uri, contactColumns, p)
        }
        if (op == "details") return detail(NonGuiValues.id(p, "contactId"))
        require(op == "create" || op == "update") { "不支持的联系人操作" }
        val name = if (p.has("name")) NonGuiValues.text(p, "name", 2000) else null
        if (op == "create") require(!name.isNullOrBlank()) { "新联系人需要 name" }
        val phones = NonGuiValues.strings(p, "phones"); val emails = NonGuiValues.strings(p, "emails")
        require(name != null || phones != null || emails != null) { "未提供需要修改的字段" }
        var rawId: Long? = null
        if (op == "update") {
            rawId = NonGuiValues.id(p, "rawContactId")
            val current = a.one(ContentUris.withAppendedId(raw, rawId), linkedMapOf("_id" to "rawContactId", "contact_id" to "contactId", "deleted" to "deleted"))
            require(current.optInt("deleted") == 0) { "联系人已删除" }
            if (p.has("contactId")) require(current.getLong("contactId") == NonGuiValues.id(p, "contactId")) { "rawContactId 不属于指定 contactId" }
        }
        val operations = arrayListOf<ContentProviderOperation>()
        if (rawId == null) operations += ContentProviderOperation.newInsert(raw).withValue("account_name", null).withValue("account_type", null).build()
        fun replace(mime: String, values: List<String>?) {
            if (values == null) return
            if (rawId != null) operations += ContentProviderOperation.newDelete(data).withSelection("raw_contact_id=? AND mimetype=?", arrayOf(rawId.toString(), mime)).build()
            values.forEach { value ->
                val builder = ContentProviderOperation.newInsert(data).withValue("mimetype", mime).withValue("data1", value)
                if (rawId == null) builder.withValueBackReference("raw_contact_id", 0) else builder.withValue("raw_contact_id", rawId)
                operations += builder.build()
            }
        }
        replace(ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE, name?.let { listOf(it) })
        replace(ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE, phones)
        replace(ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE, emails)
        active(); beforeMutation()
        val results = try { a.provider.resolver.applyBatch(ContactsContract.AUTHORITY, operations) }
            catch (error: android.content.OperationApplicationException) { throw SystemRejected(error.message ?: "联系人 Provider 拒绝批次") }
            catch (error: SecurityException) { throw SystemRejected(error.message ?: "联系人 Provider 拒绝权限") }
            catch (error: IllegalArgumentException) { throw SystemRejected(error.message ?: "联系人 Provider 拒绝参数") }
        return NonGuiValues.afterDispatch(if (op == "create") "local_unsynced" else "existing_raw_contact") { result ->
            if (rawId != null) result.put("rawContactId", rawId)
            val id = rawId ?: ContentUris.parseId(checkNotNull(results.firstOrNull()?.uri) { "批次已提交，但 Provider 未返回新建联系人编号，禁止盲目重试" })
            require(id > 0) { "批次已提交，但 Provider 返回无效联系人编号" }
            result.put("rawContactId", id)
            val current = a.one(ContentUris.withAppendedId(raw, id), linkedMapOf("_id" to "rawContactId", "contact_id" to "contactId"))
            if (!current.isNull("contactId")) result.put("contactId", current.getLong("contactId"))
            fun verify(mime: String, expected: List<String>?) {
                if (expected == null) return
                val page = a.query(data, linkedMapOf("data1" to "value"), JSONObject().put("limit", 100),
                    "raw_contact_id=? AND mimetype=?", listOf(id.toString(), mime))
                check(page.isNull("nextOffset")) { "批次已提交，Provider 回读记录超出范围，无法完整核验" }
                val readback = page.getJSONArray("items")
                val actual = (0 until readback.length()).map { readback.getJSONObject(it).optString("value") }
                check(actual.sorted() == expected.sorted()) { "Provider 回读字段与请求不一致：$mime" }
            }
            verify(ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE, name?.let { listOf(it) })
            verify(ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE, phones)
            verify(ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE, emails)
        }
    }
    private fun information(a: Access, group: String, op: String, p: JSONObject): JSONObject {
        require(op == "list" || op == "details") { "此能力仅支持读取" }
        val selection = mutableListOf<String>(); val args = mutableListOf<String>()
        var uri: Uri
        val columns: LinkedHashMap<String, String>
        val idKey: String; val dateColumn: String
        when (group) {
            "sms" -> { uri = Uri.parse("content://sms"); idKey = "messageId"; dateColumn = "date"
                columns = linkedMapOf("_id" to idKey, "address" to "address", "date" to "dateMs", "type" to "type", "read" to "read", "body" to "snippet")
                if (p.has("address")) { selection += "address=?"; args += NonGuiValues.text(p, "address", 1000) }
                if (p.has("query")) { selection += "(address LIKE ? ESCAPE '\\' OR body LIKE ? ESCAPE '\\')"; val q = NonGuiValues.literal(NonGuiValues.text(p, "query", 2000)); args += q; args += q }
            }
            "call_log" -> { uri = Uri.parse("content://call_log/calls"); idKey = "callId"; dateColumn = "date"
                columns = linkedMapOf("_id" to idKey, "number" to "number", "date" to "dateMs", "duration" to "durationSeconds", "type" to "type", "name" to "cachedName")
                if (p.has("number")) { selection += "number=?"; args += NonGuiValues.text(p, "number", 1000) }
                if (p.has("type")) { val type = p.getInt("type"); require(type in 1..7); selection += "type=?"; args += type.toString() }
            }
            else -> { val kind = p.optString("kind"); require(op != "details" || kind.isNotEmpty()) { "读取媒体详情需要 kind" }
                val path = when (kind) { "image" -> "images/media"; "video" -> "video/media"; "audio" -> "audio/media"; "" -> "file"; else -> error("kind 只支持 image/video/audio") }
                uri = Uri.parse("content://media/external/$path"); idKey = "mediaId"; dateColumn = "date_added"
                columns = linkedMapOf("_id" to idKey, "_display_name" to "name", "mime_type" to "mimeType", "_size" to "sizeBytes", "date_added" to "dateAddedSeconds", "_data" to "path")
                if (kind.isEmpty()) { selection += "media_type IN (1,2,3)"; columns["media_type"] = "mediaType" }
                if (kind in setOf("image", "video")) { columns["width"] = "width"; columns["height"] = "height" }
                if (kind in setOf("video", "audio")) columns["duration"] = "durationMs"
                if (p.has("query")) { selection += "_display_name LIKE ? ESCAPE '\\'"; args += NonGuiValues.literal(NonGuiValues.text(p, "query", 2000)) }
            }
        }
        if (op == "details") { selection += "_id=?"; args += NonGuiValues.id(p, idKey).toString() }
        for ((key, comparison) in listOf("startMs" to ">=", "endMs" to "<")) if (p.has(key)) {
            val ms = p.getLong(key).also { require(it >= 0) }; selection += "$dateColumn $comparison ?"
            args += (if (group == "media") ms / 1000 + if (ms % 1000 > 0) 1 else 0 else ms).toString()
        }
        if (p.has("startMs") && p.has("endMs")) require(p.getLong("endMs") > p.getLong("startMs"))
        val page = a.query(uri, columns, if (op == "details") JSONObject().put("limit", 1) else p,
            selection.takeIf { it.isNotEmpty() }?.joinToString(" AND "), args, "$dateColumn DESC, _id DESC") { row ->
            if (group == "media") {
                val kind = p.optString("kind").ifEmpty { when (row.optInt("mediaType")) { 1 -> "image"; 2 -> "audio"; 3 -> "video"; else -> "unknown" } }
                val path = when (kind) { "image" -> "images/media"; "audio" -> "audio/media"; "video" -> "video/media"; else -> "file" }
                row.put("kind", kind).put("uri", ContentUris.withAppendedId(Uri.parse("content://media/external/$path"), row.getLong(idKey)).toString())
                    .put("dateAddedMs", if (row.isNull("dateAddedSeconds")) JSONObject.NULL else row.getLong("dateAddedSeconds") * 1000)
            }
        }
        if (op == "list") return page
        val row = page.getJSONArray("items").optJSONObject(0) ?: error("目标不存在或当前用户无权访问")
        if (group == "sms") {
            active()
            val cursor = checkNotNull(a.provider.resolver.query(ContentUris.withAppendedId(uri, row.getLong(idKey)), arrayOf("body"), null, null, null))
            cursor.use { check(it.moveToFirst()) { "短信已不存在" }; val sliced = NonGuiValues.slice(it.getString(0) ?: "", p); sliced.keys().forEach { key -> row.put(key, sliced.get(key)) } }
            row.remove("snippet"); row.remove("snippetTruncated")
        }
        return JSONObject().put(when (group) { "sms" -> "message"; "call_log" -> "call"; else -> "media" }, row)
    }
    private fun clock(op: String, p: JSONObject): JSONObject {
        fun resolve(action: String): String? {
            active()
            val output = command(listOf("/system/bin/cmd", "package", "resolve-activity", "--brief", "--user", user.toString(), "-a", action))
            return output.lineSequence().map { it.trim() }.firstOrNull { Regex("[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+").matches(it) }
        }
        if (op == "capabilities") return JSONObject().put("alarmHandler", resolve(NonGuiValues.clockAction("create_alarm")) ?: JSONObject.NULL)
            .put("timerHandler", resolve(NonGuiValues.clockAction("create_timer")) ?: JSONObject.NULL).put("skipUiGuaranteed", false)
        val action = NonGuiValues.clockAction(op); val extras = NonGuiValues.clockArgs(op, p)
        val handler = resolve(action) ?: error("此设备没有支持此标准时钟操作的处理应用")
        active(); beforeMutation()
        val output = command(listOf("/system/bin/am", "start", "--user", user.toString(), "-a", action, "-n", handler) + extras)
        if (output.contains("Error:", true) || output.contains("Exception", true) || output.contains("Permission Denial", true)) throw SystemRejected(output.take(1500))
        return JSONObject().put("dispatched", true).put("handler", handler).put("action", action).put("skipUiRequested", true).put("skipUiGuaranteed", false)
            .put("verification", "unverified").put("creationVerified", false).put("output", output.take(1500))
    }
}
