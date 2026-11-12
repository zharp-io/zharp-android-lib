package io.zharp.android.internal

import java.net.URLDecoder
import java.util.Locale

/**
 * Request and response bodies on HTTP spans (`captureBodies`), and secrets in URLs. The same rules
 * as every other Zharp SDK (the reference is @zharp/internal/bodies in the JavaScript SDK):
 * - field names stay; values under keys that look secret (`password`, `token`, `authorization`,
 *   `card`, …) become `[Filtered]`, and an object under such a key keeps its keys with every value
 *   filtered
 * - an inline image or other long base64 value becomes `[image/png · 24 KB]`, a file in a form upload
 *   `[file photo.png · image/png · 240 KB]`, and a body that is not text `[binary · image/png · 24 KB]`
 *   or `[event stream]`
 * - text is capped at `maxSize` (32 KB by default), with `http.*.body.truncated` set when it was cut
 */
internal class BodySettings(
    val all: Boolean,
    val maxSize: Int,
    val extraKeys: List<String>,
    /** Which requests: `urls` when set, else the tracing origins, else every call through the client. */
    val urls: List<String>,
) {
    /** Raw text read before shortening and capping: inline images shrink to a few words. */
    val rawLimit: Int get() = maxOf(64 * 1024, maxSize * 2)

    /** Whether a request with this outcome keeps its bodies. `status` 0 means no response. */
    fun wanted(status: Int): Boolean = all || status == 0 || status >= 400

    companion object {
        const val DEFAULT_MAX: Int = 32 * 1024
        const val MAX: Int = 128 * 1024

        fun clampSize(size: Int): Int = if (size > 0) minOf(size, MAX) else DEFAULT_MAX
    }
}

internal object Bodies {
    const val FILTERED: String = "[Filtered]"

    const val REQUEST_BODY: String = "http.request.body"
    const val REQUEST_TRUNCATED: String = "http.request.body.truncated"
    const val REQUEST_TYPE: String = "http.request.header.content-type"
    const val RESPONSE_BODY: String = "http.response.body"
    const val RESPONSE_TRUNCATED: String = "http.response.body.truncated"
    const val RESPONSE_TYPE: String = "http.response.header.content-type"

    enum class Side(val body: String, val truncated: String, val type: String) {
        REQUEST(REQUEST_BODY, REQUEST_TRUNCATED, REQUEST_TYPE),
        RESPONSE(RESPONSE_BODY, RESPONSE_TRUNCATED, RESPONSE_TYPE),
    }

    // ── content types ────────────────────────────────────────────────────────

    fun mime(contentType: String?): String = (contentType ?: "").substringBefore(';').trim().lowercase(Locale.ROOT)

    fun isEventStream(contentType: String?): Boolean = mime(contentType) == "text/event-stream"

    fun isMultipart(contentType: String?): Boolean = mime(contentType) == "multipart/form-data"

    private val TEXT_APPLICATION = Regex("^application/(json|xml|x-www-form-urlencoded|graphql|javascript|x-ndjson|ld\\+json)$")

    /** Whether a body of this type is text worth showing. An unknown type is given the benefit of the doubt. */
    fun isTextual(contentType: String?): Boolean {
        val type = mime(contentType)
        if (type.isEmpty()) return true
        if (type == "text/event-stream") return false
        return type.startsWith("text/") || type.endsWith("+json") || type.endsWith("+xml") || TEXT_APPLICATION.matches(type)
    }

    private fun isForm(contentType: String?): Boolean = mime(contentType) == "application/x-www-form-urlencoded"

