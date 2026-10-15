package com.r3dzf.reader

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test

class ExcelReadingTest {
    private val ns = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    private val rel = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private fun xml(body: String) = "<worksheet xmlns=\"$ns\" xmlns:r=\"$rel\">$body</worksheet>"
    private fun read(sheet: String, table: String = "", styles: String = "", customPaths: Boolean = false): NativeWorksheet {
        val file = File.createTempFile("excel-reading-", ".xlsx")
        try {
            val stylePath = if (customPaths) "format/custom.xml" else "styles.xml"
            val entries = linkedMapOf(
                "[Content_Types].xml" to "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"/>",
                "xl/workbook.xml" to "<workbook xmlns=\"$ns\" xmlns:r=\"$rel\"><sheets><sheet name=\"بيانات\" sheetId=\"1\" r:id=\"s\"/></sheets></workbook>",
                "xl/_rels/workbook.xml.rels" to "<Relationships><Relationship Id=\"s\" Target=\"worksheets/sheet.xml\" Type=\"$rel/worksheet\"/><Relationship Id=\"style\" Target=\"$stylePath\" Type=\"$rel/styles\"/></Relationships>",
                "xl/worksheets/sheet.xml" to sheet,
                "xl/$stylePath" to styles.ifEmpty { "<styleSheet xmlns=\"$ns\"/>" })
            if (table.isNotEmpty()) {
                entries["xl/worksheets/_rels/sheet.xml.rels"] = "<Relationships><Relationship Id=\"table\" Target=\"../tables/sales.xml\" Type=\"$rel/table\"/></Relationships>"
                entries["xl/tables/sales.xml"] = table
            }
            ZipOutputStream(file.outputStream()).use { out -> entries.forEach { (path, value) -> out.putNextEntry(ZipEntry(path)); out.write(value.toByteArray()); out.closeEntry() } }
            val bytes = file.readBytes()
            return NativeOfficeEngine.openWorkbook(file).sheets.single().also { assertArrayEquals(bytes, file.readBytes()) }
        } finally { file.delete() }
    }
    private fun table(style: String = "TableStyleMedium2", ref: String = "A1:C30") = "<table xmlns=\"$ns\" ref=\"$ref\"><tableStyleInfo name=\"$style\" showRowStripes=\"1\" showFirstColumn=\"1\"/></table>"
    private val parts = "<tableParts><tablePart r:id=\"table\"/></tableParts>"
    @Test fun readsSavedFreezeAndSparseTableExtentWithoutExpandingRows() {
        val sheet = read(xml("<sheetViews><sheetView><pane state=\"frozen\" xSplit=\"1\" ySplit=\"2\"/></sheetView></sheetViews><sheetData><row r=\"1\"><c r=\"A1\" t=\"inlineStr\"><is><t>المنتج</t></is></c></row></sheetData>$parts"), table(ref="A1:C1000000"))
        assertEquals(2, sheet.frozenRows); assertEquals(1, sheet.frozenColumns)
        assertEquals(1_000_000, sheet.rowCount); assertEquals(3, sheet.columnCount); assertEquals(1, sheet.rows.size)
        val layout = WorksheetLayout(sheet)
        assertEquals(0x4F81BDL, layout.placement(0, 0)!!.cell.fillRgb)
        assertEquals(0xFFFFFFL, layout.placement(0, 0)!!.cell.textRgb)
        assertTrue(layout.placement(0, 0)!!.cell.bold)
        assertNotNull(layout.placement(1, 0)!!.cell.fillRgb)
        assertNull(layout.placement(2, 0)!!.cell.fillRgb)
        assertTrue(layout.window(0.0, 0.0, 200.0, 300.0).cells.size < 200)
    }
    @Test fun customTableColorsAndStripesRespectDirectCellFormattingAndNonstandardStylePath() {
        val styles = """<styleSheet xmlns="$ns"><fonts><font/><font><color rgb="FFFF0000"/></font><font><b/></font></fonts><fills><fill/><fill><patternFill patternType="solid"><fgColor rgb="FFFFDD00"/></patternFill></fill></fills><cellXfs><xf/><xf fontId="1" fillId="1"/><xf fontId="2"/></cellXfs><dxfs><dxf><font><b/><color rgb="FFFFFFFF"/></font><fill><patternFill patternType="solid"><fgColor rgb="FF174D40"/></patternFill></fill></dxf><dxf><fill><patternFill patternType="solid"><fgColor rgb="FFE7F8EC"/></patternFill></fill></dxf></dxfs><tableStyles><tableStyle name="ReaderTable"><tableStyleElement type="headerRow" dxfId="0"/><tableStyleElement type="firstRowStripe" dxfId="1" size="2"/><tableStyleElement type="secondRowStripe" size="1"/></tableStyle></tableStyles></styleSheet>"""
        val sheet = read(xml("<sheetData><row r=\"1\"><c r=\"A1\" s=\"2\" t=\"inlineStr\"><is><t>Header</t></is></c><c r=\"B1\" s=\"1\" t=\"inlineStr\"><is><t>Direct</t></is></c></row></sheetData>$parts"), table("ReaderTable"), styles, true)
        val layout = WorksheetLayout(sheet)
        assertEquals(0x174D40L, layout.placement(0, 0)!!.cell.fillRgb)
        assertEquals(0xFFFFFFL, layout.placement(0, 0)!!.cell.textRgb)
        assertEquals(0xFFDD00L, layout.placement(0, 1)!!.cell.fillRgb)
        assertEquals(0xFF0000L, layout.placement(0, 1)!!.cell.textRgb)
        assertEquals(0xE7F8ECL, layout.placement(2, 0)!!.cell.fillRgb)
        assertNull(layout.placement(3, 0)!!.cell.fillRgb)
        assertFalse(sheet.tables.single().approximate)
    }
    @Test fun cachedFormulaRetainsItsExpressionAndDisplayNumber() {
        val sheet = read(xml("<sheetData><row r=\"1\"><c r=\"A1\"><f>SUM(B1:C1)</f><v>42</v></c></row></sheetData>"))
        val cell = sheet.rows.single().cells.getValue(0)
        assertEquals("42", cell.value); assertEquals("SUM(B1:C1)", cell.formula)
        assertEquals("42", cell.rawValue); assertEquals(SheetValueKind.NUMBER, cell.valueKind)
        assertEquals(1, searchWorksheet(sheet, "sum").size)
    }
    @Test fun readsRotationIndentAndShrinkFromSavedAlignment() {
        val styles = """<styleSheet xmlns="$ns"><cellXfs><xf><alignment textRotation="90" indent="2" shrinkToFit="1"/></xf></cellXfs></styleSheet>"""
        val sheet = read(xml("<sheetData><row><c r=\"A1\"><v>12345</v></c></row></sheetData>"), styles = styles)
        val cell = sheet.rows.single().cells.getValue(0)
        assertEquals(90, cell.textRotation); assertEquals(2, cell.indent); assertTrue(cell.shrinkToFit); assertFalse(cell.appearanceUnsupported)
    }
    @Test fun rejectsInvalidFrozenCountsAndReversedOrOversizedTables() {
        for (v in listOf("NaN", "Infinity", "-1", "1.5", "513")) assertTrue(runCatching { read(xml("<sheetViews><sheetView><pane state=\"frozen\" xSplit=\"$v\"/></sheetView></sheetViews>")) }.exceptionOrNull() is InvalidOfficeDocumentException)
        for (ref in listOf("B2:A1", "A1:ZZ2", "A1:C1048577", "A1:C2:D3")) assertTrue(runCatching { read(xml(parts), table(ref=ref)) }.exceptionOrNull() is InvalidOfficeDocumentException)
    }
    @Test fun ordinarySplitPaneIsNotMistakenForFrozenRows() {
        val sheet = read(xml("<sheetViews><sheetView><pane state=\"split\" xSplit=\"2400\" ySplit=\"300\"/></sheetView></sheetViews>"))
        assertEquals(0, sheet.frozenRows); assertEquals(0, sheet.frozenColumns)
    }
    @Test fun panesPinBothAxesWhileTheBodyScrollsAndCanBeDisabledOnPhones() {
        val layout = WorksheetLayout(NativeWorksheet("Data", emptyList(), 30, false, rowCount=1000, frozenRows=2, frozenColumns=1))
        val panes = sheetPanes(layout, SheetViewport(x=200.0,y=400.0), 300.0, 500.0, true)
        assertEquals(4, panes.size)
        assertEquals(SheetPane(48.0,30.0,300.0,500.0,200.0,400.0), panes[0])
        assertEquals(SheetPane(0.0,0.0,48.0,30.0,0.0,0.0), panes.last())
        assertEquals(1, sheetPanes(layout, SheetViewport(), 40.0, 20.0, true).size)
        assertEquals(1, sheetPanes(layout, SheetViewport(), 300.0, 500.0, false).size)
    }
    @Test fun searchSkipsHiddenAxesIsCaseInsensitiveAndBounded() {
        val sheet = NativeWorksheet("Data", listOf(NativeSheetRow(0, mapOf(0 to NativeSheetCell("مرحبا Excel"), 1 to NativeSheetCell("EXCEL"))), NativeSheetRow(1, mapOf(0 to NativeSheetCell("Excel")),hidden=true)), 2, true, columnWidthsPoints=mapOf(1 to 0f))
        assertEquals(listOf(SheetSearchResult(0,0,"مرحبا Excel")), searchWorksheet(sheet,"excel"))
        assertTrue(searchWorksheet(sheet,"  ").isEmpty()); assertEquals(0,searchWorksheet(sheet,"excel",0).size)
    }
    @Test fun rotationConvertsBothSidesOfTheHorizontal() {
        assertEquals(-90f, sheetRotationDegrees(90), 0f)
        assertEquals(1f, sheetRotationDegrees(91), 0f)
        assertEquals(45f, sheetRotationDegrees(135), 0f)
        assertEquals(90f, sheetRotationDegrees(180), 0f)
    }
    @Test fun offscreenTextOriginsAreRetainedWithoutScanningTheWholeWorkbook() {
        val layout = WorksheetLayout(NativeWorksheet("Data", listOf(NativeSheetRow(0,mapOf(0 to NativeSheetCell("A title that spans columns")))), 40, false))
        assertEquals("A1",layout.overflowOrigins(96.0,0.0,96.0,15.0).single().address)
        assertTrue(layout.overflowOrigins(1800.0,0.0,96.0,15.0).isEmpty())
    }
    @Test fun overflowStopsAtSavedValuesFillsBordersAndMerges() {
        val sheet = NativeWorksheet("Data", listOf(NativeSheetRow(0,mapOf(0 to NativeSheetCell("Long title"),3 to NativeSheetCell("Stop"))), NativeSheetRow(1,mapOf(0 to NativeSheetCell("Title"),2 to NativeSheetCell("",fillRgb=0xFF0000)))), 8, false)
        val layout = WorksheetLayout(sheet)
        assertEquals(144.0,layout.overflowEnd(layout.placement(0,0)!!,false),.001)
        assertEquals(96.0,layout.overflowEnd(layout.placement(1,0)!!,false),.001)
        assertEquals(48.0,layout.overflowEnd(layout.placement(0,3)!!,true),.001)
    }
}
