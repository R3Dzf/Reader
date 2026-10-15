package com.r3dzf.reader

import org.junit.Assert.*
import org.junit.Test

class LibraryModelsTest {
    private fun doc(id: String,name: String,ext: String,time: Long=0,favorite: Boolean=false)=LibraryDocument(id,name,ext,100,time,favorite)
    @Test fun searchesArabicWithDiacriticsAndDifferentAlefForms() {
        val all=listOf(doc("a","إدارةُ الأعمالِ.docx","docx"),doc("b","محاضرات.pdf","pdf"))
        assertEquals(listOf("a"),selectDocuments(all,"ادارة الاعمال").map { it.id })
    }
    @Test fun englishSearchIsInsensitiveToCaseAndOuterWhitespace() {
        assertEquals(1,selectDocuments(listOf(doc("a","Final Report.PDF","pdf")),"  REPORT ").size)
    }
    @Test fun combinesFavoritesFileTypeAndSearchWithoutChangingOriginalOrder() {
        val all=listOf(doc("a","notes.pdf","pdf",1,true),doc("b","notes.pptx","pptx",3,true),doc("c","notes.pdf","pdf",2,false))
        assertEquals(listOf("a"),selectDocuments(all,"notes",DocumentKind.PDF,true).map { it.id })
        assertEquals(listOf("a","b","c"),all.map { it.id })
    }
    @Test fun recentOrderUsesOpeningTimeAndAStableTieBreak() {
        assertEquals(listOf("b","a","c"),selectDocuments(listOf(doc("c","C","pdf",1),doc("a","A","pdf",1),doc("b","B","pdf",2))).map { it.id })
    }
    @Test fun treatsOfficeVariantsAsTheirReadableFamilies() {
        assertEquals(DocumentKind.SLIDES,DocumentKind.fromExtension("PPSM"))
        assertEquals(DocumentKind.SHEET,DocumentKind.fromExtension("xltx"))
        assertEquals(DocumentKind.WORD,DocumentKind.fromExtension("dotm"))
    }
    @Test fun stripsDisplayNameControlsTraversalAndDirectionOverrides() {
        assertEquals("ملاحظات.pdf",safeDocumentName(" /ملاحظات.pdf\u202E\n\\ "))
        assertEquals("Document",safeDocumentName(" \n "))
        assertTrue(safeDocumentName("a".repeat(300)).length<=240)
    }
    @Test fun restoredProgressClampsOutOfRangeValuesAfterDocumentLengthChanges() {
        assertEquals(ReadingProgress(2,0,3),ReadingProgress(Int.MAX_VALUE,-3,3).bounded())
        assertEquals(0,ReadingProgress(-100,0,-1).bounded().index)
        assertEquals(1_000_000,ReadingProgress(0,Int.MAX_VALUE,2).bounded().offset)
    }
    @Test fun aSingleItemDoesNotHaveAnInvalidProgressFraction() {
        assertEquals(1f,ReadingProgress(0,0,1).fraction,0f)
        assertEquals(6f/11f,ReadingProgress(5,0,11).fraction,0f)
        assertEquals(1f,ReadingProgress(50,0,11).fraction,0f)
    }
}
