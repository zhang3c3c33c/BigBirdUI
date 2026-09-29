package io.bbui.assistant

import io.bbui.core.PreviewViewport
import org.junit.Assert.*
import org.junit.Test

class PreviewViewportTest {
    @Test fun fitsTheImageInsteadOfItsLetterboxContainer() {
        assertEquals(PreviewViewport(72, 0, 936, 1872), PreviewViewport.fit(1080, 1872, 1080, 2160))
        assertEquals(PreviewViewport(0, 540, 1080, 540), PreviewViewport.fit(1080, 1620, 2160, 1080))
        assertEquals(PreviewViewport(0, 41, 100, 100), PreviewViewport.fit(100, 181, 100, 100))
        assertNull(PreviewViewport.fit(1080, 1920, 0, 0))
    }
}
