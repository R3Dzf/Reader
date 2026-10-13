package com.r3dzf.reader

import org.junit.Assert.assertEquals
import org.junit.Test
import androidx.compose.ui.unit.Density

class PresentationLayoutTest {
    @Test
    fun fontPointsKeepTheirSlideProportionsAcrossDensityAndSystemFontScale() {
        // A 24-point font on a 720-point slide fitted to 360 dp occupies 12 dp.
        for (fontScale in listOf(1f, 1.5f, 2f)) {
            val density = Density(3f, fontScale)
            val size = slideFontSize(24f, 0.5f, density)
            assertEquals(36f, with(density) { size.toPx() }, 0.001f)
        }
        val wide = Density(2f, 1f)
        assertEquals(48f, with(wide) { slideFontSize(24f, 1f, wide).toPx() }, 0.001f)
    }

    @Test
    fun zoomAndPanCannotMoveTheSlideOutsideReach() {
        val zoomed = SlideViewport().transformed(10f, 10_000f, -10_000f, 400f, 225f)
        assertEquals(5f, zoomed.scale, 0f)
        assertEquals(800f, zoomed.translationX, 0f)
        assertEquals(-450f, zoomed.translationY, 0f)
        val fitted = zoomed.transformed(0.1f, width = 400f, height = 225f)
        assertEquals(SlideViewport(), fitted)
    }

    @Test
    fun resizeReclampsPanAndInvalidGesturesPreserveTheViewport() {
        val initial = SlideViewport(2f, 200f, -100f)
        val resized = initial.transformed(width = 200f, height = 100f)
        assertEquals(SlideViewport(2f, 100f, -50f), resized)
        assertEquals(resized, resized.transformed(Float.NaN, width = 200f, height = 100f))
    }
}
