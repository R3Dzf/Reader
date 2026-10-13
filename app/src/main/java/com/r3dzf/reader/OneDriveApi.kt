package com.r3dzf.reader

import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Read-only Microsoft Graph access. No Office file is uploaded or modified. */
object OneDriveApi {
    private const val GRAPH_ROOT = "https://graph.microsoft.com/v1.0"
    private const val MAX_PDF_BYTES = 250L * 1024L * 1024L
    private val supportedExtensions = setOf("doc", "docx", "xls", "xlsx", "ppt", "pptx")

    fun listOfficeFiles(accessToken: String): List<DriveFile> {
        val url = "$GRAPH_ROOT/me/drive/root/children?%24select=id,name,file,folder&%24top=100"
        val connection = openGraphConnection(url, accessToken)
        try {
            val status = connection.responseCode
            if (status !in 200..299) throw graphError(connection, status)
            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val values = JSONObject(body).optJSONArray("value") ?: return emptyList()
            return buildList {
                for (index in 0 until values.length()) {
                    val item = values.optJSONObject(index) ?: continue
                    val name = item.optString("name")
                    val extension = name.substringAfterLast('.', "").lowercase()
                    if (item.optJSONObject("file") != null && extension in supportedExtensions) {
                        val id = item.optString("id")
                        if (id.isNotBlank()) add(DriveFile(id, name, extension))
                    }
                }
            }.sortedBy { it.name.lowercase() }
        } finally {
            connection.disconnect()
        }
    }

    fun convertToPdf(accessToken: String, item: DriveFile, cacheDir: File): File {
        val encodedId = Uri.encode(item.id)
        val graphUrl = "$GRAPH_ROOT/me/drive/items/$encodedId/content?format=pdf"
        val graphConnection = openGraphConnection(graphUrl, accessToken)
        var redirectUrl: String? = null
        var directConnection: HttpURLConnection? = null
        try {
            val status = graphConnection.responseCode
            if (status in 200..299) {
                directConnection = graphConnection
            } else if (status in 300..399) {
                redirectUrl = graphConnection.getHeaderField("Location")
                    ?: throw IllegalStateException("Microsoft Graph did not return a download location.")
            } else {
                throw graphError(graphConnection, status)
            }
        } finally {
            if (directConnection == null) graphConnection.disconnect()
        }

        val downloadConnection = directConnection ?: openPreauthenticatedDownload(redirectUrl!!)
        val targetDirectory = File(cacheDir, "office-previews").apply { mkdirs() }
        val destination = File(targetDirectory, "${sha256(item.id)}.pdf")
        val partial = File.createTempFile("reader-", ".part", targetDirectory)
        try {
            val status = downloadConnection.responseCode
            if (status !in 200..299) throw IllegalStateException("The PDF preview download failed (HTTP $status).")
            downloadConnection.inputStream.use { input ->
                FileOutputStream(partial).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    var count: Int
                    while (input.read(buffer).also { count = it } >= 0) {
                        if (count == 0) continue
                        total += count
                        if (total > MAX_PDF_BYTES) throw IllegalStateException("The converted PDF is too large to preview safely.")
                        output.write(buffer, 0, count)
                    }
                }
            }
            val signature = partial.inputStream().use { input -> ByteArray(5).also { input.read(it) } }
            if (!signature.contentEquals("%PDF-".toByteArray(Charsets.US_ASCII))) {
                throw IllegalStateException("Microsoft Graph returned a file that is not a PDF.")
            }
            if (destination.exists()) destination.delete()
            if (!partial.renameTo(destination)) {
                partial.copyTo(destination, overwrite = true)
                partial.delete()
            }
            return destination
        } catch (error: Throwable) {
            partial.delete()
            throw error
        } finally {
            downloadConnection.disconnect()
        }
    }

    private fun openGraphConnection(url: String, token: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = false
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/json, application/pdf, */*")
        }

    private fun openPreauthenticatedDownload(url: String): HttpURLConnection {
        val parsed = URL(url)
        require(parsed.protocol.equals("https", ignoreCase = true) && parsed.userInfo == null) {
            "Microsoft Graph returned an invalid download URL."
        }
        return (parsed.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            // The bearer token is deliberately not forwarded to the preauthenticated URL.
            setRequestProperty("Accept", "application/pdf, */*")
        }
    }

    private fun graphError(connection: HttpURLConnection, status: Int): IllegalStateException {
        val details = runCatching {
            connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
        }.getOrNull().orEmpty()
        val message = runCatching {
            JSONObject(details).optJSONObject("error")?.optString("message")
        }.getOrNull().orEmpty()
        return IllegalStateException(message.ifBlank { "Microsoft Graph request failed (HTTP $status)." })
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
}
