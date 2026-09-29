package io.bbui.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.Activity
import android.app.Application
import android.content.Intent
import android.content.IntentFilter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import org.json.JSONObject

/** Owns execution independently of Activity and preview Surface lifetime. */
class AssistantService : Service() {
    inner class LocalBinder : Binder() { val service get() = this@AssistantService }
    lateinit var coordinator: AppCoordinator
        private set
    private val listeners = linkedSetOf<(JSONObject) -> Unit>()
    private val recent = ArrayDeque<JSONObject>()
    private lateinit var sessions: SessionController
    private val permissionMonitor = ShizukuMonitor({ availability ->
        if (::sessions.isInitialized) {
            sessions.permissionChanged(availability)
            if (availability != ShizukuAvailability.READY && coordinator.environmentSnapshot().optString("state") != "releasing") {
                coordinator.releasePhone()
            }
        }
        syncOverlayPreference(refreshTool = false)
    })
    private val chatListeners = linkedSetOf<(JSONObject) -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private var flushPending = false
    private var destroying = false
    private val storageWorker = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var lastStorageSweep = 0L
    private fun maintainStorage(force: Boolean = false) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (destroying || (!force && now - lastStorageSweep < 60_000)) return
        lastStorageSweep = now
        storageWorker.execute { runCatching { io.bbui.device.DiagnosticStorage.maintain(filesDir, cacheDir) } }
    }
    private var lastProjectionAt = 0L
    private var lastRevision = -1L
    private var lastTiming = JSONObject()
    private val idlePolicy = EnvironmentIdlePolicy()
    private var mainForeground = false
    private var previewVisible = false
    private val resumedActivities = linkedSetOf<Activity>()
    private val idleCheck = Runnable { updateEnvironmentRetention() }
    private val activityCallbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) {
            // WorkspaceActivity remains resumed on the virtual screen; it is not user foreground.
            if (activity.display?.displayId == android.view.Display.DEFAULT_DISPLAY) resumedActivities.add(activity)
            updateEnvironmentRetention()
        }
        override fun onActivityPaused(activity: Activity) { resumedActivities.remove(activity); updateEnvironmentRetention() }
        override fun onActivityDestroyed(activity: Activity) { resumedActivities.remove(activity); updateEnvironmentRetention() }
        override fun onActivityCreated(activity: Activity, state: Bundle?) {}
        override fun onActivityStarted(activity: Activity) {}
        override fun onActivityStopped(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
    }
    private fun chatSnapshot(): JSONObject = sessions.state.snapshot().put("environment", coordinator.environmentSnapshot()
        .put("previewVisible", previewVisible).put("inputOwner", when (if (coordinator.environmentSnapshot().optString("state") == "ready") sessions.state.controlMode else "disabled") {
            "running" -> "ai"; "manual" -> "user"; else -> "disabled"
        }))
    fun setUiVisibility(foreground: Boolean, previewVisible: Boolean) {
        mainForeground = foreground
        this.previewVisible = foreground && previewVisible
        updateEnvironmentRetention()
    }
    private fun updateEnvironmentRetention() {
        if (destroying || !::sessions.isInitialized) return
        main.removeCallbacks(idleCheck)
        val environment = coordinator.environmentSnapshot()
        val state = sessions.state
        val usage = EnvironmentIdlePolicy.Usage(
            foreground = mainForeground || resumedActivities.isNotEmpty(), previewVisible = previewVisible,
            resourceReady = environment.optString("state") == "ready", executorBusy = coordinator.isBusy(),
            running = state.running != null, manual = state.controlMode in setOf("manual", "taking_over", "resuming"),
            continuation = state.continuation != null, runnableQueue = state.queue.isNotEmpty() && !state.paused,
            waitingForUser = sessions.environmentWaitingForUser(environment.optString("sessionId"))
        )
        syncOverlayPreference(refreshTool = false)
        val remaining = idlePolicy.remaining(SystemClock.elapsedRealtime(), usage) ?: return
        if (remaining == 0L) coordinator.releasePhone(onlyIfIdle = true)
        else main.postDelayed(idleCheck, remaining)
    }
    private val flushChat = Runnable {
        flushPending = false
        val snapshot = chatSnapshot()
        lastProjectionAt = System.currentTimeMillis()
        lastRevision = snapshot.optLong("revision")
        lastTiming = snapshot.getJSONObject("timing")
        chatListeners.toList().forEach { it(snapshot) }
        updateEnvironmentRetention()
    }
    private val overlayPresentation = OverlayPresentation()
    private var overlay: ExecutionOverlay? = null
    private var overlayModel: OverlayPresentation.Model? = null
    private var screenOff = false
    private val overlayExpiry = Runnable { syncOverlayPreference(refreshTool = false) }
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) { screenOff = true; sessions.stop("lock") }
            if (intent.action in setOf(Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT)) screenOff = false
            syncOverlayPreference(refreshTool = false)
            if (intent.action == Intent.ACTION_DEVICE_STORAGE_LOW) maintainStorage(force = true)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "助手运行状态", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, AssistantService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_bbui_notification)
            .setContentTitle("BBUI 手机助手")
            .setContentText("执行服务已启动，可随时停止")
            .setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null, "停止", stop).build()).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(1, notification)
        coordinator = AppCoordinator(this) { event ->
            if (event.optString("type") == "environment_lost") {
                sessions.environmentLost(event.optString("message"))
                coordinator.releasePhone()
                return@AppCoordinator
            }
            if (event.optString("type") == "environment_state") {
                updateEnvironmentRetention()
                if (!flushPending) { flushPending = true; main.post(flushChat) }
                return@AppCoordinator
            }
            if (::sessions.isInitialized) sessions.accept(event)
            if (event.optString("type") in setOf("agent_settled", "run_stopped", "executor_idle")) maintainStorage()
            val compact = EventPresentation.compact(event)
            if (compact.optString("status").isNotBlank()) {
                recent.addLast(compact)
                while (recent.size > 30) recent.removeFirst()
            }
            // Tool start/end and question events change the capsule; text chunks do not.
            if (event.optString("type") != "message_update") syncOverlayPreference()
            listeners.toList().forEach { it(compact) }
        }
        sessions = SessionController(this, coordinator) {
            updateEnvironmentRetention()
            if (!destroying && !flushPending) { flushPending = true; main.postDelayed(flushChat, 50) }
        }
        sessions.refresh()
        application.registerActivityLifecycleCallbacks(activityCallbacks)
        permissionMonitor.start()
        val storageAndScreen = IntentFilter(Intent.ACTION_SCREEN_OFF).apply {
            addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT); addAction(Intent.ACTION_DEVICE_STORAGE_LOW)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenReceiver, storageAndScreen, RECEIVER_NOT_EXPORTED)
        else registerReceiver(screenReceiver, storageAndScreen)
        maintainStorage(force = true)
    }
    fun subscribe(listener: (JSONObject) -> Unit) {
        listeners.add(listener)
        recent.forEach(listener)
    }
    fun unsubscribe(listener: (JSONObject) -> Unit) { listeners.remove(listener) }
    fun subscribeChat(listener: (JSONObject) -> Unit) {
        chatListeners.add(listener)
        listener(chatSnapshot())
    }
    fun unsubscribeChat(listener: (JSONObject) -> Unit) { chatListeners.remove(listener) }
    fun sessionCommand(command: JSONObject) {
        sessions.command(command)
        syncOverlayPreference(refreshTool = false)
    }
    private fun diagnosticsIdle(): Boolean = !coordinator.isBusy() && sessions.state.running == null && sessions.state.continuation == null && sessions.state.queue.isEmpty() && sessions.state.controlMode !in setOf("manual", "taking_over", "resuming")
    fun runDiagnostic(action: String): Boolean {
        if (!diagnosticsIdle()) return false
        when (action) {
            "connect" -> coordinator.connect()
            "mock" -> coordinator.runRuntimeGate(JSONObject())
            "close" -> stopSelf()
            else -> return false
        }
        return true
    }
    fun memoryCommand(command: JSONObject, done: (JSONObject) -> Unit) = coordinator.memoryCommand(command, done)

    fun probeModel(connectionId: String, modelId: String, callback: (JSONObject) -> Unit) {
        if (!diagnosticsIdle()) { callback(JSONObject().put("ok", false).put("message", "任务或人工操作尚未结束")); return }
        val store = SettingsStore(this)
        val config = runCatching { store.resolve(store.binding(JSONObject().put("connectionId", connectionId).put("modelId", modelId).put("thinkingLevel", ""))) }
            .getOrElse { callback(JSONObject().put("ok", false).put("message", "模型配置不可用")); return }
        coordinator.probeModel(config, callback)
    }
    private fun syncOverlayPreference(refreshTool: Boolean = true) {
        if (destroying || !::sessions.isInitialized) return
        main.removeCallbacks(overlayExpiry)
        val now = SystemClock.elapsedRealtime()
        val candidate = overlayPresentation.project(sessions.state, now, coordinator.environmentSnapshot().optString("sessionId"), refreshTool)
        overlayPresentation.nextUpdateAtMs?.let { deadline -> main.postDelayed(overlayExpiry, (deadline - now).coerceAtLeast(1)) }
        val visibility = OverlayPresentation.Visibility(
            enabled = getSharedPreferences(packageName + "_preferences", MODE_PRIVATE).getBoolean("execution_overlay_enabled", true),
            permission = Settings.canDrawOverlays(this), shizukuReady = ShizukuMonitor.current() == ShizukuAvailability.READY,
            foreground = mainForeground || resumedActivities.isNotEmpty(),
            locked = screenOff || !getSystemService(android.os.PowerManager::class.java).isInteractive ||
                getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked
        )
        val model = candidate?.takeIf { visibility.allows() }
        if (model == overlayModel) return
        overlayModel = model
        if (model != null && overlay == null) overlay = ExecutionOverlay(this, onOpen = {
            overlayModel?.let { current ->
                startActivity(Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra("overlaySessionId", current.sessionId).apply {
                        current.requestId?.let { putExtra("overlayQuestionRequestId", it) }
                    })
            }
        }, onStop = { sessions.stop(); syncOverlayPreference(refreshTool = false) })
        overlay?.render(model)
    }
    internal fun sessionSnapshot(): JSONObject = chatSnapshot()
    internal fun useLocalSessionTestModel(enabled: Boolean) {
        check(BuildConfig.DEBUG)
        sessions.testConfig = if (enabled) coordinator.localSessionTestConfig() else null
    }
    internal fun useLocalToolTestModel(scenario: String) { check(BuildConfig.DEBUG); sessions.testConfig = coordinator.localSessionTestConfig(scenario) }
    /** Instrumentation keeps the real Activity/service route while preserving the
     * stopped user's controller, continuation, drafts and private state file. */
    internal fun isolateQuestionTestSessions(): AutoCloseable {
        check(BuildConfig.DEBUG && Looper.myLooper() == Looper.getMainLooper())
        val original = sessions
        check(!coordinator.isBusy() && original.state.running == null && original.state.pendingQuestion?.optString("status") != "pending") {
            "Cannot isolate an active user task or question"
        }
        check(original.state.queue.isEmpty() || original.state.paused) { "User queue must be paused" }
        check(original.state.controlMode !in setOf("running", "manual", "taking_over", "resuming")) { "User still controls the execution environment" }
        val directory = java.io.File(cacheDir, "question-route-${java.util.UUID.randomUUID()}").apply { check(mkdirs()) }
        val context = object : android.content.ContextWrapper(this) {
            override fun getNoBackupFilesDir(): java.io.File = directory
        }
        lateinit var isolated: SessionController
        isolated = SessionController(context, coordinator) {
            if (sessions === isolated && !destroying && !flushPending) { flushPending = true; main.postDelayed(flushChat, 50) }
        }
        sessions = isolated
        isolated.testConfig = coordinator.localSessionTestConfig("skill-question")
        try { isolated.refresh() } catch (error: Throwable) {
            sessions = original; isolated.close(); throw error
        }
        var restored = false
        return AutoCloseable {
            check(Looper.myLooper() == Looper.getMainLooper())
            if (!restored) {
                check(sessions === isolated && !coordinator.isBusy() && isolated.state.running == null) { "Stop the isolated fixture before restoring user state" }
                restored = true
                sessions = original
                isolated.close()
                main.removeCallbacks(flushChat); flushPending = false
                flushChat.run()
            }
        }
    }

    fun loadOlderChat() = sessions.command(JSONObject().put("type", "loadOlder").put("sessionId", sessions.state.selected))
    fun recordChatRendered(revision: Long) {
        if (revision == lastRevision && lastProjectionAt > 0) {
            android.util.Log.d("BBUI.ChatTiming", "revision=$revision piEmittedAtMs=${lastTiming.optLong("piEmittedAtMs")} " +
                "runtimeReceivedAtMs=${lastTiming.optLong("runtimeReceivedAtMs")} nativeReceivedAtMs=${lastTiming.optLong("nativeReceivedAtMs")} " +
                "projectionAtMs=$lastProjectionAt renderedAtMs=${System.currentTimeMillis()}")
        }
    }
    override fun onBind(intent: Intent): IBinder = LocalBinder()
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) sessions.stop()
        return START_NOT_STICKY
    }
    /** Compatibility entry points only affect presentation; they never acquire control. */
    fun showOverlay(): Boolean { syncOverlayPreference(); return overlayModel != null }
    fun hideOverlay() { overlayModel = null; overlay?.render(null) }
    override fun onTaskRemoved(rootIntent: Intent?) {
        sessions.stop()
        super.onTaskRemoved(rootIntent)
    }
    override fun onDestroy() {
        destroying = true
        storageWorker.shutdown()
        application.unregisterActivityLifecycleCallbacks(activityCallbacks)
        resumedActivities.clear()
        main.removeCallbacks(idleCheck); main.removeCallbacks(overlayExpiry)
        permissionMonitor.close()
        unregisterReceiver(screenReceiver)
        coordinator.close()
        sessions.accept(JSONObject().put("type", "run_stopped").put("sessionId", sessions.state.running?.optString("sessionId") ?: ""))
        main.removeCallbacks(flushChat)
        sessions.close()
        overlayModel = null; overlay?.dispose(); overlay = null
        listeners.clear()
        chatListeners.clear()
        super.onDestroy()
    }
    companion object {
        private const val CHANNEL = "bbui.execution"
        private const val STOP = "io.bbui.assistant.STOP"
    }
}
