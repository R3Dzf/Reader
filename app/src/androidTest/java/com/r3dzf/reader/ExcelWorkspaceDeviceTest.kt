package com.r3dzf.reader

import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ExcelWorkspaceDeviceTest {
    @get:Rule val compose = createComposeRule()
    private fun sheet(rtl: Boolean = false): NativeWorksheet {
        val headers = listOf("الصنف", "الوصف", "الكمية", "السعر", "الإجمالي", "ملاحظات")
        val rows = (0..80).map { row -> NativeSheetRow(row, (0..5).associateWith { column ->
            if (row == 0) NativeSheetCell(headers[column], bold=true, fontFamilyName="Arial", wrapText=true)
            else if (row == 40 && column == 4) NativeSheetCell("4,815.00",valueKind=SheetValueKind.NUMBER,formula="SUM(E2:E40)")
            else when (column) {
                0 -> NativeSheetCell("صنف $row")
                1 -> NativeSheetCell("وصف عربي للمنتج $row",wrapText=true)
                2 -> NativeSheetCell("${row * 2}",valueKind=SheetValueKind.NUMBER)
                3 -> NativeSheetCell("125.00",valueKind=SheetValueKind.NUMBER)
                4 -> NativeSheetCell("${row * 250}.00",valueKind=SheetValueKind.NUMBER)
                else -> NativeSheetCell(if (row % 3 == 0) "مراجعة" else "جاهز")
            }
        }, heightPoints=if(row==0) 32f else 30f) }
        val table = NativeSheetTable(NativeMergedRange(0,0,5,80),elements=mapOf(
            "headerRow" to NativeTableStyleElement(NativeSheetCell("",fillRgb=0x174D40,textRgb=0xFFFFFF,bold=true)),
            "firstRowStripe" to NativeTableStyleElement(NativeSheetCell("",fillRgb=0xE7F8EC))))
        return NativeWorksheet("المخزون",rows,6,rtl,columnWidthsPoints=mapOf(0 to 65f,1 to 130f,2 to 60f,3 to 75f,4 to 85f,5 to 80f),frozenRows=1,frozenColumns=1,tables=listOf(table))
    }
    private fun show(rtl: Boolean = false) { compose.setContent { ReaderTheme { Column(Modifier.fillMaxSize()) { DocumentTopBar("Inventory.xlsx","XLSX",{}); WorkbookDocumentView(NativeWorkbook(listOf(sheet(rtl)))) } } } }
    private fun save(name: String) {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val image=compose.onRoot().captureToImage().asAndroidBitmap()
        val values=android.content.ContentValues().apply {put(android.provider.MediaStore.Images.Media.DISPLAY_NAME,name);put(android.provider.MediaStore.Images.Media.MIME_TYPE,"image/png");put(android.provider.MediaStore.Images.Media.RELATIVE_PATH,"Pictures/ReaderTestVisuals")}
        val uri=checkNotNull(context.contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values))
        checkNotNull(context.contentResolver.openOutputStream(uri)).use { check(image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)) }
    }
    @Test fun frozenHeaderAndFirstColumnStayPinnedDuringDiagonalPan() {
        show()
        val before=compose.onNodeWithContentDescription("A1: الصنف").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("worksheet-grid").performTouchInput { swipe(Offset(width*.85f,height*.8f),Offset(width*.25f,height*.25f),600) }
        val after=compose.onNodeWithContentDescription("A1: الصنف").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertEquals(before.left,after.left,.1f);assertEquals(before.top,after.top,.1f)
        compose.onNodeWithContentDescription("A2: صنف 1").assertDoesNotExist()
        save("excel-frozen-reader.png")
    }
    @Test fun searchFindsSavedFormulaAndFullValueCanBeRead() {
        show();compose.onNodeWithTag("sheet-search").performClick()
        compose.onNodeWithTag("sheet-search-input").performTextInput("SUM")
        compose.waitUntil(3000) { compose.onAllNodesWithContentDescription("E41: 4,815.00").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("E41: 4,815.00").performClick()
        try { compose.waitUntil(3000) { runCatching { compose.onNodeWithText("=SUM(E2:E40)").assertIsDisplayed() }.isSuccess } }
        finally { save("excel-formula-reader.png") }
        compose.onNodeWithText("=SUM(E2:E40)").assertIsDisplayed();compose.onNodeWithText("4,815.00").assertIsDisplayed()
    }
    @Test fun fitColumnsRevealsAllHeadersInRtlAndSheetTabsRemainAvailable() {
        compose.setContent { ReaderTheme { WorksheetView(sheet(true).copy(columnWidthsPoints=(0..5).associateWith { 60f })) } };compose.onNodeWithTag("sheet-options").performClick();compose.onNodeWithTag("sheet-fit").performClick()
        compose.onNodeWithContentDescription("A1: الصنف").assertIsDisplayed()
        compose.onNodeWithContentDescription("F1: ملاحظات").assertIsDisplayed()
        save("excel-fit-reader.png")
    }
}
