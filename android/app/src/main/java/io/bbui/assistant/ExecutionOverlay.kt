package io.bbui.assistant

import android.content.ComponentCallbacks
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.RippleDrawable
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.hypot
import kotlin.math.roundToInt

/** Small native overlay. Service owns the model; this class owns only presentation and position. */
internal class ExecutionOverlay(context: Context, private val onOpen: () -> Unit, private val onStop: () -> Unit) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val displays = app.getSystemService(DisplayManager::class.java)
    private val windowContext = runCatching {
        app.createDisplayContext(checkNotNull(displays.getDisplay(Display.DEFAULT_DISPLAY)))
            .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
    }.getOrElse { app }
    private val manager = windowContext.getSystemService(WindowManager::class.java)
    private val preferences = app.getSharedPreferences("execution_overlay_position", Context.MODE_PRIVATE)
    private var anchor = OverlayPosition.Anchor(preferences.getBoolean("right_edge", true), preferences.getFloat("vertical_fraction", 0.35f))
    private var model: OverlayPresentation.Model? = null
    private var attached = false
    private var disposed = false
    private var observing = false
    private var placing = false
    private var appliedInsets: WindowInsets? = null
    private val retryDelays = longArrayOf(200, 800, 2000)
    private var retryAttempt = 0
    private var retryScheduled = false
    private val retryAttach = Runnable {
        retryScheduled = false
        val latest = model
        if (disposed || latest == null || !hasPermission()) cancelRetry()
        else render(latest)
    }
    private fun hasPermission() = runCatching { Settings.canDrawOverlays(app) }.getOrDefault(false)
    private fun cancelRetry() { main.removeCallbacks(retryAttach); retryScheduled = false; retryAttempt = 0 }
    private fun retryAttachment() {
        if (disposed || model == null || !hasPermission()) { cancelRetry(); return }
        if (retryScheduled || retryAttempt >= retryDelays.size) return
        retryScheduled = true
        main.postDelayed(retryAttach, retryDelays[retryAttempt++])
    }
    private val stopGate = OverlayStopGate()
    private var currentFrame = OverlayPosition.Frame(0, 0, 1, 1)
    private val layout = WindowManager.LayoutParams(1, 1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        // Android's non-focusable IME-aware combination keeps keyboard focus in the underlying app.
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT).apply {
        gravity = Gravity.TOP or Gravity.LEFT
        softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        // All positions below are in display coordinates; apply bars/cutout/IME insets exactly once.
        setFitInsetsTypes(0)
        title = app.getString(R.string.execution_status_title)
    }
    private val root = CapsuleLayout(windowContext)
    private val body = LinearLayout(windowContext)
    private val label = TextView(windowContext)
    private val stop = FrameLayout(windowContext)
    private val divider = View(windowContext)
    private val globalLayout = ViewTreeObserver.OnGlobalLayoutListener { if (attached) place() }
    private val callbacks = object : ComponentCallbacks {
        override fun onConfigurationChanged(configuration: Configuration) { root.cancelGesture(); appliedInsets = null; root.requestApplyInsets(); place() }
        override fun onLowMemory() { }
    }
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) { }
        override fun onDisplayRemoved(displayId: Int) { if (displayId == Display.DEFAULT_DISPLAY) hide() }
        override fun onDisplayChanged(displayId: Int) { if (displayId == Display.DEFAULT_DISPLAY) { root.cancelGesture(); appliedInsets = null; root.requestApplyInsets(); place() } }
    }
    init {
        root.apply {
            tag = "bbui_execution_overlay"
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = UiStyle.shape(windowContext, UiStyle.surface, 24, stroke = true)
            clipToOutline = true; elevation = dp(3).toFloat()
            setOnApplyWindowInsetsListener { _, insets ->
                val changed = appliedInsets != insets
                appliedInsets = insets
                // Moving this small window can change its local insets. It must not cancel
                // the drag and snap back to the old saved anchor. MOVE clamps against the
                // latest full-display area; UP persists the new anchor and places it.
                if (changed && !gestureDragging) place()
                insets
            }
        }
        body.apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), 0, dp(4), 0)
            background = RippleDrawable(ColorStateList.valueOf(0x183F6340), UiStyle.shape(windowContext, Color.TRANSPARENT, 24), UiStyle.shape(windowContext, Color.WHITE, 24))
            contentDescription = "打开执行会话"
            isClickable = true; isFocusable = true
            setOnClickListener { if (model != null && attached) runCatching(onOpen)
                .onFailure { android.util.Log.w("BBUI.Overlay", "Could not open conversation", it) } }
        }
        body.addView(ImageView(windowContext).apply {
            setImageResource(R.drawable.ic_bbui_mark); scaleType = ImageView.ScaleType.FIT_CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(26), dp(28)).apply { marginEnd = dp(6) })
        label.apply {
            textSize = 13f; setTextColor(UiStyle.text)
            isSingleLine = true; ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false; gravity = Gravity.CENTER_VERTICAL
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        body.addView(label, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        root.addView(body, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        divider.setBackgroundColor(UiStyle.border)
        root.addView(divider, LinearLayout.LayoutParams(dp(1).coerceAtLeast(1), dp(20)))
        stop.apply {
            tag = "bbui_overlay_stop"; contentDescription = app.getString(R.string.stop_task)
            isClickable = true; isFocusable = true
            background = RippleDrawable(ColorStateList.valueOf(0x20A05041), UiStyle.shape(windowContext, Color.TRANSPARENT, 24), UiStyle.shape(windowContext, Color.WHITE, 24))
            setOnClickListener {
                val current = model ?: return@setOnClickListener
                if (!attached || !stopGate.request(current.canStop)) return@setOnClickListener
                updateStop()
                try { onStop() } catch (error: RuntimeException) {
                    stopGate.retry(); updateStop()
                    android.util.Log.w("BBUI.Overlay", "Stop callback failed", error)
                }
            }
        }
        stop.addView(View(windowContext).apply {
            background = UiStyle.shape(windowContext, UiStyle.danger, 2)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, FrameLayout.LayoutParams(dp(12), dp(12), Gravity.CENTER))
        root.addView(stop, LinearLayout.LayoutParams(dp(48), LinearLayout.LayoutParams.MATCH_PARENT))
    }
    fun render(value: OverlayPresentation.Model?) {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { render(value) }; return }
        if (disposed) return
        model = value; stopGate.update(value?.sessionId, value?.canStop == true)
        if (value == null || !hasPermission()) { cancelRetry(); hide(); return }
        if (label.text.toString() != value.label) label.text = value.label
        val color = when (value.kind) { "waiting" -> UiStyle.primary; "error" -> UiStyle.danger; else -> UiStyle.text }
        if (label.currentTextColor != color) label.setTextColor(color)
        val description = "打开执行会话：${value.label}"
        if (body.contentDescription != description) body.contentDescription = description
        updateStop()
        if (!attached) {
            if (!place()) return
            try {
                manager.addView(root, layout); attached = true
                observe(); root.requestApplyInsets()
            } catch (error: RuntimeException) {
                hide(); retryAttachment(); android.util.Log.w("BBUI.Overlay", "Overlay unavailable", error)
            }
        }
    }
    private fun updateStop() {
        val enabled = model?.canStop == true && !stopGate.requested
        if (stop.isEnabled != enabled) stop.isEnabled = enabled
        val opacity = if (enabled) 1f else 0.3f
        if (stop.alpha != opacity) stop.alpha = opacity
    }
    private fun observe() {
        if (observing) return
        observing = true
        root.viewTreeObserver.addOnGlobalLayoutListener(globalLayout)
        windowContext.registerComponentCallbacks(callbacks)
        displays.registerDisplayListener(displayListener, main)
    }
    private fun area(): OverlayPosition.Area {
        val metrics = manager.maximumWindowMetrics
        val bounds = metrics.bounds
        val system = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        val keyboard = metrics.windowInsets.getInsets(WindowInsets.Type.ime())
        // Some OEMs resize the visible display frame instead of reporting IME insets to a
        // non-focusable overlay. Only accept a display-wide frame, never our 48dp local bounds.
        val visible = Rect()
        if (attached) root.getWindowVisibleDisplayFrame(visible)
        val visibleBottom = if (visible.width() >= bounds.width() * 0.8f && visible.height() > dp(96))
            (bounds.bottom - visible.bottom).coerceAtLeast(0) else 0
        return OverlayPosition.Area(bounds.width(), bounds.height(),
            OverlayPosition.Insets(system.left, system.top, system.right, system.bottom),
            // Local onApplyWindowInsets values are relative to the tiny capsule, not the
            // display. They trigger remeasurement but never become full-screen geometry.
            OverlayPosition.Insets(keyboard.left, keyboard.top, keyboard.right, maxOf(keyboard.bottom, visibleBottom)))
    }
    private fun dp(value: Int) = UiStyle.dp(windowContext, value)
    private fun density() = windowContext.resources.displayMetrics.density
    private fun place(frame: OverlayPosition.Frame? = null): Boolean {
        if (disposed) return false
        if (placing || (frame == null && root.gestureDragging)) return true
        placing = true
        try {
            val positioned = frame ?: OverlayPosition.place(area(), anchor, density())
            currentFrame = positioned
            if (layout.x != positioned.x || layout.y != positioned.y || layout.width != positioned.width || layout.height != positioned.height) {
                layout.x = positioned.x; layout.y = positioned.y; layout.width = positioned.width; layout.height = positioned.height
                if (attached) manager.updateViewLayout(root, layout)
            }
            // Reset only after a successful attached layout, not merely addView returning.
            if (attached && root.isAttachedToWindow) cancelRetry()
            return true
        } catch (error: RuntimeException) {
            hide(); retryAttachment(); android.util.Log.w("BBUI.Overlay", "Overlay layout unavailable", error)
            return false
        } finally { placing = false }
    }
    private fun hide() {
        root.cancelGesture()
        if (observing) {
            runCatching { if (root.viewTreeObserver.isAlive) root.viewTreeObserver.removeOnGlobalLayoutListener(globalLayout) }
            runCatching { windowContext.unregisterComponentCallbacks(callbacks) }
            runCatching { displays.unregisterDisplayListener(displayListener) }
            observing = false
        }
        if (attached || root.isAttachedToWindow) runCatching { manager.removeViewImmediate(root) }
        attached = false; appliedInsets = null
    }
    fun dispose() {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { dispose() }; return }
        if (disposed) return
        cancelRetry(); hide(); model = null; stopGate.update(null, false); disposed = true
    }
    private inner class CapsuleLayout(context: Context) : LinearLayout(context) {
        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        private var startX = 0f; private var startY = 0f
        private var startFrame = currentFrame
        private var dragging = false; private var cancelled = false
        val gestureDragging: Boolean get() = dragging
        fun cancelGesture() { dragging = false; cancelled = true }
        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX; startY = event.rawY; startFrame = currentFrame
                    dragging = false; cancelled = false
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                    super.dispatchTouchEvent(cancel); cancel.recycle(); cancelled = true
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (cancelled) return true
                    if (!dragging && hypot((event.rawX - startX).toDouble(), (event.rawY - startY).toDouble()) > slop) {
                        dragging = true
                        val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                        super.dispatchTouchEvent(cancel); cancel.recycle()
                    }
                    if (dragging) {
                        runCatching { place(OverlayPosition.drag(area(), startFrame,
                            startFrame.x + (event.rawX - startX).roundToInt(), startFrame.y + (event.rawY - startY).roundToInt(), density())) }
                        return true
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (cancelled) { dragging = false; place(); return true }
                    if (dragging) {
                        dragging = false
                        runCatching {
                            anchor = OverlayPosition.anchor(area(), currentFrame, density())
                            preferences.edit().putBoolean("right_edge", anchor.rightEdge).putFloat("vertical_fraction", anchor.fraction).apply()
                            place()
                        }
                        return true
                    }
                }
                MotionEvent.ACTION_CANCEL -> { if (dragging) { dragging = false; place() }; cancelled = true }
            }
            return super.dispatchTouchEvent(event)
        }
    }
}

/** Suppress a rapid second stop before service state arrives. */
internal class OverlayStopGate {
    private var sessionId: String? = null
    var requested = false
        private set
    fun update(session: String?, canStop: Boolean) {
        if (session == null || session != sessionId || !canStop) requested = false
        sessionId = session
    }
    fun request(canStop: Boolean): Boolean {
        if (!canStop || requested) return false
        requested = true; return true
    }
    fun retry() { requested = false }
}
