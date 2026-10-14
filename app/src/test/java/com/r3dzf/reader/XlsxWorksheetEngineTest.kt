package com.r3dzf.reader

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test

class XlsxWorksheetEngineTest {
    @Test fun preservesIndependentArabicMergesDimensionsStylesAndOriginalBytes() = fixture { file ->
        val original = file.readBytes()
        val sheet = NativeOfficeEngine.openWorkbook(file).sheets.single()
        assertTrue(sheet.rightToLeft); assertFalse(sheet.showGridLines)
        assertEquals(1_000_000, sheet.rowCount)
        assertEquals(6, sheet.columnCount)
        assertEquals(setOf(NativeMergedRange(0, 0, 1, 2), NativeMergedRange(0, 2, 2, 1), NativeMergedRange(3, 0, 3)), sheet.mergedRanges.toSet())
        assertEquals(63f, sheet.columnWidthsPoints.getValue(0), 0f)
        assertEquals(126f, sheet.columnWidthsPoints.getValue(1), 0f)
        assertEquals(0f, sheet.columnWidthsPoints.getValue(4), 0f)
        val layout = WorksheetLayout(sheet)
        val merged = layout.placement(1, 1)!!
        assertEquals("عنوان عربي مدمج", merged.cell.value)
        assertEquals(189.0, merged.right, 0.001)
        assertEquals(78.0, merged.bottom, 0.001)
        assertEquals(SheetHorizontalAlignment.CENTER, merged.cell.horizontalAlignment)
        assertEquals(SheetVerticalAlignment.CENTER, merged.cell.verticalAlignment)
        assertEquals(2, merged.cell.readingOrder)
        assertTrue(merged.cell.wrapText); assertTrue(merged.cell.bold); assertTrue(merged.cell.italic)
        assertTrue(merged.cell.underline); assertTrue(merged.cell.strikeThrough)
        assertEquals("Arial", merged.cell.fontFamilyName)
        assertEquals(0xFFF2CCL, merged.cell.fillRgb)
        assertEquals(0xFF0000L, merged.cell.borderLeft!!.rgb)
        assertEquals(SheetBorderDash.DOUBLE, merged.cell.borderRight!!.dash)
        assertEquals(SheetBorderDash.DASH, merged.cell.borderTop!!.dash)
        assertEquals(SheetBorderDash.DOT, merged.cell.borderBottom!!.dash)
        assertEquals("1,234.50", layout.placement(5, 3)!!.cell.value)
        assertEquals(SheetValueKind.NUMBER, layout.placement(5, 3)!!.cell.valueKind)
        assertEquals(15_000_045.0, layout.rows.total, 0.001)
        assertArrayEquals(original, file.readBytes())
    }
    @Test fun rejectsOverlappingMergedRanges() = fixture(sheetMutation { it.replace("</mergeCells>", "<mergeCell ref=\"B2:D3\"/></mergeCells>") }) { invalid(it) }
    @Test fun rejectsReversedMergedRanges() = fixture(sheetMutation { it.replace("A4:D4", "D4:A4") }) { invalid(it) }
    @Test fun rejectsNonfiniteOrOversizedRowHeights() {
        for (size in listOf("NaN", "Infinity", "410", "-1")) fixture(sheetMutation { it.replace("ht=\"24\"", "ht=\"$size\"") }) { invalid(it) }
    }
    @Test fun rejectsOverlappingOrOversizedColumnDefinitions() {
        fixture(sheetMutation { it.replace("</cols>", "<col min=\"1\" max=\"2\" width=\"12\"/></cols>") }) { invalid(it) }
        fixture(sheetMutation { it.replace("width=\"12\"", "width=\"256\"") }) { invalid(it) }
    }
    @Test fun rejectsDuplicateCellAddressesInsteadOfReplacingTheFirstValue() = fixture(sheetMutation {
        it.replaceFirst("</row>", "<c r=\"A1\"><v>999</v></c></row>")
    }) { invalid(it) }
    @Test fun rowStyleTakesPrecedenceOverColumnStyleWhenNoCellStyleWasSaved() = fixture(sheetMutation {
        it.replace("<col width=\"12\"", "<col style=\"1\" width=\"12\"")
            .replace("<row r=\"4\"", "<row r=\"4\" s=\"2\" customFormat=\"1\"")
    }) { file ->
        val sheet = NativeOfficeEngine.openWorkbook(file).sheets.single()
        val cell = sheet.rows.single { it.rowIndex == 3 }.cells.getValue(0)
        assertEquals(SheetHorizontalAlignment.RIGHT, cell.horizontalAlignment)
        assertEquals(SheetHorizontalAlignment.CENTER, sheet.columnStyles.getValue(0).horizontalAlignment)
    }
    @Test fun mergesWithoutSheetDataStillProduceTheirDocumentDimensions() = fixture(sheetMutation {
        it.replace(Regex("<sheetData>.*?</sheetData>"), "")
    }) { file ->
        val sheet = NativeOfficeEngine.openWorkbook(file).sheets.single()
        assertTrue(sheet.rows.isEmpty())
        assertEquals(4, sheet.rowCount)
        assertEquals(6, sheet.columnCount)
        assertEquals(3, sheet.mergedRanges.size)
    }
    @Test fun defaultHiddenRowsRetainExplicitVisibleRows() = fixture(sheetMutation {
        it.replace("defaultRowHeight=\"15\"", "defaultRowHeight=\"15\" zeroHeight=\"1\"")
    }) { file ->
        val sheet = NativeOfficeEngine.openWorkbook(file).sheets.single()
        val layout = WorksheetLayout(sheet)
        assertEquals(0f, sheet.defaultRowHeightPoints, 0f)
        assertEquals(listOf(0, 1, 2, 3, 5, 999_999), layout.rows.visible(0.0, layout.rows.total))
    }
    @Test fun rejectsCellTextBeyondTheExcelReadingLimit() = fixture(sheetMutation {
        it.replace("&#1593;&#1606;&#1608;&#1575;&#1606;", "X".repeat(32_768))
    }) { invalid(it) }

    private fun sheetMutation(change: (String) -> String): (String, String) -> String = { path, xml -> if (path == "xl/worksheets/sheet1.xml") change(xml) else xml }
    private fun invalid(file: File) { assertTrue(runCatching { NativeOfficeEngine.openWorkbook(file) }.exceptionOrNull() is InvalidOfficeDocumentException) }
    private fun fixture(mutate: (String, String) -> String = { _, xml -> xml }, check: (File) -> Unit) {
        val source = File.createTempFile("reader-layout-source-", ".xlsx")
        val file = File.createTempFile("reader-layout-test-", ".xlsx")
        try {
            javaClass.getResourceAsStream("/fixtures/worksheet-layout-arabic.xlsx")!!.use { input -> source.outputStream().use { input.copyTo(it) } }
            ZipFile(source).use { zip -> ZipOutputStream(file.outputStream()).use { output ->
                zip.entries().asSequence().forEach { entry ->
                    val bytes = zip.getInputStream(entry).use { it.readBytes() }
                    output.putNextEntry(ZipEntry(entry.name))
                    output.write(if (entry.name.endsWith(".xml")) mutate(entry.name, bytes.toString(Charsets.UTF_8)).toByteArray(Charsets.UTF_8) else bytes)
                    output.closeEntry()
                }
            } }
            check(file)
        } finally { source.delete(); file.delete() }
    }
}
