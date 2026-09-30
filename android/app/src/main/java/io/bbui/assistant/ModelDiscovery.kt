package io.bbui.assistant

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.io.Closeable

data class DiscoveredModel(val id: String, val name: String, val settings: JSONObject = JSONObject()) {
    fun toJson(): JSONObject = JSONObject(settings.toString()).put("id", id).put("name", name)
}
class ModelDiscoveryFailure(message: String) : Exception(message)

/** Read-only provider catalogue, independent of the single Pi/phone execution lease. */
class ModelDiscovery : Closeable {
    @Volatile private var cancelled = false
    @Volatile private var active: HttpURLConnection? = null
    override fun close() { cancelled = true; active?.disconnect() }

    fun fetch(api: String, baseUrl: String, key: String): List<DiscoveredModel> {
        try {
            val endpoint = endpoint(api, baseUrl)
            val anthropic = api == "anthropic-messages"
            val found = linkedMapOf<String, DiscoveredModel>()
            val cursors = mutableSetOf<String>()
            val deadline = System.nanoTime() + 60_000_000_000L
            var after: String? = null
            do {
                if (cancelled || Thread.currentThread().isInterrupted) throw ModelDiscoveryFailure("获取已取消")
                val remaining = ((deadline - System.nanoTime()) / 1_000_000).toInt()
                if (remaining <= 0) throw ModelDiscoveryFailure("获取模型列表超时，请重试")
                val suffix = if (anthropic) "?limit=1000" + (after?.let { "&after_id=${URLEncoder.encode(it, "UTF-8")}" } ?: "") else ""
                val connection = URI(endpoint + suffix).toURL().openConnection() as HttpURLConnection
                active = connection
                val page = try {
                    if (cancelled) throw ModelDiscoveryFailure("获取已取消")
                    connection.instanceFollowRedirects = false
                    connection.connectTimeout = minOf(15000, remaining); connection.readTimeout = minOf(15000, remaining)
                    connection.requestMethod = "GET"; connection.setRequestProperty("Accept", "application/json")
                    if (anthropic) {
                        connection.setRequestProperty("x-api-key", key)
                        connection.setRequestProperty("anthropic-version", "2023-06-01")
                    } else if (key.isNotBlank()) connection.setRequestProperty("Authorization", "Bearer $key")
                    val status = connection.responseCode
                    if (status !in 200..299) throw ModelDiscoveryFailure(when (status) {
                        401, 403 -> "无法获取模型列表（HTTP $status），请检查 API Key 和权限"
                        404, 405 -> "此地址未提供模型列表接口，可手动添加模型"
                        in 300..399 -> "模型列表地址发生重定向，请直接填写目标 API 地址"
                        else -> "获取模型列表失败（HTTP $status），请稍后重试"
                    })
                    val bytes = connection.inputStream.use { input ->
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (output.size() <= 2 * 1024 * 1024) {
                            if (cancelled) throw ModelDiscoveryFailure("获取已取消")
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    }
                    if (bytes.size > 2 * 1024 * 1024) throw ModelDiscoveryFailure("模型列表响应过大")
                    JSONObject(String(bytes, Charsets.UTF_8))
                } finally { connection.disconnect(); active = null }
                val data = page.getJSONArray("data")
                for (index in 0 until data.length()) {
                    val model = data.getJSONObject(index)
                    val id = model.getString("id").trim()
                    if (id.isBlank()) throw ModelDiscoveryFailure("供应商返回了无效的模型 ID")
                    found.putIfAbsent(id, DiscoveredModel(id, model.optString("display_name").ifBlank { model.optString("name").ifBlank { id } }, ModelSettings.fromEndpoint(model, api, baseUrl)))
                }
                after = if (anthropic && page.optBoolean("has_more")) {
                    page.optString("last_id").takeIf { it.isNotBlank() && cursors.add(it) }
                        ?: throw ModelDiscoveryFailure("供应商返回了无效的分页游标")
                } else null
            } while (after != null)
            return found.values.sortedBy { it.id.lowercase() }
        } catch (error: ModelDiscoveryFailure) { throw error }
        catch (_: java.net.SocketTimeoutException) { throw ModelDiscoveryFailure("获取模型列表超时，请重试") }
        catch (_: Exception) { throw ModelDiscoveryFailure("无法读取模型列表，请检查网络、地址及 API 协议") }
    }

    companion object {
        internal fun endpoint(api: String, baseUrl: String): String {
            require(api in setOf("openai-completions", "openai-responses", "anthropic-messages"))
            val uri = URI(baseUrl.trim().trimEnd('/'))
            require(uri.scheme in setOf("https", "http") && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null)
            val base = uri.toASCIIString()
            return base + if (api == "anthropic-messages" && !uri.path.endsWith("/v1")) "/v1/models" else "/models"
        }
    }
}
