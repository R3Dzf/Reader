package com.r3dzf.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class PresentationLayoutTest {
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
