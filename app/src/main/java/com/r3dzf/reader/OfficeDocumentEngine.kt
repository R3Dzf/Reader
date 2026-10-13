package com.r3dzf.reader

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipInputStream

enum class OfficeFormat(val extension: String, val mimeType: String, val supportedOffline: Boolean) {
    DOCX("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", true),
    XLSX("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", true),
    PPTX("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation", true),
    DOC("doc", "application/msword", false),
    XLS("xls", "application/vnd.ms-excel", false),
    PPT("ppt", "application/vnd.ms-powerpoint", false);

    companion object {
        fun fromName(name: String): OfficeFormat? =
            entries.firstOrNull { it.extension == name.substringAfterLast('.', "").lowercase(Locale.ROOT) }

        fun fromMimeType(mimeType: String?): OfficeFormat? =
            entries.firstOrNull { it.mimeType.equals(mimeType, ignoreCase = true) }
    }
}

data class PreparedOfficeDocument(val file: File, val displayName: String, val format: OfficeFormat)

private const val MAX_OFFICE_FILE_BYTES = 250L * 1024L * 1024L

class LegacyOfficeFormatException(val officeFormat: OfficeFormat) : IOException(
    "The built-in offline renderer does not support .${officeFormat.extension} yet."
)

/** Copies a picked document into private cache; the parser never writes back to the selected file. */
fun prepareOfficeDocument(context: Context, uri: Uri): PreparedOfficeDocument {
    val resolver = context.contentResolver
    val originalName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        ?: uri.lastPathSegment?.substringAfterLast('/')
        ?: "office-document"
    val format = OfficeFormat.fromName(originalName)
        ?: OfficeFormat.fromMimeType(resolver.getType(uri))
        ?: throw IOException("Choose a Word, Excel, or PowerPoint document.")
    if (!format.supportedOffline) throw LegacyOfficeFormatException(format)

    val incoming = File(context.cacheDir, "office-input").apply { mkdirs() }
    val target = File(incoming, "${UUID.randomUUID()}.${format.extension}")
    try {
        val input = resolver.openInputStream(uri) ?: throw IOException("Could not read the selected document.")
        input.use { source ->
            FileOutputStream(target).use { destination ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var totalBytes = 0L
                while (true) {
                    val count = source.read(buffer)
                    if (count < 0) break
                    totalBytes += count
                    if (totalBytes > MAX_OFFICE_FILE_BYTES) {
                        throw IOException("The selected document is larger than the 250 MB limit.")
                    }
                    destination.write(buffer, 0, count)
                }
            }
        }
        return PreparedOfficeDocument(target, originalName, format)
    } catch (error: Throwable) {
        target.delete()
        throw error
    }
}

/** Unpacks the pinned, bundled WebAssembly renderer to app-private storage on first use. */
object OfficeRendererAssets {
    private const val ARCHIVE_NAME = "office-renderer.zip"
    private const val VERSION_NAME = "office-renderer-version.txt"
    private const val READY_MARKER = ".ready"
    private const val MAX_UNPACKED_BYTES = 160L * 1024L * 1024L

    @Throws(IOException::class)
    fun ensureExtracted(context: Context): File {
        val version = context.assets.open(VERSION_NAME).bufferedReader().use { it.readText().trim() }
        if (version.isBlank()) throw IOException("The built-in document renderer has no version marker.")
        val parent = File(context.filesDir, "reader-renderer")
        val target = File(parent, version)
        if (File(target, READY_MARKER).isFile) return target

        parent.mkdirs()
        val temporary = File(parent, "$version.tmp")
        temporary.deleteRecursively()
        target.deleteRecursively()
        temporary.mkdirs()
        try {
            val rootPath = temporary.canonicalPath + File.separator
            var unpackedBytes = 0L
            context.assets.open(ARCHIVE_NAME).use { archive ->
                ZipInputStream(archive.buffered()).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        val output = File(temporary, entry.name).canonicalFile
                        if (!output.path.startsWith(rootPath)) throw IOException("Invalid renderer package path.")
                        if (entry.isDirectory) {
                            output.mkdirs()
                        } else {
                            output.parentFile?.mkdirs()
                            output.outputStream().buffered().use { destination ->
                                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                                while (true) {
                                    val count = zip.read(buffer)
                                    if (count < 0) break
                                    unpackedBytes += count
                                    if (unpackedBytes > MAX_UNPACKED_BYTES) {
                                        throw IOException("The built-in renderer package exceeds its extraction limit.")
                                    }
                                    destination.write(buffer, 0, count)
                                }
                            }
                        }
                        zip.closeEntry()
                    }
                }
            }
            File(temporary, READY_MARKER).writeText(version)
            if (!temporary.renameTo(target)) throw IOException("Could not install the built-in document renderer.")
            parent.listFiles()?.filter { it.isDirectory && it != target }?.forEach { it.deleteRecursively() }
            return target
        } catch (error: Throwable) {
            temporary.deleteRecursively()
            throw error
        }
    }

}

internal fun officeMimeType(extension: String): String =
    when (extension.lowercase(Locale.ROOT)) {
        "mjs", "js" -> "text/javascript"
        "css" -> "text/css"
        "wasm" -> "application/wasm"
        "json" -> "application/json"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.lowercase(Locale.ROOT))
            ?: "application/octet-stream"
    }
