package com.r3dzf.reader

import java.io.File
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeOfficeEngineTest {
    @Test
    fun readsArabicParagraphFormattingAndTableCellsFromDocx() {
        val file = makeDocx(
            """<?xml version="1.0" encoding="UTF-8"?>
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:body>
                    <w:p>
                      <w:pPr><w:bidi/><w:jc w:val="right"/><w:spacing w:after="240"/></w:pPr>
                      <w:r><w:rPr><w:b/><w:sz w:val="32"/><w:color w:val="AA1122"/></w:rPr><w:t>مرحبا</w:t></w:r>
                      <w:r><w:t> بالعالم</w:t></w:r>
                    </w:p>
                    <w:tbl><w:tr><w:tc><w:p><w:r><w:t>الاسم</w:t></w:r></w:p></w:tc>
                      <w:tc><w:p><w:r><w:t>القيمة</w:t></w:r></w:p></w:tc></w:tr></w:tbl>
                    <w:sectPr/>
                  </w:body>
                </w:document>"""
        )

        try {
            val document = NativeOfficeEngine.open(file, OfficeFormat.DOCX) as NativeWordDocument
            assertEquals(2, document.blocks.size)
            val paragraph = document.blocks[0] as WordParagraph
            assertTrue(paragraph.rightToLeft)
            assertEquals(WordAlignment.END, paragraph.alignment)
            assertEquals("مرحبا بالعالم", paragraph.plainText)
            assertTrue(paragraph.runs.first().bold)
            assertEquals(16f, paragraph.runs.first().fontSizePoints!!, 0f)
            assertEquals(0xAA1122L, paragraph.runs.first().colorRgb!!)
            assertEquals(12f, paragraph.spaceAfterPoints, 0f)

            val table = document.blocks[1] as WordTable
            assertEquals(listOf(listOf("الاسم", "القيمة")), table.rows.map { row -> row.map { it.text } })
        } finally {
            file.delete()
        }
    }

    @Test
    fun rejectsXmlWithDoctype() {
        val file = makeDocx(
            """<!DOCTYPE w:document [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:body><w:p><w:r><w:t>&xxe;</w:t></w:r></w:p></w:body>
                </w:document>"""
        )

        try {
            val error = runCatching { NativeOfficeEngine.open(file, OfficeFormat.DOCX) }.exceptionOrNull()
            assertTrue(error is InvalidOfficeDocumentException)
            assertFalse(error?.message.orEmpty().contains("root:"))
        } finally {
            file.delete()
        }
    }

    @Test
    fun readsPageLayoutPageBreaksAndNumberedListMarkers() {
        val numbering = """<w:numbering xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
            <w:abstractNum w:abstractNumId="0"><w:lvl w:ilvl="0"><w:numFmt w:val="decimal"/><w:lvlText w:val="%1."/></w:lvl></w:abstractNum>
            <w:num w:numId="5"><w:abstractNumId w:val="0"/></w:num>
          </w:numbering>"""
        val file = makeDocx(
            """<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <w:body>
                <w:p><w:pPr><w:numPr><w:ilvl w:val="0"/><w:numId w:val="5"/></w:numPr></w:pPr><w:r><w:t>أول عنصر</w:t></w:r></w:p>
                <w:p><w:pPr><w:numPr><w:ilvl w:val="0"/><w:numId w:val="5"/></w:numPr></w:pPr><w:r><w:t>ثاني عنصر</w:t></w:r></w:p>
                <w:p><w:pPr><w:pageBreakBefore/></w:pPr><w:r><w:t>صفحة جديدة</w:t><w:br w:type="page"/></w:r></w:p>
                <w:sectPr><w:pgSz w:w="15840" w:h="12240" w:orient="landscape"/><w:pgMar w:top="720" w:right="720" w:bottom="720" w:left="1440"/></w:sectPr>
              </w:body>
            </w:document>""",
            numbering
        )

        try {
            val document = NativeOfficeEngine.open(file, OfficeFormat.DOCX) as NativeWordDocument
            val first = document.blocks[0] as WordParagraph
            val second = document.blocks[1] as WordParagraph
            val third = document.blocks[2] as WordParagraph
            assertEquals("1.", first.listMarker)
            assertEquals("2.", second.listMarker)
            assertTrue(third.startsNewPage)
            assertTrue(third.endsPage)
            assertEquals(792f, document.pageSettings.widthPoints, 0f)
            assertEquals(612f, document.pageSettings.heightPoints, 0f)
            assertEquals(36f, document.pageSettings.marginTopPoints, 0f)
            assertEquals(72f, document.pageSettings.marginLeftPoints, 0f)
        } finally {
            file.delete()
        }
    }

    @Test
    fun readsArabicInlineDocxImageAndItsDisplaySize() {
        val imageBytes = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGP4z8DwHwAFAAH/iZk9HQAAAABJRU5ErkJggg=="
        )
        val file = makeDocx(
            documentXml = """<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:pic="http://schemas.openxmlformats.org/drawingml/2006/picture">
                <w:body><w:p><w:r><w:t>قبل الصورة</w:t><w:drawing><wp:inline><wp:extent cx="914400" cy="457200"/><wp:docPr id="1" name="Logo" descr="شعار الجامعة"/><a:graphic><a:graphicData><pic:pic><pic:blipFill><a:blip r:embed="rIdImage"/></pic:blipFill></pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p></w:body>
              </w:document>""",
            imageBytes = imageBytes
        )
        try {
            val paragraph = (NativeOfficeEngine.open(file, OfficeFormat.DOCX) as NativeWordDocument)
                .blocks.filterIsInstance<WordParagraph>().single()
            val image = paragraph.images.single()
            assertEquals("قبل الصورة", paragraph.plainText)
            assertTrue(image.imageBytes.contentEquals(imageBytes))
            assertEquals(72f, image.widthPoints, 0f)
            assertEquals(36f, image.heightPoints, 0f)
            assertEquals("شعار الجامعة", image.description)
        } finally {
            file.delete()
        }
    }

    @Test
    fun readsImageInsideDocxTableCell() {
        val imageBytes = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGP4z8DwHwAFAAH/iZk9HQAAAABJRU5ErkJggg=="
        )
        val file = makeDocx(
            documentXml = """<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main">
                <w:body><w:tbl><w:tr><w:tc><w:p><w:r><w:t>شعار</w:t><w:drawing><wp:inline><wp:extent cx="457200" cy="457200"/><a:graphic><a:graphicData><a:blip r:embed="rIdImage"/></a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p></w:tc></w:tr></w:tbl></w:body>
              </w:document>""",
            imageBytes = imageBytes
        )
        try {
            val table = (NativeOfficeEngine.open(file, OfficeFormat.DOCX) as NativeWordDocument)
                .blocks.filterIsInstance<WordTable>().single()
            val cell = table.rows.single().single()
            assertEquals("شعار", cell.text)
            assertTrue(cell.images.single().imageBytes.contentEquals(imageBytes))
        } finally {
            file.delete()
        }
    }

    @Test
    fun rejectsOversizedEmbeddedDocxImage() {
        val file = makeDocx(
            documentXml = """<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main">
                <w:body><w:p><w:r><w:drawing><wp:inline><wp:extent cx="457200" cy="457200"/><a:graphic><a:graphicData><a:blip r:embed="rIdImage"/></a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p></w:body>
              </w:document>""",
            imageBytes = ByteArray(10 * 1024 * 1024 + 1)
        )
        try {
            val error = runCatching { NativeOfficeEngine.open(file, OfficeFormat.DOCX) }.exceptionOrNull()
            assertTrue(error is InvalidOfficeDocumentException)
        } finally {
            file.delete()
        }
    }

    @Test
    fun readsSharedInlineAndCachedValuesFromRtlWorkbook() {
        val file = makeXlsx()
        try {
            val workbook = NativeOfficeEngine.open(file, OfficeFormat.XLSX) as NativeWorkbook
            assertEquals(1, workbook.sheets.size)
            val sheet = workbook.sheets.single()
            assertEquals("الميزانية", sheet.name)
            assertTrue(sheet.rightToLeft)
            assertEquals("اسم الموظف", sheet.rows[0].cells[0]?.value)
            assertEquals("أحمد", sheet.rows[0].cells[1]?.value)
            assertTrue(sheet.rows[0].cells[1]?.bold == true)
            assertTrue(sheet.rows[0].cells[1]?.italic == true)
            assertEquals(14f, sheet.rows[0].cells[1]?.fontSizePoints!!, 0f)
            assertEquals(0x336699L, sheet.rows[0].cells[1]?.textRgb)
            assertEquals(0xEEEE00L, sheet.rows[0].cells[1]?.fillRgb)
            assertEquals(listOf(NativeMergedRange(0, 6, 7)), sheet.horizontalMerges)
            assertEquals(8, sheet.columnCount)
            assertEquals(2, sheet.rows[1].rowIndex)
            assertEquals("TRUE", sheet.rows[1].cells[3]?.value)
            assertEquals("3", sheet.rows[1].cells[4]?.value)
        } finally {
            file.delete()
        }
    }

    @Test
    fun rejectsWorkbookRelationshipOutsidePackage() {
        val file = makeXlsx(sheetTarget = "../../../../outside.xml")
        try {
            val error = runCatching { NativeOfficeEngine.open(file, OfficeFormat.XLSX) }.exceptionOrNull()
            assertTrue(error is InvalidOfficeDocumentException)
        } finally {
            file.delete()
        }
    }

    @Test
    fun unsupportedLegacyFormatsFailExplicitly() {
        val error = runCatching {
            NativeOfficeEngine.open(File("unused.doc"), OfficeFormat.DOC)
        }.exceptionOrNull()
        assertTrue(error is OfficeFormatNotImplementedException)
        assertTrue(error?.message.orEmpty().contains("DOC"))
    }

    @Test
    fun readsNativePptxSlideOrderArabicTextAndBasicShapeStyle() {
        val file = makePptx()
        try {
            val presentation = NativeOfficeEngine.open(file, OfficeFormat.PPTX) as NativePresentation
            assertEquals(2, presentation.slides.size)
            assertEquals(720f, presentation.slideWidthPoints, 0.1f)
            assertEquals(405f, presentation.slideHeightPoints, 0.1f)
            val firstSlide = presentation.slides.first()
            assertEquals(1, firstSlide.number)
            val shape = firstSlide.shapes.single { it.kind == NativeSlideShapeKind.TEXT }
            assertEquals("عنوان عربي", shape.text)
            assertEquals(NativeSlideShapeKind.TEXT, shape.kind)
            assertEquals(0.1f, shape.xFraction, 0.001f)
            assertEquals(0.1f, shape.yFraction, 0.001f)
            assertEquals(0xFFFFFFL, shape.fillRgb)
            assertEquals(0x123456L, shape.textRgb)
            assertEquals(24f, shape.fontSizePoints!!, 0f)
            assertTrue(shape.bold)
            assertTrue(shape.centered)
            assertTrue(shape.rightToLeft)
        } finally {
            file.delete()
        }
    }

    @Test
    fun rejectsPptxRelationshipOutsidePackage() {
        val file = makePptx(slideTarget = "../../../../outside.xml")
        try {
            val error = runCatching { NativeOfficeEngine.open(file, OfficeFormat.PPTX) }.exceptionOrNull()
            assertTrue(error is InvalidOfficeDocumentException)
        } finally {
            file.delete()
        }
    }

    @Test
    fun readsEmbeddedPptxImageBytesThroughSlideRelationships() {
        val expectedImage = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGP4z8DwHwAFAAH/iZk9HQAAAABJRU5ErkJggg=="
        )
        val file = makePptx(imageBytes = expectedImage)
        try {
            val presentation = NativeOfficeEngine.open(file, OfficeFormat.PPTX) as NativePresentation
            val picture = presentation.slides.first().shapes.single { it.kind == NativeSlideShapeKind.IMAGE }
            assertTrue(picture.imageBytes!!.contentEquals(expectedImage))
        } finally {
            file.delete()
        }
    }

    @Test
    fun readsArabicTextFromPptxTableCells() {
        val file = makePptx()
        try {
            val presentation = NativeOfficeEngine.open(file, OfficeFormat.PPTX) as NativePresentation
            val table = presentation.slides.first().shapes.single { it.kind == NativeSlideShapeKind.TABLE }
            assertEquals(
                listOf(listOf("الاسم", "القيمة"), listOf("أحمد", "١٢٣")),
                table.tableRows.map { row -> row.map { it.text } }
            )
            val styledCell = table.tableRows.first().first()
            assertEquals(0xFFF2CCL, styledCell.fillRgb)
            assertEquals(0xCC0000L, styledCell.textRgb)
            assertEquals(18f, styledCell.fontSizePoints!!, 0f)
            assertTrue(styledCell.bold)
            assertTrue(styledCell.italic)
            assertTrue(styledCell.centered)
            assertTrue(styledCell.rightToLeft)
        } finally {
            file.delete()
        }
    }

    @Test
    fun rejectsPptxTablesBeyondColumnLimit() {
        val file = makePptx(tableColumnCount = 65)
        try {
            val error = runCatching { NativeOfficeEngine.open(file, OfficeFormat.PPTX) }.exceptionOrNull()
            assertTrue(error is InvalidOfficeDocumentException)
        } finally {
            file.delete()
        }
    }

    @Test
    fun preservesMergedArabicTableGeometryBordersAndOriginalFile() {
        val file = pptxReferenceFixture()
        val original = file.readBytes()
        try {
            val document = NativeOfficeEngine.open(file, OfficeFormat.PPTX) as NativePresentation
            val shape = document.slides.single().shapes.single { it.kind == NativeSlideShapeKind.TABLE }
            val table = shape.table!!
            assertTrue(table.rightToLeft)
            assertEquals(9, table.rows.sumOf { it.size })
            assertEquals(6, table.placedCells.size)
            val merged = table.placedCells.single { it.row == 0 && it.column == 0 }
            assertEquals("عنوان مدمج", merged.cell.text)
            assertEquals(2, merged.cell.rowSpan)
            assertEquals(2, merged.cell.columnSpan)
            assertEquals(0.5f, merged.xFraction, 0.001f)
            assertEquals(0f, merged.yFraction, 0f)
            assertEquals(0.5f, merged.widthFraction, 0.001f)
            assertEquals(0.5f, merged.heightFraction, 0.001f)
            val bottomLeft = table.placedCells.single { it.row == 2 && it.column == 0 }
            assertEquals(5f / 6f, bottomLeft.xFraction, 0.001f)
            assertEquals(1f / 6f, bottomLeft.widthFraction, 0.001f)
            assertEquals(0.5f, bottomLeft.yFraction, 0.001f)
            assertEquals(SlideCellVerticalAlignment.CENTER, merged.cell.verticalAlignment)
            assertEquals(3.6f, merged.cell.marginLeftPoints, 0.01f)
            assertEquals(0f, merged.cell.borderLeft!!.widthPoints, 0f)
            assertEquals(2f, merged.cell.borderTop!!.widthPoints, 0f)
            assertEquals(0x123456L, merged.cell.borderTop!!.colorRgb)
            assertEquals(SlideBorderDash.DASH, merged.cell.borderTop!!.dash)
            assertEquals(0.1f, shape.xFraction, 0.001f)
            assertEquals(0.6f, shape.widthFraction, 0.001f)
            assertTrue(original.contentEquals(file.readBytes()))
        } finally {
            file.delete()
        }
    }

    @Test
    fun rejectsPptxMergeBeyondTableBounds() {
        val file = pptxReferenceFixture { xml -> xml.replace("gridSpan=\"2\"", "gridSpan=\"2147483647\"") }
        try {
            assertTrue(runCatching { NativeOfficeEngine.open(file, OfficeFormat.PPTX) }.exceptionOrNull() is InvalidOfficeDocumentException)
        } finally {
            file.delete()
        }
    }

    @Test
    fun rejectsOrphanPptxMergeContinuation() {
        val file = pptxReferenceFixture { xml -> xml.replace("rowSpan=\"2\" gridSpan=\"2\"", "rowSpan=\"2\" gridSpan=\"1\"") }
        try {
            assertTrue(runCatching { NativeOfficeEngine.open(file, OfficeFormat.PPTX) }.exceptionOrNull() is InvalidOfficeDocumentException)
        } finally {
            file.delete()
        }
    }

    @Test
    fun rejectsNonFinitePptxShapeCoordinates() {
        val file = pptxReferenceFixture { xml -> xml.replaceFirst("x=\"914400\"", "x=\"NaN\"") }
        try {
            assertTrue(runCatching { NativeOfficeEngine.open(file, OfficeFormat.PPTX) }.exceptionOrNull() is InvalidOfficeDocumentException)
        } finally {
            file.delete()
        }
    }

    @Test
    fun identifiesChartGraphicFrameAsUnsupportedElementInsteadOfTable() {
        val file = pptxReferenceFixture { xml ->
            xml.replace(Regex("<a:graphicData[^>]*>.*?</a:graphicData>"),
                """<a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/chart"><c:chart xmlns:c="http://schemas.openxmlformats.org/drawingml/2006/chart"/></a:graphicData>""")
        }
        try {
            val presentation = NativeOfficeEngine.open(file, OfficeFormat.PPTX) as NativePresentation
            val shape = presentation.slides.single().shapes.single()
            assertEquals(NativeSlideShapeKind.OTHER, shape.kind)
            assertTrue(shape.table == null)
        } finally {
            file.delete()
        }
    }

    private fun pptxReferenceFixture(mutate: (String) -> String = { it }): File {
        val source = File.createTempFile("reader-reference-", ".pptx")
        javaClass.getResourceAsStream("/fixtures/merged-arabic-table.pptx")!!.use { input ->
            source.outputStream().use { input.copyTo(it) }
        }
        val result = File.createTempFile("reader-test-", ".pptx")
        try {
            ZipFile(source).use { zip ->
                ZipOutputStream(result.outputStream()).use { output ->
                    zip.entries().asSequence().forEach { entry ->
                        output.putNextEntry(ZipEntry(entry.name))
                        val bytes = zip.getInputStream(entry).use { it.readBytes() }
                        output.write(if (entry.name == "ppt/slides/slide1.xml") mutate(bytes.toString(Charsets.UTF_8)).toByteArray(Charsets.UTF_8) else bytes)
                        output.closeEntry()
                    }
                }
            }
            return result
        } finally {
            source.delete()
        }
    }

    @Test
    fun rejectsOversizedEmbeddedPptxImage() {
        val file = makePptx(imageBytes = ByteArray(10 * 1024 * 1024 + 1))
        try {
            val error = runCatching { NativeOfficeEngine.open(file, OfficeFormat.PPTX) }.exceptionOrNull()
            assertTrue(error is InvalidOfficeDocumentException)
        } finally {
            file.delete()
        }
    }

    @Test
    fun readsMacroEnabledAndTemplateOoxmlVariantsWithoutExecutingMacros() {
        val wordFile = makeDocx(
            """<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p><w:r><w:t>مستند</w:t></w:r></w:p></w:body></w:document>"""
        )
        val excelFile = makeXlsx()
        val slidesFile = makePptx()
        try {
            assertEquals("مستند", (NativeOfficeEngine.open(wordFile, OfficeFormat.DOCM) as NativeWordDocument)
                .blocks.filterIsInstance<WordParagraph>().single().plainText)
            assertEquals(1, (NativeOfficeEngine.open(excelFile, OfficeFormat.XLTX) as NativeWorkbook).sheets.size)
            assertEquals(2, (NativeOfficeEngine.open(slidesFile, OfficeFormat.PPSX) as NativePresentation).slides.size)
            assertEquals(OfficeFormat.PPTM, OfficeFormat.fromName("lecture.PPTM"))
            assertEquals(OfficeFormat.XLSM, OfficeFormat.fromMimeType(OfficeFormat.XLSM.mimeType))
        } finally {
            wordFile.delete()
            excelFile.delete()
            slidesFile.delete()
        }
    }

    private fun makeDocx(
        documentXml: String,
        numberingXml: String? = null,
        imageBytes: ByteArray? = null
    ): File = File.createTempFile("reader-test-", ".docx").also { file ->
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("[Content_Types].xml"))
            zip.write("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"/>".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("word/document.xml"))
            zip.write(documentXml.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            if (numberingXml != null) {
                zip.putNextEntry(ZipEntry("word/numbering.xml"))
                zip.write(numberingXml.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            if (imageBytes != null) {
                fun entry(path: String, bytes: ByteArray) {
                    zip.putNextEntry(ZipEntry(path))
                    zip.write(bytes)
                    zip.closeEntry()
                }
                entry(
                    "word/_rels/document.xml.rels",
                    """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rIdImage" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="media/image1.png"/></Relationships>""".toByteArray()
                )
                entry("word/media/image1.png", imageBytes)
            }
        }
    }

    private fun makeXlsx(sheetTarget: String = "worksheets/sheet1.xml"): File = File.createTempFile("reader-test-", ".xlsx").also { file ->
        ZipOutputStream(file.outputStream()).use { zip ->
            fun entry(path: String, xml: String) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(xml.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            entry("[Content_Types].xml", "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"/>")
            entry(
                "xl/workbook.xml",
                """<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                    <sheets><sheet name="الميزانية" sheetId="1" r:id="rId1"/></sheets>
                  </workbook>"""
            )
            entry(
                "xl/_rels/workbook.xml.rels",
                """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                    <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="$sheetTarget"/>
                  </Relationships>"""
            )
            entry(
                "xl/sharedStrings.xml",
                """<sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><si><t>اسم الموظف</t></si></sst>"""
            )
            entry(
                "xl/styles.xml",
                """<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                    <fonts count="2"><font><sz val="11"/><color rgb="FF000000"/><name val="Calibri"/></font><font><b/><i/><sz val="14"/><color rgb="FF336699"/></font></fonts>
                    <fills count="3"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill><fill><patternFill patternType="solid"><fgColor rgb="FFEEEE00"/></patternFill></fill></fills>
                    <cellXfs count="2"><xf fontId="0" fillId="0" borderId="0" xfId="0"/><xf fontId="1" fillId="2" borderId="0" xfId="0" applyFont="1" applyFill="1"/></cellXfs>
                  </styleSheet>"""
            )
            entry(
                "xl/worksheets/sheet1.xml",
                """<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                    <sheetViews><sheetView rightToLeft="1"/></sheetViews>
                    <sheetData>
                      <row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1" s="1" t="inlineStr"><is><t>أحمد</t></is></c></row>
                      <row r="3"><c r="D3" t="b"><v>1</v></c><c r="E3"><f>1+2</f><v>3</v></c></row>
                    </sheetData>
                    <mergeCells count="1"><mergeCell ref="G1:H1"/></mergeCells>
                  </worksheet>"""
            )
        }
    }

    private fun makePptx(
        slideTarget: String = "slides/slide1.xml",
        imageBytes: ByteArray? = null,
        tableColumnCount: Int = 2
    ): File = File.createTempFile("reader-test-", ".pptx").also { file ->
        ZipOutputStream(file.outputStream()).use { zip ->
            fun entry(path: String, xml: String) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(xml.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            fun binaryEntry(path: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(bytes)
                zip.closeEntry()
            }
            entry("[Content_Types].xml", "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"/>")
            entry(
                "ppt/presentation.xml",
                """<p:presentation xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                    <p:sldIdLst><p:sldId id="257" r:id="rId2"/><p:sldId id="256" r:id="rId1"/></p:sldIdLst>
                    <p:sldSz cx="9144000" cy="5143500"/>
                  </p:presentation>"""
            )
            entry(
                "ppt/_rels/presentation.xml.rels",
                """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                    <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide" Target="$slideTarget"/>
                    <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide" Target="slides/slide2.xml"/>
                  </Relationships>"""
            )
            entry(
                "ppt/slides/slide1.xml",
                """<p:sld xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main">
                    <p:cSld><p:spTree><p:sp>
                      <p:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="9144000" cy="5143500"/></a:xfrm><a:solidFill><a:srgbClr val="EEEEEE"/></a:solidFill></p:spPr>
                      <p:txBody><a:bodyPr/><a:lstStyle/><a:p><a:pPr algn="ctr"/><a:r><a:rPr b="1" sz="1800"/><a:t>Hello</a:t></a:r></a:p></p:txBody>
                    </p:sp></p:spTree></p:cSld>
                  </p:sld>"""
            )
            if (imageBytes != null) {
                entry(
                    "ppt/slides/_rels/slide2.xml.rels",
                    """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                        <Relationship Id="rIdImage" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="../media/pixel.png"/>
                      </Relationships>"""
                )
                binaryEntry(
                    "ppt/media/pixel.png",
                    imageBytes
                )
            }
            val optionalPicture = if (imageBytes != null) """
                <p:pic><p:blipFill><a:blip r:embed="rIdImage"/></p:blipFill>
                  <p:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="914400" cy="914400"/></a:xfrm></p:spPr>
                </p:pic>""" else ""
            val tableGrid = List(tableColumnCount) { """<a:gridCol w="2286000"/>""" }.joinToString("")
            fun tableRow(values: List<String>, styleFirstCell: Boolean = false) = """<a:tr h="914400">""" +
                (0 until tableColumnCount).joinToString("") { index ->
                    val text = values.getOrNull(index).orEmpty()
                    val styled = styleFirstCell && index == 0
                    val paragraphProperties = if (styled) """<a:pPr algn="ctr" rtl="1"/>""" else ""
                    val runProperties = if (styled) """
                        <a:rPr b="1" i="1" sz="1800"><a:solidFill><a:srgbClr val="CC0000"/></a:solidFill></a:rPr>
                    """ else ""
                    val textRun = if (text.isEmpty()) "" else """<a:r>$runProperties<a:t>$text</a:t></a:r>"""
                    val cellFill = if (styled) """<a:solidFill><a:srgbClr val="FFF2CC"/></a:solidFill>""" else ""
                    """<a:tc><a:txBody><a:bodyPr/><a:lstStyle/><a:p>$paragraphProperties$textRun</a:p></a:txBody><a:tcPr>$cellFill</a:tcPr></a:tc>"""
                } + "</a:tr>"
            val optionalTable = """
                <p:graphicFrame>
                  <p:xfrm><a:off x="3657600" y="2743200"/><a:ext cx="4572000" cy="1828800"/></p:xfrm>
                  <a:graphic><a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/table">
                    <a:tbl><a:tblPr/><a:tblGrid>$tableGrid</a:tblGrid>
                      ${tableRow(listOf("الاسم", "القيمة"), styleFirstCell = true)}
                      ${tableRow(listOf("أحمد", "١٢٣"))}
                    </a:tbl>
                  </a:graphicData></a:graphic>
                </p:graphicFrame>"""
            entry(
                "ppt/slides/slide2.xml",
                """<p:sld xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                    <p:cSld><p:spTree><p:sp>
                      <p:spPr><a:xfrm><a:off x="914400" y="514350"/><a:ext cx="4572000" cy="1828800"/></a:xfrm><a:solidFill><a:srgbClr val="FFFFFF"/></a:solidFill></p:spPr>
                      <p:txBody><a:bodyPr/><a:lstStyle/><a:p><a:pPr algn="ctr" rtl="1"/><a:r><a:rPr b="1" sz="2400"><a:solidFill><a:srgbClr val="123456"/></a:solidFill></a:rPr><a:t>عنوان عربي</a:t></a:r></a:p></p:txBody>
                    </p:sp>$optionalPicture$optionalTable</p:spTree></p:cSld>
                  </p:sld>"""
            )
        }
    }
}
