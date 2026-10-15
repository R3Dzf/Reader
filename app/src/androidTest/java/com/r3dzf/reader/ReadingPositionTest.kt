package com.r3dzf.reader

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import java.util.concurrent.atomic.AtomicReference

class ReadingPositionTest {
    @get:Rule val compose=createComposeRule()
    @Test fun wordRestoresARealScrollPosition() {
        val progress=AtomicReference<ReadingProgress>()
        val document=NativeWordDocument((0 until 50).map { WordParagraph(listOf(WordRun("Paragraph $it"))) })
        compose.setContent { ReaderTheme { WordDocumentView(document,ReadingProgress(25,0,52),progress::set) } }
        compose.onNodeWithText("Paragraph 24").assertIsDisplayed()
        compose.runOnIdle { assertEquals(25,progress.get().index) }
    }
    @Test fun excelRestoresTheSelectedSheetAndReportsLaterChanges() {
        val progress=AtomicReference<ReadingProgress>()
        val workbook=NativeWorkbook(listOf(NativeWorksheet("Tab A",emptyList(),1,false),NativeWorksheet("Tab B",emptyList(),1,false)))
        compose.setContent { ReaderTheme { WorkbookDocumentView(workbook,ReadingProgress(1,0,2),progress::set) } }
        compose.runOnIdle { assertEquals(1,progress.get().index) }
        compose.onNodeWithText("Tab A").performClick()
        compose.runOnIdle { assertEquals(0,progress.get().index) }
    }
}
