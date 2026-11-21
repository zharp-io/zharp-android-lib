package io.zharp.android.internal

import java.util.Locale

/**
 * Request and response headers on HTTP spans, as OpenTelemetry names them
 * (`http.request.header.user-agent`, `http.response.header.x-request-id`), so a failed call shows
 * what was sent and what came back — and what wasn't sent (no `authorization`). Secret headers
 * are recorded as `[Filtered]`: sent, but the value never leaves the device. The same rules as
 * every other Zharp SDK (the reference is @zharp/internal/headers in the JavaScript SDK).
 */
internal class HeaderSettings(
    /** More header names to treat as secret. */
    val redact: List<String> = emptyList(),
)

internal object Headers {
    /** Secret whatever the key rules say. */
    private val SECRET = setOf(
        "authorization", "proxy-authorization", "cookie", "set-cookie", "x-api-key", "x-auth-token", "x-csrf-token", "x-xsrf-token",
    )

    /** Per side, so a call stuffed with headers can't fill the span. */
    private const val MAX_HEADERS = 40
    private const val MAX_VALUE = 512

    /** `http.<side>.header.<name>` attributes: secrets filtered, long values cut, repeats joined. */
    fun attributes(side: String, headers: List<Pair<String, String>>, settings: HeaderSettings): Map<String, String> {
        val joined = LinkedHashMap<String, MutableList<String>>()
        for ((rawName, value) in headers) {
            val name = rawName.lowercase(Locale.ROOT)
            if (name !in joined && joined.size >= MAX_HEADERS) continue
            joined.getOrPut(name) { mutableListOf() } += value
        }
        val out = LinkedHashMap<String, String>()
        for ((name, values) in joined) {
            val text = if (name in SECRET || Bodies.isSecretKey(name, settings.redact)) {
                Bodies.FILTERED
            } else {
                values.joinToString(", ").let { if (it.length > MAX_VALUE) it.take(MAX_VALUE) + "…" else it }
            }
            out["http.$side.header.$name"] = text
        }
        return out
    }
}
