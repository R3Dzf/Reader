package com.r3dzf.reader

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.InputStream
import java.util.UUID

class LibraryDeviceTest {
    private fun isolated(): Context {
        val original=InstrumentationRegistry.getInstrumentation().targetContext
        val root=File(original.cacheDir,"library-test-${UUID.randomUUID()}").apply { mkdirs() }
        return object: ContextWrapper(original) {
            override fun getFilesDir()=File(root,"files").apply { mkdirs() }
            override fun getCacheDir()=File(root,"cache").apply { mkdirs() }
            override fun getDatabasePath(name: String)=File(File(root,"db").apply { mkdirs() },name)
            override fun openOrCreateDatabase(name: String,mode: Int,factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase=SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name),factory)
            override fun openOrCreateDatabase(name: String,mode: Int,factory: SQLiteDatabase.CursorFactory?,errorHandler: DatabaseErrorHandler?): SQLiteDatabase=SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).path,factory,errorHandler)
        }
    }
    private val pdf="%PDF-1.4\nRafolio library test".toByteArray()
    @Test fun identicalImportsPersistOneRecordFavoritesAndReadingPositionAcrossRecreation()=runBlocking {
        val context=isolated();var store=DocumentLibrary(context)
        try {
            val first=store.importForTest(pdf.inputStream(),"الملف.pdf","pdf")
            store.favorite(first.id,true);store.saveProgress(first.id,ReadingProgress(2,40,5))
            assertEquals(1,store.documents().size)
            store.closeForTest();store=DocumentLibrary(context)
            val repeated=store.importForTest(pdf.inputStream(),"Renamed.pdf","pdf")
            assertEquals(first.id,repeated.id);assertTrue(repeated.favorite);assertEquals(ReadingProgress(2,40,5),repeated.progress)
            assertEquals(1,store.documents().size);assertEquals("Renamed.pdf",repeated.displayName)
        } finally { store.clear();store.closeForTest() }
    }
    @Test fun selectedSourceIsReadOnlyAndPrivateCopiesSurviveCacheEviction()=runBlocking {
        val context=isolated();val store=DocumentLibrary(context)
        val original=File(context.filesDir,"original.pdf").apply { writeBytes(pdf) }
        try {
            val interrupted=File(context.filesDir,"library/.pending-interrupted").apply { writeBytes(ByteArray(1024)) }
            val document=store.import(Uri.fromFile(original))
            assertFalse(interrupted.exists())
            context.cacheDir.deleteRecursively()
            assertArrayEquals(pdf,original.readBytes())
            val (_,saved)=store.open(document.id);assertArrayEquals(pdf,saved.readBytes())
            assertNotEquals(original.canonicalPath,saved.canonicalPath)
        } finally { store.clear();store.closeForTest() }
    }
    @Test fun missingCopiesAreVisibleAndCannotBeReopenedOrResurrectedByProgressWrites()=runBlocking {
        val context=isolated();val store=DocumentLibrary(context)
        try {
            val doc=store.importForTest(pdf.inputStream(),"Lost.pdf","pdf")
            store.open(doc.id).second.delete()
            assertFalse(store.documents().single().available)
            assertEquals(LibraryProblem.MISSING,(runCatching { store.open(doc.id) }.exceptionOrNull() as LibraryException).problem)
            store.remove(doc.id);store.saveProgress(doc.id,ReadingProgress(2,0,5));assertTrue(store.documents().isEmpty())
        } finally { store.clear();store.closeForTest() }
    }
    @Test fun clearRemovesPrivateCopiesButKeepsTheOriginal()=runBlocking {
        val context=isolated();val store=DocumentLibrary(context)
        val original=File(context.filesDir,"source.pdf").apply { writeBytes(pdf) }
        try {
            val doc=store.import(Uri.fromFile(original));val copy=store.open(doc.id).second
            store.clear();assertFalse(copy.exists());assertTrue(original.exists());assertTrue(store.documents().isEmpty())
        } finally { store.closeForTest() }
    }
    @Test fun oversizedAndInvalidImportsLeaveNoRowsOrPendingFiles()=runBlocking {
        val context=isolated();val store=DocumentLibrary(context)
        try {
            val invalid=runCatching { store.importForTest("not a PDF".byteInputStream(),"fake.pdf","pdf") }.exceptionOrNull()
            assertEquals(LibraryProblem.UNREADABLE,(invalid as LibraryException).problem)
            val large=object: InputStream() {
                var remaining=DocumentLibrary.MAX_FILE_BYTES+1
                override fun read(): Int=if(remaining-->0) 0 else -1
                override fun read(buffer: ByteArray,offset: Int,length: Int): Int {
                    if(remaining<=0) return -1
                    val count=minOf(remaining,length.toLong()).toInt();buffer.fill(0,offset,offset+count);remaining-=count;return count
                }
            }
            val failure=runCatching { store.importForTest(large,"large.pdf","pdf") }.exceptionOrNull()
            assertEquals(LibraryProblem.TOO_LARGE,(failure as LibraryException).problem)
            assertTrue(store.documents().isEmpty());assertTrue(File(context.filesDir,"library").listFiles().orEmpty().isEmpty())
        } finally { store.closeForTest() }
    }
    @Test fun traversalAndArbitraryPrivatePathsCannotBePassedToAViewer() {
        val context=isolated();val outsider=File(context.filesDir,"outside.pdf").apply { writeBytes(pdf) }
        assertFalse(isReaderPrivateFile(context,outsider,"incoming"))
        val traversal=File(context.filesDir,"library/../outside.pdf")
        assertFalse(isReaderPrivateFile(context,traversal,"incoming"))
    }
}
