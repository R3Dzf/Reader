package com.r3dzf.reader

import android.graphics.Bitmap
import android.graphics.Canvas as NativeCanvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.core.graphics.withTranslation
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ChartDeviceTest {
    @get:Rule val compose=createComposeRule()
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private fun presentation(): NativePresentation {
        val file=File.createTempFile("charts-", ".pptx",instrumentation.targetContext.cacheDir)
        try {
            instrumentation.context.assets.open("charts-native-arabic.pptx").use { input -> file.outputStream().use { input.copyTo(it) } }
            return NativeOfficeEngine.open(file,OfficeFormat.PPTX) as NativePresentation
        } finally { file.delete() }
    }
    private fun charts()=presentation().slides.map { it.shapes.single().chart!! }
    private fun save(name: String,bitmap: Bitmap) {
        if(android.os.Build.VERSION.SDK_INT<29) return
        val values=android.content.ContentValues().apply {
            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME,name)
            put(android.provider.MediaStore.Images.Media.MIME_TYPE,"image/png")
            put(android.provider.MediaStore.Images.Media.RELATIVE_PATH,"Pictures/ReaderTestVisuals")
        }
        val resolver=instrumentation.targetContext.contentResolver
        val uri=checkNotNull(resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values))
        checkNotNull(resolver.openOutputStream(uri)).use { check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) }
    }
    private fun show(chart: NativeSlideChart) = compose.setContent { ReaderTheme {
        SlideChartView(chart,.7f,Modifier.fillMaxWidth().height(240.dp).background(Color.White))
    } }
    @Test fun realPowerPointChartRendersArabicBarsAndFullSavedValuesCanBeRead() {
        val document=presentation()
        compose.setContent { ReaderTheme { SlideContent(document,document.slides[0],1f,Modifier.fillMaxWidth().height(240.dp)) } }
        val image=compose.onNodeWithTag("chart-canvas").assertIsDisplayed().captureToImage()
        val pixels=image.toPixelMap();var blue=0;var red=0
        for(y in 0 until pixels.height) for(x in 0 until pixels.width) {
            val c=pixels[x,y]
            if(c.blue>.7f && c.red<.2f && c.green in .3f.. .5f) blue++
            if(c.red>.7f && c.green<.4f && c.blue<.3f) red++
        }
        assertTrue("Saved blue and red series must be visible",blue>50 && red>50)
        save("chart-columns.png",image.asAndroidBitmap())
        compose.onNodeWithTag("chart-read-data").performClick()
        compose.onNodeWithText("المبيعات: يناير: 12").assertIsDisplayed()
    }
    @Test fun doughnutHasARealTransparentHoleInsteadOfASecondFilledPie() {
        show(charts()[6].copy(title="",legendPosition=null))
        val image=compose.onNodeWithTag("chart-canvas").captureToImage();val pixels=image.toPixelMap()
        val center=pixels[pixels.width/2,pixels.height/2]
        assertTrue("Doughnut center must reveal the white paper",center.red>.995f && center.green>.995f && center.blue>.995f)
        var colored=0
        for(y in 0 until pixels.height step 2) for(x in 0 until pixels.width step 2) {
            val c=pixels[x,y];if(maxOf(c.red,c.green,c.blue)-minOf(c.red,c.green,c.blue)>.4f) colored++
        }
        assertTrue(colored>100);save("chart-doughnut.png",image.asAndroidBitmap())
    }
    @Test fun scatterPointSpacingFollowsTheSavedNumericXCoordinates() {
        show(charts()[7].copy(title="",legendPosition=null))
        val image=compose.onNodeWithTag("chart-canvas").captureToImage();val p=image.toPixelMap()
        val columns=(0 until p.width).filter { x -> (0 until p.height).any { y ->
            val c=p[x,y];c.blue>.7f && c.red<.2f && c.green in .3f.. .5f
        } }
        val clusters=mutableListOf<MutableList<Int>>()
        columns.forEach { x -> if(clusters.isEmpty() || x>clusters.last().last()+1) clusters.add(mutableListOf(x)) else clusters.last().add(x) }
        assertEquals("Only three point clusters should be drawn",3,clusters.size)
        val centers=clusters.map { it.average() }
        assertTrue("The 2-to-10 gap must exceed the 1-to-2 gap",(centers[2]-centers[1])/(centers[1]-centers[0])>4)
        save("chart-scatter.png",image.asAndroidBitmap())
    }
    @Test fun unsupportedChartShowsANoticeAndKeepsItsSavedValuesAccessible() {
        show(charts().last())
        compose.onNodeWithTag("chart-notice").assertIsDisplayed()
        compose.onNodeWithTag("chart-canvas").assertDoesNotExist()
        compose.onNodeWithTag("chart-read-data").performClick()
        compose.onNodeWithText("القيم: أ: 2").assertIsDisplayed()
    }
    @Test fun allSupportedKindsDrawOnAndroidAndExportAVisualRegressionGallery() {
        val all=charts();val bitmap=Bitmap.createBitmap(1320,900,Bitmap.Config.ARGB_8888)
        val canvas=NativeCanvas(bitmap);canvas.drawColor(android.graphics.Color.WHITE)
        all.take(8).forEachIndexed { i,chart ->
            assertTrue(chart.drawable)
            canvas.withTranslation((i%3)*440f,(i/3)*300f) {
                drawNativeChart(this,chart,440f,300f,1.2f,instrumentation.targetContext)
            }
        }
        save("charts-gallery.png",bitmap);bitmap.recycle()
    }
}
