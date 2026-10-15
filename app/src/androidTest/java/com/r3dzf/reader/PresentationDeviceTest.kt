package com.r3dzf.reader

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import org.json.JSONObject

@RunWith(AndroidJUnit4::class)
class PresentationDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun saveVisual(name: String, bitmap: Bitmap) {
        val context=instrumentation.targetContext
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            // AGP uninstalls test apps on completion. Public test images survive that cleanup.
            val values=android.content.ContentValues().apply {
                put(android.provider.MediaStore.Images.Media.DISPLAY_NAME,name)
                put(android.provider.MediaStore.Images.Media.MIME_TYPE,"image/png")
                put(android.provider.MediaStore.Images.Media.RELATIVE_PATH,"Pictures/ReaderTestVisuals")
            }
            val resolver=context.contentResolver
            val uri=checkNotNull(resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values))
            checkNotNull(resolver.openOutputStream(uri)).use { check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) }
        } else {
            File(context.filesDir,name).outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) }
        }
    }
    private fun presentation(): NativePresentation {
        val file = File.createTempFile("drawing-fonts-", ".pptx", instrumentation.targetContext.cacheDir)
        try {
            instrumentation.context.assets.open("drawing-fonts-white.pptx").use { input -> file.outputStream().use { input.copyTo(it) } }
            return NativeOfficeEngine.open(file,OfficeFormat.PPTX) as NativePresentation
        } finally { file.delete() }
    }
    private fun assertWhite(tag: String) {
        val pixels = compose.onNodeWithTag(tag).captureToImage().toPixelMap()
        val points = listOf(0.1f to 0.5f,0.5f to 0.5f,0.9f to 0.8f)
        points.forEach { (x,y) ->
            val c = pixels[(pixels.width*x).toInt(),(pixels.height*y).toInt()]
            assertTrue("White must be #FFFFFF; found $c",c.red > .995f && c.green > .995f && c.blue > .995f)
        }
    }
    @Test fun blankPowerPointStaysPureWhiteInsideDarkElevatedMaterialSurface() {
        val document = presentation()
        compose.setContent { MaterialTheme(colorScheme=darkColorScheme()) { Surface(tonalElevation=12.dp) {
            SlideContent(document,document.slides[3],1f,Modifier.fillMaxWidth().height(200.dp))
        } } }
        assertWhite("slide-4")
    }
    @Test fun explicitWhiteBgReferenceOverridesYellowMasterInRenderedPixels() {
        val document = presentation()
        val blank = document.slides[0].copy(shapes=emptyList())
        compose.setContent { ReaderTheme { SlideContent(document,blank,1f,Modifier.fillMaxWidth().height(200.dp)) } }
        assertWhite("slide-1")
    }
    @Test fun realArabicAndEnglishRunsRenderWithBundledFontsAndMasterShapes() {
        val document = presentation()
        compose.setContent { ReaderTheme { SlideContent(document,document.slides[0],1f,Modifier.fillMaxWidth().height(220.dp)) } }
        compose.onNodeWithText("مرحبا Reader",substring=true).assertIsDisplayed()
        val image=compose.onNodeWithTag("slide-1").captureToImage()
        saveVisual("powerpoint-reference.png",image.asAndroidBitmap())
        val pixels=image.toPixelMap()
        var dark=0;var red=0;var blue=0
        for(y in 0 until pixels.height step 2) for(x in 0 until pixels.width step 2) {
            val c=pixels[x,y]
            if(c.red<.3 && c.green<.3 && c.blue<.3) dark++
            if(c.red>.8 && c.green<.2 && c.blue<.2) red++
            if(c.blue>.3 && c.red<.2) blue++
        }
        assertTrue("Arabic and English glyphs must be drawn",dark>20)
        assertTrue("Grouped ellipse must be drawn",red>30)
        assertTrue("Master decoration must be drawn",blue>30)
    }
    @Test fun groupScaleTransformsTheGlyphsAlongWithTheirFrames() {
        val paragraph=NativeSlideTextParagraph(listOf(NativeSlideTextRun("Reader",fontSizePoints=24f,fontFamilyName="Lato")))
        val base=NativeSlideShape(NativeSlideShapeKind.TEXT,"Reader",.05f,.1f,.4f,.2f,
            paragraphs=listOf(paragraph),textBox=SlideTextBox(0f,0f,0f,0f))
        val scaled=base.copy(xFraction=.55f,heightFraction=.4f,drawingScaleY=2f)
        val document=NativePresentation(listOf(NativeSlide(1,listOf(base,scaled))),400f,200f)
        compose.setContent { ReaderTheme { SlideContent(document,document.slides[0],1f,Modifier.width(400.dp).height(200.dp)) } }
        val pixels=compose.onNodeWithTag("slide-1").captureToImage().toPixelMap()
        fun inkHeight(left: Float,right: Float): Int {
            var top=pixels.height;var bottom=-1
            for(y in 0 until pixels.height) for(x in (left*pixels.width).toInt() until (right*pixels.width).toInt()) {
                val c=pixels[x,y]
                if(c.red<.3f && c.green<.3f && c.blue<.3f) { top=minOf(top,y);bottom=maxOf(bottom,y) }
            }
            return if(bottom<top) 0 else bottom-top+1
        }
        val normal=inkHeight(0f,.48f);val doubled=inkHeight(.52f,1f)
        assertTrue("Regular glyphs must be visible",normal>5)
        assertTrue("Group-scaled glyphs must double in height: $normal -> $doubled",doubled.toFloat()/normal in 1.8f..2.2f)
    }
    @Test fun pdfPaperStaysWhiteInDarkTheme() {
        val file=File.createTempFile("white-paper-", ".pdf", instrumentation.targetContext.cacheDir)
        try {
            val pdf=android.graphics.pdf.PdfDocument()
            try {
                val document=pdf
                val page=document.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(300,300,1).create())
                page.canvas.drawColor(android.graphics.Color.WHITE)
                document.finishPage(page)
                file.outputStream().use { document.writeTo(it) }
            } finally { pdf.close() }
            compose.setContent { MaterialTheme(colorScheme=darkColorScheme()) { PdfDocumentView(file) {} } }
            compose.waitUntil(15000) { compose.onAllNodesWithTag("pdf-page-image-0").fetchSemanticsNodes().isNotEmpty() }
            assertWhite("pdf-page-image-0")
        } finally { file.delete() }
    }
    @Test fun wordPaperStaysWhiteInDarkTheme() {
        val document=NativeWordDocument(listOf(WordParagraph(listOf(WordRun("Reader")))))
        compose.setContent { MaterialTheme(colorScheme=darkColorScheme()) { WordDocumentView(document) } }
        val pixels=compose.onNodeWithTag("word-document").captureToImage().toPixelMap()
        val c=pixels[pixels.width/2,10]
        assertTrue("Word page margin must be pure white",c.red>.995f && c.green>.995f && c.blue>.995f)
    }
    @Test fun all100FamiliesLoadAndTheirLicensedAssetsMatchPinnedHashesOffline() {
        val context=instrumentation.targetContext
        val families=DocumentFonts.families(context)
        assertEquals(100,families.size)
        assertEquals(30,families.count { it.arabic })
        val manifest=context.assets.open("fonts/catalog.json").bufferedReader().use { JSONObject(it.readText()) }.getJSONArray("families")
        for(i in 0 until manifest.length()) {
            val files=manifest.getJSONObject(i).getJSONArray("files")
            for(j in 0 until files.length()) {
                val file=files.getJSONObject(j)
                val bytes=context.assets.open(file.getString("asset")).use { it.readBytes() }
                val hash=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                assertEquals(file.getString("asset"),file.getString("sha256"),hash)
            }
        }
        val bitmap=Bitmap.createBitmap(800,2400,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(bitmap);canvas.drawColor(android.graphics.Color.WHITE)
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize=20f;color=android.graphics.Color.BLACK }
        families.forEachIndexed { i,family ->
            paint.typeface=DocumentFonts.typeface(context,family.name,arabic=family.arabic)
            assertNotNull(family.name,paint.typeface)
            assertTrue(family.name,paint.hasGlyph(if(family.arabic) "م" else "A"))
            canvas.drawText(if(family.arabic) "مرحبا بالعالم 123" else "Reader English 123",10f,22f+i*23,paint)
            DocumentFonts.typeface(context,family.name,bold=true,italic=true,arabic=family.arabic)
        }
        assertEquals("Carlito",DocumentFonts.resolve(context,"Calibri",false).name)
        assertEquals("Noto Naskh Arabic",DocumentFonts.resolve(context,"Missing font",true).name)
        saveVisual("fonts-reference.png",bitmap)
        bitmap.recycle()
    }
}
