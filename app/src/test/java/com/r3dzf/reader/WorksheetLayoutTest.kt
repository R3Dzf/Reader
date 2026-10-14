package com.r3dzf.reader

import org.junit.Assert.*
import org.junit.Test

class WorksheetLayoutTest {
    @Test fun navigationAddressesAreBoundedAndSupportAbsoluteReferences() {
        assertEquals(999_999 to 1, sheetAddress("b1000000"))
        assertEquals(0 to 0, sheetAddress("\$A\$1"))
        assertEquals(1_048_575 to 511, sheetAddress("SR1048576"))
        for (address in listOf("A0", "A1048577", "SS1", "A1:B2", "A-1", "A999999999999")) assertNull(sheetAddress(address))
    }
    @Test fun contextAndExplicitCellDirectionDoNotDependOnSheetOrPhoneLanguage() {
        assertTrue(sheetCellIsRtl(NativeSheetCell("  عنوان عربي")))
        assertTrue(sheetCellIsRtl(NativeSheetCell("\uFB50")))
        assertFalse(sheetCellIsRtl(NativeSheetCell("2024-01-01")))
        assertFalse(sheetCellIsRtl(NativeSheetCell("1 عربي")))
        assertFalse(sheetCellIsRtl(NativeSheetCell("عربي", readingOrder = 1)))
        assertTrue(sheetCellIsRtl(NativeSheetCell("English", readingOrder = 2)))
    }
    @Test fun sparseAxesKeepMissingRowsAndSkipHiddenIntervals() {
        val axis = SheetAxis(1_000_000, 15f, mapOf(0 to 24f, 1 to 36f, 4 to 0f))
        assertEquals(24.0, axis.offset(1), 0.001)
        assertEquals(60.0, axis.offset(2), 0.001)
        assertEquals(axis.offset(4), axis.offset(5), 0.0)
        assertEquals(5, axis.indexAt(axis.offset(4)))
        assertEquals(listOf(3, 5, 6), axis.visible(axis.offset(3), axis.offset(7)))
        assertEquals(15_000_015.0, axis.total, 0.001)
        assertEquals(999_999, axis.indexAt(axis.offset(999_999) + 1))
    }
    @Test fun axesWithZeroDefaultSizeJumpToExplicitVisibleRows() {
        val axis = SheetAxis(1_000_000, 0f, mapOf(20 to 15f, 900_000 to 30f))
        assertEquals(listOf(20, 900_000), axis.visible(0.0, 45.0))
        assertEquals(1_000_000, axis.indexAt(45.0))
        assertEquals(emptyList<Int>(), SheetAxis(10, 0f, emptyMap()).visible(0.0, 100.0))
    }
    @Test fun rectangleMergeRemainsVisibleWithItsOriginOffscreen() {
        val origin = NativeSheetCell("عنوان")
        val sheet = NativeWorksheet("Sheet", listOf(NativeSheetRow(0, mapOf(0 to origin))), 4, true,
            listOf(NativeMergedRange(0, 0, 1, 3)), defaultColumnWidthPoints = 40f, defaultRowHeightPoints = 20f)
        val layout = WorksheetLayout(sheet)
        val visible = layout.visible(45.0, 25.0, 30.0, 40.0)
        assertEquals(1, visible.size)
        assertEquals("عنوان", visible.single().cell.value)
        assertEquals("A1:B4", visible.single().address)
        assertEquals(0.0, visible.single().left, 0.0)
        assertEquals(80.0, visible.single().bottom, 0.0)
    }
    @Test fun rejectsIntersectingMergesButAcceptsTouchingEdges() {
        assertTrue(runCatching { SheetMergeIndex(listOf(NativeMergedRange(0, 0, 1, 2), NativeMergedRange(2, 1, 2, 3)), 3, 4) }.exceptionOrNull() is InvalidOfficeDocumentException)
        val valid = SheetMergeIndex(listOf(NativeMergedRange(0, 0, 1, 1), NativeMergedRange(2, 1, 2, 3)), 3, 4)
        assertNull(valid.at(2, 0))
        assertEquals(2, valid.at(3, 2)!!.row)
    }
    @Test fun validatesNegativeSizesAndLimitsMergeIndexMemory() {
        assertTrue(runCatching { SheetAxis(10, 15f, mapOf(2 to Float.NaN)) }.exceptionOrNull() is InvalidOfficeDocumentException)
        assertTrue(runCatching { SheetAxis(10, 15f, mapOf(2 to -1f)) }.exceptionOrNull() is InvalidOfficeDocumentException)
        val ranges = (0..500).map { NativeMergedRange(it, 0, 511) }
        assertTrue(runCatching { SheetMergeIndex(ranges, 512, 501) }.exceptionOrNull() is InvalidOfficeDocumentException)
    }
    @Test fun visiblePlacementsStayBoundedForVerySmallCellSizes() {
        val layout = WorksheetLayout(NativeWorksheet("Small", emptyList(), 512, false,
            defaultColumnWidthPoints = 0.01f, defaultRowHeightPoints = 0.01f, rowCount = 1_000_000))
        assertTrue(layout.visible(0.0, 0.0, 500.0, 500.0).size <= 4096)
    }
    @Test fun emptyCellsInheritRowThenColumnThenNormalStyle() {
        val column = NativeSheetCell("", fillRgb = 0x00FF00L)
        val row = NativeSheetCell("", fillRgb = 0xFF0000L)
        val layout = WorksheetLayout(NativeWorksheet("Styles", listOf(NativeSheetRow(0, emptyMap(), defaultCell = row)), 2, false,
            rowCount = 2, columnStyles = mapOf(0 to column)))
        assertEquals(0xFF0000L, layout.placement(0, 0)!!.cell.fillRgb)
        assertEquals(0x00FF00L, layout.placement(1, 0)!!.cell.fillRgb)
        assertNull(layout.placement(1, 1)!!.cell.fillRgb)
    }
    @Test fun mergeCanUseSavedBottomAndRightEdgesFromItsEndCell() {
        val end = NativeSheetCell("", borderBottom = NativeSheetBorder(1.5f, 0x0000FFL))
        val layout = WorksheetLayout(NativeWorksheet("Border", listOf(NativeSheetRow(1, mapOf(1 to end))), 2, false, listOf(NativeMergedRange(0, 0, 1, 1))))
        assertEquals(end.borderBottom, layout.placement(0, 0)!!.cell.borderBottom)
    }
    @Test fun zoomAnchorsTheCenterAndPanIsClamped() {
        val zoomed = SheetViewport().transformed(2f, width = 100.0, height = 100.0, contentWidth = 500.0, contentHeight = 1000.0)
        assertEquals(SheetViewport(2f, 25.0, 25.0), zoomed)
        val panned = zoomed.transformed(panX = -10_000.0, panY = 10_000.0, width = 50.0, height = 50.0, contentWidth = 500.0, contentHeight = 1000.0)
        assertEquals(450.0, panned.x, 0.0)
        assertEquals(0.0, panned.y, 0.0)
        assertEquals(panned, panned.transformed(Float.NaN, width = 50.0, height = 50.0, contentWidth = 500.0, contentHeight = 1000.0))
    }
    @Test fun referenceWidthConversionAndColumnLabelsAreStable() {
        assertEquals(63f, xlsxColumnPoints(12f), 0f)
        assertEquals("A", sheetColumnLabel(0))
        assertEquals("SR", sheetColumnLabel(511))
    }
}
