package com.r3dzf.reader

import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class WorksheetReadabilityDeviceTest {
    @get:Rule val compose=createComposeRule()
    private val header="Applicant name\n\nPlease add as many rows as needed"
    private fun sheet(): NativeWorksheet = NativeWorksheet("Score",listOf(
        NativeSheetRow(0,mapOf(0 to NativeSheetCell(header,textRgb=0xFFFFFFL,fillRgb=0xFFFFFFL,wrapText=true,hasExplicitFont=true),
            1 to NativeSheetCell("رقم الطلب",textRgb=0xFFFFFFL,fillRgb=0xFFFFFFL,wrapText=true,hasExplicitFont=true)),168.75f),
        NativeSheetRow(1,mapOf(0 to NativeSheetCell("0",valueKind=SheetValueKind.NUMBER),1 to NativeSheetCell("بيانات تجريبية")),18.75f)),
        19,false,columnWidthsPoints=mapOf(0 to 119.25f),defaultColumnWidthPoints=63f,frozenColumns=1,
        tables=listOf(NativeSheetTable(NativeMergedRange(0,0,18,17),elements=mapOf(
            "headerRow" to NativeTableStyleElement(NativeSheetCell("",fillRgb=0x5B9BD5L,textRgb=0xFFFFFFL)),
            "firstRowStripe" to NativeTableStyleElement(NativeSheetCell("",fillRgb=0xBDD7EEL))))))
    private fun headerInk(): Int {
        val node=compose.onNodeWithContentDescription("A1: $header").fetchSemanticsNode().boundsInRoot
        val grid=compose.onNodeWithTag("worksheet-grid")
        val origin=grid.fetchSemanticsNode().boundsInRoot
        val pixels=grid.captureToImage().toPixelMap()
        val left=(node.left-origin.left).toInt()+3;val right=(node.right-origin.left).toInt()-3
        val top=(node.top-origin.top).toInt()+3;val bottom=(node.bottom-origin.top).toInt()-3
        var ink=0
        for(y in top.coerceAtLeast(0) until bottom.coerceAtMost(pixels.height)) for(x in left.coerceAtLeast(0) until right.coerceAtMost(pixels.width)) {
            val c=pixels[x,y];if(c.red<.4f && c.green<.4f && c.blue<.4f)ink++
        }
        return ink
    }
    private fun save(name: String) {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val target=if(compose.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty())compose.onRoot() else compose.onNode(isDialog())
        val bitmap=target.captureToImage().asAndroidBitmap()
        val values=android.content.ContentValues().apply {put(android.provider.MediaStore.Images.Media.DISPLAY_NAME,name);put(android.provider.MediaStore.Images.Media.MIME_TYPE,"image/png");put(android.provider.MediaStore.Images.Media.RELATIVE_PATH,"Pictures/ReaderTestVisuals")}
        val uri=checkNotNull(context.contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values))
        checkNotNull(context.contentResolver.openOutputStream(uri)).use {check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it))}
    }
    @Test fun nearInvisibleHeaderHasVisibleTextAtFiftyPercentAndOriginalColorsCanBeSelected() {
        val original=sheet();compose.setContent {ReaderTheme {WorksheetView(original)}}
        repeat(4) {compose.onNodeWithTag("sheet-zoom-out").performClick()}
        compose.onNodeWithTag("sheet-zoom-level").assertTextEquals("50%")
        assertTrue("The header must contain actual visible text pixels at 50%",headerInk()>30)
        save("excel-readable-header-50.png")
        compose.onNodeWithTag("sheet-options").performClick();compose.onNodeWithTag("sheet-contrast").performClick()
        assertEquals("Disabling assistance restores the saved white ink",0,headerInk())
        assertEquals(0xFFFFFFL,original.rows.first().cells.getValue(0).textRgb)
    }
    @Test fun nearInvisibleHeaderRemainsReadableAtNormalZoom() {
        compose.setContent {ReaderTheme {WorksheetView(sheet())}}
        assertTrue(headerInk()>100);save("excel-readable-header-100.png")
    }
    private fun book()=NativeWorkbook(listOf(
        NativeWorksheet("Public A",emptyList(),1,false),
        NativeWorksheet("Internal lists",listOf(NativeSheetRow(0,mapOf(0 to NativeSheetCell("Hidden value")))),1,false,hidden=true),
        NativeWorksheet("Public B",emptyList(),1,false)))
    @Test fun chooserOpensHiddenSheetWithoutChangingItsHiddenStateAndReportsOriginalIndex() {
        val b=book();val progress=AtomicReference<ReadingProgress>()
        compose.setContent {ReaderTheme {WorkbookDocumentView(b,onProgress=progress::set)}}
        compose.onNodeWithText("Internal lists").assertDoesNotExist()
        compose.onNodeWithTag("workbook-sheets").performClick()
        compose.onNodeWithTag("workbook-sheet-1").assertIsDisplayed();save("excel-all-sheets.png")
        compose.onNodeWithTag("workbook-sheet-1").performClick()
        compose.onNodeWithContentDescription("A1: Hidden value").assertIsDisplayed()
        compose.runOnIdle {assertEquals(1,progress.get().index);assertEquals(3,progress.get().total);assertTrue(b.sheets[1].hidden)}
    }
    @Test fun readingPositionCanRestoreAHiddenSheet() {
        val progress=AtomicReference<ReadingProgress>()
        compose.setContent {ReaderTheme {WorkbookDocumentView(book(),ReadingProgress(1,0,3),progress::set)}}
        compose.onNodeWithContentDescription("A1: Hidden value").assertIsDisplayed()
        compose.runOnIdle {assertEquals(1,progress.get().index)}
    }
    @Test fun olderVisibleTabPositionStillOpensTheSameSheet() {
        val progress=AtomicReference<ReadingProgress>()
        compose.setContent {ReaderTheme {WorkbookDocumentView(book(),ReadingProgress(1,0,2),progress::set)}}
        compose.runOnIdle {assertEquals(2,progress.get().index);assertEquals(3,progress.get().total)}
        compose.onNodeWithContentDescription("A1: Hidden value").assertDoesNotExist()
    }
}
