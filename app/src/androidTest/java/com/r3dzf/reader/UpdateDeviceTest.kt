package com.r3dzf.reader

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.Assert.*
import org.json.JSONObject
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64
import java.security.MessageDigest

class UpdateDeviceTest {
    private val pair=KeyPairGenerator.getInstance("RSA").apply {initialize(2048)}.generateKeyPair()
    private fun payload()=JSONObject().put("schema",1).put("package","com.r3dzf.reader").put("versionCode",10).put("versionName","0.9.1").put("minSdk",26).put("certificateSha256",UpdateProtocol.CERTIFICATE_SHA256)
        .put("full",JSONObject().put("url","https://github.com/R3Dzf/Reader/releases/download/v0.9.1/Rafolio.apk").put("bytes",1000).put("sha256","a".repeat(64))).put("deltas",org.json.JSONArray())
    private fun envelope(data: JSONObject): ByteArray {
        val bytes=data.toString().toByteArray();val signature=Signature.getInstance("SHA256withRSA").run {initSign(pair.private);update(bytes);sign()}
        return JSONObject().put("payload",Base64.getEncoder().encodeToString(bytes)).put("signature",Base64.getEncoder().encodeToString(signature)).toString().toByteArray()
    }
    private fun rejects(action: ()->Unit) {try {action();fail("Accepted unsafe feed")} catch(expected: IllegalArgumentException) {} }
    @Test fun authenticatedFeedParses() {assertEquals(10L,UpdateProtocol.parse(envelope(payload()),pair.public).versionCode)}
    @Test fun tamperingFailsSignature() {val root=JSONObject(envelope(payload()).toString(Charsets.UTF_8));root.put("payload",Base64.getEncoder().encodeToString(payload().put("versionCode",99).toString().toByteArray()));rejects {UpdateProtocol.parse(root.toString().toByteArray(),pair.public)}}
    @Test fun wrongSigningKeyIsRejected() {val other=KeyPairGenerator.getInstance("RSA").apply {initialize(2048)}.generateKeyPair();rejects {UpdateProtocol.parse(envelope(payload()),other.public)}}
    @Test fun unsafeAssetAndOversizedPackageRejected() {
        val p=payload();p.getJSONObject("full").put("url","https://evil.test/a.apk");rejects {UpdateProtocol.parse(envelope(p),pair.public)}
        val q=payload();q.getJSONObject("full").put("bytes",DeltaPatch.MAX_APK+1);rejects {UpdateProtocol.parse(envelope(q),pair.public)}
    }
    @Test fun wrongPackageAndCertificateRejected() {
        rejects {UpdateProtocol.parse(envelope(payload().put("package","evil.app")),pair.public)}
        rejects {UpdateProtocol.parse(envelope(payload().put("certificateSha256","b".repeat(64))),pair.public)}
    }
    @Test fun bundledCertificateMatchesPermanentIdentity() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val cert=context.resources.openRawResource(R.raw.rafolio_release_certificate).use {CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate}
        assertEquals(UpdateProtocol.CERTIFICATE_SHA256,MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString("") {"%02x".format(it)})
        assertTrue(cert.notAfter.time>System.currentTimeMillis()+90L*365*24*60*60*1000)
    }
    @Test fun developmentApkCannotMasqueradeAsRelease() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val file=java.io.File(context.applicationInfo.sourceDir)
        val release=UpdateRelease(BuildConfig.VERSION_CODE.toLong()+1,"new",26,"",UpdateAsset("",file.length(),file.sha256()),emptyList())
        try { AppUpdates.get(context).verifyApk(file,release);fail("Accepted debug signing") }
        catch(expected: IllegalArgumentException) {assertEquals("signature",expected.message)}
    }

    @Test fun pythonGeneratedDeltaRebuildsExactBytesOnAndroid() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val fixture=instrumentation.context.assets.open("delta-reference.json").bufferedReader().use {JSONObject(it.readText())}
        val folder=java.io.File(instrumentation.targetContext.cacheDir,"delta-${java.util.UUID.randomUUID()}").apply {mkdirs()}
        try {
            fun bytes(key: String)=Base64.getDecoder().decode(fixture.getString(key))
            val base=java.io.File(folder,"base.apk").apply {writeBytes(bytes("base"))}
            val patch=java.io.File(folder,"patch.rfd").apply {writeBytes(bytes("patch"))}
            val expected=bytes("target");val hash=MessageDigest.getInstance("SHA-256").digest(expected).joinToString("") {"%02x".format(it)}
            val output=java.io.File(folder,"new.apk")
            DeltaPatch.apply(base,patch,output,hash,expected.size.toLong())
            assertArrayEquals(expected,output.readBytes());assertArrayEquals(bytes("base"),base.readBytes())
        } finally { folder.listFiles()?.forEach {it.delete()};folder.delete() }
    }

}
