package com.r3dzf.reader

import java.net.URI
import java.security.Signature
import java.security.cert.X509Certificate
import java.util.Base64
import org.json.JSONObject

internal data class UpdateAsset(val url: String,val bytes: Long,val sha256: String)
internal data class UpdateDelta(val baseSha256: String,val asset: UpdateAsset)
internal data class UpdateRelease(val versionCode: Long,val versionName: String,val minSdk: Int,val notes: String,val full: UpdateAsset,val deltas: List<UpdateDelta>)
internal object UpdateProtocol {
    const val FEED="https://github.com/R3Dzf/Reader/releases/latest/download/rafolio-update.json"
    const val CERTIFICATE_SHA256="f81569437e31be6e14b0cdd3ce91b0e03c28684418e0a9b9598d13eb30d1d7f3"
    private val hash=Regex("[a-f0-9]{64}")
    fun validAssetUrl(value: String): Boolean = runCatching {
        val uri=URI(value)
        uri.scheme=="https" && uri.host=="github.com" && uri.port in listOf(-1,443) && uri.rawUserInfo==null && uri.rawQuery==null && uri.rawFragment==null &&
            uri.rawPath.matches(Regex("/R3Dzf/Reader/releases/download/[A-Za-z0-9._-]+/[A-Za-z0-9._-]+"))
    }.getOrDefault(false)
    fun validTransferUrl(value: String): Boolean = runCatching {
        val uri=URI(value)
        uri.scheme=="https" && uri.port in listOf(-1,443) && uri.rawUserInfo==null && uri.rawFragment==null &&
            uri.host in setOf("github.com","release-assets.githubusercontent.com","objects.githubusercontent.com")
    }.getOrDefault(false)
    fun parse(envelope: ByteArray,certificate: X509Certificate): UpdateRelease = parse(envelope,certificate.publicKey)
    internal fun parse(envelope: ByteArray,key: java.security.PublicKey): UpdateRelease {
        require(envelope.size<=512*1024)
        val root=JSONObject(envelope.toString(Charsets.UTF_8))
        val payload=Base64.getDecoder().decode(root.getString("payload"));require(payload.size<=256*1024)
        val signed=Base64.getDecoder().decode(root.getString("signature"))
        require(Signature.getInstance("SHA256withRSA").run { initVerify(key);update(payload);verify(signed) }) { "Untrusted update feed" }
        val data=JSONObject(payload.toString(Charsets.UTF_8))
        require(data.getInt("schema")==1 && data.getString("package")=="com.r3dzf.reader")
        require(data.getString("certificateSha256")==CERTIFICATE_SHA256)
        fun asset(value: JSONObject): UpdateAsset {
            val result=UpdateAsset(value.getString("url"),value.getLong("bytes"),value.getString("sha256"))
            require(validAssetUrl(result.url) && result.bytes in 1..DeltaPatch.MAX_APK && hash.matches(result.sha256))
            return result
        }
        val version=data.getLong("versionCode");val name=data.getString("versionName");val sdk=data.getInt("minSdk")
        require(version in 1..2_100_000_000 && name.matches(Regex("[A-Za-z0-9.+_-]{1,60}")) && sdk in 26..100)
        val deltas=data.getJSONArray("deltas");require(deltas.length()<=20)
        val patches=(0 until deltas.length()).map { i -> val d=deltas.getJSONObject(i);val base=d.getString("baseSha256");require(hash.matches(base));UpdateDelta(base,asset(d)) }
        require(patches.map { it.baseSha256 }.distinct().size==patches.size)
        return UpdateRelease(version,name,sdk,data.optString("notes").take(2000),asset(data.getJSONObject("full")),patches)
    }
    fun deltaFor(release: UpdateRelease,baseSha256: String): UpdateAsset? = release.deltas.firstOrNull {
        it.baseSha256==baseSha256 && it.asset.bytes < release.full.bytes * 9 / 10
    }?.asset
}
