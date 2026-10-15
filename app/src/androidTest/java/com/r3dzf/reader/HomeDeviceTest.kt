package com.r3dzf.reader

import android.graphics.Bitmap
import android.content.res.Configuration
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*
import org.junit.Assert.*
import java.util.Locale

class HomeDeviceTest {
    @get:Rule val compose=createComposeRule()
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var oldTheme: ReaderThemeMode
    @Before fun before() { oldTheme=readerThemeMode(context);saveReaderTheme(context,ReaderThemeMode.LIGHT) }
    @After fun after() { saveReaderTheme(context,oldTheme) }
    private fun documents(): List<LibraryDocument> {
        val now=System.currentTimeMillis()
        return listOf(
            LibraryDocument("pdf","Designing Better Systems.pdf","pdf",4_350_000,now,progress=ReadingProgress(17,0,42)),
            LibraryDocument("slides","محاضرات الذكاء الاصطناعي.pptx","pptx",2_700_000,now-10_000,progress=ReadingProgress(3,0,24)),
            LibraryDocument("sheet","Semester budget.xlsx","xlsx",96_000,now-20_000,true,ReadingProgress(1,0,3)),
            LibraryDocument("word","Research notes.docx","docx",360_000,now-86_400_000),
            LibraryDocument("pdf2","The creative process.pdf","pdf",720_000,now-172_800_000))
    }
    private fun show(docs: List<LibraryDocument>,arabic: Boolean=false) {
        val config=Configuration(context.resources.configuration).apply { setLocale(Locale(if(arabic) "ar" else "en")) }
        val localized=context.createConfigurationContext(config)
        compose.setContent {
            var items by remember { mutableStateOf(docs) }
            CompositionLocalProvider(LocalContext provides localized,LocalConfiguration provides config,LocalLayoutDirection provides if(arabic) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                ReaderTheme { ReaderHome(items,onOpen={},onOpenDocument={},onFavorite={doc -> items=items.map { if(it.id==doc.id) it.copy(favorite=!it.favorite) else it }},
                    onRemove={doc -> items=items.filterNot { it.id==doc.id }},onClear={items=emptyList()}) }
            }
        }
    }
    private fun save(name: String,tag: String?=null) {
        if(android.os.Build.VERSION.SDK_INT<29) return
        val bitmap=(if(tag==null) compose.onRoot() else compose.onNodeWithTag(tag)).captureToImage().asAndroidBitmap()
        val resolver=context.contentResolver
        val values=android.content.ContentValues().apply {
            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME,name)
            put(android.provider.MediaStore.Images.Media.MIME_TYPE,"image/png")
            put(android.provider.MediaStore.Images.Media.RELATIVE_PATH,"Pictures/ReaderTestVisuals")
        }
        val uri=checkNotNull(resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values))
        checkNotNull(resolver.openOutputStream(uri)).use { check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) }
    }
    @Test fun lightHomeShowsBrandRealContinuePositionAndImportAction() {
        show(documents())
        compose.onNodeWithText("Rafolio").assertIsDisplayed()
        compose.onNodeWithTag("continue-reading").assertIsDisplayed()
        compose.onNodeWithText("Page 18 of 42").assertIsDisplayed()
        compose.onNodeWithTag("open-file").assertIsDisplayed()
        save("rafolio-home-light.png")
    }
    @Test fun searchTypeFilterFavoriteAndRemovalWorkTogether() {
        show(documents())
        compose.onNodeWithTag("nav-history").performClick()
        compose.onNodeWithTag("filter-slides").performScrollTo().performClick()
        compose.onNodeWithTag("library-search").performTextInput("محاضرات")
        compose.onNodeWithTag("library-search").performImeAction()
        compose.onNodeWithTag("document-slides").assertIsDisplayed()
        compose.onNodeWithTag("document-pdf").assertDoesNotExist()
        compose.onNodeWithTag("document-menu-slides").performScrollTo().performClick()
        compose.onNodeWithText("Add to favorites").performClick()
        compose.onNodeWithTag("nav-favorites").performClick()
        compose.onNodeWithTag("document-slides").assertIsDisplayed()
        compose.onNodeWithTag("document-menu-slides").performScrollTo().performClick()
        compose.onNodeWithText("Remove from library").performClick()
        compose.onNodeWithTag("document-slides").assertExists()
        compose.onNodeWithTag("confirm-remove").performClick()
        compose.onNodeWithTag("document-slides").assertDoesNotExist()
    }
    @Test fun returningHomeFromLongHistoryRevealsContinueReading() {
        val many=documents()+(0 until 50).map { index -> LibraryDocument("extra-$index","Document $index.pdf","pdf",1000,System.currentTimeMillis()-index*1000) }
        show(many)
        compose.onNodeWithTag("nav-history").performClick()
        compose.onNodeWithTag("library-list").performScrollToIndex(30)
        compose.onNodeWithTag("nav-home").performClick()
        compose.onNodeWithTag("continue-reading").assertIsDisplayed()
        compose.onNodeWithTag("resume-file").assertIsDisplayed()
    }
    @Test fun arabicHistoryUsesRtlAndKeepsArabicNamesReadable() {
        show(documents(),true)
        compose.onNodeWithTag("nav-history").performClick()
        compose.onNodeWithTag("library-list").performScrollToNode(hasText("محاضرات الذكاء الاصطناعي.pptx"))
        compose.onNodeWithText("محاضرات الذكاء الاصطناعي.pptx").assertIsDisplayed()
        save("rafolio-history-ar.png")
    }
    @Test fun darkHomeKeepsDocumentActionsReadable() {
        saveReaderTheme(context,ReaderThemeMode.DARK)
        show(documents())
        compose.onNodeWithTag("resume-file").assertIsDisplayed()
        save("rafolio-home-dark.png")
    }
    @Test fun emptyStateAndAppearanceChoiceAreRealAndPersisted() {
        show(emptyList())
        compose.onNodeWithTag("welcome-open").assertIsDisplayed()
        save("rafolio-empty.png")
        compose.onNodeWithTag("home-settings").performClick()
        compose.onNodeWithText("Dark").performClick()
        compose.runOnIdle { assertEquals(ReaderThemeMode.DARK,readerThemeMode(context)) }
        save("rafolio-settings.png","appearance-dialog")
        compose.onNodeWithTag("clear-library").assertIsNotEnabled()
    }
}
