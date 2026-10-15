package com.r3dzf.reader

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test

/** Independently constructed packages; no user workbook or applicant data is stored here. */
class XlsxFullColumnFormattingTest {
    private val ns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    private val rel="http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private fun read(columns: String, cells: String="<c r=\"A1\" t=\"inlineStr\"><is><t>تعليمات</t></is></c>", hidden: Boolean=false): NativeWorkbook {
        val file=File.createTempFile("whole-sheet-format-", ".xlsx")
        try {
            val entries=mapOf(
                "[Content_Types].xml" to """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"/>""",
                "xl/workbook.xml" to """<workbook xmlns="$ns" xmlns:r="$rel"><sheets><sheet name="Guide" sheetId="1" r:id="s"/>${if(hidden) "<sheet name=\"Lists\" sheetId=\"2\" state=\"hidden\" r:id=\"h\"/><sheet name=\"Internal\" sheetId=\"3\" state=\"veryHidden\" r:id=\"h\"/>" else ""}</sheets></workbook>""",
                "xl/_rels/workbook.xml.rels" to """<Relationships><Relationship Id="s" Target="worksheets/sheet.xml"/><Relationship Id="h" Target="worksheets/sheet.xml"/></Relationships>""",
                "xl/worksheets/sheet.xml" to """<worksheet xmlns="$ns"><cols>$columns</cols><sheetData><row r="1" ht="168.75">$cells</row></sheetData></worksheet>""")
            ZipOutputStream(file.outputStream()).use { out -> entries.forEach { (name,xml) -> out.putNextEntry(ZipEntry(name));out.write(xml.toByteArray());out.closeEntry() } }
            val original=file.readBytes()
            return NativeOfficeEngine.openWorkbook(file).also { assertArrayEquals(original,file.readBytes()) }
        } finally { file.delete() }
    }
    @Test fun wholeSheetTrailingFormatDoesNotRejectOrInflateSmallWorkbook() {
        val s=read("""<col min="1" max="1" width="254.42578125"/><col min="2" max="16384" width="11.42578125"/>""").sheets.single()
        assertEquals(1,s.columnCount)
        assertEquals(1335.75f,s.columnWidthsPoints.getValue(0),.01f)
        assertTrue(s.columnWidthsPoints.size <= 26)
        assertEquals(168.75,WorksheetLayout(s).rows.total,.001)
        assertEquals(1335.75,WorksheetLayout(s).columns.total,.001)
        assertTrue(WorksheetLayout(s,minimumColumns=26).columns.total > 1335.75)
    }
    @Test fun formattingBeyondReadingWindowIsValidatedWithoutCreatingCells() {
        val s=read("""<col min="513" max="16384" width="12"/>""").sheets.single()
        assertEquals(1,s.columnCount);assertTrue(s.columnWidthsPoints.isEmpty())
        assertEquals("تعليمات",s.rows.single().cells.getValue(0).value)
    }
    @Test fun realDataStillEnforcesColumnLimit() {
        assertEquals(512,read("""<col min="1" max="16384" width="12"/>""","<c r=\"SR1\"><v>7</v></c>").sheets.single().columnCount)
        assertTrue(runCatching {read("","<c r=\"SS1\"><v>7</v></c>")}.exceptionOrNull() is InvalidOfficeDocumentException)
    }
    @Test fun malformedAndOverlappingFullRangesAreRejected() {
        for(c in listOf("<col min=\"1\" max=\"16385\"/>","<col min=\"16384\" max=\"1\"/>","<col min=\"513\" max=\"16384\"/><col min=\"1000\" max=\"1100\"/>")) {
            assertTrue(runCatching {read(c)}.exceptionOrNull() is InvalidOfficeDocumentException)
        }
    }
    @Test fun hiddenSheetsRetainDataAndStayOutOfReaderTabs() {
        val b=read("",hidden=true)
        assertEquals(3,b.sheets.size);assertEquals(listOf("Guide"),b.visibleSheets.map {it.name})
        assertEquals(listOf(false,true,true),b.sheets.map {it.hidden})
        assertEquals("تعليمات",b.sheets[1].rows.single().cells.getValue(0).value)
    }
}
