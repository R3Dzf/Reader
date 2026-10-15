package com.r3dzf.reader

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.StatFs
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicReference

internal enum class UpdatePhase { IDLE,CHECKING,CURRENT,AVAILABLE,DOWNLOADING,PATCHING,VERIFYING,READY,FULL_REQUIRED,ERROR }
internal data class UpdateStatus(val phase: UpdatePhase=UpdatePhase.IDLE,val release: UpdateRelease?=null,val delta: UpdateAsset?=null,val done: Long=0,val total: Long=0,val error: String?=null)
internal class AppUpdates private constructor(private val context: Context) {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val mutable=MutableStateFlow(UpdateStatus())
    val state=mutable.asStateFlow()
    private val connection=AtomicReference<HttpURLConnection?>()
    private var operation: Job?=null
    private val folder=File(context.filesDir,"updates").apply { mkdirs() }
    private val ready=File(folder,"ready.apk")
    private val saved=File(folder,"ready-feed.json")
    private val certificate by lazy { context.resources.openRawResource(R.raw.rafolio_release_certificate).use { CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate } }
    private val base get()=File(context.applicationInfo.sourceDir)
    private val busy get()=mutable.value.phase in setOf(UpdatePhase.CHECKING,UpdatePhase.DOWNLOADING,UpdatePhase.PATCHING,UpdatePhase.VERIFYING)
    @Synchronized fun check() {
        if(busy || operation?.isActive==true) return
        mutable.value=UpdateStatus(UpdatePhase.CHECKING)
        operation=scope.launch {
            mutable.value=UpdateStatus(UpdatePhase.CHECKING)
            try {
                // Recover an already verified download before requesting the network again.
                if(ready.exists() && saved.exists()) {
                    val cached=runCatching { UpdateProtocol.parse(saved.readBytes(),certificate) }.getOrNull()
                    if(cached!=null && cached.versionCode>BuildConfig.VERSION_CODE && cached.minSdk<=Build.VERSION.SDK_INT) {
                        runCatching { verifyApk(ready,cached) }.onSuccess { mutable.value=UpdateStatus(UpdatePhase.READY,cached);return@launch }
                    }
                    ready.delete();saved.delete()
                }
                val envelope=fetchFeed()
                val release=UpdateProtocol.parse(envelope,certificate)
                if(release.versionCode<=BuildConfig.VERSION_CODE) { mutable.value=UpdateStatus(UpdatePhase.CURRENT);return@launch }
                require(release.minSdk<=Build.VERSION.SDK_INT) { "android_version" }
                require(installedCertificate()==UpdateProtocol.CERTIFICATE_SHA256) { "signing_migration" }
                require(context.applicationInfo.splitSourceDirs.isNullOrEmpty()) { "split_install" }
                val delta=UpdateProtocol.deltaFor(release,base.sha256())
                File(folder,"checked-feed.json").writeBytes(envelope)
                mutable.value=UpdateStatus(UpdatePhase.AVAILABLE,release,delta)
            } catch(cancel: CancellationException) { throw cancel }
            catch(failure: Exception) { coroutineContext.ensureActive();mutable.value=UpdateStatus(UpdatePhase.ERROR,error=errorCode(failure)) }
            finally { if(!isActive) mutable.value=UpdateStatus() }
        }
    }
    @Synchronized fun download(full: Boolean=false) {
        if(busy || operation?.isActive==true) return
        val status=mutable.value;val release=status.release?:return
        val asset=if(full) release.full else status.delta?:release.full
        val isDelta=asset!=release.full
        mutable.value=status.copy(phase=UpdatePhase.DOWNLOADING,done=0,total=asset.bytes,error=null)
        operation=scope.launch {
            val part=File(folder,"transfer.part");val output=File(folder,"prepared.apk")
            try {
                val needed=release.full.bytes+asset.bytes+32L*1024*1024
                require(StatFs(folder.absolutePath).availableBytes>=needed) { "space" }
                mutable.value=status.copy(phase=UpdatePhase.DOWNLOADING,done=0,total=asset.bytes,error=null)
                downloadAsset(asset,part) { done -> mutable.value=mutable.value.copy(done=done) }
                coroutineContext.ensureActive()
                if(isDelta) {
                    mutable.value=mutable.value.copy(phase=UpdatePhase.PATCHING,done=0,total=release.full.bytes)
                    DeltaPatch.apply(base,part,output,release.full.sha256,release.full.bytes,{n -> mutable.value=mutable.value.copy(done=n)},{!isActive})
                } else { require(part.renameTo(output)) }
                mutable.value=mutable.value.copy(phase=UpdatePhase.VERIFYING)
                verifyApk(output,release);coroutineContext.ensureActive()
                ready.delete();require(output.renameTo(ready))
                File(folder,"checked-feed.json").copyTo(saved,overwrite=true)
                mutable.value=status.copy(phase=UpdatePhase.READY,error=null)
            } catch(cancel: CancellationException) { throw cancel }
            catch(failure: Exception) {
                coroutineContext.ensureActive()
                mutable.value=status.copy(phase=if(isDelta) UpdatePhase.FULL_REQUIRED else UpdatePhase.ERROR,error=errorCode(failure))
            } finally { part.delete();output.delete();connection.getAndSet(null)?.disconnect();if(!isActive) mutable.value=status.copy(phase=UpdatePhase.AVAILABLE,done=0,total=0,error=null) }
        }
    }
    @Synchronized fun cancel() { operation?.cancel();connection.getAndSet(null)?.disconnect() }
    @Synchronized fun install(activity: Activity) {
        if(busy || operation?.isActive==true || mutable.value.phase!=UpdatePhase.READY) return
        if(!context.packageManager.canRequestPackageInstalls()) {
            activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,android.net.Uri.parse("package:${context.packageName}")))
            return
        }
        val release=mutable.value.release?:return
        mutable.value=mutable.value.copy(phase=UpdatePhase.VERIFYING)
        operation=scope.launch {
            try {
                mutable.value=mutable.value.copy(phase=UpdatePhase.VERIFYING)
                verifyApk(ready,release)
                val uri=FileProvider.getUriForFile(context,"${context.packageName}.updates",ready)
                withContext(Dispatchers.Main) {
                    activity.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                }
                mutable.value=mutable.value.copy(phase=UpdatePhase.READY)
            } catch(cancel: CancellationException) { throw cancel }
            catch(failure: Exception) { coroutineContext.ensureActive();mutable.value=mutable.value.copy(phase=UpdatePhase.ERROR,error=errorCode(failure)) }
            finally { if(!isActive) mutable.value=mutable.value.copy(phase=UpdatePhase.READY) }
        }
    }
    internal fun verifyApk(file: File,release: UpdateRelease) {
        require(file.length()==release.full.bytes && file.sha256()==release.full.sha256) { "integrity" }
        // The signed feed authenticates the complete APK hash. Android's parser supplies
        // its signing identity; the system installer performs platform signature enforcement.
        @Suppress("DEPRECATION") val flags=if(Build.VERSION.SDK_INT>=28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val info=context.packageManager.getPackageArchiveInfo(file.absolutePath,flags)?:error("package")
        @Suppress("DEPRECATION") val signers=if(Build.VERSION.SDK_INT>=28) info.signingInfo?.apkContentsSigners else info.signatures
        require(signers?.size==1 && signatureHash(signers.single().toByteArray())==UpdateProtocol.CERTIFICATE_SHA256) { "signature" }
        @Suppress("DEPRECATION") val code=if(Build.VERSION.SDK_INT>=28) info.longVersionCode else info.versionCode.toLong()
        require(info.packageName==context.packageName && code==release.versionCode && code>BuildConfig.VERSION_CODE && info.versionName==release.versionName) { "package" }
        require(installedCertificate()==UpdateProtocol.CERTIFICATE_SHA256) { "signing_migration" }
    }
    @Suppress("DEPRECATION")
    private fun installedCertificate(): String {
        val info=context.packageManager.getPackageInfo(context.packageName,if(Build.VERSION.SDK_INT>=28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES)
        val signatures=if(Build.VERSION.SDK_INT>=28) info.signingInfo!!.apkContentsSigners else info.signatures!!
        require(signatures.size==1)
        return MessageDigest.getInstance("SHA-256").digest(signatures.single().toByteArray()).joinToString("") { "%02x".format(it) }
    }
    private fun fetchFeed(): ByteArray = open(UpdateProtocol.FEED) { input,length ->
        require(length<=512*1024)
        val output=ByteArrayOutputStream();val buffer=ByteArray(8192)
        while(true) { val n=input.read(buffer);if(n<0) break;require(output.size()+n<=512*1024);output.write(buffer,0,n) }
        output.toByteArray()
    }
    private fun downloadAsset(asset: UpdateAsset,path: File,progress: (Long)->Unit) {
        require(UpdateProtocol.validAssetUrl(asset.url))
        open(asset.url) { input,length ->
            require(length<0 || length==asset.bytes)
            FileOutputStream(path).use { output ->
                val buffer=ByteArray(64*1024);var count=0L;var last=0L
                while(true) {
                    if(Thread.currentThread().isInterrupted || operation?.isActive==false) throw InterruptedIOException("Cancelled")
                    val n=input.read(buffer);if(n<0) break
                    count+=n;require(count<=asset.bytes);output.write(buffer,0,n)
                    if(count-last>=128*1024 || count==asset.bytes) { progress(count);last=count }
                }
                require(count==asset.bytes);output.fd.sync()
            }
        }
        require(path.sha256()==asset.sha256) { "integrity" }
    }
    private fun <T> open(initial: String,read: (InputStream,Long)->T): T {
        var url=initial
        repeat(6) {
            require(UpdateProtocol.validTransferUrl(url))
            val conn=(URL(url).openConnection() as HttpURLConnection).apply { instanceFollowRedirects=false;connectTimeout=15_000;readTimeout=30_000;setRequestProperty("Accept-Encoding","identity");setRequestProperty("User-Agent","Rafolio/${BuildConfig.VERSION_NAME}") }
            connection.set(conn)
            try {
                val status=conn.responseCode
                if(status in listOf(301,302,303,307,308)) { url=URL(URL(url),conn.getHeaderField("Location")?:error("redirect")).toString() }
                else {
                    if(status==404 && initial==UpdateProtocol.FEED) throw IOException("no_release")
                    require(status==200) { "network" }
                    return conn.inputStream.use { read(it,conn.contentLengthLong) }
                }
            } finally { connection.compareAndSet(conn,null);conn.disconnect() }
        }
        error("redirect")
    }
    private fun signatureHash(bytes: ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun errorCode(error: Exception): String = error.message?.takeIf { it in setOf("no_release","android_version","signing_migration","split_install","space","integrity","signature","package") } ?: "network"
    companion object {
        @Volatile private var instance: AppUpdates?=null
        fun get(context: Context): AppUpdates=instance?:synchronized(this) { instance?:AppUpdates(context.applicationContext).also { instance=it } }
    }
}
