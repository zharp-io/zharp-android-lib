package io.zharp.gradle

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.util.UUID

/**
 * `POST <url>/api/v1/applications/<appId>/artifacts` as multipart/form-data: the text fields, then
 * the file (part name `file`), streamed so a large mapping never sits in memory. Authenticated with
 * a workspace API key (`Authorization: Bearer zh_…`, scope `artifacts:write`). Network errors,
 * 429 and 5xx are retried; other failures are reported with the server's answer.
 */
internal class ArtifactUploader(
    private val url: String,
    private val token: String,
    private val retryDelaysMillis: LongArray = longArrayOf(2_000, 5_000),
) {
    class Failed(message: String, val retryable: Boolean) : IOException(message)

    fun upload(appId: String, fields: Map<String, String>, file: File, fileName: String, contentType: String) {
        val target = URI("${url.trimEnd('/')}/api/v1/applications/${URLEncoder.encode(appId, "UTF-8")}/artifacts").toURL()
        var attempt = 0
        while (true) {
            try {
                send(target, fields, file, fileName, contentType)
                return
            } catch (error: IOException) {
                val retryable = error !is Failed || error.retryable
                if (!retryable || attempt >= retryDelaysMillis.size) throw error
                Thread.sleep(retryDelaysMillis[attempt++])
            }
        }
    }

    private fun send(target: java.net.URL, fields: Map<String, String>, file: File, fileName: String, contentType: String) {
        val boundary = "zharp-" + UUID.randomUUID().toString().replace("-", "")
        val head = StringBuilder()
        for ((name, value) in fields) {
            head.append("--").append(boundary).append("\r\n")
                .append("Content-Disposition: form-data; name=\"").append(name).append("\"\r\n\r\n")
                .append(value).append("\r\n")
        }
        head.append("--").append(boundary).append("\r\n")
            .append("Content-Disposition: form-data; name=\"file\"; filename=\"").append(fileName).append("\"\r\n")
            .append("Content-Type: ").append(contentType).append("\r\n\r\n")
        val headBytes = head.toString().toByteArray(Charsets.UTF_8)
        val tailBytes = "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)

        val connection = target.openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = 30_000
            connection.readTimeout = 5 * 60_000
            connection.setFixedLengthStreamingMode(headBytes.size + file.length() + tailBytes.size)
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Accept", "application/json")
            connection.outputStream.use { out ->
                out.write(headBytes)
                file.inputStream().use { it.copyTo(out, 64 * 1024) }
                out.write(tailBytes)
            }
            val status = connection.responseCode
            if (status in 200..299) return
            val body = (connection.errorStream ?: null)?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty().take(500)
            val hint = when (status) {
                401 -> " (the token isn't a valid Zharp API key)"
                403 -> " (the API key needs the artifacts:write scope, in the application's workspace)"
                404 -> " (no application with that ID in the key's workspace)"
                413 -> " (the file is larger than Zharp accepts)"
                else -> ""
            }
            throw Failed("HTTP $status$hint${if (body.isNotEmpty()) ": $body" else ""}", retryable = status == 429 || status >= 500)
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        const val USER_AGENT = "zharp-android-gradle/1.0.0"
    }
}
