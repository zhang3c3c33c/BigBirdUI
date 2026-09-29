package io.bbui.assistant

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.SystemClock
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Synthetic state only. Main agent owns execution; no Pi prompt, new Pi session or phone tool is used. */
@RunWith(AndroidJUnit4::class)
class ExecutionOverlayTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test(timeout = 180000) fun capsuleDragNavigationStopAndVisibilityUseIsolatedState() {
        val context = instrumentation.targetContext
        assumeTrue("Overlay permission must already be granted; this test never changes authorization", Settings.canDrawOverlays(context))
        assumeTrue("Shizuku must already be ready", ShizukuMonitor.current() == ShizukuAvailability.READY)
        val saved = JSONObject(File(context.noBackupFilesDir, "conversations.json").readText())
        assertNull("Never interrupt user work", saved.optJSONObject("running"))
        assertEquals("Never execute a user's waiting task", 0, saved.optJSONArray("queue")?.length() ?: 0)
        assertNotEquals("Never replace a user question", "pending", saved.optJSONObject("pendingQuestion")?.optString("status"))
        assertFalse(saved.optString("controlMode") in setOf("running", "manual", "taking_over", "resuming"))
        assertTrue("Do not create a Pi session for an empty catalog", (saved.optJSONArray("sessions")?.length() ?: 0) > 0)
        val protectedBefore = protectedFiles(context)
        val positionPrefs = context.getSharedPreferences("execution_overlay_position", Context.MODE_PRIVATE)
        val positionBefore = HashMap(positionPrefs.all)
        val prefs = context.getSharedPreferences(context.packageName + "_preferences", Context.MODE_PRIVATE)
        val hadEnabled = prefs.contains("execution_overlay_enabled")
        val wasEnabled = prefs.getBoolean("execution_overlay_enabled", true)
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        var service: AssistantService? = null
        var original: SessionController? = null
        var isolated: SessionController? = null
        var isolation: AutoCloseable? = null
        var originalHash = ""
        var capsule: ExecutionOverlay? = null
        var failure: Throwable? = null
        val a = "overlay-A-${UUID.randomUUID()}"
        val b = "overlay-B-${UUID.randomUUID()}"
        val request = "overlay-question"
        var openCount = 0
        var stopCount = 0
        var stopService = false
        fun state() = requireNotNull(isolated).state
        fun publish() = onMain { requireNotNull(service).sessionCommand(JSONObject().put("type", "loadOlder").put("sessionId", state().selected)) }
        fun serviceOverlay(): ExecutionOverlay? = onMain { field(requireNotNull(service), "overlay") as? ExecutionOverlay }
        fun attached(overlay: ExecutionOverlay?) = onMain { root(overlay)?.isAttachedToWindow == true }
        fun newCapsule(): ExecutionOverlay = onMain { ExecutionOverlay(context, onOpen = { openCount++ }, onStop = {
            stopCount++
            if (stopService) requireNotNull(service).sessionCommand(JSONObject().put("type", "stop"))
        }) }
        try {
            await("MainActivity service binding") { onMain { service = field(activity, "service") as? AssistantService }; service != null }
            await("catalog ready") { onMain { requireNotNull(service).sessionSnapshot().optBoolean("sessionsReady") } && !requireNotNull(service).coordinator.isBusy() }
            onMain {
                original = field(requireNotNull(service), "sessions") as SessionController
                originalHash = stableState(requireNotNull(original).state)
                isolation = requireNotNull(service).isolateQuestionTestSessions()
                isolated = field(requireNotNull(service), "sessions") as SessionController
            }
            await("isolated catalog history settled") { onMain {
                state().ready && state().selected in (field(requireNotNull(isolated), "loaded") as Set<*>) && !requireNotNull(service).coordinator.isBusy()
            } }
            onMain {
                state().sessions.clear(); state().chats.clear(); state().questionHistory.clear(); state().pendingQuestion = null
                state().queue.clear(); state().running = null; state().continuation = null; state().paused = true; state().transition("idle")
                for (id in listOf(a, b)) {
                    state().sessions[id] = JSONObject().put("id", id).put("title", if (id == a) "测试执行会话A" else "测试浏览会话B").put("modified", 1)
                    state().chats[id] = ChatStore().apply { restore(history(id)) }
                }
                state().selected = b
                @Suppress("UNCHECKED_CAST")
                (field(requireNotNull(isolated), "loaded") as MutableSet<String>).addAll(listOf(a, b))
                prefs.edit().putBoolean("execution_overlay_enabled", true).commit()
            }
            publish()

            // Window interaction is initially callback-only: no fake runtime is involved.
            val running = OverlayPresentation.Model("running", "正在搜索测试列表中的目标内容", a, canStop = true)
            capsule = newCapsule()
            onMain { requireNotNull(capsule).render(running) }
            await("native capsule attached") { attached(capsule) }
            val firstRoot = onMain { requireNotNull(root(capsule)) }
            await("capsule measured") { onMain { firstRoot.width > 0 && firstRoot.height > 0 } }
            val body = onMain { find(firstRoot, "打开执行会话") }
            val stop = onMain { find(firstRoot, "停止任务") }
            assertEquals("bbui_execution_overlay", onMain { firstRoot.tag })
            assertTrue("Independent STOP touch target", onMain { stop.height >= 47 * context.resources.displayMetrics.density })
            val texts = onMain { descendants(firstRoot).filterIsInstance<TextView>().map { it.text.toString() } }
            assertTrue(texts.any { it.contains("正在搜索") })
            assertFalse("No takeover, continue or menu controls", texts.any { it in setOf("接管", "继续", "菜单", "我来操作", "×") })
            onMain { requireNotNull(capsule).render(running.copy(label = "查看下一项测试内容")) }
            assertSame("Intent updates reuse the window", firstRoot, onMain { root(capsule) })
            saveEvidence(context, "execution-overlay-running.png", firstRoot)

            val safe = onMain { safeBounds(context) }
            reportGeometry(context, requireNotNull(capsule), safe, "before-drag")
            drag(body, safe.left + 2f, safe.bottom - 2f)
            reportGeometry(context, requireNotNull(capsule), safe, "after-drag-up")
            try {
                await("drag settled at the bottom edge inside safe display bounds") { onMain {
                    val bounds = bounds(firstRoot)
                    inside(bounds, safe) && kotlin.math.abs(bounds.bottom - safe.bottom) <= 32 * context.resources.displayMetrics.density
                } }
            } catch (error: Throwable) {
                runCatching { reportGeometry(context, requireNotNull(capsule), safe, "drag-timeout") }
                runCatching { saveEvidence(context, "execution-overlay-drag-failure.png", firstRoot) }
                throw error
            }
            assertEquals("Dragging must not open a session", 0, openCount)
            assertEquals("Dragging must not stop execution", 0, stopCount)
            await("position saved after drag") { positionPrefs.contains("right_edge") && positionPrefs.contains("vertical_fraction") }
            val position = onMain { bounds(firstRoot) }
            assertTrue("Capsule snaps to an edge", minOf(kotlin.math.abs(position.left - safe.left), kotlin.math.abs(position.right - safe.right)) <= 32 * context.resources.displayMetrics.density)
            val anchorBeforeKeyboard = HashMap(positionPrefs.all)
            val composerWeb = onMain { field(activity, "chat") as ChatWebView }
            await("composer available for IME geometry test") { evaluate(composerWeb, "!!document.querySelector('.composer-input')") == "true" }
            try {
                evaluate(composerWeb, "document.querySelector('.composer-input').focus(); true")
                onMain {
                    composerWeb.requestFocus()
                    activity.getSystemService(InputMethodManager::class.java).showSoftInput(composerWeb, InputMethodManager.SHOW_IMPLICIT)
                }
                await("real activity IME becomes visible") { onMain { activity.window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true } }
                await("bottom capsule moves above the actual keyboard") { onMain {
                    val inset = activity.window.decorView.rootWindowInsets ?: return@onMain false
                    val screen = activity.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
                    val keyboardTop = screen.bottom - inset.getInsets(WindowInsets.Type.ime()).bottom
                    val moved = bounds(firstRoot)
                    inset.isVisible(WindowInsets.Type.ime()) && inside(moved, safe) && moved.bottom <= keyboardTop + 2 && moved.top < position.top
                } }
                reportGeometry(context, requireNotNull(capsule), safe, "keyboard-visible")
                assertEquals("Keyboard avoidance must not persist a different drag anchor", anchorBeforeKeyboard, positionPrefs.all)
                assertEquals("Keyboard test never navigates", 0, openCount)
                assertEquals("Keyboard test never stops", 0, stopCount)
                saveEvidence(context, "execution-overlay-keyboard.png", firstRoot)
            } catch (error: Throwable) {
                runCatching { reportGeometry(context, requireNotNull(capsule), safe, "keyboard-failure") }
                throw error
            } finally {
                onMain {
                    activity.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(composerWeb.windowToken, 0)
                    composerWeb.evaluateJavascript("document.querySelector('.composer-input')?.blur()", null)
                }
            }
            await("keyboard hidden") { onMain { activity.window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) != true } }
            await("keyboard close restores the saved edge anchor") { onMain {
                val restored = bounds(firstRoot)
                inside(restored, safe) && kotlin.math.abs(restored.top - position.top) < 5 && kotlin.math.abs(restored.left - position.left) < 5
            } }
            assertEquals(anchorBeforeKeyboard, positionPrefs.all)
            awaitInputStable(firstRoot)
            reportGeometry(context, requireNotNull(capsule), safe, "keyboard-hidden")
            onMain { requireNotNull(capsule).dispose() }
            capsule = newCapsule()
            onMain { requireNotNull(capsule).render(running) }
            await("saved position restored") { onMain { root(capsule)?.let { it.width > 0 && kotlin.math.abs(bounds(it).top - position.top) < 5 && inside(bounds(it), safe) } == true } }
            awaitInputStable(onMain { requireNotNull(root(capsule)) })
            reportGeometry(context, requireNotNull(capsule), safe, "before-restored-body-tap")
            tap(onMain { find(requireNotNull(root(capsule)), "打开执行会话") })
            await("body click calls open once") { openCount == 1 }
            val restoredStop = onMain { find(requireNotNull(root(capsule)), "停止任务") }
            onMain { restoredStop.performClick(); restoredStop.performClick() }
            assertEquals("A rapid repeated STOP calls its callback only once", 1, stopCount)
            assertEquals("STOP is isolated from body navigation", 1, openCount)
            onMain { requireNotNull(capsule).render(null) }
            assertFalse(attached(capsule))
            onMain { requireNotNull(capsule).dispose() }; capsule = null

