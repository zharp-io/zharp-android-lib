package io.zharp.android.internal

import java.util.Locale

/**
 * Which requests get `traceparent` and `baggage`: only those to the app's own backends, listed in
 * `tracingOrigins`. Never third parties by default — the headers would leak the session and
 * user ID, and some APIs reject unknown headers (CORS-style allowlists, signed requests).
 *
 * An entry is matched against the request as:
 * - `api.example.com` — that host, any port and scheme
 * - `api.example.com:8443` — that host and port
 * - `*.example.com` — example.com and any subdomain of it
 * - `https://api.example.com/v2` — URLs starting with exactly this
 */
internal class TracingOrigins(entries: List<String>) {
    private val prefixes = mutableListOf<String>()
    private val hostPorts = mutableSetOf<String>()
    private val hosts = mutableSetOf<String>()
    private val suffixes = mutableListOf<String>()

    init {
        for (raw in entries) {
            val entry = raw.trim().lowercase(Locale.ROOT)
            when {
                entry.isEmpty() -> Unit
                entry.contains("://") -> prefixes += entry
                entry.startsWith("*.") -> suffixes += entry.substring(2)
                entry.startsWith(".") -> suffixes += entry.substring(1)
                entry.contains(':') -> hostPorts += entry
                else -> hosts += entry.trimEnd('/')
            }
        }
    }

    val isEmpty: Boolean get() = prefixes.isEmpty() && hostPorts.isEmpty() && hosts.isEmpty() && suffixes.isEmpty()

    fun matches(url: String, host: String, port: Int): Boolean {
        val h = host.lowercase(Locale.ROOT)
        if (h in hosts) return true
        if ("$h:$port" in hostPorts) return true
        if (suffixes.any { h == it || h.endsWith(".$it") }) return true
        if (prefixes.isNotEmpty()) {
            val lower = url.lowercase(Locale.ROOT)
            // "https://api.example.com" must not match "https://api.example.com.evil.net".
            if (prefixes.any { lower.startsWith(it) && (it.endsWith("/") || lower.length == it.length || lower[it.length] in "/?#:") }) return true
        }
        return false
    }
}
