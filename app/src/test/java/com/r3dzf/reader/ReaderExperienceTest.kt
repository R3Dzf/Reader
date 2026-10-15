package com.r3dzf.reader
import org.junit.Test
import org.junit.Assert.*
import javax.xml.parsers.DocumentBuilderFactory
class ReaderExperienceTest {
    private fun xml(text: String)=DocumentBuilderFactory.newInstance().apply {isNamespaceAware=true}.newDocumentBuilder().parse(text.byteInputStream()).documentElement
    @Test fun mostlyVisiblePageWinsOverTinyPreviousTail() {assertEquals(1,dominantPage(listOf(0 to (-490 to 500),1 to (20 to 500)),0,600))}
    @Test fun aSingleVisiblePageKeepsItsIndex() {assertEquals(7,dominantPage(listOf(7 to (0 to 800)),0,800))}
    @Test fun emptyLayoutHasNoInventedPage() {assertNull(dominantPage(emptyList(),0,800))}
    @Test fun readingFractionCountsVisiblePageAndSinglePage() {assertEquals(.3f,ReadingProgress(2,0,10).fraction,.0001f);assertEquals(1f,ReadingProgress(0,0,1).fraction,0f);assertEquals(0f,ReadingProgress().fraction,0f)}
    @Test fun spreadsheetThemeOrderResolvesWhiteAndBlack() {val colors=XlsxColors();assertEquals(0xFFFFFFL,colors.read(xml("<color theme='0'/>")));assertEquals(0L,colors.read(xml("<color theme='1'/>")))}
    @Test fun customThemePreservesContrastingHeaderBackground() {
        val theme=xml("<theme><themeElements><clrScheme><accent1><srgbClr val='174D40'/></accent1></clrScheme></themeElements></theme>")
        val colors=XlsxColors(theme);assertEquals(0x174D40L,colors.read(xml("<fgColor theme='4'/>")));assertEquals(0xFFFFFFL,colors.read(xml("<color rgb='FFFFFFFF'/>")))
    }
    @Test fun indexedAndTintedColorsResolve() {val c=XlsxColors();assertEquals(0xFF0000L,c.read(xml("<color indexed='2'/>")));assertEquals(0xFFFFFFL,c.read(xml("<color theme='4' tint='1'/>")));assertEquals(0L,c.read(xml("<color theme='4' tint='-1'/>")))}
    @Test fun customIndexedPaletteIsUsed() {val c=XlsxColors(styles=xml("<styleSheet><colors><indexedColors><rgbColor rgb='FF123456'/></indexedColors></colors></styleSheet>"));assertEquals(0x123456L,c.read(xml("<color indexed='0'/>")))}
    @Test fun themedFixtureKeepsVisibleTextAndBackground() {
        val f=java.io.File(javaClass.getResource("/fixtures/reader-budget-theme.xlsx")!!.toURI())
        val sheet=NativeOfficeEngine.openWorkbook(f).sheets.single();val header=sheet.rows.first().cells.getValue(0)
        assertEquals("المنتج",header.value);assertEquals(0xFFFFFFL,header.textRgb);assertNotNull(header.fillRgb);assertNotEquals(header.fillRgb,header.textRgb)
        assertEquals(0L,sheet.rows[1].cells.getValue(0).textRgb)
    }
}