    /** `240 KB`, `1.4 MB` — rounded as the JavaScript SDK rounds. */
    fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${Math.floor(bytes / 1024.0 + 0.5).toLong()} KB"
        else -> String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0))
    }

    /** How a file in a form upload is shown: `[file photo.png · image/png · 240 KB]`. */
    fun fileLabel(name: String?, type: String?, size: Long?, partial: Boolean = false): String {
        val parts = mutableListOf("file ${if (name.isNullOrEmpty()) "blob" else name}")
        if (!type.isNullOrEmpty()) parts += type
        if (size != null) parts += formatSize(size) + if (partial) "+" else ""
        return parts.joinToString(" · ", "[", "]")
    }

    /** What a body that is not text says instead: `[binary · image/png · 24 KB]`, `[event stream]`. */
    fun describeBody(contentType: String?, size: Long? = null, partial: Boolean = false): String {
        if (isEventStream(contentType)) return "[event stream]"
        val parts = mutableListOf("binary")
        val type = mime(contentType)
        if (type.isNotEmpty()) parts += type
        if (size != null && size > 0) parts += formatSize(size) + if (partial) "+" else ""
        return parts.joinToString(" · ", "[", "]")
    }

    // ── redaction ────────────────────────────────────────────────────────────

    private val CONTAINS = listOf(
        "password", "passwd", "passphrase", "secret", "token", "apikey", "accesskey", "privatekey",
        "authorization", "cookie", "creditcard", "cardnumber", "cvv", "cvc", "ssn", "iban", "sessionid",
        "signature", "credential",
    )
    private val EXACT = setOf("auth", "pin", "pwd", "otp", "card", "pan", "jwt", "sid", "codeverifier", "pwd")

    /** Also secret in a query string, where they are API keys and signed-URL signatures. */
    private val URL_EXACT = setOf("key", "sig", "code", "state", "session")

    private fun normalize(key: String): String = key.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]"), "")

    fun isSecretKey(key: String, extra: List<String> = emptyList()): Boolean {
        val k = normalize(key)
        if (k.isEmpty()) return false
        if (k in EXACT || CONTAINS.any { k.contains(it) }) return true
        return extra.any { normalize(it) == k }
    }

    private val DATA_URI = Regex("^data:([\\w.+-]+/[\\w.+-]+)?(?:;[\\w=.+-]+)*;base64,([A-Za-z0-9+/=\\s]*)$")
    private val BASE64 = Regex("^[A-Za-z0-9+/_-]{256,}={0,2}$")
    private val HAS_DIGIT_OR_SYMBOL = Regex("[0-9+/]")

    /** A long base64 value as a short description; other strings as they are. */
    fun shorten(value: String): String {
        if (value.length < 256) return value
        DATA_URI.matchEntire(value)?.let { m ->
            val type = m.groupValues[1].ifEmpty { "data" }
            return "[$type · ${formatSize(m.groupValues[2].length * 3L / 4)}]"
        }
        if (BASE64.matches(value) && HAS_DIGIT_OR_SYMBOL.containsMatchIn(value)) return "[base64 · ${formatSize(value.length * 3L / 4)}]"
        return value
    }

    /** Every value under `value` filtered, its keys kept. */
    private fun filterAll(value: Any?, depth: Int): Any? = when {
        depth > 20 -> FILTERED
        value == null || value == "" -> value
        value is List<*> -> value.map { filterAll(it, depth + 1) }
        value is Map<*, *> -> LinkedHashMap<String, Any?>().also { out -> value.forEach { (k, v) -> out[k.toString()] = filterAll(v, depth + 1) } }
        else -> FILTERED
    }

    fun redactValue(value: Any?, extra: List<String>, depth: Int = 0): Any? = when {
        value is String -> shorten(value)
        depth > 20 || value == null -> value
        value is List<*> -> value.map { redactValue(it, extra, depth + 1) }
        value is Map<*, *> -> LinkedHashMap<String, Any?>().also { out ->
            value.forEach { (k, v) ->
                val key = k.toString()
                out[key] = if (isSecretKey(key, extra)) filterAll(v, depth + 1) else redactValue(v, extra, depth + 1)
            }
        }
        else -> value
    }

    private val LOOSE_RUN = Regex("\"((?:data:[\\w.+-]+/[\\w.+-]+(?:;[\\w=.+-]+)*;base64,)?[A-Za-z0-9+/_=-]{256,})(\"?)")
    private val LOOSE_PAIR = Regex("(\"([^\"\\\\]{1,100})\"\\s*:\\s*)(\"(?:[^\"\\\\]|\\\\.)*\"?|-?\\d[\\d.eE+-]*|true|false)")

    /** Text that did not parse: secret `"key": value` pairs filtered where they appear, long base64 shortened. */
    fun redactLoose(text: String, extra: List<String>): String {
        val shortened = LOOSE_RUN.replace(text) { m ->
            val run = m.groupValues[1]
            val short = shorten(run)
            when {
                short == run -> m.value
                m.groupValues[2].isNotEmpty() -> "\"$short\""
                else -> "\"${short.dropLast(1)}+]\""
            }
        }
        return LOOSE_PAIR.replace(shortened) { m ->
            if (isSecretKey(m.groupValues[2], extra)) "${m.groupValues[1]}\"$FILTERED\"" else m.value
        }
    }

    private fun decodeComponent(text: String): String? = try {
        URLDecoder.decode(text, "UTF-8")
    } catch (e: Exception) {
        null
    }

    fun redactForm(text: String, extra: List<String>): String = text.split('&').joinToString("&") { pair ->
        val eq = pair.indexOf('=')
        if (eq < 0) return@joinToString pair
        val rawKey = pair.substring(0, eq)
        val key = decodeComponent(rawKey) ?: rawKey
        if (isSecretKey(key, extra)) return@joinToString "$rawKey=$FILTERED"
        val value = pair.substring(eq + 1)
        if (value.length >= 256) {
            val decoded = decodeComponent(value)
            val short = decoded?.let { shorten(it) }
            if (decoded != null && short != decoded) return@joinToString "$rawKey=$short"
        }
        pair
    }

    // ── the attribute value ──────────────────────────────────────────────────

    /**
     * A body as it is sent: redacted, then cut to `maxSize`. `cut` says the text was already cut
     * short where it was read. Null for an empty body. A `multipart/form-data` body is passed as the
     * JSON of its fields (files already described).
     */
    fun formatBody(text: String?, contentType: String?, settings: BodySettings, cut: Boolean = false): Pair<String, Boolean>? {
        if (text == null || text.isBlank()) return null
        var body = text
        if (isForm(contentType)) {
            body = redactForm(body, settings.extraKeys)
        } else {
            val trimmed = body.trim()
            val parsed = if (!cut && (trimmed.startsWith("{") || trimmed.startsWith("["))) Json.parseOrNull(trimmed) else Json.NOT_JSON
            body = if (parsed !== Json.NOT_JSON) Json.write(redactValue(parsed, settings.extraKeys)) else redactLoose(body, settings.extraKeys)
        }
        val truncated = cut || body.length > settings.maxSize
        return (if (body.length > settings.maxSize) body.substring(0, settings.maxSize) else body) to truncated
    }

    /** The span attributes for one side of a request. Empty when there is nothing to show. */
    fun bodyAttributes(side: Side, text: String?, contentType: String?, settings: BodySettings, cut: Boolean = false): Map<String, Any> {
        if (!isTextual(contentType) && !isMultipart(contentType)) return emptyMap()
        val (value, truncated) = formatBody(text, contentType, settings, cut) ?: return emptyMap()
        val out = LinkedHashMap<String, Any>()
        out[side.body] = value
        if (truncated) out[side.truncated] = true
        if (!contentType.isNullOrEmpty()) out[side.type] = contentType
        return out
    }

    /** The attributes for a body that is not text: what it was, not what it said. */
    fun describedAttributes(side: Side, contentType: String?, size: Long? = null, partial: Boolean = false): Map<String, Any> {
        val out = LinkedHashMap<String, Any>()
        out[side.body] = describeBody(contentType, size, partial)
        if (!contentType.isNullOrEmpty()) out[side.type] = contentType
        return out
    }

    // ── URLs ─────────────────────────────────────────────────────────────────

    private val USER_INFO = Regex("^([a-z][a-z0-9+.-]*://)[^/?#@]*@", RegexOption.IGNORE_CASE)

    /** A URL as it is recorded: secret-looking query values `[Filtered]`, `user:password@` dropped. */
    fun redactUrl(url: String, extra: List<String> = emptyList()): String {
        if (url.isEmpty()) return url
        var out = USER_INFO.replace(url, "$1")
        val q = out.indexOf('?')
        if (q < 0) return out
        val hash = out.indexOf('#', q)
        val query = if (hash < 0) out.substring(q + 1) else out.substring(q + 1, hash)
        val redacted = redactQuery(query, extra)
        if (redacted != query) out = out.substring(0, q + 1) + redacted + (if (hash < 0) "" else out.substring(hash))
        return out
    }

    /** A query string (without `?`) with secret-looking values filtered. */
    fun redactQuery(query: String, extra: List<String> = emptyList()): String {
        if (query.isEmpty()) return query
        return query.split('&').joinToString("&") { pair ->
            val eq = pair.indexOf('=')
            if (eq < 0) return@joinToString pair
            val rawKey = pair.substring(0, eq)
            val key = decodeComponent(rawKey) ?: rawKey
            val secret = isSecretKey(key, extra) || normalize(key) in URL_EXACT
            if (secret && eq < pair.length - 1) "$rawKey=$FILTERED" else pair
        }
    }
}

