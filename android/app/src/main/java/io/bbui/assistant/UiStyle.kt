package io.bbui.assistant

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.widget.Button

/** Shared native palette and controls; matches the bundled chat surface. */
internal object UiStyle {
    val background = Color.rgb(250, 249, 246)
    val surface = Color.rgb(255, 254, 251)
    val secondary = Color.rgb(237, 240, 233)
    val border = Color.rgb(220, 225, 216)
    val text = Color.rgb(48, 59, 53)
    val muted = Color.rgb(113, 128, 118)
    val primary = Color.rgb(63, 99, 64)
    val danger = Color.rgb(160, 80, 65)
    val dangerSurface = Color.rgb(247, 233, 226)

    fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density).toInt()
    fun shape(context: Context, color: Int, radius: Int = 12, stroke: Boolean = false) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(context, radius).toFloat()
        if (stroke) setStroke(dp(context, 1), border)
    }
    fun button(button: Button, primaryAction: Boolean = false, destructive: Boolean = false, quiet: Boolean = false) {
        val context = button.context
        button.apply {
            isAllCaps = false
            textSize = 14f
            minWidth = 0; minimumWidth = 0
            minHeight = dp(context, 44); minimumHeight = dp(context, 44)
            setPadding(dp(context, 14), dp(context, 6), dp(context, 14), dp(context, 6))
            setTextColor(when { destructive -> danger; primaryAction -> Color.WHITE; else -> primary })
            val fill = when { quiet -> Color.TRANSPARENT; destructive -> dangerSurface; primaryAction -> primary; else -> secondary }
            background = RippleDrawable(ColorStateList.valueOf(0x223F6340), shape(context, fill), shape(context, Color.WHITE))
            backgroundTintList = null
            stateListAnimator = null
            elevation = 0f
        }
    }
}
