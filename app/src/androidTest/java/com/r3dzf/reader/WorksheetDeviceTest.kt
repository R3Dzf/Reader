package com.r3dzf.reader

import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class WorksheetDeviceTest {
    @get:Rule val compose = createComposeRule()
    private fun show(sheet: NativeWorksheet) = compose.setContent { ReaderTheme { WorksheetView(sheet, Modifier.fillMaxSize()) } }
    private fun sheet(rtl: Boolean = false) = NativeWorksheet("Arabic", listOf(
        NativeSheetRow(0, mapOf(0 to NativeSheetCell("مرحبا بالعالم", fillRgb = 0xFFF2CCL, wrapText = true))),
        NativeSheetRow(20, mapOf(0 to NativeSheetCell("Far row")))
    ), 8, rtl, listOf(NativeMergedRange(0, 0, 1, 3)), defaultRowHeightPoints = 20f)

    @Test fun rendersArabicMergedCellAndSupportsAccessibleFullValueReading() {
        show(sheet())
        compose.onNodeWithContentDescription("A1:B4: مرحبا بالعالم").assertIsDisplayed().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
        compose.onNodeWithText("A1:B4").assertIsDisplayed()
        compose.onNodeWithText("مرحبا بالعالم").assertIsDisplayed()
    }
    @Test fun zoomControlsAndResetChangeTheViewportWithoutLosingMergedText() {
        show(sheet())
        compose.onNodeWithTag("sheet-zoom-in").performClick()
        compose.onNodeWithTag("sheet-zoom-level").assertTextContains("125", substring = true)
        compose.onNodeWithTag("sheet-reset").performClick()
        compose.onNodeWithTag("sheet-zoom-level").assertTextContains("100", substring = true)
        compose.onNodeWithContentDescription("A1:B4: مرحبا بالعالم").assertIsDisplayed()
    }
    @Test fun verticalPanRetainsMergedOriginThatMovedAboveTheViewport() {
        show(sheet())
        compose.onNodeWithTag("worksheet-grid").performTouchInput {
            swipe(Offset(width * 0.6f, height * 0.5f), Offset(width * 0.6f, height * 0.5f - 50f), 500)
        }
        compose.onNodeWithContentDescription("A1:B4: مرحبا بالعالم").assertIsDisplayed()
    }
    @Test fun rtlPlacesColumnAOnTheRightAndItsMergedFillRenders() {
        show(sheet(true))
        val node = compose.onNodeWithContentDescription("A1:B4: مرحبا بالعالم").assertIsDisplayed().fetchSemanticsNode()
        val grid = compose.onNodeWithTag("worksheet-grid").fetchSemanticsNode()
        assertTrue(node.boundsInRoot.right > grid.boundsInRoot.center.x)
        val image = compose.onNodeWithTag("worksheet-grid").captureToImage().toPixelMap()
        var colored = 0
        for (y in 0 until image.height step 5) for (x in 0 until image.width step 5) {
            val color = image[x, y]
            if (color.red > 0.95f && color.green > 0.90f && color.blue in 0.70f..0.85f) colored++
        }
        assertTrue("Merged fill must be present in the rendered pixels", colored > 20)
    }
    @Test fun realIndependentWorkbookRendersOnAndroidWithFarRowsAndHiddenAxes() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.context
        val file = File.createTempFile("reader-device-fixture-", ".xlsx", instrumentation.targetContext.cacheDir)
        try {
            context.assets.open("worksheet-layout-arabic.xlsx").use { input -> file.outputStream().use { input.copyTo(it) } }
            val worksheet = NativeOfficeEngine.openWorkbook(file).sheets.single()
            assertEquals(1_000_000, worksheet.rowCount)
            show(worksheet)
            compose.onNodeWithContentDescription("A1:B3: عنوان عربي مدمج").assertIsDisplayed()
            compose.onNodeWithTag("sheet-zoom-in").performClick()
            compose.onNodeWithTag("worksheet-grid").captureToImage()
            compose.onNodeWithTag("sheet-jump").performScrollTo().performClick()
            compose.onNodeWithTag("sheet-jump-address").performTextInput("B1000000")
            compose.onNodeWithTag("sheet-jump-confirm").performClick()
            compose.onNodeWithContentDescription("B1000000: صف بعيد").assertIsDisplayed()
            compose.onNodeWithTag("sheet-selected-cell").assertIsDisplayed()
        } finally { file.delete() }
    }
}