            // The production overlay reads A's execution owner while the user browses B.
            onMain {
                state().running = JSONObject().put("id", "overlay-synthetic-run").put("sessionId", a).put("runId", "424242").put("started", true)
                state().transition("running")
                state().chat(a).accept(JSONObject().put("type", "run_started").put("sessionId", a).put("runId", "424242"))
                state().pendingQuestion = question(a, request)
                state().questionHistory.add(requireNotNull(state().pendingQuestion))
                state().queue.add(JSONObject().put("id", "overlay-waiting").put("sessionId", b).put("text", "仅本地等待样本，不得执行").put("submittedAt", 1))
                state().paused = true
            }
            publish()
            await("BBUI foreground hides its service overlay") { !attached(serviceOverlay()) }
            val runningBefore = onMain { state().running.toString() }
            val queueBefore = onMain { JSONArray(state().queue).toString() }
            onMain { assertTrue(activity.moveTaskToBack(true)) }
            await("background activity reaches stopped lifecycle") { onMain {
                ActivityLifecycleMonitorRegistry.getInstance().getLifecycleStageOf(activity) == Stage.STOPPED
            } }
            await("background waiting capsule appears") { attached(serviceOverlay()) }
            val liveModel = onMain { field(requireNotNull(service), "overlayModel") as OverlayPresentation.Model }
            assertEquals(a, liveModel.sessionId); assertEquals(request, liveModel.requestId); assertEquals("waiting", liveModel.kind)
            saveEvidence(context, "execution-overlay-waiting.png", onMain { requireNotNull(root(serviceOverlay())) })
            val productionBody = onMain { find(requireNotNull(root(serviceOverlay())), "打开执行会话") }
            awaitInputStable(productionBody)
            reportGeometry(context, requireNotNull(serviceOverlay()), safe, "before-production-body-tap")
            captureInputDiagnostic(context, activity, productionBody, "before-production-body-tap")
            try {
                tap(productionBody)
            } catch (error: Throwable) {
                runCatching { captureInputDiagnostic(context, activity, productionBody, "production-body-tap-failed") }
                // Private diagnostic only: the background can contain user data.
                // Keep it in app cache; do not publish it as synthetic UI evidence.
                runCatching {
                    instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                        try {
                            File(context.cacheDir, "execution-overlay-private-input-failure.png").outputStream().use {
                                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                            }
                        } finally { bitmap.recycle() }
                    }
                }
                throw error
            }
            await("production body click returns to execution session A") { onMain { state().selected == a } }
            await("return to BBUI hides capsule") { !attached(serviceOverlay()) }
            val web = onMain { field(activity, "chat") as ChatWebView }
            await("pending question is located in viewport without keyboard focus") {
                evaluate(web, "(() => { const e=document.querySelector('[data-request-id='+CSS.escape(${JSONObject.quote(request)})+']'); const v=document.querySelector('.viewport');if(!e||!v)return false;const r=e.getBoundingClientRect(),b=v.getBoundingClientRect();return r.bottom>b.top&&r.top<b.bottom&&document.activeElement?.tagName!=='TEXTAREA'; })()") == "true"
            }
            assertEquals("Navigation cannot replace execution", runningBefore, onMain { state().running.toString() })
            assertEquals("Navigation cannot drain waiting work", queueBefore, onMain { JSONArray(state().queue).toString() })
            assertFalse("No real executor was started", requireNotNull(service).coordinator.isBusy())

            // STOP uses the service route once; the independent window supplies a
            // deterministic repeated click without ever tapping through to another app.
            stopCount = 0; stopService = true
            capsule = newCapsule()
            onMain { requireNotNull(capsule).render(running) }
            await("STOP test window attached") { attached(capsule) }
            val serviceStop = onMain { find(requireNotNull(root(capsule)), "停止任务") }
            tap(serviceStop)
            onMain { serviceStop.performClick() }
            assertEquals(1, stopCount)
            await("service stop settled") { !requireNotNull(service).coordinator.isBusy() }
            assertTrue(onMain { state().paused && "stop" in state().pauseReasons })
            assertNull(onMain { state().running })
            assertEquals("STOP preserves waiting tasks", queueBefore, onMain { JSONArray(state().queue).toString() })
            onMain { requireNotNull(capsule).render(OverlayPresentation.Model("stopped", "已停止", a, canStop = false)) }
            assertFalse("Terminal STOP is unavailable", onMain { serviceStop.isEnabled && serviceStop.visibility == View.VISIBLE })
            onMain { requireNotNull(capsule).render(null) }
            assertFalse(attached(capsule))
        } catch (error: Throwable) { failure = error }
        finally {
            try {
                onMain { capsule?.dispose() }
                if (service != null) await("coordinator idle before restoring original controller") { !requireNotNull(service).coordinator.isBusy() }
                onMain {
                    isolated?.state?.let { state ->
                        state.running = null; state.queue.clear(); state.pendingQuestion = null; state.continuation = null; state.transition("idle")
                        state.remove(a); state.remove(b)
                    }
                    isolation?.close()
                    val edit = prefs.edit()
                    if (hadEnabled) edit.putBoolean("execution_overlay_enabled", wasEnabled) else edit.remove("execution_overlay_enabled")
                    edit.commit(); restorePreferences(positionPrefs, positionBefore)
                    if (original != null) {
                        assertSame(original, field(requireNotNull(service), "sessions"))
                        assertEquals("User controller and message caches unchanged", originalHash, stableState(requireNotNull(original).state))
                    }
                }
                assertEquals("User histories, memories and credentials preserved", protectedBefore, protectedFiles(context))
                assertEquals("Saved overlay position restored", positionBefore, positionPrefs.all)
            } catch (error: Throwable) { if (failure == null) failure = error else failure!!.addSuppressed(error) }
            finally { onMain { activity.finish() } }
        }
        failure?.let { throw it }
    }

    @Test fun terminalExpiryAndVisibilityDoNotRequireRealPermissionChanges() {
        val store = ConversationStore()
        store.running = JSONObject().put("sessionId", "A").put("id", "local").put("runId", "1")
        store.transition("running")
        val projection = OverlayPresentation()
        assertEquals("running", projection.project(store, 1000)!!.kind)
        store.running = null; store.transition("idle")
        assertEquals("complete", projection.project(store, 1100)!!.kind)
        assertEquals("complete", projection.project(store, 4099)!!.kind)
        assertNull("Terminal status expires without extending on refresh", projection.project(store, 4100))
        val visible = OverlayPresentation.Visibility(true, true, true, false, false)
        assertTrue(visible.allows())
        assertFalse(visible.copy(foreground = true).allows())
        assertFalse(visible.copy(shizukuReady = false).allows())
        assertFalse(visible.copy(permission = false).allows())
        assertFalse(visible.copy(locked = true).allows())
        assertFalse(visible.copy(enabled = false).allows())
    }

    private fun history(id: String) = JSONObject().put("sessionId", id).put("messages", JSONArray().put(JSONObject()
        .put("id", "message-$id").put("sourceKey", "source-$id").put("role", "assistant").put("status", "complete")
        .put("parts", JSONArray().put(JSONObject().put("id", "text").put("type", "text").put("state", "complete").put("text", "本地悬浮状态验收，不执行手机任务。")))))
    private fun question(id: String, request: String) = JSONObject().put("sessionId", id).put("runId", "424242").put("requestId", request)
        .put("toolCallId", "overlay-question-call").put("status", "pending").put("questions", JSONArray().put(JSONObject()
            .put("id", "target").put("header", "收件人").put("question", "发给谁？").put("multiSelect", false).put("options", JSONArray())))
    private fun root(overlay: ExecutionOverlay?): View? = overlay?.let { field(it, "root") as? View }
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun find(root: View, description: String): View = descendants(root).first { it.contentDescription?.toString()?.let { text -> text == description || text.startsWith("$description：") } == true }
    private fun bounds(view: View): Rect { val point = IntArray(2); view.getLocationOnScreen(point); return Rect(point[0], point[1], point[0] + view.width, point[1] + view.height) }
    private fun safeBounds(context: Context): Rect {
        val metrics = context.getSystemService(WindowManager::class.java).currentWindowMetrics
        val inset = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        return Rect(metrics.bounds).apply { left += inset.left; right -= inset.right; top += inset.top; bottom -= inset.bottom }
    }
    private fun inside(child: Rect, parent: Rect) = child.width() > 0 && child.height() > 0 && child.left >= parent.left - 2 && child.top >= parent.top - 2 && child.right <= parent.right + 2 && child.bottom <= parent.bottom + 2
    private fun tap(view: View) {
        awaitInputStable(view)
        val r = onMain { bounds(view) }
        instrumentation.sendStatus(0, android.os.Bundle().apply {
            putString("stream", "BBUI_OVERLAY_TAP target=${onMain { view.contentDescription }} bounds=${r.toShortString()}\n")
        })
        // Inject exactly once. A failed DOWN must fail the test, never retry an
        // uncertain tap against a different input window.
        pointer(r.exactCenterX(), r.exactCenterY(), r.exactCenterX(), r.exactCenterY(), 1)
    }
    private fun awaitInputStable(view: View) {
        instrumentation.waitForIdleSync()
        instrumentation.uiAutomation.waitForIdle(200, 3000)
        val done = CountDownLatch(1)
        val frames = object : Runnable {
            var previous: Rect? = null
            var stable = 0
            override fun run() {
                val current = bounds(view)
                stable = if (view.isAttachedToWindow && view.isShown && !view.isLayoutRequested &&
                    current.width() > 0 && current.height() > 0 && current == previous) stable + 1 else 0
                previous = current
                if (stable >= 3) done.countDown() else view.postOnAnimation(this)
            }
        }
        onMain { view.postOnAnimation(frames) }
        try {
            assertTrue("Overlay input geometry must settle before a single real tap", done.await(3000, TimeUnit.MILLISECONDS))
        } finally {
            onMain { view.removeCallbacks(frames) }
        }
        instrumentation.waitForIdleSync()
    }
    private fun drag(view: View, x: Float, y: Float) { val r = onMain { bounds(view) }; pointer(r.exactCenterX(), r.exactCenterY(), x, y, 24) }
    private fun pointer(x0: Float, y0: Float, x1: Float, y1: Float, steps: Int) {
        val down = SystemClock.uptimeMillis()
        fun send(action: Int, x: Float, y: Float) { val e = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0); try { instrumentation.sendPointerSync(e) } finally { e.recycle() } }
        send(MotionEvent.ACTION_DOWN, x0, y0)
        try { for (i in 1..steps) { SystemClock.sleep(16); if (steps > 1) send(MotionEvent.ACTION_MOVE, x0 + (x1 - x0) * i / steps, y0 + (y1 - y0) * i / steps) } }
        finally { send(MotionEvent.ACTION_UP, x1, y1) }
    }
    private fun saveEvidence(context: Context, name: String, view: View) {
        // Only draw the synthetic native capsule; never capture the unrelated app
        // or launcher which happens to be underneath the background overlay.
        val bitmap = onMain { Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) } }
        try { File(context.cacheDir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } } finally { bitmap.recycle() }
    }
    private fun reportGeometry(context: Context, overlay: ExecutionOverlay, safe: Rect, phase: String) {
        val diagnostic = onMain {
            val view = requireNotNull(root(overlay))
            val layout = field(overlay, "layout") as WindowManager.LayoutParams
            JSONObject().put("phase", phase).put("safe", safe.toShortString()).put("actual", bounds(view).toShortString())
                .put("measuredWidth", view.measuredWidth).put("measuredHeight", view.measuredHeight).put("attached", view.isAttachedToWindow)
                .put("layout", JSONObject().put("x", layout.x).put("y", layout.y).put("width", layout.width).put("height", layout.height))
                .put("currentFrame", field(overlay, "currentFrame").toString()).put("anchor", field(overlay, "anchor").toString())
                .put("dragging", field(view, "dragging")).put("cancelled", field(view, "cancelled"))
                .put("rootInsets", view.rootWindowInsets?.toString()?.take(1600) ?: "none")
                .put("preferences", JSONObject(context.getSharedPreferences("execution_overlay_position", Context.MODE_PRIVATE).all))
        }
        File(context.cacheDir, "execution-overlay-$phase.json").writeText(diagnostic.toString())
        instrumentation.sendStatus(0, android.os.Bundle().apply { putString("stream", "BBUI_OVERLAY_GEOMETRY $diagnostic\n") })
    }
    private fun captureInputDiagnostic(context: Context, activity: MainActivity, target: View, phase: String) {
        val state = onMain {
            val decor = activity.window.decorView
            val visible = Rect()
            val globallyVisible = target.getGlobalVisibleRect(visible)
            JSONObject().put("phase", phase).put("uptimeMs", SystemClock.uptimeMillis())
                .put("activityStage", ActivityLifecycleMonitorRegistry.getInstance().getLifecycleStageOf(activity).name)
                .put("activityFinishing", activity.isFinishing).put("activityDestroyed", activity.isDestroyed)
                .put("activityWindowFocus", activity.hasWindowFocus()).put("decorAttached", decor.isAttachedToWindow)
                .put("decorShown", decor.isShown).put("decorWindowVisibility", decor.windowVisibility)
                .put("decorBounds", bounds(decor).toShortString()).put("activityWindowFlags", activity.window.attributes.flags)
                .put("targetAttached", target.isAttachedToWindow).put("targetShown", target.isShown)
                .put("targetWindowVisibility", target.windowVisibility).put("targetBounds", bounds(target).toShortString())
                .put("targetGloballyVisible", globallyVisible).put("targetVisibleRect", visible.toShortString())
                .put("targetWindowFocus", target.hasWindowFocus()).put("targetAlpha", target.alpha)
        }
        File(context.cacheDir, "execution-overlay-$phase-input.json").writeText(state.toString())
        instrumentation.sendStatus(0, android.os.Bundle().apply { putString("stream", "BBUI_OVERLAY_INPUT $state\n") })
        for ((name, command) in listOf("windows" to "dumpsys window windows", "input" to "dumpsys input")) {
            val file = File(context.cacheDir, "execution-overlay-$phase-$name.txt")
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
        }
    }
    private fun field(target: Any, name: String): Any? = target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)
    private fun <T> onMain(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val value = AtomicReference<Result<T>>(); instrumentation.runOnMainSync { value.set(runCatching(block)) }; return value.get().getOrThrow()
    }
    private fun evaluate(web: ChatWebView, script: String): String {
        val done = CountDownLatch(1); val value = AtomicReference("")
        onMain { web.evaluateJavascript(script) { value.set(it); done.countDown() } }
        return if (done.await(2000, TimeUnit.MILLISECONDS)) value.get() else "<context-transition>"
    }
    private fun await(label: String, check: () -> Boolean) { val end = SystemClock.uptimeMillis() + 30000; while (SystemClock.uptimeMillis() < end) { if (check()) return; SystemClock.sleep(75) }; fail("Timed out: $label") }
    private fun stableState(state: ConversationStore): String {
        val value = state.save(); value.optJSONObject("chats")?.let { rows -> rows.keys().forEach { rows.getJSONObject(it).remove("timing") } }; return hash(value.toString().toByteArray())
    }
    private fun protectedFiles(context: Context): Map<String, String> = listOf("pi-agent/sessions", "user-memory", "model.enc", "search.enc").flatMap { name ->
        val file = File(context.noBackupFilesDir, name); if (file.isFile) listOf(file) else if (file.isDirectory) file.walkTopDown().filter { it.isFile }.toList() else emptyList()
    }.associate { it.relativeTo(context.noBackupFilesDir).path to hash(it.readBytes()) }
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun restorePreferences(prefs: SharedPreferences, values: Map<String, *>) {
        val editor = prefs.edit().clear()
        for ((key, value) in values) when (value) {
            is Boolean -> editor.putBoolean(key, value); is Float -> editor.putFloat(key, value); is Long -> editor.putLong(key, value)
            is Int -> editor.putInt(key, value); is String -> editor.putString(key, value)
            is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
        }
        check(editor.commit())
    }
}
