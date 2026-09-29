package io.bbui.assistant

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import org.json.JSONArray
import java.util.UUID
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Configuration never enters Android backup or plaintext app preferences. */
class SettingsStore(private val context: Context) {
    private val file = File(context.noBackupFilesDir, "model.enc")
    private val searchFile = File(context.noBackupFilesDir, "search.enc")
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
            generateKey()
        }
    }
    private fun read(source: File = file): JSONObject {
        if (!source.exists()) return JSONObject()
        val stored = JSONObject(source.readText())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(stored.getString("iv"), Base64.NO_WRAP)))
        return JSONObject(String(cipher.doFinal(Base64.decode(stored.getString("data"), Base64.NO_WRAP)), Charsets.UTF_8))
    }
    private fun write(config: JSONObject, destination: File = file) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val value = JSONObject()
            .put("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .put("data", Base64.encodeToString(cipher.doFinal(config.toString().toByteArray(Charsets.UTF_8)), Base64.NO_WRAP))
        val temporary = File(destination.parentFile, "${destination.name}.tmp")
        temporary.writeText(value.toString())
        check(temporary.renameTo(destination)) { "无法保存配置" }
    }
    /** Native settings only: never add this credential-bearing value to a chat snapshot. */
    fun searchSettings(): JSONObject = synchronized(LOCK) {
        read(searchFile).apply {
            if (!has("provider")) put("provider", "bocha")
            if (!has("keys")) put("keys", JSONObject())
        }
    }
    fun saveSearchSettings(value: JSONObject) = synchronized(LOCK) {
        require(value.optString("provider") in setOf("bocha", "baidu")) { "请选择搜索供应商" }
        val keys = value.optJSONObject("keys") ?: JSONObject()
        val clean = JSONObject()
        for (provider in listOf("bocha", "baidu")) clean.put(provider, keys.optString(provider).trim())
        write(JSONObject().put("provider", value.getString("provider")).put("keys", clean), searchFile)
    }
    fun toolsConfig(): JSONObject = synchronized(LOCK) {
        val value = searchSettings()
        JSONObject().put("search", JSONObject().put("provider", value.getString("provider"))
            .put("apiKey", value.getJSONObject("keys").optString(value.getString("provider"))))
    }
    fun load(): JSONObject = synchronized(LOCK) {
        val data = read()
        val registry = data.optJSONObject("registry") ?: return@synchronized data
        val selection = registry.optJSONObject("defaultSelection") ?: return@synchronized JSONObject()
        runCatching { resolveFrom(registry, bindingFrom(registry, selection)) }.getOrElse { JSONObject() }
    }
    /** Legacy writers update the default connection without losing other saved connections. */
    fun save(config: JSONObject) = synchronized(LOCK) {
        val data = read()
        val existing = data.optJSONObject("registry")
        if (existing == null) write(config) else {
            val selected = existing.optJSONObject("defaultSelection") ?: JSONObject()
            val model = JSONObject().put("id", config.optString("model")).put("name", config.optString("model"))
            for (key in listOf("input", "reasoning", "contextWindow", "maxTokens", "thinkingLevels", "thinkingMode")) if (config.has(key)) model.put(key, config.get(key))
            val connection = JSONObject(config.toString()).put("id", selected.optString("connectionId"))
                .put("name", config.optString("name").ifBlank { config.optString("provider").ifBlank { "模型连接" } }).put("api", config.optString("api", "openai-completions"))
                .put("models", JSONArray().put(model))
            val savedId = saveConnection(connection)
            setDefaultSelection(savedId, config.optString("model"))
        }
    }
    fun registry(): JSONObject = synchronized(LOCK) {
        val data = read()
        data.optJSONObject("registry")?.let { return@synchronized JSONObject(it.toString()) }
        val registry = JSONObject().put("connections", JSONArray()).put("versions", JSONObject()).put("defaultSelection", JSONObject())
        if (data.optString("model").isNotBlank()) {
            val id = UUID.randomUUID().toString()
            val model = JSONObject().put("id", data.getString("model")).put("name", data.getString("model"))
            for (key in listOf("input", "reasoning", "contextWindow", "maxTokens", "thinkingLevels", "thinkingMode")) if (data.has(key)) model.put(key, data.get(key))
            val connection = JSONObject(data.toString()).put("id", id).put("name", data.optString("provider", "已保存连接"))
                .put("revision", 1).put("api", data.optString("api", "openai-completions")).put("models", JSONArray().put(model))
            registry.getJSONArray("connections").put(connection)
            registry.getJSONObject("versions").put("$id:1", JSONObject(connection.toString()))
            registry.put("defaultSelection", JSONObject().put("connectionId", id).put("modelId", model.getString("id")).put("thinkingLevel", data.optString("thinkingLevel")))
        }
        if (registry.getJSONArray("connections").length() > 0) data.remove("apiKey") // Migrated connection exclusively owns its credential.
        data.put("registry", registry); write(data)
        JSONObject(registry.toString())
    }
    fun saveConnection(connection: JSONObject): String = synchronized(LOCK) {
        val registry = registry()
        val copy = JSONObject(connection.toString())
        val id = copy.optString("id").ifBlank { UUID.randomUUID().toString() }
        val rows = registry.getJSONArray("connections")
        val index = (0 until rows.length()).firstOrNull { rows.getJSONObject(it).optString("id") == id }
        val previous = index?.let { rows.getJSONObject(it) }
        require(copy.optString("name").isNotBlank()) { "请填写连接名称" }
        require(copy.optString("api") in APIS) { "请选择支持的 API 协议" }
        val uri = java.net.URI(copy.optString("baseUrl"))
        require(uri.scheme in setOf("https", "http") && !uri.host.isNullOrBlank() && uri.userInfo == null) { "API 地址须为有效 HTTP(S) 地址" }
        val models = copy.optJSONArray("models") ?: JSONArray()
        require(models.length() > 0) { "至少添加一个模型" }
        val ids = mutableSetOf<String>()
        for (i in 0 until models.length()) require(models.getJSONObject(i).optString("id").isNotBlank() && ids.add(models.getJSONObject(i).getString("id"))) { "模型 ID 不能为空或重复" }
        for (i in 0 until models.length()) {
            val model = models.getJSONObject(i)
            for (field in listOf("contextWindow", "maxTokens")) if (model.has(field)) require(model.opt(field) is Number && model.optDouble(field) == model.optLong(field).toDouble() && model.optLong(field) > 0 && model.optLong(field) <= Int.MAX_VALUE) { "$field 必须为正整数" }
            if (model.has("reasoning")) require(model.opt("reasoning") is Boolean) { "思考能力设置无效" }
            if (model.has("thinkingLevels")) {
                val levels = model.getJSONArray("thinkingLevels")
                require((0 until levels.length()).all { levels.getJSONObject(it).optString("id") in ModelSettings.levels }) { "思考档位设置无效" }
            }
            if (model.has("input")) {
                val input = model.getJSONArray("input")
                require(input.length() > 0 && (0 until input.length()).all { input.optString(it) in setOf("text", "image") }) { "无效的模型输入类型" }
            }
        }
        copy.put("id", id).put("provider", copy.optString("provider").ifBlank { "bbui-$id" }).put("revision", (previous?.optInt("revision") ?: 0) + 1)
        if (index == null) rows.put(copy) else rows.put(index, copy)
        registry.getJSONObject("versions").put("$id:${copy.getInt("revision")}", JSONObject(copy.toString()))
        if (registry.getJSONObject("defaultSelection").optString("connectionId").isBlank()) registry.put("defaultSelection", JSONObject().put("connectionId", id).put("modelId", models.getJSONObject(0).getString("id")).put("thinkingLevel", ""))
        write(read().put("registry", registry)); id
    }
    fun deleteConnection(id: String) = synchronized(LOCK) {
        val registry = registry(); val rows = registry.getJSONArray("connections")
        for (i in rows.length() - 1 downTo 0) if (rows.getJSONObject(i).getString("id") == id) rows.remove(i)
        // Retained versions are deliberately unusable after deletion, then removed with credentials.
        val versions = registry.getJSONObject("versions")
        versions.keys().asSequence().filter { it.startsWith("$id:") }.toList().forEach { versions.remove(it) }
        if (registry.getJSONObject("defaultSelection").optString("connectionId") == id) registry.put("defaultSelection", JSONObject())
        write(read().put("registry", registry))
    }
    fun setDefaultSelection(connectionId: String, modelId: String) = synchronized(LOCK) {
        val registry = registry(); val selection = JSONObject().put("connectionId", connectionId).put("modelId", modelId).put("thinkingLevel", "")
        bindingFrom(registry, selection)
        write(read().put("registry", registry.put("defaultSelection", selection)))
    }
    fun modelOptions(): JSONArray {
        val rows = registry().getJSONArray("connections"); val output = JSONArray()
        for (i in 0 until rows.length()) {
            val connection = rows.getJSONObject(i); val models = connection.getJSONArray("models")
            for (j in 0 until models.length()) {
                val model = models.getJSONObject(j)
                output.put(JSONObject().put("connectionId", connection.getString("id")).put("connectionName", connection.getString("name"))
                    .put("modelId", model.getString("id")).put("modelName", model.optString("name").ifBlank { model.getString("id") })
                    .put("thinkingLevels", ModelSettings.thinkingLevels(model)).put("defaultThinkingLevel", ""))
            }
        }
        return output
    }
    fun binding(selection: JSONObject): JSONObject = synchronized(LOCK) { bindingFrom(registry(), selection) }
    private fun bindingFrom(registry: JSONObject, selection: JSONObject): JSONObject {
        val rows = registry.getJSONArray("connections")
        val connection = (0 until rows.length()).map { rows.getJSONObject(it) }.firstOrNull { it.getString("id") == selection.optString("connectionId") }
            ?: error("所选连接已删除或尚未配置，请重新选择模型")
        require((0 until connection.getJSONArray("models").length()).any { connection.getJSONArray("models").getJSONObject(it).getString("id") == selection.optString("modelId") }) { "所选模型已删除" }
        return JSONObject(selection.toString()).put("revision", connection.getInt("revision"))
    }
    fun resolve(binding: JSONObject): JSONObject = synchronized(LOCK) { resolveFrom(registry(), binding) }
    private fun resolveFrom(registry: JSONObject, binding: JSONObject): JSONObject {
        val id = binding.getString("connectionId")
        val rows = registry.getJSONArray("connections")
        require((0 until rows.length()).any { rows.getJSONObject(it).getString("id") == id }) { "任务绑定的模型连接已删除" }
        val connection = registry.getJSONObject("versions").optJSONObject("$id:${binding.getInt("revision")}") ?: error("任务绑定的配置版本不可用")
        val models = connection.getJSONArray("models")
        val model = (0 until models.length()).map { models.getJSONObject(it) }.firstOrNull { it.getString("id") == binding.getString("modelId") } ?: error("任务绑定的模型不可用")
        return JSONObject(connection.toString()).apply {
            for (key in listOf("input", "reasoning", "contextWindow", "maxTokens", "thinkingLevels", "thinkingMode")) remove(key)
            remove("models"); remove("registry"); put("model", model.getString("id")); put("thinkingLevel", binding.optString("thinkingLevel"))
            for (key in listOf("input", "reasoning", "contextWindow", "maxTokens", "thinkingLevels", "thinkingMode")) if (model.has(key)) put(key, model.get(key))
        }
    }
    companion object { private val LOCK = Any(); private val APIS = setOf("openai-completions", "openai-responses", "anthropic-messages"); private const val ALIAS = "bbui.model.config.v1" }
}
