package io.bbui.assistant

import android.content.*
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.text.*
import android.view.*
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModelProvider
import androidx.preference.*
import io.bbui.device.DevicePermission
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/** Credentials are retained only in memory across rotation, never view state or Preferences. */
class SettingsDraft : ViewModel() {
    var connection: JSONObject? = null
    var original = ""
    var probing = false
    var probeResult = ""
    val probeChanged = MutableLiveData(Unit)
    var discovering = false
    var discoveryError = ""
    var discovered = emptyList<DiscoveredModel>()
    var modelSearch = ""
    val pickedModels = linkedSetOf<String>()
    val discoveryChanged = MutableLiveData(Unit)
    var search: JSONObject? = null
    var searchOriginal = ""
    var memoryContent: String? = null
    var memoryOriginal = ""
    var memoryRevision = ""
    var memoryBusy = false
    var memoryError = ""
    val toolsChanged = MutableLiveData(Unit)
    private val discoveryWorker = Executors.newSingleThreadExecutor()
    private var discovery: ModelDiscovery? = null
    private var discoveryGeneration = 0
    private val main = Handler(Looper.getMainLooper())
    fun cancelDiscovery() {
        discoveryGeneration++; discovery?.close(); discovery = null; discovering = false
    }
    fun fetchModels() {
        val source = connection ?: return
        cancelDiscovery()
        val generation = discoveryGeneration
        val api = source.optString("api"); val url = source.optString("baseUrl"); val key = source.optString("apiKey")
        discovering = true; discoveryError = ""; discovered = emptyList(); modelSearch = ""; pickedModels.clear()
        val request = ModelDiscovery().also { discovery = it }
        discoveryChanged.value = Unit
        discoveryWorker.execute {
            val result = runCatching { request.fetch(api, url, key) }
            main.post {
                if (generation != discoveryGeneration || source !== connection) return@post
                discovering = false; discovery = null
                if (source.optString("api") != api || source.optString("baseUrl") != url || source.optString("apiKey") != key) {
                    discoveryError = "连接已修改，请重新获取模型列表"
                } else result.fold({ discovered = it }, { discoveryError = it.message ?: "获取失败" })
                discoveryChanged.value = Unit
            }
        }
    }
    override fun onCleared() { cancelDiscovery(); discoveryWorker.shutdownNow(); connection?.remove("apiKey"); original = ""; search = null; searchOriginal = ""; memoryContent = null; memoryOriginal = "" }
}

