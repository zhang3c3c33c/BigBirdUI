package io.bbui.device

import org.json.JSONArray
import org.json.JSONObject

internal object NonGuiValues {
    fun text(p: JSONObject, key: String, max: Int): String = p.getString(key).also {
        require(it.codePointCount(0, it.length) <= max) { "$key 超过 $max 字符" }
        require(!it.contains('\u0000')) { "$key 不能包含 NUL" }
    }
    fun strings(p: JSONObject, key: String): List<String>? {
        if (!p.has(key)) return null
        val values = p.getJSONArray(key); require(values.length() <= 20) { "$key 最多 20 项" }
        return (0 until values.length()).map { text(JSONObject().put("value", values.getString(it)), "value", 320) }
    }
    fun id(p: JSONObject, key: String): Long = p.getLong(key).also { require(it > 0) { "$key 必须大于零" } }
    fun literal(text: String): String = "%" + text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
    fun slice(text: String, p: JSONObject): JSONObject {
        val start = p.optInt("textOffset", 0).also { require(it >= 0) }.coerceAtMost(text.length)
        require(start == text.length || !Character.isLowSurrogate(text[start])) { "textOffset 不能位于 Emoji 中间" }
        val limit = p.optInt("textLimit", 4000).also { require(it in 1..16384) }
        var end = minOf(text.length, start + limit)
        if (end < text.length && end > start && Character.isHighSurrogate(text[end - 1])) end--
        require(end > start || start == text.length) { "textLimit 太小，无法容纳下一个字符" }
        return JSONObject().put("body", text.substring(start, end)).put("textOffset", start).put("textNextOffset", if (end < text.length) end else JSONObject.NULL)
            .put("totalChars", text.length).put("offsetUnit", "UTF-16 characters")
    }
    fun afterDispatch(storage: String, verify: (JSONObject) -> Unit): JSONObject {
        val result = JSONObject().put("dispatched", true).put("storage", storage)
        try { verify(result); result.put("verification", "observed") }
        catch (error: Throwable) { result.put("verification", "failed").put("verificationError", (error.message ?: "回读失败").take(1000)) }
        return result
    }
    fun clockAction(op: String): String = when (op) { "create_alarm" -> "android.intent.action.SET_ALARM"; "create_timer" -> "android.intent.action.SET_TIMER"; else -> error("不支持的时钟操作") }
    fun clockArgs(op: String, p: JSONObject): List<String> {
        val args = mutableListOf("--ez", "android.intent.extra.alarm.SKIP_UI", "true")
        if (p.has("label")) args += listOf("--es", "android.intent.extra.alarm.MESSAGE", text(p, "label", 2000))
        if (op == "create_alarm") {
            val hour = p.getInt("hour"); val minute = p.getInt("minute"); require(hour in 0..23 && minute in 0..59)
            args += listOf("--ei", "android.intent.extra.alarm.HOUR", hour.toString(), "--ei", "android.intent.extra.alarm.MINUTES", minute.toString())
            if (p.has("vibrate")) args += listOf("--ez", "android.intent.extra.alarm.VIBRATE", p.getBoolean("vibrate").toString())
            if (p.has("days")) {
                val days = p.getJSONArray("days"); require(days.length() <= 7)
                val values = (0 until days.length()).map { days.getInt(it).also { day -> require(day in 1..7) } }.distinct()
                if (values.isNotEmpty()) args += listOf("--eial", "android.intent.extra.alarm.DAYS", values.joinToString(","))
            }
        } else {
            require(op == "create_timer"); val seconds = p.getInt("seconds"); require(seconds in 1..86400)
            args += listOf("--ei", "android.intent.extra.alarm.LENGTH", seconds.toString())
        }
        return args
    }
}
