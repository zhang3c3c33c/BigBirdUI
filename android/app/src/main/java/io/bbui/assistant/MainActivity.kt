package io.bbui.assistant

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import io.bbui.device.DevicePermission

/** Privileged controls remain native and independent of WebView. */
class MainActivity : Activity(), SurfaceHolder.Callback {
    private var service: AssistantService? = null
    private var bound = false
    private var resumed = false
    private lateinit var status: TextView
    private lateinit var chatContainer: FrameLayout
    private var chat: ChatWebView? = null
    private lateinit var retry: Button
    private lateinit var previewPanel: LinearLayout
    private lateinit var previewFrame: FrameLayout
    private lateinit var executionEdge: ExecutionEdgeView
    private lateinit var preview: SurfaceView
    private lateinit var content: LinearLayout
    private lateinit var previewTitle: TextView
    private lateinit var emergencyStop: Button
    private lateinit var previewControl: Button
    private var previewBack: android.window.OnBackInvokedCallback? = null
    private var latestSnapshot: JSONObject? = null
    private var pendingOverlayNavigation: Pair<String, String?>? = null
    private var questionNavigation: JSONObject? = null
    private lateinit var permissionBar: LinearLayout
    private lateinit var permissionText: TextView
    private lateinit var permissionButton: Button
    private val permissionMonitor = ShizukuMonitor({ availability ->
        if (availability != ShizukuAvailability.READY && !isFinishing && !isDestroyed) {
            service?.coordinator?.attachPreview(null, 0, 0)
            startActivity(Intent(this, OnboardingActivity::class.java).putExtra("launchMain", true))
            finish()
        }
    })
    
