package com.r3dzf.reader

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.*
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executors

/** App-private snapshots survive cache eviction and revoked/moved provider URIs. Originals are read only. */
class DocumentLibrary internal constructor(private val context: Context) {
    private val directory=File(context.filesDir,"library").apply { check(mkdirs() || isDirectory) }
    private val worker=Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val scope=CoroutineScope(SupervisorJob()+worker)
    private val helper=object: SQLiteOpenHelper(context,"reading-library.db",null,1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE documents (id TEXT PRIMARY KEY NOT NULL, name TEXT NOT NULL, extension TEXT NOT NULL, bytes INTEGER NOT NULL, opened INTEGER NOT NULL, favorite INTEGER NOT NULL DEFAULT 0, position INTEGER NOT NULL DEFAULT 0, scroll_offset INTEGER NOT NULL DEFAULT 0, total INTEGER NOT NULL DEFAULT 0)")
            db.execSQL("CREATE INDEX documents_opened ON documents(opened DESC)")
        }
        override fun onUpgrade(db: SQLiteDatabase,oldVersion: Int,newVersion: Int) = Unit
    }
    private fun file(document: LibraryDocument): File {
        if(!document.id.matches(Regex("[a-f0-9]{64}")) || !document.extension.matches(Regex("[a-z0-9]{2,5}"))) throw LibraryException(LibraryProblem.MISSING)
        val result=File(directory,"${document.id}.${document.extension}").canonicalFile
        if(result.parentFile!=directory.canonicalFile) throw LibraryException(LibraryProblem.MISSING)
        return result
    }
    private fun row(cursor: Cursor): LibraryDocument {
        fun s(name: String)=cursor.getString(cursor.getColumnIndexOrThrow(name))
        fun n(name: String)=cursor.getLong(cursor.getColumnIndexOrThrow(name))
        val document=LibraryDocument(s("id"),s("name"),s("extension"),n("bytes"),n("opened"),n("favorite")==1L,
            ReadingProgress(n("position").toInt(),n("scroll_offset").toInt(),n("total").toInt()).bounded())
        return document.copy(available=runCatching { file(document).isFile }.getOrDefault(false))
    }
    private fun find(id: String): LibraryDocument? = helper.readableDatabase.query("documents",null,"id=?",arrayOf(id),null,null,null)
        .use { if(it.moveToFirst()) row(it) else null }
    suspend fun documents(): List<LibraryDocument> = withContext(worker) {
        helper.readableDatabase.query("documents",null,null,null,null,null,"opened DESC, id ASC").use { cursor ->
            buildList { while(cursor.moveToNext()) add(row(cursor)) }
        }
    }
    suspend fun import(uri: Uri, mimeHint: String? = null): LibraryDocument = withContext(worker) {
        val resolver=context.contentResolver
        val rawName=runCatching { resolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use {
            if(it.moveToFirst()) it.getString(0) else null
        } }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Document"
        val mime=runCatching { resolver.getType(uri) }.getOrNull() ?: mimeHint
        val extension=when {
            rawName.substringAfterLast('.',"").equals("pdf",true) -> "pdf"
            OfficeFormat.fromName(rawName)!=null -> OfficeFormat.fromName(rawName)!!.extension
            mime=="application/pdf" -> "pdf"
            else -> OfficeFormat.fromMimeType(mime)?.extension ?: throw LibraryException(LibraryProblem.UNSUPPORTED)
        }
        val input=resolver.openInputStream(uri) ?: throw LibraryException(LibraryProblem.UNREADABLE)
        input.use { importStream(it,safeDocumentName(rawName),extension) }
    }
    /** Internal stream entry point also lets device tests use independent fixtures. Runs on the serial worker. */
    internal suspend fun importForTest(input: InputStream,name: String,extension: String): LibraryDocument = withContext(worker) {
        input.use { importStream(it,safeDocumentName(name),extension) }
    }
    private fun importStream(input: InputStream,name: String,extension: String): LibraryDocument {
        if(extension!="pdf" && OfficeFormat.entries.none { it.extension==extension }) throw LibraryException(LibraryProblem.UNSUPPORTED)
        // This worker serializes imports. Any pre-existing pending copy belongs to an interrupted import.
        directory.listFiles()?.filter { it.name.startsWith(".pending-") }?.forEach { it.delete() }
        val pending=File(directory,".pending-${UUID.randomUUID()}")
        try {
            val digest=MessageDigest.getInstance("SHA-256").apply { update(extension.toByteArray(Charsets.UTF_8));update(byteArrayOf(0)) };var size=0L
            pending.outputStream().use { output ->
                val buffer=ByteArray(64*1024)
                while(true) {
                    val count=input.read(buffer);if(count<0) break;if(count==0) continue
                    size+=count;if(size>MAX_FILE_BYTES) throw LibraryException(LibraryProblem.TOO_LARGE)
                    digest.update(buffer,0,count);output.write(buffer,0,count)
                }
            }
            if(size==0L) throw LibraryException(LibraryProblem.UNREADABLE)
            val prefix=pending.inputStream().use { stream -> ByteArray(minOf(size,1024).toInt()).also { stream.read(it) } }
            if(extension=="pdf" && !prefix.toString(Charsets.ISO_8859_1).contains("%PDF-")) throw LibraryException(LibraryProblem.UNREADABLE)
            val id=digest.digest().joinToString("") { "%02x".format(it) }
            val existing=find(id)
            if(existing!=null && existing.extension==extension && existing.available) {
                val opened=System.currentTimeMillis()
                helper.writableDatabase.update("documents",ContentValues().apply { put("opened",opened);put("name",name) },"id=?",arrayOf(id))
                return existing.copy(displayName=name,openedAt=opened)
            }
            val count=helper.readableDatabase.rawQuery("SELECT COUNT(*) FROM documents",null).use { it.moveToFirst();it.getInt(0) }
            val used=directory.listFiles()?.filter { it!=pending && !it.name.startsWith(".pending-") }?.sumOf { it.length() } ?: 0
            if(existing==null && count>=MAX_DOCUMENTS || used+size>MAX_LIBRARY_BYTES) throw LibraryException(LibraryProblem.FULL)
            val document=LibraryDocument(id,name,extension,size,System.currentTimeMillis(),existing?.favorite ?: false,existing?.progress ?: ReadingProgress())
            val destination=file(document)
            if(destination.exists() && !destination.delete()) throw LibraryException(LibraryProblem.UNREADABLE)
            if(!pending.renameTo(destination)) throw LibraryException(LibraryProblem.UNREADABLE)
            val values=ContentValues().apply {
                put("id",id);put("name",name);put("extension",extension);put("bytes",size);put("opened",document.openedAt)
                put("favorite",if(document.favorite) 1 else 0);put("position",document.progress.index);put("scroll_offset",document.progress.offset);put("total",document.progress.total)
            }
            try { helper.writableDatabase.insertWithOnConflict("documents",null,values,SQLiteDatabase.CONFLICT_REPLACE).also { check(it!=-1L) } }
            catch(error: Throwable) { destination.delete();throw error }
            return document
        } finally { pending.delete() }
    }
    suspend fun open(id: String): Pair<LibraryDocument,File> = withContext(worker) {
        val document=find(id) ?: throw LibraryException(LibraryProblem.MISSING)
        val source=file(document);if(!source.isFile) throw LibraryException(LibraryProblem.MISSING)
        val opened=System.currentTimeMillis()
        helper.writableDatabase.update("documents",ContentValues().apply { put("opened",opened) },"id=?",arrayOf(id))
        document.copy(openedAt=opened) to source
    }
    fun saveProgress(id: String,progress: ReadingProgress) {
        val safe=progress.bounded()
        scope.launch { runCatching { helper.writableDatabase.update("documents",ContentValues().apply {
            put("position",safe.index);put("scroll_offset",safe.offset);put("total",safe.total)
        },"id=?",arrayOf(id)) } }
    }
    suspend fun favorite(id: String,value: Boolean) = withContext(worker) {
        helper.writableDatabase.update("documents",ContentValues().apply { put("favorite",if(value) 1 else 0) },"id=?",arrayOf(id));Unit
    }
    suspend fun remove(id: String) = withContext(worker) {
        find(id)?.let { document ->
            val target=file(document)
            if(target.exists() && !target.delete()) throw LibraryException(LibraryProblem.UNREADABLE)
            helper.writableDatabase.delete("documents","id=?",arrayOf(id))
        };Unit
    }
    suspend fun clear() = withContext(worker) {
        val ids=helper.readableDatabase.query("documents",arrayOf("id"),null,null,null,null,null).use { c -> buildList { while(c.moveToNext()) add(c.getString(0)) } }
        ids.forEach { id -> find(id)?.let { document ->
            val target=file(document)
            if(!target.exists() || target.delete()) helper.writableDatabase.delete("documents","id=?",arrayOf(id))
        } }
        if(helper.readableDatabase.rawQuery("SELECT COUNT(*) FROM documents",null).use { it.moveToFirst();it.getInt(0) }>0) throw LibraryException(LibraryProblem.UNREADABLE)
    }
    internal suspend fun closeForTest() { withContext(worker) { helper.close() };scope.cancel();worker.close() }
    companion object {
        const val MAX_FILE_BYTES=250L*1024*1024
        const val MAX_LIBRARY_BYTES=1024L*1024*1024
        const val MAX_DOCUMENTS=200
        @Volatile private var instance: DocumentLibrary?=null
        fun get(context: Context): DocumentLibrary = instance ?: synchronized(this) {
            instance ?: DocumentLibrary(context.applicationContext).also { instance=it }
        }
    }
}

internal fun isReaderPrivateFile(context: Context,file: File,cacheFolder: String): Boolean = runCatching {
    val path=file.canonicalFile
    listOf(File(context.filesDir,"library"),File(context.cacheDir,cacheFolder)).any { path.path.startsWith(it.canonicalPath+File.separator) } && path.isFile
}.getOrDefault(false)
