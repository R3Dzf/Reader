package com.r3dzf.reader

import java.io.*
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

internal fun File.sha256(): String = inputStream().use { input ->
    val digest=MessageDigest.getInstance("SHA-256");val buffer=ByteArray(64*1024)
    while(true) { val count=input.read(buffer);if(count<0) break;digest.update(buffer,0,count) }
    digest.digest().joinToString("") { "%02x".format(it) }
}
internal object DeltaPatch {
    const val MAX_APK=300L*1024*1024
    private const val MAX_OP=16*1024*1024
    /** Writes a complete signed APK byte for byte. Never changes the installed APK. */
    fun apply(base: File,patch: File,target: File,expectedSha256: String,expectedBytes: Long,progress: (Long)->Unit = {},cancelled: ()->Boolean = {false}) {
        require(base.canonicalPath!=target.canonicalPath && patch.canonicalPath!=target.canonicalPath)
        require(patch.length() in 77..MAX_APK && expectedBytes in 1..MAX_APK)
        try {
            DataInputStream(BufferedInputStream(patch.inputStream())).use { header ->
                require(header.readInt()==0x52464431) { "Delta format" }
                require(header.readLong()==expectedBytes)
                fun hash()=ByteArray(32).also { header.readFully(it) }.joinToString("") { "%02x".format(it) }
                val baseHash=hash();require(hash()==expectedSha256)
                require(base.sha256()==baseHash) { "Delta base mismatch" }
                RandomAccessFile(base,"r").use { source ->
                    DataInputStream(GZIPInputStream(header)).use { commands ->
                        FileOutputStream(target).use { out ->
                            var written=0L;var count=0;val buffer=ByteArray(64*1024)
                            while(true) {
                                if(cancelled()) throw InterruptedIOException("Cancelled")
                                require(++count<=100_000)
                                val op=commands.readUnsignedByte();if(op==127) break
                                val offset=if(op==0) commands.readLong() else 0L
                                require(op==0 || op==1)
                                val length=commands.readInt();require(length in 1..MAX_OP && length.toLong()<=expectedBytes-written)
                                if(op==0) { require(offset>=0 && offset<=source.length() && length<=source.length()-offset);source.seek(offset) }
                                var remaining=length
                                while(remaining>0) {
                                    if(cancelled()) throw InterruptedIOException("Cancelled")
                                    val n=minOf(remaining,buffer.size)
                                    if(op==0) source.readFully(buffer,0,n) else commands.readFully(buffer,0,n)
                                    out.write(buffer,0,n);remaining-=n
                                }
                                written+=length;progress(written)
                            }
                            require(written==expectedBytes && commands.read()==-1) { "Incomplete delta" }
                            out.fd.sync()
                        }
                    }
                }
            }
            require(target.sha256()==expectedSha256) { "APK hash mismatch" }
        } catch(failure: Exception) { target.delete();throw failure }
    }
}
