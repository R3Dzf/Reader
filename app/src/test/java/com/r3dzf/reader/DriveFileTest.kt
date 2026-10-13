package com.r3dzf.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class DriveFileTest {
    @Test
    fun fileExtensionIsPreservedForReaderRows() {
        val file = DriveFile(id = "item-1", name = "تقرير الربيع.XLSX", extension = "xlsx")

        assertEquals("xlsx", file.extension)
        assertEquals("تقرير الربيع.XLSX", file.name)
    }
}
