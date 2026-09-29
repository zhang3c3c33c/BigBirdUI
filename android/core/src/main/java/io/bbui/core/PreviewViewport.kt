package io.bbui.core

import kotlin.math.min

/** The exact aspect-fit video rectangle, in Android (top-left origin) preview coordinates. */
data class PreviewViewport(val left: Int, val top: Int, val width: Int, val height: Int) {
    companion object {
        fun fit(areaWidth: Int, areaHeight: Int, videoWidth: Int, videoHeight: Int): PreviewViewport? {
            if (minOf(areaWidth, areaHeight, videoWidth, videoHeight) <= 0) return null
            val scale = min(areaWidth.toFloat() / videoWidth, areaHeight.toFloat() / videoHeight)
            val width = (videoWidth * scale).toInt()
            val height = (videoHeight * scale).toInt()
            // EGL centers from the bottom; an odd remaining pixel belongs above the image.
            return PreviewViewport((areaWidth - width) / 2, areaHeight - (areaHeight - height) / 2 - height, width, height)
        }
    }
}