/**
 * Just enough JSON for bodies: parses to [LinkedHashMap] / [List] / [String] / [Boolean] / null /
 * [Json.Number] (kept as written), and writes compactly, the way `JSON.stringify` does.
 */
internal object Json {
    /** A number, kept as it was written. */
    class Number(val text: String) {
        override fun toString(): String = text
    }

    /** Returned by [parseOrNull] when the text is not JSON (null is a valid JSON value). */
    val NOT_JSON: Any = Any()

    fun parseOrNull(text: String): Any? = try {
        val p = Parser(text)
        val value = p.value(0)
        p.skipSpace()
        if (p.pos != text.length) NOT_JSON else value
    } catch (e: Exception) {
        NOT_JSON
    }

    private class Parser(val s: String) {
        var pos = 0

        fun skipSpace() {
            while (pos < s.length && s[pos].let { it == ' ' || it == '\n' || it == '\r' || it == '\t' }) pos++
        }

        fun value(depth: Int): Any? {
            if (depth > 512) throw IllegalStateException("too deep")
            skipSpace()
            if (pos >= s.length) throw IllegalStateException("end")
            return when (val c = s[pos]) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> str()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c in '0'..'9') num() else throw IllegalStateException("unexpected")
            }
        }

        fun literal(word: String, v: Any?): Any? {
            if (!s.startsWith(word, pos)) throw IllegalStateException("literal")
            pos += word.length
            return v
        }

        fun obj(depth: Int): Map<String, Any?> {
            pos++
            val out = LinkedHashMap<String, Any?>()
            skipSpace()
            if (pos < s.length && s[pos] == '}') {
                pos++
                return out
            }
            while (true) {
                skipSpace()
                if (pos >= s.length || s[pos] != '"') throw IllegalStateException("key")
                val key = str()
                skipSpace()
                if (pos >= s.length || s[pos] != ':') throw IllegalStateException("colon")
                pos++
                out[key] = value(depth + 1)
                skipSpace()
                if (pos >= s.length) throw IllegalStateException("end")
                when (s[pos++]) {
                    ',' -> continue
                    '}' -> return out
                    else -> throw IllegalStateException("object")
                }
            }
        }

        fun arr(depth: Int): List<Any?> {
            pos++
            val out = ArrayList<Any?>()
            skipSpace()
            if (pos < s.length && s[pos] == ']') {
                pos++
                return out
            }
            while (true) {
                out += value(depth + 1)
                skipSpace()
                if (pos >= s.length) throw IllegalStateException("end")
                when (s[pos++]) {
                    ',' -> continue
                    ']' -> return out
                    else -> throw IllegalStateException("array")
                }
            }
        }

        fun str(): String {
            pos++
            val out = StringBuilder()
            while (true) {
                if (pos >= s.length) throw IllegalStateException("string")
                val c = s[pos++]
                when {
                    c == '"' -> return out.toString()
                    c == '\\' -> {
                        if (pos >= s.length) throw IllegalStateException("escape")
                        when (val e = s[pos++]) {
                            '"' -> out.append('"')
                            '\\' -> out.append('\\')
                            '/' -> out.append('/')
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                if (pos + 4 > s.length) throw IllegalStateException("unicode")
                                out.append(s.substring(pos, pos + 4).toInt(16).toChar())
                                pos += 4
                            }
                            else -> throw IllegalStateException("escape $e")
                        }
                    }
                    c < ' ' -> throw IllegalStateException("control")
                    else -> out.append(c)
                }
            }
        }

        fun num(): Number {
            val start = pos
            if (s[pos] == '-') pos++
            while (pos < s.length && (s[pos].isDigit() || s[pos] in ".eE+-")) pos++
            val text = s.substring(start, pos)
            text.toDouble() // throws when it is not a number
            return Number(text)
        }
    }

    fun write(value: Any?): String = StringBuilder().also { write(it, value) }.toString()

    private fun write(out: StringBuilder, value: Any?) {
        when (value) {
            null -> out.append("null")
            is String -> quote(out, value)
            is Boolean -> out.append(value)
            is Number -> out.append(value.text)
            is kotlin.Number -> out.append(value)
            is Map<*, *> -> {
                out.append('{')
                var first = true
                for ((k, v) in value) {
                    if (!first) out.append(',')
                    first = false
                    quote(out, k.toString())
                    out.append(':')
                    write(out, v)
                }
                out.append('}')
            }
            is List<*> -> {
                out.append('[')
                value.forEachIndexed { i, v ->
                    if (i > 0) out.append(',')
                    write(out, v)
                }
                out.append(']')
            }
            else -> quote(out, value.toString())
        }
    }

    private fun quote(out: StringBuilder, s: String) {
        out.append('"')
        for (c in s) {
            when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c == '\n' -> out.append("\\n")
                c == '\r' -> out.append("\\r")
                c == '\t' -> out.append("\\t")
                c == '\b' -> out.append("\\b")
                c == '\u000C' -> out.append("\\f")
                c < ' ' -> out.append(String.format(Locale.ROOT, "\\u%04x", c.code))
                else -> out.append(c)
            }
        }
        out.append('"')
    }
}
