package io.bbui.assistant

/** Pixel geometry independent of Android; keyboard clamping never changes the saved anchor. */
internal object OverlayPosition {
    data class Insets(val left: Int = 0, val top: Int = 0, val right: Int = 0, val bottom: Int = 0)
    data class Anchor(val rightEdge: Boolean = true, val fraction: Float = 0.35f)
    data class Frame(val x: Int, val y: Int, val width: Int, val height: Int)
    data class Area(val width: Int, val height: Int, val system: Insets, val keyboard: Insets = Insets())
    private fun fraction(value: Float) = if (value.isFinite()) value.coerceIn(0f, 1f) else 0.35f
    private fun xRange(area: Area, width: Int, margin: Int): IntRange {
        val low = (maxOf(area.system.left, area.keyboard.left) + margin).coerceAtMost((area.width - width).coerceAtLeast(0))
        val high = (area.width - maxOf(area.system.right, area.keyboard.right) - margin - width).coerceAtLeast(low)
        return low..high
    }
    private fun yRange(area: Area, height: Int, margin: Int, keyboard: Boolean): IntRange {
        val low = (maxOf(area.system.top, if (keyboard) area.keyboard.top else 0) + margin).coerceAtMost((area.height - height).coerceAtLeast(0))
        val high = (area.height - maxOf(area.system.bottom, if (keyboard) area.keyboard.bottom else 0) - margin - height).coerceAtLeast(low)
        return low..high
    }
    fun place(area: Area, anchor: Anchor, density: Float): Frame {
        val margin = (8 * density).toInt().coerceAtLeast(0)
        val availableWidth = (area.width - maxOf(area.system.left, area.keyboard.left) - maxOf(area.system.right, area.keyboard.right) - margin * 2).coerceAtLeast(1)
        val availableHeight = (area.height - maxOf(area.system.top, area.keyboard.top) - maxOf(area.system.bottom, area.keyboard.bottom) - margin * 2).coerceAtLeast(1)
        val width = minOf((240 * density).toInt().coerceAtLeast(1), maxOf((180 * density).toInt(), availableWidth / 2), availableWidth)
        val height = minOf((48 * density).toInt().coerceAtLeast(1), availableHeight)
        val x = xRange(area, width, margin)
        val fullY = yRange(area, height, margin, false)
        val y = fullY.first + ((fullY.last - fullY.first) * fraction(anchor.fraction)).toInt()
        return Frame(if (anchor.rightEdge) x.last else x.first, y.coerceIn(yRange(area, height, margin, true)), width, height)
    }
    fun drag(area: Area, frame: Frame, x: Int, y: Int, density: Float): Frame {
        val margin = (8 * density).toInt().coerceAtLeast(0)
        return frame.copy(x = x.coerceIn(xRange(area, frame.width, margin)), y = y.coerceIn(yRange(area, frame.height, margin, true)))
    }
    fun anchor(area: Area, frame: Frame, density: Float): Anchor {
        val margin = (8 * density).toInt().coerceAtLeast(0)
        val x = xRange(area, frame.width, margin); val y = yRange(area, frame.height, margin, false)
        return Anchor(frame.x - x.first >= x.last - frame.x,
            if (y.last == y.first) 0f else ((frame.y - y.first).toFloat() / (y.last - y.first)).coerceIn(0f, 1f))
    }
}
