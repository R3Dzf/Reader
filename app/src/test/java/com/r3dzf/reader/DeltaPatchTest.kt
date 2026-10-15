package com.r3dzf.reader

import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import java.io.*
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream

class DeltaPatchTest {
    @get:Rule val folder=TemporaryFolder()
    private fun hash(data: ByteArray)=MessageDigest.getInstance("SHA-256").digest(data)
    private fun patch(base: ByteArray,target: ByteArray,ops: DataOutputStream.()->Unit): File {
        val out=folder.newFile();DataOutputStream(out.outputStream()).use { d ->
            d.writeInt(0x52464431);d.writeLong(target.size.toLong());d.write(hash(base));d.write(hash(target))
            DataOutputStream(GZIPOutputStream(d)).use { it.ops() }
        };return out
    }
    private fun apply(base: ByteArray,target: ByteArray,patch: File,cancel: ()->Boolean={false}): File {
        val input=folder.newFile().apply { writeBytes(base) };val output=folder.newFile()
        DeltaPatch.apply(input,patch,output,hash(target).joinToString("") { "%02x".format(it) },target.size.toLong(),cancelled=cancel)
        return output
    }
    private fun rejects(action: ()->Unit) { try { action();fail("Accepted invalid patch") } catch(expected: IOException) {} catch(expected: IllegalArgumentException) {} }
    @Test fun copiesAndLiteralsRebuildExactBytes() {
        val base="abcdefghij".toByteArray();val target="abcXYZhij".toByteArray()
        val p=patch(base,target) { writeByte(0);writeLong(0);writeInt(3);writeByte(1);writeInt(3);writeBytes("XYZ");writeByte(0);writeLong(7);writeInt(3);writeByte(127) }
        assertArrayEquals(target,apply(base,target,p).readBytes())
    }
    @Test fun wrongBaseRejectedWithoutOutput() {
        val a="base".toByteArray();val t="new".toByteArray();val p=patch(a,t) {writeByte(1);writeInt(3);write(t);writeByte(127)}
        rejects {apply("wrong".toByteArray(),t,p)}
    }
    @Test fun copyOutsideBaseRejected() { val a=byteArrayOf(1);val p=patch(a,a) {writeByte(0);writeLong(Long.MAX_VALUE);writeInt(1);writeByte(127)};rejects {apply(a,a,p)} }
    @Test fun negativeLengthRejected() { val a=byteArrayOf(1);val p=patch(a,a) {writeByte(1);writeInt(-1)};rejects {apply(a,a,p)} }
    @Test fun trailingCommandsRejected() { val a=byteArrayOf(1);val p=patch(a,a) {writeByte(0);writeLong(0);writeInt(1);writeByte(127);writeByte(1)};rejects {apply(a,a,p)} }
    @Test fun targetHashMismatchDeletesPartialFile() {
        val a=byteArrayOf(1);val p=patch(a,a) {writeByte(1);writeInt(1);writeByte(2);writeByte(127)}
        val input=folder.newFile().apply {writeBytes(a)};val output=folder.newFile()
        rejects {DeltaPatch.apply(input,p,output,input.sha256(),1)};assertFalse(output.exists());assertArrayEquals(a,input.readBytes())
    }
    @Test fun truncatedGzipRejected() {val a=byteArrayOf(1);val p=patch(a,a) {writeByte(0);writeLong(0);writeInt(1);writeByte(127)};p.writeBytes(p.readBytes().dropLast(7).toByteArray());rejects {apply(a,a,p)} }
    @Test fun cancelledPatchLeavesBaseUnchanged() {val a=byteArrayOf(1);val p=patch(a,a) {writeByte(127)};rejects {apply(a,a,p) {true}} }
}
