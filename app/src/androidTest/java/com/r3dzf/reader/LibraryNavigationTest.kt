package com.r3dzf.reader

import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.pressBack
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import java.io.ByteArrayOutputStream
import java.util.UUID

class LibraryNavigationTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private fun pdf(): ByteArray {
        val output=ByteArrayOutputStream()
        PdfDocument().use { pdf ->
            repeat(3) { index ->
                val page=pdf.startPage(PdfDocument.PageInfo.Builder(600,800,index+1).create())
                page.canvas.drawText("Rafolio ${UUID.randomUUID()} page ${index+1}",35f,60f,Paint().apply { textSize=16f })
                pdf.finishPage(page)
            }
            pdf.writeTo(output)
        }
        return output.toByteArray()
    }
    private fun waitForResume() { compose.waitUntil(10_000) { compose.onAllNodesWithTag("resume-file").fetchSemanticsNodes().isNotEmpty() } }
    @Test fun pdfHistoryReopensAtSavedPageAfterActivityRecreation()=runBlocking {
        val library=DocumentLibrary.get(context);val item=library.importForTest(pdf().inputStream(),"Reading journey.pdf","pdf")
        val activity=ActivityScenario.launch(MainActivity::class.java)
        try {
            waitForResume();compose.onNodeWithTag("resume-file").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("pdf-list").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("pdf-list").performScrollToIndex(1)
            compose.onNodeWithTag("pdf-page-1").assertIsDisplayed()
            pressBack();waitForResume()
            compose.waitUntil(5000) { runBlocking { library.documents().first { it.id==item.id }.progress.index }==1 }
            activity.recreate();waitForResume()
            compose.onNodeWithTag("resume-file").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("pdf-page-1").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("pdf-page-1").assertIsDisplayed()
            assertEquals(1,library.documents().first { it.id==item.id }.progress.index)
        } finally { activity.close();library.remove(item.id) }
    }
    @Test fun powerPointHistoryRestoresTheThirdSlideInsideTheNativeViewer()=runBlocking {
        val library=DocumentLibrary.get(context)
        val source=InstrumentationRegistry.getInstrumentation().context.assets.open("charts-native-arabic.pptx")
        val item=library.importForTest(source,"محاضراتي.pptx","pptx")
        library.saveProgress(item.id,ReadingProgress(2,0,9));library.documents()
        val activity=ActivityScenario.launch(MainActivity::class.java)
        try {
            waitForResume();compose.onNodeWithTag("resume-file").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("slide-3").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("slide-3").assertIsDisplayed()
            pressBack();waitForResume()
            assertEquals(2,library.documents().first { it.id==item.id }.progress.index)
        } finally { activity.close();library.remove(item.id) }
    }
}
