package com.r3dzf.reader

import java.io.File
import java.util.zip.ZipFile
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test

class PptxDrawingTest {
    private fun fixture() = File(javaClass.getResource("/fixtures/drawing-fonts-white.pptx")!!.toURI())
    private fun open(file: File = fixture()) = NativeOfficeEngine.open(file, OfficeFormat.PPTX) as NativePresentation
    @Test fun explicitWhiteThemeBackgroundDoesNotInheritYellowMaster() {
        val document = open()
        assertEquals(4,document.slides.size)
        document.slides.forEach { assertEquals(0xFFFFFFL,it.backgroundRgb) }
    }
    @Test fun rendersMasterThenLayoutThenScaledGroupChildren() {
        val shapes = open().slides.first().shapes
        assertEquals(4, shapes.size)
        assertEquals(0x124578L,shapes[0].fillRgb)
        assertEquals(0x228833L,shapes[1].fillRgb)
        val ellipse = shapes.single { it.geometry == "ellipse" }
        assertEquals(0.2f,ellipse.xFraction,0.06f) // rotation changes its center, not the group scale
        assertEquals(0.1f,ellipse.widthFraction,0.04f)
        assertEquals(2f,ellipse.outline!!.widthPoints,0f)
        assertTrue(ellipse.rotationDegrees > 30f)
        val text = shapes.single { it.text?.contains("مرحبا") == true }
        assertEquals(0.4f,text.xFraction,0.001f)
        assertEquals(0.2f,text.yFraction,0.001f)
        assertEquals(0.2f,text.widthFraction,0.001f)
        assertEquals(0.4f,text.heightFraction,0.001f)
        assertEquals(SlideCellVerticalAlignment.CENTER,text.textBox.vertical)
        assertEquals(0f,text.textBox.left,0f)
        assertEquals("Cairo",text.paragraphs[0].runs[0].fontFamilyName)
        assertEquals("Lato",text.paragraphs[0].runs[1].fontFamilyName)
    }
    @Test fun hiddenMasterShapesAndHorizontalFlipArePreserved() {
        val shapes = open().slides[1].shapes
        assertEquals(1,shapes.size)
        assertEquals("diamond",shapes.single().geometry)
        assertTrue(shapes.single().flipVertical) // decomposed reflection with equivalent rotation
    }
    @Test fun gradientStopsAndAngleAreReadWithoutChangingWhiteSlide() {
        val slide = open().slides[2]
        val fill = slide.shapes.single().fill!!
        assertEquals(listOf(0x0000FFL,0xFF0000L),fill.stops.map { it.rgb })
        assertEquals(0f,fill.angleDegrees,0f)
        assertEquals(0xFFFFFFL,slide.backgroundRgb)
    }
    @Test fun bulletAndHangingIndentAreIndependentOfPlainText() {
        val paragraph = open().slides[0].shapes.single { it.text != null }.paragraphs.single()
        assertEquals("مرحبا Reader",paragraph.plainText)
        assertEquals("•",paragraph.bulletMarker)
        assertEquals(12f,paragraph.marginStartPoints,0f)
        assertEquals(-6f,paragraph.indentPoints,0f)
        assertTrue(paragraph.rightToLeft)
    }
    @Test fun alphabetAndRomanNumberingHandleContinuation() {
        assertEquals("aa.",pptxNumberMarker(27,"alphaLcPeriod"))
        assertEquals("(IV)",pptxNumberMarker(4,"romanUcParenBoth"))
        assertEquals("12)",pptxNumberMarker(12,"arabicParenR"))
    }
    @Test fun rejectsExcessivelyNestedGroups() {
        val target = File.createTempFile("nested-pptx-", ".pptx")
        try {
            ZipFile(fixture()).use { input -> ZipOutputStream(target.outputStream()).use { out ->
                input.entries().asSequence().forEach { entry ->
                    var bytes = input.getInputStream(entry).readBytes()
                    if (entry.name == "ppt/slides/slide1.xml") {
                        val xml = bytes.toString(Charsets.UTF_8)
                        val start = "<p:grpSp><p:grpSpPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"1\" cy=\"1\"/><a:chOff x=\"0\" y=\"0\"/><a:chExt cx=\"1\" cy=\"1\"/></a:xfrm></p:grpSpPr>"
                        bytes = xml.replace("</p:spTree>", start.repeat(34) + "</p:grpSp>".repeat(34) + "</p:spTree>").toByteArray()
                    }
                    out.putNextEntry(ZipEntry(entry.name));out.write(bytes);out.closeEntry()
                }
            } }
            assertTrue(runCatching { open(target) }.exceptionOrNull() is InvalidOfficeDocumentException)
        } finally { target.delete() }
    }
}
