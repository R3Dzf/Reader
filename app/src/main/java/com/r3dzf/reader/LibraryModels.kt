package com.r3dzf.reader

import java.text.Normalizer
import java.util.Locale

enum class DocumentKind { PDF, WORD, SHEET, SLIDES;
    companion object {
        fun fromExtension(extension: String): DocumentKind = when(extension.lowercase(Locale.ROOT)) {
            "pdf" -> PDF
            "xlsx","xlsm","xltx","xltm","xls","xlt","xlsb" -> SHEET
            "pptx","pptm","ppsx","ppsm","potx","potm","ppt","pps" -> SLIDES
            else -> WORD
        }
    }
}

data class ReadingProgress(val index: Int = 0, val offset: Int = 0, val total: Int = 0) {
    fun bounded(): ReadingProgress {
        val count=total.coerceIn(0,1_048_576)
        return ReadingProgress(index.coerceIn(0,if(count>0) count-1 else 1_048_575),offset.coerceIn(0,1_000_000),count)
    }
    val fraction: Float get() = if(total<=0) 0f else ((index.coerceIn(0,total-1)+1f)/total).coerceIn(0f,1f)
}

data class LibraryDocument(val id: String, val displayName: String, val extension: String, val bytes: Long,
    val openedAt: Long, val favorite: Boolean = false, val progress: ReadingProgress = ReadingProgress(),
    val available: Boolean = true) {
    val kind: DocumentKind get() = DocumentKind.fromExtension(extension)
}

internal fun safeDocumentName(name: String): String = name.filterNot {
    it.isISOControl() || it in '\u202A'..'\u202E' || it in '\u2066'..'\u2069' || it=='/' || it=='\\'
}.trim().take(240).ifBlank { "Document" }

private fun searchKey(text: String): String = Normalizer.normalize(text,Normalizer.Form.NFKD)
    .replace(Regex("\\p{M}+"),"").replace('أ','ا').replace('إ','ا').replace('آ','ا').replace('ى','ي')
    .lowercase(Locale.ROOT).trim()

internal fun selectDocuments(documents: List<LibraryDocument>, query: String = "", kind: DocumentKind? = null,
    favoritesOnly: Boolean = false): List<LibraryDocument> {
    val key=searchKey(query)
    return documents.asSequence().filter { (kind==null || it.kind==kind) && (!favoritesOnly || it.favorite) &&
        (key.isBlank() || searchKey(it.displayName).contains(key)) }.sortedWith(compareByDescending<LibraryDocument> { it.openedAt }.thenBy { it.id }).toList()
}

enum class LibraryProblem { UNREADABLE, UNSUPPORTED, TOO_LARGE, FULL, MISSING }
class LibraryException(val problem: LibraryProblem) : java.io.IOException(problem.name)