    private val chatListener: (JSONObject) -> Unit = { snapshot ->
        latestSnapshot = snapshot
        val state = snapshot.optJSONObject("status")
        showStatus(if (state?.optString("phase") == "error") state.optString("message").take(160) else "")
        updatePreviewTitle()
        openOverlayDestination(snapshot)
        val navigation = questionNavigation?.takeIf { it.optString("sessionId") == snapshot.optString("sessionId") }
        val presented = if (navigation == null) snapshot else JSONObject().also { copy ->
            snapshot.keys().forEach { key -> copy.put(key, snapshot.get(key)) }
            copy.put("focusQuestion", navigation)
        }
        chat?.showSnapshot(presented)
    }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = (binder as AssistantService.LocalBinder).service
            service?.subscribeChat(chatListener)
            service?.sessionCommand(JSONObject().put("type", "settingsChanged"))
            publishUiVisibility()
            if (previewPanel.visibility == View.VISIBLE && preview.holder.surface.isValid) attachPreview()
        }
        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            latestSnapshot = latestSnapshot?.let { JSONObject(it.toString()).put("environment", JSONObject().put("state", "invalid")) }
            if (::preview.isInitialized) preview.visibility = View.INVISIBLE
            if (::executionEdge.isInitialized) executionEdge.setHostControl(false)
            if (::previewTitle.isInitialized) previewTitle.text = "执行画面 · 未连接"
            showStatus("执行服务已断开")
        }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        state?.getString("pendingOverlaySession")?.let { session ->
            pendingOverlayNavigation = session to state.getString("pendingOverlayQuestion")
        }
        questionNavigation = state?.getString("overlayQuestionNavigation")?.let { runCatching { JSONObject(it) }.getOrNull() }
        readOverlayDestination(intent)
        if (ShizukuMonitor.current() != ShizukuAvailability.READY || OnboardingState.shouldShow(this)) {
            startActivity(Intent(this, OnboardingActivity::class.java).putExtra("launchMain", true))
            finish(); return
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(UiStyle.background)
            setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                WindowInsets.CONSUMED
            }
        }
        permissionBar = LinearLayout(this).apply { gravity = android.view.Gravity.CENTER_VERTICAL; setPadding(dp(16), 0, dp(12), 0); visibility = View.GONE }
        permissionText = TextView(this).apply { textSize = 13f; setTextColor(UiStyle.text) }
        permissionButton = button("授权") {
            when (ShizukuMonitor.current()) {
                ShizukuAvailability.UNAUTHORIZED -> DevicePermission.request()
                ShizukuAvailability.STOPPED -> startActivity(packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                    ?: Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://shizuku.rikka.app/download/")))
                ShizukuAvailability.READY -> permissionMonitor.refresh()
            }
        }.apply { UiStyle.button(this, quiet = true) }
        permissionBar.addView(permissionText, LinearLayout.LayoutParams(0, -2, 1f)); permissionBar.addView(permissionButton)
        root.addView(permissionBar)
        status = TextView(this).apply { visibility = View.GONE; textSize = 12f; setTextColor(UiStyle.danger); maxLines = 2; setPadding(dp(16), 0, dp(16), dp(4)) }
        root.addView(status)
        retry = button("重新加载聊天") { createChat() }.apply { visibility = View.GONE }
        root.addView(retry)
        emergencyStop = button("停止操作", "紧急停止") { sessionCommand(JSONObject().put("type", "stop")) }.apply { visibility = View.GONE }
        root.addView(emergencyStop)
        content = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        chatContainer = FrameLayout(this)
        content.addView(chatContainer, LinearLayout.LayoutParams(0, -1, 1f))
        previewFrame = FrameLayout(this)
        previewPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        val previewHeader = LinearLayout(this).apply { gravity = android.view.Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(6), dp(12), dp(6)) }
        previewHeader.addView(button("←", "返回对话") { showPreview(false) }.apply {
            UiStyle.button(this, quiet = true); textSize = 24f
            setPadding(0, 0, 0, 0)
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        previewTitle = TextView(this).apply { text = "执行画面"; textSize = 16f; setTextColor(UiStyle.text); setPadding(dp(8), dp(8), dp(8), dp(8)); maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        previewTitle.gravity = android.view.Gravity.CENTER
        previewHeader.addView(previewTitle, LinearLayout.LayoutParams(0, -2, 1f))
        previewHeader.addView(button("⋮", "执行画面菜单") {}.apply {
            UiStyle.button(this, quiet = true)
            minWidth = dp(44); minimumWidth = dp(44)
            setOnClickListener { showPreviewMenu(this) }
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        previewPanel.addView(previewHeader)
        val controls = LinearLayout(this).apply { setPadding(dp(12), dp(10), dp(12), dp(10)) }
        previewControl = button("我来操作") {}.apply { isEnabled = false }
        controls.addView(previewControl, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(4), 0, dp(4), 0) })

        preview = SurfaceView(this).apply {
            contentDescription = getString(R.string.preview_description)
            visibility = View.INVISIBLE
            holder.addCallback(this@MainActivity)
            setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (latestSnapshot?.optJSONObject("control")?.optString("mode") == "manual")
                            safely { coordinator().touch(event.actionMasked, event.x, event.y) }
                        if (event.actionMasked == MotionEvent.ACTION_UP) view.performClick()
                    }
                }
                true
            }
        }
        previewFrame.addView(preview, FrameLayout.LayoutParams(-1, -1))
        executionEdge = ExecutionEdgeView(this)
        previewFrame.addView(executionEdge, FrameLayout.LayoutParams(-1, -1))
        previewPanel.addView(previewFrame, LinearLayout.LayoutParams(-1, 0, 1f))
        previewPanel.addView(controls)
        content.addView(previewPanel, LinearLayout.LayoutParams(0, -1, 1f))
        setContentView(root)
        createChat()
        if (state?.getBoolean("previewVisible") == true) showPreview(true)
        startForegroundService(Intent(this, AssistantService::class.java))
        bound = bindService(Intent(this, AssistantService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readOverlayDestination(intent)
        latestSnapshot?.let { openOverlayDestination(it) }
    }

    private fun readOverlayDestination(source: Intent) {
        val sessionId = source.getStringExtra("overlaySessionId")?.takeIf { it.isNotBlank() && it.length <= 200 } ?: return
        val requestId = source.getStringExtra("overlayQuestionRequestId")?.takeIf { it.isNotBlank() && it.length <= 200 }
        pendingOverlayNavigation = sessionId to requestId
        source.removeExtra("overlaySessionId")
        source.removeExtra("overlayQuestionRequestId")
    }

    private fun openOverlayDestination(snapshot: JSONObject) {
        val destination = pendingOverlayNavigation ?: return
        val connected = service ?: return
        if (!snapshot.optBoolean("sessionsReady")) return
        pendingOverlayNavigation = null
        val catalog = snapshot.optJSONArray("sessions") ?: return
        if ((0 until catalog.length()).none { catalog.getJSONObject(it).optString("id") == destination.first }) return
        questionNavigation = destination.second?.let { request -> JSONObject()
            .put("id", java.util.UUID.randomUUID().toString()).put("sessionId", destination.first).put("requestId", request) }
        showPreview(false)
        // This is a browsing action only; it neither resumes nor submits a task.
        connected.sessionCommand(JSONObject().put("type", "selectSession").put("sessionId", destination.first))
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        if (::permissionBar.isInitialized) permissionMonitor.start()
        service?.sessionCommand(JSONObject().put("type", "settingsChanged"))
        publishUiVisibility()
        updatePreviewTitle()
        if (::preview.isInitialized && preview.holder.surface.isValid) attachPreview()
    }
    override fun onPause() {
        resumed = false
        if (::executionEdge.isInitialized) executionEdge.setHostControl(false)
        publishUiVisibility()
        service?.coordinator?.attachPreview(null, 0, 0)
        permissionMonitor.close()
        super.onPause()
    }

    private fun button(label: String, description: String = label, action: () -> Unit) = Button(this).apply {
        text = label; contentDescription = description
        UiStyle.button(this)
        setOnClickListener { safely(action) }
    }
    private fun createChat() {
        chat?.let { chatContainer.removeView(it); it.dispose() }
        retry.visibility = View.GONE
        emergencyStop.visibility = View.GONE
        chat = ChatWebView(this, ::onChatCommand) { message -> showStatus(message); retry.visibility = View.VISIBLE; emergencyStop.visibility = View.VISIBLE }.also {
            chatContainer.addView(it, FrameLayout.LayoutParams(-1, -1))
            latestSnapshot?.let(it::showSnapshot)
        }
    }
    private fun onChatCommand(command: JSONObject) = safely {
        when (command.getString("type")) {
            "ready" -> service?.sessionSnapshot()?.let(chatListener)
            "questionFocused" -> if (questionNavigation?.let { navigation ->
                listOf("id", "sessionId", "requestId").all { navigation.optString(it) == command.optString(it) }
            } == true) questionNavigation = null
            "send", "steer", "stop", "resumeTask", "endManual", "answerQuestion", "questionDraft" -> sessionCommand(command)
            "takeOver" -> { sessionCommand(command); showPreview(true) }
            "loadOlder", "newSession", "selectSession", "renameSession", "deleteSession", "pinSession", "refreshSessions",
            "cancelQueued", "pauseQueue", "resumeQueue", "viewState", "selectModel", "setThinkingLevel" -> sessionCommand(command)
            "rendered" -> service?.recordChatRendered(command.getLong("revision"))
            "openPreview" -> showPreview(true)
            "openSettings" -> showSettings(command.optString("page"))
        }
    }
    private fun showPreview(show: Boolean) {
        if (!show) service?.coordinator?.attachPreview(null, 0, 0)
        val sideBySide = resources.configuration.screenWidthDp >= 760
        chatContainer.visibility = if (show && !sideBySide) View.GONE else View.VISIBLE
        previewPanel.visibility = if (show) View.VISIBLE else View.GONE
        previewFrame.visibility = previewPanel.visibility
        publishUiVisibility()
        if (Build.VERSION.SDK_INT >= 33) {
            previewBack?.let { onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it) }
            previewBack = if (show) android.window.OnBackInvokedCallback { showPreview(false) }.also {
                onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, it)
            } else null
        }
        updatePreviewTitle()
        if (show) {
            currentFocus?.let { getSystemService(android.view.inputmethod.InputMethodManager::class.java).hideSoftInputFromWindow(it.windowToken, 0) }
            preview.post { if (preview.holder.surface.isValid) attachPreview() }
        }
    }
    private fun showPreviewMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add("关闭执行环境")
            setOnMenuItemClickListener {
                confirmCloseEnvironment()
                true
            }
            show()
        }
    }
    private fun confirmCloseEnvironment() {
        val controlId = latestSnapshot?.optJSONObject("control")?.optString("id").orEmpty()
        val environmentId = latestSnapshot?.optJSONObject("environment")?.optString("id").orEmpty()
        AlertDialog.Builder(this)
            .setTitle("关闭执行环境？")
            .setMessage("执行画面将关闭。有未完成任务时，执行与队列会暂停；聊天记录和待执行任务将保留。")
            .setNegativeButton("取消", null)
            .setPositiveButton("关闭") { _, _ -> safely {
                sessionCommand(JSONObject().put("type", "closeEnvironment").put("controlId", controlId).put("environmentId", environmentId))
                showPreview(false)
            } }
            .show()
            .getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(UiStyle.danger)
    }
    private fun publishUiVisibility() {
        chat?.setPresentationActive(resumed && ::chatContainer.isInitialized && chatContainer.visibility == View.VISIBLE)
        service?.setUiVisibility(resumed, resumed && ::previewPanel.isInitialized && previewPanel.visibility == View.VISIBLE)
    }
    private fun updatePreviewTitle() {
        val snapshot = latestSnapshot ?: return
        val control = snapshot.optJSONObject("control")
        val environmentState = snapshot.optJSONObject("environment")?.optString("state") ?: "absent"
        val environmentLabel = when (environmentState) {
            "ready" -> null
            "creating" -> "准备中"
            "releasing" -> "关闭中"
            else -> "未连接"
        }
        // Removing the Surface hides its last buffer when the producer is no longer live.
        preview.visibility = if (environmentState == "ready") View.VISIBLE else View.INVISIBLE
        val mode = control?.optString("mode") ?: "idle"
        val aiActive = environmentState == "ready" && mode == "running"
        val intent = snapshot.optJSONObject("execution")?.optString("intent").orEmpty()
        previewTitle.text = environmentLabel?.let { "执行画面 · $it" }
            ?: if (aiActive) intent.ifBlank { "AI 正在处理" }
            else if (mode == "manual") "你正在操作"
            else when (mode) {
                "taking_over" -> "正在交接"; "resuming" -> "正在恢复"
                "stopped" -> "已停止"; "error" -> "执行异常"; else -> "闲置"
            }
        val hostControl = environmentState == "ready" && mode != "manual"
        previewTitle.contentDescription = if (hostControl) "未接管，${previewTitle.text}" else previewTitle.text
        val environment = snapshot.optJSONObject("environment")
        executionEdge.setVideoSize(environment?.optInt("videoWidth") ?: 0, environment?.optInt("videoHeight") ?: 0)
        executionEdge.setHostControl(hostControl && resumed && previewPanel.visibility == View.VISIBLE, animate = aiActive)
        val canResume = control?.optBoolean("canResume") == true
        val controlId = control?.optString("id").orEmpty()
        val action = when {
            mode == "manual" -> if (canResume) "resumeTask" else "endManual"
            (mode == "stopped" || mode == "error") && canResume -> "resumeTask"
            mode in setOf("running", "idle", "stopped", "error") -> "takeOver"
            else -> null
        }
        previewControl.text = when {
            mode == "taking_over" -> "正在交接"
            mode == "resuming" -> "正在恢复"
            mode == "manual" -> if (canResume) "交给 AI 继续" else "结束操作"
            action == "resumeTask" -> "继续任务"
            else -> "我来操作"
        }
        previewControl.contentDescription = previewControl.text
        previewControl.isEnabled = action != null && controlId.isNotBlank()
        previewControl.setOnClickListener { if (action != null) safely {
            sessionCommand(JSONObject().put("type", action).put("controlId", controlId))
        } }
    }
    @Deprecated("Activity back compatibility")
    override fun onBackPressed() {
        if (previewPanel.visibility == View.VISIBLE) showPreview(false) else super.onBackPressed()
    }
    private fun showSettings(page: String? = null) {
        startActivity(Intent(this, SettingsActivity::class.java).apply {
            if (page == "models") putExtra("page", "models")
        })
    }

    private fun coordinator() = checkNotNull(service?.coordinator) { "执行服务尚未连接" }
    private fun sessionCommand(command: JSONObject) = checkNotNull(service) { "执行服务尚未连接" }.sessionCommand(command)
    private fun showStatus(message: String) {
        status.text = message
        status.visibility = if (message.isBlank()) View.GONE else View.VISIBLE
    }
    private fun safely(action: () -> Unit) {
        try { action() } catch (error: Exception) { val message = error.message ?: "操作失败"; showStatus(message); Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun attachPreview() { if (resumed && previewPanel.visibility == View.VISIBLE && preview.visibility == View.VISIBLE) service?.coordinator?.attachPreview(preview.holder.surface, preview.width, preview.height) }
    override fun surfaceCreated(holder: SurfaceHolder) { attachPreview() }
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) { attachPreview() }
    override fun surfaceDestroyed(holder: SurfaceHolder) { service?.coordinator?.attachPreview(null, 0, 0) }
    override fun onSaveInstanceState(outState: Bundle) {
        if (::previewPanel.isInitialized) outState.putBoolean("previewVisible", previewPanel.visibility == View.VISIBLE)
        pendingOverlayNavigation?.let { (session, request) ->
            outState.putString("pendingOverlaySession", session)
            outState.putString("pendingOverlayQuestion", request)
        }
        questionNavigation?.let { outState.putString("overlayQuestionNavigation", it.toString()) }
        super.onSaveInstanceState(outState)
    }
    override fun onDestroy() {
        permissionMonitor.close()
        if (Build.VERSION.SDK_INT >= 33) previewBack?.let { onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it) }
        service?.unsubscribeChat(chatListener)
        service?.coordinator?.attachPreview(null, 0, 0)
        if (bound) unbindService(connection)
        service = null
        chat?.let { chatContainer.removeView(it); it.dispose() }
        chat = null
        super.onDestroy()
    }
}
