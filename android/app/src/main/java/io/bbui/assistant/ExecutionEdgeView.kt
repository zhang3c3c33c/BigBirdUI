package io.bbui.assistant

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import android.view.View
import android.view.animation.LinearInterpolator
import io.bbui.core.PreviewViewport

/** Native preview decoration only: never part of the virtual display or its captured frames. */
internal class ExecutionEdgeView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val matrix = Matrix()
    private val bounds = RectF()
    private var gradient: SweepGradient? = null
    private var angle = 0f
    private var animator: ValueAnimator? = null
    private var active = false
    private var moving = false
    private var videoWidth = 0
    private var videoHeight = 0

    init {
        visibility = GONE
        isClickable = false; isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun setHostControl(value: Boolean, animate: Boolean = false) {
        if (active == value && moving == animate) return
        active = value
        moving = animate
        visibility = if (value) VISIBLE else GONE
        refreshAnimation()
    }

    private fun refreshAnimation() {
        val animate = active && moving && isAttachedToWindow && isShown && windowVisibility == VISIBLE && ValueAnimator.areAnimatorsEnabled()
        if (!animate) { animator?.cancel(); animator = null; invalidate(); return }
        if (animator != null) return
        animator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 9000; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
            addUpdateListener {
                angle = it.animatedValue as Float
                if (!ValueAnimator.areAnimatorsEnabled()) refreshAnimation() else invalidate()
            }
            start()
        }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); refreshAnimation() }
    override fun onDetachedFromWindow() { animator?.cancel(); animator = null; super.onDetachedFromWindow() }
    override fun onVisibilityAggregated(isVisible: Boolean) { super.onVisibilityAggregated(isVisible); refreshAnimation() }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateBounds()
    }

    fun setVideoSize(width: Int, height: Int) {
        if (width == videoWidth && height == videoHeight) return
        videoWidth = width; videoHeight = height
        updateBounds()
    }

    internal fun pictureBounds(): RectF = RectF(bounds)

    private fun updateBounds() {
        val viewport = PreviewViewport.fit(width, height, videoWidth, videoHeight)
        if (viewport == null) { bounds.setEmpty(); invalidate(); return }
        bounds.set(viewport.left.toFloat(), viewport.top.toFloat(),
            (viewport.left + viewport.width).toFloat(), (viewport.top + viewport.height).toFloat())
        gradient = SweepGradient(bounds.centerX(), bounds.centerY(), intArrayOf(
            Color.rgb(69, 185, 153), Color.rgb(80, 163, 225), Color.rgb(151, 112, 220),
            Color.rgb(228, 151, 170), Color.rgb(69, 185, 153)
        ), null)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!active || bounds.isEmpty) return
        matrix.setRotate(angle, bounds.centerX(), bounds.centerY())
        gradient?.setLocalMatrix(matrix); paint.shader = gradient
        val saved = canvas.save()
        // Clip all color inside the actual rectangular image, never into the letterbox bars.
        canvas.clipRect(bounds)
        for (depth in 24 downTo 1) {
            paint.strokeWidth = depth * 2 * density; paint.alpha = 3 + (24 - depth) / 3
            canvas.drawRect(bounds, paint)
        }
        paint.strokeWidth = 4 * density; paint.alpha = 255
        canvas.drawRect(bounds, paint)
        canvas.restoreToCount(saved)
    }
}
