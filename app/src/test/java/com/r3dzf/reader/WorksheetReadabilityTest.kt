package com.r3dzf.reader

import org.junit.Assert.*
import org.junit.Test

class WorksheetReadabilityTest {
    @Test fun hiddenSheetHistoryUsesWorkbookIndicesAndMigratesOlderVisibleIndices() {
        val b=NativeWorkbook(listOf(NativeWorksheet("A",emptyList(),1,false),NativeWorksheet("Internal",emptyList(),1,false,hidden=true),NativeWorksheet("B",emptyList(),1,false)))
        assertEquals(0,workbookSheetIndex(b,0,0))
        assertEquals(2,workbookSheetIndex(b,1,2))
        assertEquals(1,workbookSheetIndex(b,1,3))
        assertEquals(2,workbookSheetIndex(b,100,3))
        assertEquals(1,workbookSheetIndex(b.copy(sheets=b.sheets.reversed()),1,3))
        assertEquals(0,workbookSheetIndex(NativeWorkbook(emptyList()),0,0))
    }
    @Test fun invisibleSavedTextGetsReadableInkWithoutChangingSourceStyles() {
        val c=NativeSheetCell("Header",textRgb=0xFFFFFFL,fillRgb=0xFFFFFFL)
        assertEquals(0x111827L,sheetReadableTextRgb(c,true))
        assertEquals(0xFFFFFFL,c.textRgb);assertEquals(0xFFFFFFL,c.fillRgb)
        assertEquals(0xFFFFFFL,sheetReadableTextRgb(c,false))
    }
    @Test fun invisibleDarkTextOnDarkFillGetsLightInk() {
        assertEquals(0xFFFFFFL,sheetReadableTextRgb(NativeSheetCell("Header",textRgb=0x000000L,fillRgb=0x000000L),true))
        assertEquals(0x111827L,sheetReadableTextRgb(NativeSheetCell("Header",textRgb=0xFEFEFEL),true))
    }
    @Test fun readableOriginalTableAndBodyColorsAreRetained() {
        for(c in listOf(NativeSheetCell("Header",textRgb=0xFFFFFFL,fillRgb=0x5B9BD5L),NativeSheetCell("Value",textRgb=0x000000L,fillRgb=0xBDD7EEL),NativeSheetCell("Input",textRgb=0xFFFF00L,fillRgb=0x174D40L))) {
            assertEquals(c.textRgb,sheetReadableTextRgb(c,true))
        }
        assertEquals(0x111827L,sheetReadableTextRgb(NativeSheetCell("Value"),true))
    }
}