class SettingsActivity : AppCompatActivity() {
    private lateinit var titleView: TextView
    private lateinit var body: FrameLayout
    private lateinit var draft: SettingsDraft
    private var page = "home"
    private var modelIndex = -1
    private var service: AssistantService? = null
    private var bound = false
    private var pendingChanges = false
    private var probeButton: Button? = null
    private var probeResultView: TextView? = null
    private var permissionWasReady = false
    private val permissionMonitor = ShizukuMonitor({ availability ->
        if (availability == ShizukuAvailability.READY) permissionWasReady = true
        else if (permissionWasReady || !intent.getBooleanExtra("fromOnboarding", false)) {
            draft.cancelDiscovery()
            if (!isFinishing && !isDestroyed) {
                startActivity(Intent(this, OnboardingActivity::class.java).putExtra("launchMain", true).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
                finish()
            }
        }
        if (!isFinishing && !isDestroyed && page == "permissions" && ::body.isInitialized && !supportFragmentManager.isStateSaved) render()
    })
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = (binder as AssistantService.LocalBinder).service
            if (pendingChanges) changed()
            if (page == "memory" && draft.memoryContent == null && !draft.memoryBusy) loadMemory()
        }
        override fun onServiceDisconnected(name: ComponentName) { service = null }
    }
    internal val store get() = SettingsStore(this)
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        draft = ViewModelProvider(this)[SettingsDraft::class.java]
        permissionWasReady = state?.getBoolean("permissionWasReady") ?: (ShizukuMonitor.current() == ShizukuAvailability.READY)
        page = state?.getString("page") ?: intent.getStringExtra("page")?.takeIf { it in setOf("models", "search", "memory") } ?: "home"
        modelIndex = state?.getInt("modelIndex", -1) ?: -1
        if (draft.connection == null && state?.containsKey("draft") == true) {
            draft.connection = JSONObject(state.getString("draft")!!)
            val id = draft.connection!!.optString("id")
            savedConnections().firstOrNull { it.optString("id") == id }?.let { draft.connection!!.put("apiKey", it.optString("apiKey")) }
            if (state.getBoolean("editedKey")) {
                draft.connection!!.put("apiKey", "")
                Toast.makeText(this, "请重新填写尚未保存的 API Key", Toast.LENGTH_LONG).show()
            }
            draft.original = state.getString("original", "")
            if (draft.original.isNotBlank()) draft.original = JSONObject(draft.original).apply {
                put("apiKey", draft.connection!!.optString("apiKey"))
            }.toString()
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(UiStyle.background)
            setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom); WindowInsets.CONSUMED
            }
        }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(6), dp(16), dp(6)) }
        header.addView(button("返回", quiet = true) { back() })
        titleView = TextView(this).apply { textSize = 20f; setTextColor(UiStyle.text); setPadding(dp(12), 0, 0, 0) }
        header.addView(titleView, LinearLayout.LayoutParams(0, -2, 1f)); root.addView(header)
        body = FrameLayout(this).apply { id = BODY_ID }
        root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f)); setContentView(root)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) { override fun handleOnBackPressed() = back() })
        bound = bindService(Intent(this, AssistantService::class.java), connection, Context.BIND_AUTO_CREATE)
        render()
        draft.probeChanged.observe(this) { updateProbeUi() }
        draft.discoveryChanged.observe(this) { if (page == "discover" && !supportFragmentManager.isStateSaved) render() }
        draft.toolsChanged.observe(this) { if (page in setOf("search", "memory") && !supportFragmentManager.isStateSaved && !isFinishing) render() }
    }
    override fun onResume() { super.onResume(); permissionMonitor.start(); if (::body.isInitialized && page !in setOf("connection", "model")) render() }
    override fun onPause() { permissionMonitor.close(); super.onPause() }
    internal fun open(target: String) { page = target; render(); if (target == "memory" && draft.memoryContent == null && !draft.memoryBusy && service != null) loadMemory() }
    internal fun changed() {
        pendingChanges = service == null
        service?.sessionCommand(JSONObject().put("type", "settingsChanged"))
    }
    internal fun safely(action: () -> Unit) { try { action() } catch (error: Exception) { Toast.makeText(this, error.message ?: "操作失败", Toast.LENGTH_LONG).show() } }
    internal fun savedConnections(): List<JSONObject> = store.registry().getJSONArray("connections").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
    internal fun editConnection(value: JSONObject? = null) {
        draft.cancelDiscovery()
        draft.connection = value?.let { JSONObject(it.toString()) } ?: JSONObject().put("name", "").put("provider", "").put("api", "openai-completions").put("baseUrl", "").put("apiKey", "").put("models", JSONArray())
        draft.original = draft.connection.toString(); open("connection")
    }
    private fun back() {
        when (page) {
            "home" -> finish()
            "connection" -> if (draft.connection.toString() != draft.original) confirm("放弃未保存的修改？", "放弃") { discard() } else discard()
            "model" -> open("connection")
            "discover" -> { draft.cancelDiscovery(); open("connection") }
            "search" -> if (draft.search.toString() != draft.searchOriginal) confirm("放弃未保存的修改？", "放弃") { draft.search = null; open("home") } else { draft.search = null; open("home") }
            "memory" -> if (draft.memoryContent != null && draft.memoryContent != draft.memoryOriginal) confirm("放弃未保存的修改？", "放弃") { draft.memoryContent = null; open("home") } else { draft.memoryContent = null; open("home") }
            else -> open("home")
        }
    }
    private fun discard() { draft.cancelDiscovery(); draft.connection?.remove("apiKey"); draft.connection = null; draft.original = ""; open("models") }
    private fun render() {
        probeButton = null; probeResultView = null
        titleView.text = when (page) { "models" -> "模型与 API"; "connection" -> "编辑连接"; "model" -> "编辑模型"; "discover" -> "选择模型"; "search" -> "联网搜索"; "memory" -> "用户记忆"; "permissions" -> "手机权限"; "ui" -> "界面"; else -> "设置" }
        if (page in setOf("connection", "model", "discover", "search", "memory")) {
            supportFragmentManager.findFragmentById(BODY_ID)?.let { supportFragmentManager.beginTransaction().remove(it).commitNow() }
            body.removeAllViews()
            if (page in setOf("connection", "model", "discover") && draft.connection == null) { open("models"); return }
            when (page) { "connection" -> connectionForm(); "model" -> modelForm(); "search" -> searchForm(); "memory" -> memoryForm(); else -> discoveryForm() }
        } else {
            body.removeAllViews()
            supportFragmentManager.beginTransaction().replace(BODY_ID, SettingsPreferences().apply { arguments = Bundle().apply { putString("page", page) } }).commitNow()
        }
    }
    private fun form(): LinearLayout {
        val fields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(24))
        }
        body.addView(ScrollView(this).apply { isFillViewport = true; addView(fields) })
        return fields
    }
    private fun label(form: LinearLayout, title: String) { form.addView(TextView(this).apply { text = title; textSize = 13f; setTextColor(UiStyle.muted); setPadding(0, dp(16), 0, dp(6)) }) }
    private fun field(form: LinearLayout, title: String, value: String, password: Boolean = false, number: Boolean = false, decimal: Boolean = false, update: (String) -> Unit): EditText {
        label(form, title)
        return EditText(this).apply {
            hint = title; contentDescription = title; isSingleLine = true; textSize = 15f
            setTextColor(UiStyle.text); setHintTextColor(UiStyle.muted); background = UiStyle.shape(this@SettingsActivity, UiStyle.surface, stroke = true)
            setPadding(dp(12), dp(10), dp(12), dp(10)); minHeight = dp(48)
            inputType = if (password) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else if (decimal) InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL else if (number) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            isSaveEnabled = false; importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            setText(value); addTextChangedListener(object : TextWatcher { override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {} ; override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { update(s.toString()) }; override fun afterTextChanged(s: Editable?) {} })
            form.addView(this, LinearLayout.LayoutParams(-1, -2))
        }
    }
    private fun button(text: String, quiet: Boolean = false, danger: Boolean = false, action: () -> Unit) = Button(this).apply {
        this.text = text; contentDescription = text; UiStyle.button(this, quiet = quiet, destructive = danger)
        setOnClickListener { safely(action) }
    }
    private fun action(form: LinearLayout, text: String, danger: Boolean = false, block: () -> Unit) { form.addView(button(text, danger = danger, action = block), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) }) }
    private fun connectionForm() {
        val value = draft.connection!!; val form = form()
        action(form, "使用配置预设") {
            val presets = ProviderPresets.parse(assets.open("provider-presets.json").bufferedReader().use { it.readText() })
            val names = listOf("自定义") + presets.map { it.getString("name") }
            androidx.appcompat.app.AlertDialog.Builder(this).setTitle("配置预设").setItems(names.toTypedArray()) { _, index ->
                ProviderPresets.apply(value, presets.getOrNull(index - 1), presets); render()
            }.show()
        }
        field(form, "连接名称", value.optString("name")) { value.put("name", it) }
        label(form, "API 协议")
        val ids = listOf("openai-completions", "openai-responses", "anthropic-messages")
        form.addView(Spinner(this).apply {
            contentDescription = "API 协议"; adapter = ArrayAdapter(this@SettingsActivity, android.R.layout.simple_spinner_dropdown_item, listOf("OpenAI Chat Completions", "OpenAI Responses", "Anthropic Messages"))
            setSelection(ids.indexOf(value.optString("api")).coerceAtLeast(0)); onItemSelectedListener = object : AdapterView.OnItemSelectedListener { override fun onNothingSelected(parent: AdapterView<*>?) {}; override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { value.put("api", ids[position]) } }
        }, LinearLayout.LayoutParams(-1, dp(48)))
        field(form, "API 地址", value.optString("baseUrl")) { value.put("baseUrl", it.trim()) }
        field(form, "API Key", value.optString("apiKey"), password = true) { value.put("apiKey", it) }
        label(form, "模型")
        val models = value.optJSONArray("models") ?: JSONArray().also { value.put("models", it) }
        for (index in 0 until models.length()) {
            val model = models.getJSONObject(index)
            action(form, model.optString("name").ifBlank { model.optString("id").ifBlank { "未命名模型" } }) { modelIndex = index; open("model") }
        }
        action(form, "从供应商获取模型") {
            require(value.optString("baseUrl").isNotBlank()) { "请先填写 API 地址" }
            page = "discover"; draft.fetchModels()
        }
        action(form, "手动添加模型") { models.put(JSONObject().put("id", "").put("name", "")); modelIndex = models.length() - 1; open("model") }
        action(form, "保存连接") {
            require(value.optString("name").isNotBlank()) { "请填写连接名称" }
            require(models.length() > 0 && (0 until models.length()).all { models.getJSONObject(it).optString("id").isNotBlank() }) { "请添加模型并填写模型 ID" }
            store.saveConnection(value); changed(); discard()
        }
        if (value.optString("id").isNotBlank()) {
            probeButton = button("发送测试请求") {
                check(value.toString() == draft.original) { "请先保存连接修改" }
                val storedModels = value.getJSONArray("models")
                val choices = (0 until storedModels.length()).map { storedModels.getJSONObject(it).optString("id") }
                androidx.appcompat.app.AlertDialog.Builder(this).setTitle("选择测试模型").setItems(choices.toTypedArray()) { _, index ->
                    confirm("发送模型测试请求？", "发送", "使用固定测试文字、合成图片和模拟工具，不包含聊天或手机截图。") {
                        check(!draft.probing) { "测试正在进行" }
                        val target = checkNotNull(service) { "执行服务尚未连接" }
                        draft.probing = true; draft.probeResult = "正在测试"; updateProbeUi()
                        target.probeModel(value.getString("id"), choices[index]) { result ->
                            runOnUiThread {
                                draft.probing = false
                                draft.probeResult = result.optString("message").ifBlank { if (result.optBoolean("ok")) "测试通过" else "测试失败" }
                                draft.probeChanged.value = Unit
                            }
                        }
                    }
                }.show()
            }.also { form.addView(it, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) }) }
            probeResultView = TextView(this).apply { textSize = 14f; setTextColor(UiStyle.muted); setPadding(0, dp(8), 0, 0) }.also { form.addView(it) }
            updateProbeUi()
        }
        if (value.optString("id").isNotBlank()) action(form, "删除连接", danger = true) {
            confirm("删除连接？", "删除", "引用此连接的排队任务将无法执行。") { store.deleteConnection(value.getString("id")); changed(); discard() }
        }
    }
    private fun discoveryForm() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(16)) }
        body.addView(root)
        if (draft.discovering) {
            root.addView(ProgressBar(this)); label(root, "正在获取模型列表…"); return
        }
        if (draft.discoveryError.isNotBlank() || draft.discovered.isEmpty()) {
            label(root, draft.discoveryError.ifBlank { "供应商未返回模型，可返回手动添加" })
            action(root, "重新获取") { draft.fetchModels() }; return
        }
        val saved = draft.connection!!.getJSONArray("models")
        for (index in 0 until saved.length()) {
            val model = saved.getJSONObject(index)
            draft.discovered.firstOrNull { it.id == model.optString("id") }?.let { ModelSettings.fillMissing(model, it.toJson()) }
        }
        val existing = (0 until saved.length()).map { saved.getJSONObject(it).optString("id") }.toSet()
        val list = ListView(this).apply { choiceMode = ListView.CHOICE_MODE_MULTIPLE; divider = null }
        var visible = emptyList<DiscoveredModel>()
        val add = button("添加所选模型") {
            for (model in draft.discovered) if (model.id in draft.pickedModels && model.id !in existing) saved.put(model.toJson())
            draft.pickedModels.clear(); open("connection")
        }
        fun update() {
            visible = draft.discovered.filter { it.id.contains(draft.modelSearch, true) || it.name.contains(draft.modelSearch, true) }
            list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_multiple_choice, visible.map {
                (if (it.name == it.id) it.id else "${it.name}\n${it.id}") + if (it.id in existing) " · 已添加" else ""
            })
            visible.forEachIndexed { index, model -> list.setItemChecked(index, model.id in existing || model.id in draft.pickedModels) }
            add.isEnabled = draft.pickedModels.isNotEmpty()
            add.text = if (draft.pickedModels.isEmpty()) "添加所选模型" else "添加 ${draft.pickedModels.size} 个模型"
        }
        field(root, "搜索模型", draft.modelSearch) { draft.modelSearch = it; update() }
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f)); root.addView(add, LinearLayout.LayoutParams(-1, -2))
        list.setOnItemClickListener { _, _, position, _ ->
            val id = visible[position].id
            if (id !in existing) { if (!draft.pickedModels.add(id)) draft.pickedModels.remove(id) }
            update()
        }
        update()
    }
    private fun modelForm() {
        val connection = draft.connection!!; val models = connection.getJSONArray("models")
        if (modelIndex !in 0 until models.length()) { open("connection"); return }
        val value = models.getJSONObject(modelIndex); val form = form()
        field(form, "模型 ID", value.optString("id")) { value.put("id", it.trim()) }
        field(form, "显示名称", value.optString("name")) { value.put("name", it) }
        fun capability(title: String, current: Boolean?, change: (Boolean?) -> Unit) {
            label(form, title)
            var selected = if (current == null) 0 else if (current) 1 else 2
            form.addView(Spinner(this).apply {
                contentDescription = title
                adapter = ArrayAdapter(this@SettingsActivity, android.R.layout.simple_spinner_dropdown_item, listOf("未设置", "支持", "不支持"))
                setSelection(selected)
                onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onNothingSelected(parent: AdapterView<*>?) {}
                    override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                        if (position == selected) return
                        selected = position; change(if (position == 0) null else position == 1)
                    }
                }
            }, LinearLayout.LayoutParams(-1, dp(48)))
        }
        capability("图片输入", if (value.has("input")) value.getJSONArray("input").toString().contains("\"image\"") else null) { supported ->
            if (supported == null) value.remove("input") else value.put("input", JSONArray().put("text").apply { if (supported) put("image") })
        }
        capability("思考能力", value.opt("reasoning") as? Boolean) { supported ->
            if (supported == null) value.remove("reasoning") else value.put("reasoning", supported)
        }
        for ((key, title) in listOf("contextWindow" to "上下文长度（K）", "maxTokens" to "最大输出长度（K）")) {
            val display = if (value.has(key)) java.math.BigDecimal.valueOf(value.getLong(key)).movePointLeft(3).stripTrailingZeros().toPlainString() else ""
            field(form, title, display, decimal = true) {
                if (it.isBlank()) value.remove(key)
                else value.put(key, runCatching { it.toBigDecimal().movePointRight(3).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact() }.getOrDefault(0L))
            }
        }
        action(form, "完成") { require(value.optString("id").isNotBlank()) { "请填写模型 ID" }; open("connection") }
        action(form, "移除此模型", danger = true) { models.remove(modelIndex); open("connection") }
    }
    private fun updateProbeUi() {
        probeButton?.isEnabled = !draft.probing
        probeResultView?.text = draft.probeResult
        probeResultView?.visibility = if (draft.probeResult.isBlank()) View.GONE else View.VISIBLE
    }
    private fun searchForm() {
        val value = draft.search ?: store.searchSettings().also { draft.search = it; draft.searchOriginal = it.toString() }
        val form = form()
        val providers = listOf("bocha", "baidu")
        label(form, "搜索供应商")
        val keys = value.getJSONObject("keys")
        val picker = Spinner(this).apply {
            contentDescription = "搜索供应商"
            adapter = ArrayAdapter(this@SettingsActivity, android.R.layout.simple_spinner_dropdown_item, listOf("博查", "百度 AI 搜索"))
            setSelection(providers.indexOf(value.getString("provider")).coerceAtLeast(0))
        }
        form.addView(picker, LinearLayout.LayoutParams(-1, dp(48)))
        field(form, "API Key", keys.optString(value.getString("provider")), password = true) {
            keys.put(value.getString("provider"), it)
        }
        picker.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) {}
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (value.optString("provider") != providers[position]) {
                    value.put("provider", providers[position]); render()
                }
            }
        }
        action(form, "保存") {
            store.saveSearchSettings(value); draft.search = null; changed(); open("home")
        }
    }
    private fun memoryForm() {
        val form = form()
        if (draft.memoryBusy) label(form, "正在读取或保存…")
        if (draft.memoryError.isNotBlank()) label(form, draft.memoryError)
        val content = draft.memoryContent
        if (content != null) {
            form.addView(EditText(this).apply {
                contentDescription = "用户记忆内容"; hint = "记录偏好和需要长期记住的信息"
                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                gravity = Gravity.TOP; minLines = 10; maxLines = 24
                isSaveEnabled = false; setText(content); isEnabled = !draft.memoryBusy
                setTextColor(UiStyle.text); setHintTextColor(UiStyle.muted)
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { draft.memoryContent = s.toString() }
                    override fun afterTextChanged(s: Editable?) {}
                })
            }, LinearLayout.LayoutParams(-1, -2))
            form.addView(button("保存") { memoryOperation("write") }.apply { isEnabled = !draft.memoryBusy }, LinearLayout.LayoutParams(-1, -2))
            form.addView(button("删除全部记忆", danger = true) {
                confirm("删除全部记忆？", "删除") { memoryOperation("delete") }
            }.apply { isEnabled = !draft.memoryBusy }, LinearLayout.LayoutParams(-1, -2))
        }
        form.addView(button("重新读取", quiet = true) {
            if (content != null && content != draft.memoryOriginal) confirm("放弃编辑并重新读取？", "重新读取") { loadMemory() }
            else loadMemory()
        }.apply { isEnabled = !draft.memoryBusy }, LinearLayout.LayoutParams(-1, -2))
    }
    private fun loadMemory() = memoryOperation("read")
    private fun memoryOperation(action: String) = safely {
        check(!draft.memoryBusy) { "记忆操作正在进行" }
        val target = checkNotNull(service) { "执行服务尚未连接" }
        check(ShizukuMonitor.current() == ShizukuAvailability.READY) { "Shizuku 未就绪" }
        val command = JSONObject().put("action", action)
        if (action != "read") command.put("revision", draft.memoryRevision)
        if (action == "write") command.put("content", draft.memoryContent ?: "")
        draft.memoryBusy = true; draft.memoryError = ""; render()
        target.memoryCommand(command) { result -> runOnUiThread {
            draft.memoryBusy = false
            if (result.optBoolean("success")) {
                val data = result.optJSONObject("data") ?: JSONObject()
                draft.memoryContent = data.optString("content"); draft.memoryOriginal = data.optString("content")
                draft.memoryRevision = data.optString("revision"); draft.memoryError = ""
            } else draft.memoryError = result.optString("error").ifBlank { "记忆操作失败，请重新读取后重试" }
            draft.toolsChanged.value = Unit
        } }
    }
    internal fun chooseDefault() {
        val choices = store.modelOptions(); val labels = (0 until choices.length()).map { choices.getJSONObject(it).let { c -> "${c.optString("connectionName")} / ${c.optString("modelName")}" } }
        if (labels.isEmpty()) return
        androidx.appcompat.app.AlertDialog.Builder(this).setTitle("新会话默认模型").setItems(labels.toTypedArray()) { _, index -> safely {
            val choice = choices.getJSONObject(index); store.setDefaultSelection(choice.getString("connectionId"), choice.getString("modelId")); changed(); render()
        } }.show()
    }
    internal fun confirm(title: String, label: String, message: String? = null, action: () -> Unit) {
        androidx.appcompat.app.AlertDialog.Builder(this).setTitle(title).apply { if (message != null) setMessage(message) }
            .setNegativeButton("取消", null).setPositiveButton(label) { _, _ -> safely(action) }.show()
    }
    override fun onSaveInstanceState(out: Bundle) {
        out.putBoolean("permissionWasReady", permissionWasReady)
        out.putString("page", page); out.putInt("modelIndex", modelIndex)
        draft.connection?.let {
            out.putString("draft", JSONObject(it.toString()).apply { remove("apiKey") }.toString())
            out.putBoolean("editedKey", it.optString("apiKey") != if (draft.original.isNotBlank()) JSONObject(draft.original).optString("apiKey") else "")
        }
        // The original contains credentials as well; only retain a sanitized comparison after process death.
        if (draft.original.isNotBlank()) out.putString("original", JSONObject(draft.original).apply { remove("apiKey") }.toString())
        super.onSaveInstanceState(out)
    }
    override fun onDestroy() { permissionMonitor.close(); if (bound) unbindService(connection); service = null; super.onDestroy() }
    private fun dp(value: Int) = UiStyle.dp(this, value)
    companion object { private const val BODY_ID = 0x0bb0001 }
}

