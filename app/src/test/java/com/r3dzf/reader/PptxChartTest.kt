package com.r3dzf.reader

import java.io.File
import java.util.zip.ZipFile
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test

class PptxChartTest {
    private fun fixture()=File(javaClass.getResource("/fixtures/charts-native-arabic.pptx")!!.toURI())
    private fun charts(file: File=fixture())=(NativeOfficeEngine.open(file,OfficeFormat.PPTX) as NativePresentation).slides.map { it.shapes.single().chart!! }
    private fun changed(path: String="ppt/charts/chart1.xml",replace: (String)->String,block: (File)->Unit) {
        val target=File.createTempFile("chart-case-", ".pptx")
        try {
            ZipFile(fixture()).use { input -> ZipOutputStream(target.outputStream()).use { output ->
                input.entries().asSequence().forEach { entry ->
                    val bytes=input.getInputStream(entry).readBytes()
                    output.putNextEntry(ZipEntry(entry.name))
                    output.write(if(entry.name==path) replace(bytes.toString(Charsets.UTF_8)).toByteArray() else bytes)
                    output.closeEntry()
                }
            } }
            block(target)
        } finally { target.delete() }
    }
    @Test fun readsRealChartRelationshipsKindsArabicNamesCachedValuesAndStyles() {
        val all=charts()
        assertEquals(listOf(SlideChartKind.COLUMN,SlideChartKind.BAR,SlideChartKind.COLUMN,SlideChartKind.LINE,SlideChartKind.AREA,SlideChartKind.PIE,SlideChartKind.DOUGHNUT,SlideChartKind.SCATTER,SlideChartKind.UNSUPPORTED),all.map { it.kind })
        val first=all[0];assertTrue(first.drawable)
        assertEquals("مبيعات الأشهر",first.title)
        assertEquals(listOf("يناير","فبراير","مارس"),first.categories)
        assertEquals("المبيعات",first.series[0].name)
        assertEquals(listOf(12.0,-6.0,18.0),first.series[0].values)
        assertEquals(0x1464C8L,first.series[0].colorRgb)
        assertEquals(-10.0,first.valueAxis.minimum!!,0.0);assertEquals(20.0,first.valueAxis.maximum!!,0.0)
        assertTrue(first.showValues)
        assertEquals(60,all[6].holeSize)
        assertEquals(0xDA492AL,all[5].series[0].pointColors[1])
    }
    @Test fun keepsGapsAndScatterCoordinatesWithoutRedistributingUnequalXValues() {
        val all=charts();val line=all[3]
        assertEquals(listOf(5.0,null,12.0,9.0),line.series[0].values)
        assertTrue(SlideChartNotice.PARTIAL_VALUES in line.notices)
        val scatter=all[7];assertEquals(listOf(1.0,2.0,10.0),scatter.series[0].xValues)
        assertFalse(scatter.series[0].lineVisible);assertTrue(scatter.series[0].markerVisible)
        val range=chartRange(scatter.series[0].xValues.filterNotNull(),scatter.categoryAxis,false)
        assertEquals(.1f,range.fraction(1.0),.00001f);assertEquals(.2f,range.fraction(2.0),.00001f)
    }
    @Test fun stacksPositiveAndNegativeValuesOnTheirRespectiveSidesOfZero() {
        val chart=charts()[1];assertEquals(SlideChartGrouping.STACKED,chart.grouping)
        val segments=chartSegments(chart)
        val first=segments.single { it.series==1 && it.index==0 };assertEquals(10.0,first.start,0.0);assertEquals(15.0,first.end,0.0)
        val negative=segments.single { it.series==1 && it.index==1 };assertEquals(0.0,negative.start,0.0);assertEquals(-8.0,negative.end,0.0)
    }
    @Test fun scatterWithoutNumericXValuesShowsMissingDataRatherThanAnEmptyPlot() {
        changed(path="ppt/charts/chart8.xml",replace={ xml ->
            xml.replace(Regex("<c:xVal>.*?</c:xVal>",RegexOption.DOT_MATCHES_ALL)) { match ->
                match.value.replace(Regex("<c:v>.*?</c:v>"),"<c:v>#N/A</c:v>")
            }
        }) { file ->
            val scatter=charts(file)[7]
            assertFalse(scatter.drawable)
            assertTrue(SlideChartNotice.MISSING_VALUES in scatter.notices)
            assertTrue(SlideChartNotice.PARTIAL_VALUES in scatter.notices)
        }
    }
    @Test fun normalizesPercentColumnsAndDrawsPieSectorsFromSavedValues() {
        val all=charts();val percent=chartSegments(all[2])
        assertEquals(.25,percent.single { it.series==0 && it.index==0 }.end,1e-10)
        assertEquals(1.0,percent.single { it.series==1 && it.index==0 }.end,1e-10)
        val pie=chartSlices(all[5]);assertEquals(listOf(60f,120f,180f),pie.map { it.sweepDegrees })
        assertEquals(-90f,pie[0].startDegrees,0f);assertEquals(90f,pie[2].startDegrees,0f)
    }
    @Test fun readsCachesWhenEmbeddedWorkbookIsNotPresentAndDoesNotModifySource() {
        val source=fixture();val before=source.readBytes();val target=File.createTempFile("cache-only-", ".pptx")
        try {
            ZipFile(source).use { input -> ZipOutputStream(target.outputStream()).use { output ->
                input.entries().asSequence().filterNot { it.name.startsWith("ppt/embeddings/") }.forEach { entry ->
                    output.putNextEntry(ZipEntry(entry.name));output.write(input.getInputStream(entry).readBytes());output.closeEntry()
                }
            } }
            assertEquals(18.0,charts(target)[0].series[0].values[2]!!,0.0)
            assertArrayEquals(before,source.readBytes())
        } finally { target.delete() }
    }
    @Test fun unsupportedRadarRetainsCachedValuesAndHasAnExplicitNotice() {
        val chart=charts().last();assertFalse(chart.drawable)
        assertTrue(SlideChartNotice.UNSUPPORTED_TYPE in chart.notices)
        assertEquals(listOf(2.0,4.0,3.0),chart.series[0].values)
    }
    @Test fun missingCacheDoesNotInventZeroValues() {
        changed(replace={it.replace(Regex("<c:numCache>.*?</c:numCache>",RegexOption.DOT_MATCHES_ALL),"")}) { file ->
            val chart=charts(file)[0];assertFalse(chart.drawable)
            assertTrue(SlideChartNotice.MISSING_VALUES in chart.notices)
            assertTrue(chart.series.all { it.values.isEmpty() })
        }
    }
    @Test fun rejectsDuplicatePointsAndExtremeIndices() {
        for(replacement in listOf("idx=\"0\"","idx=\"999999999\"")) changed(replace={it.replace("idx=\"1\"",replacement)}) { file ->
            assertTrue(runCatching { charts(file) }.exceptionOrNull() is InvalidOfficeDocumentException)
        }
    }
    @Test fun rejectsNonfiniteNumbersAndOutOfPackageChartRelationships() {
        changed(replace={it.replace("<c:v>12</c:v>","<c:v>NaN</c:v>")}) { file ->
            assertTrue(runCatching { charts(file) }.exceptionOrNull() is InvalidOfficeDocumentException)
        }
        changed(path="ppt/slides/_rels/slide1.xml.rels",replace={it.replace("../charts/chart1.xml","../../../../chart1.xml")}) { file ->
            assertTrue(runCatching { charts(file) }.exceptionOrNull() is InvalidOfficeDocumentException)
        }
    }
    @Test fun rejectsEntityDeclarationsInAChartPart() {
        changed(replace={it.replaceFirst("?>","?><!DOCTYPE c:chartSpace [<!ENTITY xxe SYSTEM 'file:///etc/passwd'>]>")}) { file ->
            assertTrue(runCatching { charts(file) }.exceptionOrNull() is InvalidOfficeDocumentException)
        }
    }
    @Test fun logarithmicAxesAreFlaggedRatherThanPlottedOnALinearScale() {
        changed(replace={it.replace("<c:scaling>","<c:scaling><c:logBase val=\"10\"/>")}) { file ->
            assertTrue(SlideChartNotice.UNSUPPORTED_AXIS in charts(file)[0].notices);assertFalse(charts(file)[0].drawable)
        }
    }
    @Test fun rangesRespectSavedBoundsAndRemainFiniteForConstantOrTinyData() {
        val normal=chartRange(listOf(0.0,0.0),SlideChartAxis());assertEquals(0.0,normal.minimum,0.0);assertEquals(1.0,normal.maximum,0.0)
        val saved=chartRange(listOf(2.0,3.0),SlideChartAxis(minimum=1000.0),false);assertEquals(1000.0,saved.minimum,0.0);assertTrue(saved.maximum>1000)
        val tiny=chartRange(listOf(1e-90,2e-90),SlideChartAxis(),false)
        assertTrue(tiny.minimum.isFinite() && tiny.maximum>tiny.minimum);assertTrue(tiny.ticks.size<=11)
        val subnormal=chartRange(listOf(1e-323,2e-323),SlideChartAxis(),false)
        assertTrue(subnormal.minimum.isFinite() && subnormal.maximum>subnormal.minimum)
        assertTrue(subnormal.ticks.all { it.isFinite() })
        val extremeUnit=chartRange(listOf(1e99,2e99),SlideChartAxis(majorUnit=Double.MIN_VALUE),false)
        assertTrue(extremeUnit.minimum.isFinite() && extremeUnit.maximum.isFinite())
        assertTrue(extremeUnit.maximum>extremeUnit.minimum && extremeUnit.ticks.all { it.isFinite() })
    }
}
