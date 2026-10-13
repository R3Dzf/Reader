package com.r3dzf.reader

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PptxStyleTest {
    @Test
    fun readsThemeOverrideColorsBackgroundAndInheritedPlaceholderPosition() {
        withFixture { file ->
            val document = NativeOfficeEngine.open(file, OfficeFormat.PPTX) as NativePresentation
            assertEquals(2, document.slides.size)
            assertEquals(0x808080L, document.slides[0].backgroundRgb)
            assertEquals(0xABCDEFL, document.slides[1].backgroundRgb)
            val first = document.slides[0].shapes.single { it.kind == NativeSlideShapeKind.TEXT }
            assertEquals(0.1f, first.xFraction, 0.001f)
            assertEquals(0.8f, first.widthFraction, 0.001f)
            assertEquals(0.5f / 5.625f, first.yFraction, 0.001f)
            assertEquals(0x40FF40L, first.paragraphs[0].runs[0].textRgb)
            val second = document.slides[1].shapes.single { it.kind == NativeSlideShapeKind.TEXT }
            assertEquals(0x001122L, second.paragraphs[0].runs[0].textRgb)
            assertEquals(36f, second.paragraphs[0].runs[0].fontSizePoints!!, 0f)
            assertEquals("serif", second.paragraphs[0].runs[0].fontFamilyName)
        }
    }

    @Test
    fun preservesRunStylesFontsParagraphDirectionSpacingAndFieldText() {
        withFixture { file ->
            val presentation = NativeOfficeEngine.open(file, OfficeFormat.PPTX) as NativePresentation
            val paragraphs = presentation.slides[0].shapes.single { it.kind == NativeSlideShapeKind.TEXT }.paragraphs
            assertEquals("عنوان عربي", paragraphs[0].plainText)
            val first = paragraphs[0].runs[0]
            assertTrue(first.bold)
            assertFalse(first.italic)
            assertEquals(24f, first.fontSizePoints!!, 0f)
            assertEquals("serif", first.fontFamilyName)
            val second = paragraphs[0].runs[1]
            assertFalse(second.bold)
            assertTrue(second.italic)
            assertTrue(second.underline)
            assertTrue(second.strikeThrough)
            assertEquals(30f, second.fontSizePoints!!, 0f)
            assertEquals(0x0000FFL, second.textRgb)
            assertEquals("monospace", second.fontFamilyName)
            assertEquals(SlideParagraphAlignment.RIGHT, paragraphs[0].alignment)
            assertTrue(paragraphs[0].rightToLeft)
            assertEquals("English \nنص٧", paragraphs[1].plainText)
            assertEquals(SlideParagraphAlignment.LEFT, paragraphs[1].alignment)
            assertFalse(paragraphs[1].rightToLeft)
            assertEquals(6f, paragraphs[1].spaceBeforePoints, 0f)
            assertEquals(3f, paragraphs[1].spaceAfterPoints, 0f)
            assertEquals(0x0000FFL, paragraphs[1].runs[0].textRgb)
            assertEquals("monospace", paragraphs[1].runs[0].fontFamilyName)
            assertEquals("٧", paragraphs[1].runs.last().text)
        }
    }

    @Test
    fun resolvesThemedCellFillBorderAndEachTextRun() {
        withFixture { file ->
            val presentation = NativeOfficeEngine.open(file, OfficeFormat.PPTX) as NativePresentation
            val cell = presentation.slides[0].shapes.single { it.kind == NativeSlideShapeKind.TABLE }.tableRows.single().single()
            assertEquals(0xBFBFFFL, cell.fillRgb)
            assertEquals(0x008000L, cell.borderBottom!!.colorRgb)
            assertEquals(2f, cell.borderBottom!!.widthPoints, 0f)
            assertEquals("قيمة مهمة", cell.text)
            val runs = cell.paragraphs.single().runs
            assertFalse(runs[0].bold)
            assertEquals(16f, runs[0].fontSizePoints!!, 0f)
            assertEquals(0x0000FFL, runs[0].textRgb)
            assertTrue(runs[1].bold)
            assertEquals(22f, runs[1].fontSizePoints!!, 0f)
            assertEquals(0x008000L, runs[1].textRgb)
        }
    }

    @Test
    fun infersDirectionFromUnicodeWhenNoDirectionWasSaved() {
        withFixture({ _, xml ->
            xml.replace(Regex(" rtl=\"[01]\""), "").replace("عنوان ", "\u0750\uFB50").replace("عربي", "")
        }) { file ->
            val presentation = NativeOfficeEngine.open(file, OfficeFormat.PPTX) as NativePresentation
            val paragraphs = presentation.slides[0].shapes.single { it.kind == NativeSlideShapeKind.TEXT }.paragraphs
            assertTrue(paragraphs[0].rightToLeft)
            assertFalse(paragraphs[1].rightToLeft)
            assertEquals("serif", paragraphs[0].runs[0].fontFamilyName)
        }
    }

    @Test
    fun rejectsThemeXmlEntityDeclarations() {
        withFixture({ path, xml ->
            if (path == "ppt/theme/theme1.xml") xml.replaceFirst("?>", "?><!DOCTYPE a:theme [<!ENTITY xxe SYSTEM 'file:///etc/passwd'>]>") else xml
        }) { assertInvalid(it) }
    }

    @Test
    fun rejectsMissingReferencedTheme() {
        withFixture({ path, xml ->
            if (path == "ppt/slideMasters/_rels/slideMaster1.xml.rels") xml.replace("../theme/theme1.xml", "../theme/missing.xml") else xml
        }) { assertInvalid(it) }
    }

    @Test
    fun rejectsStyleRelationshipOutsideThePackage() {
        withFixture({ path, xml ->
            if (path == "ppt/slideMasters/_rels/slideMaster1.xml.rels") xml.replace("../theme/theme1.xml", "../../../../outside.xml") else xml
        }) { assertInvalid(it) }
    }

    @Test
    fun rejectsOversizedTextBodyBeforeRendering() {
        withFixture({ path, xml ->
            if (path == "ppt/slides/slide1.xml") xml.replace("عنوان ", "A".repeat(100_001)) else xml
        }) { assertInvalid(it) }
    }

    private fun assertInvalid(file: File) {
        assertTrue(runCatching { NativeOfficeEngine.open(file, OfficeFormat.PPTX) }.exceptionOrNull() is InvalidOfficeDocumentException)
    }

    private fun withFixture(mutate: (String, String) -> String = { _, xml -> xml }, verify: (File) -> Unit) {
        val source = File.createTempFile("reader-style-source-", ".pptx")
        val result = File.createTempFile("reader-style-test-", ".pptx")
        try {
            javaClass.getResourceAsStream("/fixtures/theme-rich-text.pptx")!!.use { input ->
                source.outputStream().use { input.copyTo(it) }
            }
            ZipFile(source).use { zip ->
                ZipOutputStream(result.outputStream()).use { output ->
                    zip.entries().asSequence().forEach { entry ->
                        val bytes = zip.getInputStream(entry).use { it.readBytes() }
                        output.putNextEntry(ZipEntry(entry.name))
                        output.write(if (entry.name.endsWith(".xml") || entry.name.endsWith(".rels")) {
                            mutate(entry.name, bytes.toString(Charsets.UTF_8)).toByteArray(Charsets.UTF_8)
                        } else bytes)
                        output.closeEntry()
                    }
                }
            }
            verify(result)
        } finally {
            source.delete()
            result.delete()
        }
    }
}
