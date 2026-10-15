package com.r3dzf.reader

import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Synthetic geometry regression for a desktop template with whole-sheet column formatting. */
class ExcelTemplateDeviceTest {
    @get:Rule val compose=createComposeRule()
    private fun workbook(): NativeWorkbook {
        val ns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"
        val rel="http://schemas.openxmlformats.org/officeDocument/2006/relationships"
        val file=File.createTempFile("desktop-template-", ".xlsx",InstrumentationRegistry.getInstrumentation().targetContext.cacheDir)
        try {
            val sheet="""<worksheet xmlns="$ns" xmlns:r="$rel"><sheetViews><sheetView><pane state="frozen" xSplit="1"/></sheetView></sheetViews><cols><col min="1" max="1" width="22.7109375"/><col min="2" max="19" width="12"/><col min="20" max="16384" width="11.42578125"/></cols><sheetData><row r="1" ht="168.75"><c r="A1" t="inlineStr" s="1"><is><t>Applicant name

Please add as many rows as needed</t></is></c><c r="B1" t="inlineStr" s="1"><is><t>رقم الطلب</t></is></c><c r="S1" t="inlineStr" s="1"><is><t>Result</t></is></c></row><row r="2" ht="18.75"><c r="A2"><f>SUM(B2:C2)</f><v>0</v></c><c r="B2" t="inlineStr"><is><t>طلب تجريبي</t></is></c></row><row r="21" ht="25.5"><c r="A21" t="inlineStr"><is><t>Institution details</t></is></c></row></sheetData><mergeCells><mergeCell ref="A21:S21"/></mergeCells><tableParts><tablePart r:id="t"/></tableParts></worksheet>"""
            val entries=mapOf(
                "[Content_Types].xml" to """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"/>""",
                "xl/workbook.xml" to """<workbook xmlns="$ns" xmlns:r="$rel"><sheets><sheet name="Score" sheetId="1" r:id="s"/><sheet name="Internal lists" sheetId="2" state="hidden" r:id="s"/></sheets></workbook>""",
                "xl/_rels/workbook.xml.rels" to """<Relationships><Relationship Id="s" Target="worksheets/sheet.xml"/><Relationship Id="styles" Target="styles.xml" Type="$rel/styles"/></Relationships>""",
                "xl/styles.xml" to """<styleSheet xmlns="$ns"><cellXfs><xf/><xf><alignment horizontal="left" vertical="top" wrapText="1"/></xf></cellXfs></styleSheet>""",
                "xl/worksheets/sheet.xml" to sheet,
                "xl/worksheets/_rels/sheet.xml.rels" to """<Relationships><Relationship Id="t" Target="../tables/score.xml"/></Relationships>""",
                "xl/tables/score.xml" to """<table xmlns="$ns" ref="A1:S18" totalsRowCount="1"><tableStyleInfo name="TableStyleMedium13" showRowStripes="1"/></table>""")
            ZipOutputStream(file.outputStream()).use {out -> entries.forEach {(name,xml) ->out.putNextEntry(ZipEntry(name));out.write(xml.toByteArray());out.closeEntry()}}
            val original=file.readBytes()
            return NativeOfficeEngine.openWorkbook(file).also {assertArrayEquals(original,file.readBytes())}
        } finally {file.delete()}
    }
    private fun save(name: String) {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap=compose.onRoot().captureToImage().asAndroidBitmap()
        val values=android.content.ContentValues().apply {put(android.provider.MediaStore.Images.Media.DISPLAY_NAME,name);put(android.provider.MediaStore.Images.Media.MIME_TYPE,"image/png");put(android.provider.MediaStore.Images.Media.RELATIVE_PATH,"Pictures/ReaderTestVisuals")}
        val uri=checkNotNull(context.contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values))
        checkNotNull(context.contentResolver.openOutputStream(uri)).use {check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it))}
    }
    @Test fun fullColumnTemplateRendersTallWrappedHeadersAndHidesInternalSheet() {
        val book=workbook()
        assertEquals(19,book.sheets.first().columnCount)
        compose.setContent {ReaderTheme {WorkbookDocumentView(book)}}
        compose.onNodeWithText("Internal lists").assertDoesNotExist()
        compose.onNodeWithContentDescription("A1: Applicant name\n\nPlease add as many rows as needed").assertIsDisplayed()
        compose.onNodeWithContentDescription("B1: رقم الطلب").assertIsDisplayed()
        val pixels=compose.onNodeWithTag("worksheet-grid").captureToImage().toPixelMap()
        var blue=0;var whiteText=0
        for(y in 50 until minOf(pixels.height,270) step 3) for(x in 55 until pixels.width step 3) {
            val c=pixels[x,y]
            if(c.blue>.5f && c.blue>c.red+.15f)blue++
            if(c.red>.95f && c.green>.95f && c.blue>.95f)whiteText++
        }
        assertTrue("Saved table header must have a colored background",blue>100)
        assertTrue("Header text must contrast with its background",whiteText>10)
        save("excel-desktop-template.png")
    }
    @Test fun cachedZeroAndMergedFooterRemainReadableAfterNavigation() {
        val book=workbook();compose.setContent {ReaderTheme {WorkbookDocumentView(book)}}
        compose.onNodeWithContentDescription("A2: 0").assertIsDisplayed()
        compose.onNodeWithTag("sheet-jump").performClick()
        compose.onNodeWithTag("sheet-jump-address").performTextInput("A21")
        compose.onNodeWithTag("sheet-jump-confirm").performClick()
        compose.onAllNodesWithContentDescription("A21:S21: Institution details").onFirst().assertIsDisplayed()
        save("excel-template-footer.png")
    }
}
