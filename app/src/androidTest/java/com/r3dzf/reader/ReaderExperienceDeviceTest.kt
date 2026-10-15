package com.r3dzf.reader

import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.concurrent.atomic.AtomicReference

class ReaderExperienceDeviceTest {
    @get:Rule val compose=createComposeRule()
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private val files=mutableListOf<File>()
    @Before fun reset() {context.getSharedPreferences("reader-view",0).edit().clear().commit()}
    @After fun cleanup() {files.forEach {it.delete()};context.getSharedPreferences("reader-view",0).edit().clear().commit()}
    private fun pdf(): File {
        val f=File.createTempFile("pdf-experience-",".pdf",context.cacheDir);files+=f
        val document=PdfDocument()
        try {repeat(4) {index ->
            val page=document.startPage(PdfDocument.PageInfo.Builder(600,800,index+1).create());page.canvas.drawColor(android.graphics.Color.WHITE)
            page.canvas.drawText("Rafolio page ${index+1}",50f,100f,Paint().apply {textSize=30f;color=android.graphics.Color.BLACK})
            document.finishPage(page)
        };f.outputStream().use {document.writeTo(it)}} finally {document.close()};return f
    }
    private fun showPdf() {val f=pdf();compose.setContent {ReaderTheme {PdfDocumentView(f) {}}};compose.waitUntil(15000) {compose.onAllNodesWithTag("pdf-page-image-0").fetchSemanticsNodes().isNotEmpty()}}
    private fun mode(name: String) {compose.onNodeWithTag("reader-mode-menu").performClick();compose.onNodeWithTag("reader-mode-$name").performClick()}
    private fun save(name: String,tag: String?=null) {
        val bitmap=(if(tag==null) compose.onRoot() else compose.onNodeWithTag(tag)).captureToImage().asAndroidBitmap()
        val values=android.content.ContentValues().apply {put(android.provider.MediaStore.Images.Media.DISPLAY_NAME,name);put(android.provider.MediaStore.Images.Media.MIME_TYPE,"image/png");put(android.provider.MediaStore.Images.Media.RELATIVE_PATH,"Pictures/ReaderTestVisuals")}
        val uri=checkNotNull(context.contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values));checkNotNull(context.contentResolver.openOutputStream(uri)).use {check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it))}
    }
    @Test fun pdfSinglePageHidesNextPageAndButtonsTrackExactPosition() {
        showPdf();mode("horizontal");compose.onNodeWithTag("reader-page-counter").assertTextEquals("1 / 4")
        compose.onNodeWithTag("pdf-page-image-1").assertIsNotDisplayed()
        compose.onNodeWithTag("reader-next").performClick();compose.onNodeWithTag("reader-page-counter").assertTextEquals("2 / 4")
        compose.onNodeWithTag("pdf-page-1").assertIsDisplayed();save("pdf-single-page.png")
    }
    @Test fun pdfPinchZoomWorksAndTapRestoresHiddenHeader() {
        showPdf();mode("horizontal")
        compose.onNodeWithTag("pdf-page-0").performTouchInput {pinch(Offset(width*.4f,height*.5f),Offset(width*.6f,height*.5f),Offset(width*.15f,height*.5f),Offset(width*.85f,height*.5f),600)}
        compose.onNodeWithTag("pdf-page-0").assert(SemanticsMatcher("Pinch must enlarge the page") {it.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.StateDescription)?.removePrefix("zoom:")?.toIntOrNull()?.let {n -> n>120}==true})
        compose.onNodeWithTag("reader-header").assertDoesNotExist()
        save("pdf-fullscreen-zoom.png")
        compose.onNodeWithTag("pdf-page-0").performTouchInput {click()};compose.onNodeWithTag("reader-header").assertIsDisplayed()
    }
    @Test fun pdfContinuousAndVerticalPagingBothWork() {
        showPdf();compose.onNodeWithTag("pdf-list").performTouchInput {swipeUp()};compose.onNodeWithTag("reader-header").assertDoesNotExist()
        compose.onNodeWithTag("pdf-list").performTouchInput {click(center)};compose.onNodeWithTag("reader-header").assertIsDisplayed();mode("vertical")
        compose.onNodeWithTag("reader-page-counter").performClick();compose.onNodeWithTag("reader-page-input").performTextReplacement("1");compose.onNodeWithTag("reader-page-confirm").performClick()
        compose.onNodeWithTag("reader-next").performClick();compose.onNodeWithTag("reader-page-counter").assertTextEquals("2 / 4")
    }
    private fun presentation()=NativePresentation((1..5).map {n -> NativeSlide(n,listOf(NativeSlideShape(NativeSlideShapeKind.TEXT,"Slide $n",.1f,.2f,.8f,.3f,fontSizePoints=28f)))},800f,450f)
    @Test fun powerPointHasOneCleanSlideAndAccurateSavedCounter() {
        val progress=AtomicReference<ReadingProgress>()
        compose.setContent {ReaderTheme {PresentationDocumentView(presentation(),ReadingProgress(2,0,5),progress::set)}}
        compose.onNodeWithTag("reader-page-counter").assertTextEquals("3 / 5");compose.onNodeWithTag("slide-3").assertIsDisplayed()
        compose.onNodeWithText("Fit").assertDoesNotExist();compose.onNodeWithText("100%").assertDoesNotExist()
        compose.onNodeWithTag("reader-next").performClick();compose.onNodeWithTag("reader-page-counter").assertTextEquals("4 / 5")
        compose.runOnIdle {assertEquals(3,progress.get().index);assertEquals(.8f,progress.get().fraction,.0001f)};save("powerpoint-clean-reader.png")
    }
    private fun workbook(): NativeWorkbook {
        val f=File.createTempFile("visual-budget-",".xlsx",context.cacheDir);files+=f
        instrumentation.context.assets.open("reader-budget-theme.xlsx").use {input -> f.outputStream().use {input.copyTo(it)}}
        return NativeOfficeEngine.openWorkbook(f)
    }
    @Test fun workbookRendersThemedHeaderArabicGlyphsAndGridPixels() {
        val book=workbook();compose.setContent {ReaderTheme {Column(Modifier.fillMaxSize()) {DocumentTopBar("Budget.xlsx","XLSX",{});WorkbookDocumentView(book)}}}
        compose.onNodeWithContentDescription("A1: المنتج").assertIsDisplayed();compose.onNodeWithContentDescription("A2: منتج عربي 1").assertIsDisplayed()
        val image=compose.onNodeWithTag("worksheet-grid").captureToImage().toPixelMap()
        val header=compose.onNodeWithContentDescription("A1: المنتج").fetchSemanticsNode().boundsInRoot
        val gridBounds=compose.onNodeWithTag("worksheet-grid").fetchSemanticsNode().boundsInRoot
        val hx=(header.left-gridBounds.left).toInt();val hy=(header.top-gridBounds.top).toInt()
        val hr=(header.right-gridBounds.left).toInt();val hb=(header.bottom-gridBounds.top).toInt()
        var blue=0;var whiteText=0;var darkText=0
        for(y in 2 until image.height) for(x in 2 until image.width) {
            val p=image[x,y]
            if(p.blue>.4f && p.blue>p.red*1.2f && p.red<.6f) blue++
            if(x in (hx+3)..(hr-3) && y in (hy+3)..(hb-3) && p.red>.95f && p.green>.95f && p.blue>.95f) whiteText++
            if(y>90 && p.red<.2f && p.green<.2f && p.blue<.2f) darkText++
        }
        assertTrue("Themed blue header fill must render",blue>200)
        assertTrue("Saved Arabic glyphs must render as dark pixels",darkText>40)
        assertTrue("White header glyphs must render",whiteText>10);save("excel-themed-reader.png")
    }
    @Test fun emptySheetStillShowsColumnRowHeadersAndGrid() {
        compose.setContent {ReaderTheme {WorksheetView(NativeWorksheet("Empty",emptyList(),0,false))}}
        val pixels=compose.onNodeWithTag("worksheet-grid").captureToImage().toPixelMap();var grid=0
        for(y in 45 until pixels.height step 2) for(x in 60 until pixels.width step 2) {val c=pixels[x,y];if(c.red in .75f.. .92f && c.green in .75f.. .92f && c.blue in .75f.. .95f) grid++}
        assertTrue("Blank sheets must have an actual grid",grid>50)
    }
}
