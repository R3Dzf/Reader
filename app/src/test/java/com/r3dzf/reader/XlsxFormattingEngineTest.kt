package com.r3dzf.reader

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test

class XlsxFormattingEngineTest {
    @Test fun formatsIndependentArabicWorkbookAndPreservesTheInput() = withFixture { file ->
        val bytes = file.readBytes()
        val workbook = NativeOfficeEngine.openWorkbook(file)
        assertFalse(workbook.date1904)
        val sheet = workbook.sheets.single()
        assertEquals("الميزانية", sheet.name)
        assertTrue(sheet.rightToLeft)
        val cells = sheet.rows.map { it.cells.getValue(0) }
        assertEquals(listOf("1,234.57", "12.5%", "2024-01-01 12:00:00", "(1,234.50)",
            "ج.م 1,234.50", "36:00:00", "1900-02-29", "00123", "TRUE", "#DIV/0!", "0.333",
            "25.00%", "=SUM(2,3)", "1.23E+03"), cells.map { it.value })
        assertEquals(0xFF0000L, cells[3].textRgb)
        assertEquals("-1234.5", cells[3].rawValue)
        assertEquals("0.25", cells[11].rawValue)
        assertEquals(listOf(10), cells.indices.filter { cells[it].numberFormatUnsupported })
        assertArrayEquals(bytes, file.readBytes())
    }

    @Test fun readsWorkbookDate1904WithoutChangingSavedSerialValues() = withFixture({ path, xml ->
        if (path == "xl/workbook.xml") xml.replace("<workbookPr/>", "<workbookPr date1904=\"1\"/>") else xml
    }) { file ->
        val workbook = NativeOfficeEngine.openWorkbook(file)
        assertTrue(workbook.date1904)
        val cell = workbook.sheets.single().rows[6].cells.getValue(0)
        assertEquals("1904-03-01", cell.value)
        assertEquals("60", cell.rawValue)
    }

    @Test fun unknownFormatIdsFallBackToRawValues() = withFixture({ path, xml ->
        if (path == "xl/styles.xml") xml.replace("numFmtId=\"4\"", "numFmtId=\"999\"") else xml
    }) { file ->
        val cell = NativeOfficeEngine.openWorkbook(file).sheets.single().rows.first().cells.getValue(0)
        assertEquals("1234.567", cell.value)
        assertTrue(cell.numberFormatUnsupported)
    }

    @Test fun appliesInheritedNumberFormatWhenLocalApplicationIsDisabled() = withFixture({ path, xml ->
        if (path != "xl/styles.xml") xml else xml.replace("<cellStyleXfs count=\"1\"><xf numFmtId=\"0\"", "<cellStyleXfs count=\"1\"><xf numFmtId=\"10\"")
            .replace("numFmtId=\"4\"", "numFmtId=\"4\" applyNumberFormat=\"0\"")
    }) { file ->
        val cell = NativeOfficeEngine.openWorkbook(file).sheets.single().rows.first().cells.getValue(0)
        assertEquals("123456.70%", cell.value)
        assertEquals(10, cell.numberFormatId)
    }

    @Test fun rejectsDuplicateCustomFormatIdentifiers() = withFixture({ path, xml ->
        if (path != "xl/styles.xml") xml else {
            val format = Regex("<numFmt [^>]+/>").find(xml)!!.value
            xml.replace("</numFmts>", "$format</numFmts>")
        }
    }) { assertInvalid(it) }

    @Test fun rejectsOversizedCustomFormatCodes() = withFixture({ path, xml ->
        if (path != "xl/styles.xml") xml else xml.replaceFirst(Regex("formatCode=\"[^\"]*\""), "formatCode=\"${"0".repeat(513)}\"")
    }) { assertInvalid(it) }

    private fun assertInvalid(file: File) {
        assertTrue(runCatching { NativeOfficeEngine.openWorkbook(file) }.exceptionOrNull() is InvalidOfficeDocumentException)
    }
    private fun withFixture(mutate: (String, String) -> String = { _, xml -> xml }, verify: (File) -> Unit) {
        val source = File.createTempFile("reader-excel-source-", ".xlsx")
        val result = File.createTempFile("reader-excel-test-", ".xlsx")
        try {
            javaClass.getResourceAsStream("/fixtures/number-formats-arabic.xlsx")!!.use { input -> source.outputStream().use { input.copyTo(it) } }
            ZipFile(source).use { zip ->
                ZipOutputStream(result.outputStream()).use { output ->
                    zip.entries().asSequence().forEach { entry ->
                        val bytes = zip.getInputStream(entry).use { it.readBytes() }
                        output.putNextEntry(ZipEntry(entry.name))
                        output.write(if (entry.name.endsWith(".xml")) mutate(entry.name, bytes.toString(Charsets.UTF_8)).toByteArray(Charsets.UTF_8) else bytes)
                        output.closeEntry()
                    }
                }
            }
            verify(result)
        } finally { source.delete(); result.delete() }
    }
}