class SettingsPreferences : PreferenceFragmentCompat() {
    private val host get() = requireActivity() as SettingsActivity
    override fun onCreatePreferences(state: Bundle?, rootKey: String?) {
        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext())
        when (arguments?.getString("page")) {
            "models" -> {
                val registry = host.store.registry(); val default = registry.optJSONObject("defaultSelection")
                row("新会话默认模型", default?.optString("modelId").orEmpty()) { host.chooseDefault() }
                host.savedConnections().forEach { connection -> row(connection.optString("name"), "${connection.optJSONArray("models")?.length() ?: 0} 个模型") { host.editConnection(connection) } }
                row("添加连接") { host.editConnection() }
            }
            "permissions" -> {
                val available = DevicePermission.available(); val granted = DevicePermission.granted()
                row("Shizuku", if (!available) "未启动" else if (!granted) "未授权" else "已授权") {
                    if (!DevicePermission.available()) Toast.makeText(host, "请在 Shizuku 中启动服务", Toast.LENGTH_SHORT).show()
                    else if (!DevicePermission.granted()) DevicePermission.request()
                }
                row("悬浮窗", if (Settings.canDrawOverlays(host)) "已允许" else "未允许") { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${host.packageName}"))) }
                row("通知", if (androidx.core.app.NotificationManagerCompat.from(host).areNotificationsEnabled()) "已允许" else "未允许") { startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, host.packageName)) }
                row("应用系统设置") { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${host.packageName}"))) }
            }
            "ui" -> {
                preferenceScreen.addPreference(SwitchPreferenceCompat(requireContext()).apply {
                    key = "execution_overlay_enabled"; title = "后台任务状态悬浮窗"; setDefaultValue(true)
                    onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, value ->
                        if (value == true && !Settings.canDrawOverlays(host)) startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${host.packageName}")))
                        view?.post { host.changed() }; true
                    }
                })
            }
            else -> {
                row("模型与 API", "${host.savedConnections().size} 个连接") { host.open("models") }
                row("联网搜索") { host.open("search") }
                row("用户记忆") { host.open("memory") }
                row("手机权限", if (DevicePermission.granted()) "Shizuku 已授权" else "Shizuku 未就绪") { host.open("permissions") }
                row("界面") { host.open("ui") }
            }
        }
    }
    private fun row(label: String, detail: String = "", action: (() -> Unit)? = null) {
        preferenceScreen.addPreference(Preference(requireContext()).apply {
            title = label; if (detail.isNotBlank()) summary = detail; isIconSpaceReserved = false; isSelectable = action != null
            setOnPreferenceClickListener { host.safely { action?.invoke() }; true }
        })
    }
}
