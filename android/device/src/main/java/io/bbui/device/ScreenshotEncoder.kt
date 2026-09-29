package io.bbui.device

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream

data class EncodedScreenshot(val originalPng: ByteArray, val modelBytes: ByteArray, val mimeType: String)

/** Encode the same pixels at the same dimensions; observation validation retains the source bitmap. */
object ScreenshotEncoder {
    fun encode(bitmap: Bitmap): EncodedScreenshot {
        val png = ByteArrayOutputStream().also {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) { "截图 PNG 编码失败" }
        }.toByteArray()
        // Android display frames are opaque. JPEG is only a transport optimization: keep the PNG
        // when encoding fails or a flat UI compresses better losslessly.
        val jpeg = try {
            ByteArrayOutputStream().let {
                if (bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) it.toByteArray() else null
            }
        } catch (_: Exception) {
            null
        }
        return if (jpeg != null && jpeg.isNotEmpty() && jpeg.size < png.size) {
            EncodedScreenshot(png, jpeg, "image/jpeg")
        } else {
            EncodedScreenshot(png, png, "image/png")
        }
    }
}
