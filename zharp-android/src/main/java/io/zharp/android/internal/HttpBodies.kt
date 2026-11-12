package io.zharp.android.internal

import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.Buffer
import okio.ForwardingSink
import okio.buffer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.Charset
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream

/**
 * Reads what [Bodies] needs from OkHttp calls without changing them: a request body is written
 * once more into a capped buffer (never for one-shot or duplex bodies, never past the cap); a
 * multipart upload is summarised from its parts' headers and lengths, file bytes never read; a
 * response is read with `peekBody`, which leaves the app's body untouched.
 */
internal object HttpBodies {
    private const val MAX_FIELDS = 200

    fun requestAttributes(request: Request, settings: BodySettings): Map<String, Any> {
        val body = request.body ?: return emptyMap()
        if (body is MultipartBody) return multipart(body, settings)
        val type = body.contentType()?.toString() ?: request.header("Content-Type")
        val length = lengthOf(body)
        if (body.isOneShot() || body.isDuplex() || !Bodies.isTextual(type)) {
            return Bodies.describedAttributes(Bodies.Side.REQUEST, type, length)
        }
        if (length == 0L) return emptyMap()
        val (bytes, cut) = copy(body, settings.rawLimit)
        val text = decode(bytes, request.header("Content-Encoding"), type)
            ?: return Bodies.describedAttributes(Bodies.Side.REQUEST, type, length)
        return Bodies.bodyAttributes(Bodies.Side.REQUEST, text, type, settings, cut)
    }

    fun responseAttributes(response: Response, settings: BodySettings): Map<String, Any> {
        val body = response.body ?: return emptyMap()
        if (response.request.method == "HEAD" || response.code == 204 || response.code == 304) return emptyMap()
        val type = response.header("Content-Type") ?: body.contentType()?.toString()
        val length = body.contentLength()
        if (length == 0L) return emptyMap()
        if (!Bodies.isTextual(type)) return Bodies.describedAttributes(Bodies.Side.RESPONSE, type, length.takeIf { it > 0 })
        // A copy of the first bytes; the app still reads the whole body.
        val peeked = response.peekBody(settings.rawLimit + 1L).bytes()
        val cut = peeked.size > settings.rawLimit
        val bytes = if (cut) peeked.copyOf(settings.rawLimit) else peeked
        val text = decode(bytes, response.header("Content-Encoding"), type)
            ?: return Bodies.describedAttributes(Bodies.Side.RESPONSE, type, length.takeIf { it > 0 })
        return Bodies.bodyAttributes(Bodies.Side.RESPONSE, text, type, settings, cut)
    }

    /** A form upload as the JSON of its fields: text values kept, each file as its name, type and size. */
    private fun multipart(body: MultipartBody, settings: BodySettings): Map<String, Any> {
        val fields = LinkedHashMap<String, Any?>()
        var cut = false
        for ((count, part) in body.parts.withIndex()) {
            if (count >= MAX_FIELDS) {
                cut = true
                break
            }
            val disposition = part.headers?.get("Content-Disposition").orEmpty()
            val name = NAME.find(disposition)?.groupValues?.get(1) ?: BARE_NAME.find(disposition)?.groupValues?.get(1) ?: ""
            val filename = FILENAME_STAR.find(disposition)?.groupValues?.get(1)?.let { decodeOrSelf(it) }
                ?: FILENAME.find(disposition)?.groupValues?.get(1)
            val partBody = part.body
            val value: String = if (filename != null) {
                val type = partBody.contentType()?.let { "${it.type}/${it.subtype}" }
                Bodies.fileLabel(filename, type, lengthOf(partBody))
            } else if (partBody.isOneShot() || partBody.isDuplex()) {
                ""
            } else {
                val (bytes, partCut) = copy(partBody, settings.maxSize)
                if (partCut) cut = true
                String(bytes, partBody.contentType()?.charset() ?: Charsets.UTF_8).removeSuffix("\uFFFD")
            }
            val existing = fields[name]
            fields[name] = when {
                !fields.containsKey(name) -> value
                existing is List<*> -> existing + value
                else -> listOf(existing, value)
            }
        }
        return Bodies.bodyAttributes(Bodies.Side.REQUEST, Json.write(fields), "multipart/form-data", settings, cut)
    }

    private val NAME = Regex("\\bname=\"([^\"]*)\"", RegexOption.IGNORE_CASE)
    private val BARE_NAME = Regex("\\bname=([^;\\s]+)", RegexOption.IGNORE_CASE)
    private val FILENAME = Regex("\\bfilename=\"([^\"]*)\"", RegexOption.IGNORE_CASE)
    private val FILENAME_STAR = Regex("\\bfilename\\*=(?:UTF-8'')?([^;\\s]+)", RegexOption.IGNORE_CASE)

    private fun decodeOrSelf(text: String): String = try {
        java.net.URLDecoder.decode(text, "UTF-8")
    } catch (e: Exception) {
        text
    }

    private fun lengthOf(body: RequestBody): Long? = try {
        body.contentLength().takeIf { it >= 0 }
    } catch (e: Exception) {
        null
    }

    private class Full : RuntimeException() {
        override fun fillInStackTrace(): Throwable = this
    }

    /** Up to `limit` bytes of a request body, written into our own buffer; whether it was longer. */
    private fun copy(body: RequestBody, limit: Int): Pair<ByteArray, Boolean> {
        val buffer = Buffer()
        var cut = false
        val capped = object : ForwardingSink(buffer) {
            var written = 0L

            override fun write(source: Buffer, byteCount: Long) {
                val room = limit - written
                if (byteCount > room) {
                    if (room > 0) super.write(source, room)
                    written = limit.toLong()
                    source.skip(byteCount - maxOf(room, 0))
                    throw Full()
                }
                super.write(source, byteCount)
                written += byteCount
            }
        }
        try {
            val sink = capped.buffer()
            body.writeTo(sink)
            sink.flush()
        } catch (full: Full) {
            cut = true
        }
        return buffer.readByteArray() to cut
    }

    /** The bytes as text, inflated first if they are compressed; null when that isn't possible. */
    private fun decode(bytes: ByteArray, encoding: String?, contentType: String?): String? {
        val enc = encoding?.trim()?.lowercase().orEmpty()
        val raw = when (enc) {
            "", "identity" -> bytes
            "gzip", "x-gzip" -> inflate(GZIPInputStream(ByteArrayInputStream(bytes))) ?: return null
            "deflate" -> inflate(InflaterInputStream(ByteArrayInputStream(bytes))) ?: return null
            else -> return null // brotli and others: described rather than guessed at
        }
        val charset = charsetOf(contentType)
        // A multi-byte character split at the cap decodes as U+FFFD; drop it.
        return String(raw, charset).removeSuffix("\uFFFD")
    }

    /** As much as inflates; a stream cut at the cap still gives what came before the cut. */
    private fun inflate(input: InputStream): ByteArray? {
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(8 * 1024)
        try {
            input.use {
                while (true) {
                    val n = it.read(chunk)
                    if (n < 0) break
                    out.write(chunk, 0, n)
                }
            }
        } catch (e: Exception) {
            if (out.size() == 0) return null
        }
        return out.toByteArray()
    }

    private fun charsetOf(contentType: String?): Charset {
        val name = contentType?.let { Regex("charset=\"?([^;\"\\s]+)", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1) }
        return try {
            if (name != null) Charset.forName(name) else Charsets.UTF_8
        } catch (e: Exception) {
            Charsets.UTF_8
        }
    }
}
