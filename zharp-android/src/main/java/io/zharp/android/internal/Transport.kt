package io.zharp.android.internal

import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

internal enum class Signal(val path: String) {
    TRACES("/v1/traces"),
    LOGS("/v1/logs"),
}

internal enum class Outcome {
    /** Zharp has it. */
    DELIVERED,

    /** Refused for good (bad payload, rejected key): sending it again won't help. */
    DROPPED,

    /** The network or the server failed throughout: keep it and try later. */
    FAILED,
}

/**
 * POSTs gzipped OTLP protobuf to `{endpoint}/v1/traces` and `/v1/logs`, on the calling thread
 * (always one of the SDK's own). Status codes are read the OTLP way: 2xx delivered; 429, 408 and
 * 5xx and network errors retried with backoff; 401/403 mean a bad key, which is said once in
 * Logcat and then nothing is sent for an hour; any other 4xx is dropped.
 */
internal open class Transport(
    endpoint: String,
    private val key: String,
    private val userAgent: String,
    /** Waits before the 2nd, 3rd… attempt; its length + 1 is the number of attempts. */
    private val retryDelaysMillis: LongArray = longArrayOf(1_000, 4_000),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val base = normalizeEndpoint(endpoint)

    private val keyState = Any()
    private var keyRejected = false
    private var retryKeyAt = 0L

    fun send(signal: Signal, gzipped: ByteArray, attempts: Int = retryDelaysMillis.size + 1, timeoutMillis: Int = TIMEOUT_MILLIS): Outcome {
        synchronized(keyState) {
            if (keyRejected && clock() < retryKeyAt) return Outcome.DROPPED
        }
        for (attempt in 0 until attempts) {
            var waitMillis = if (attempt < retryDelaysMillis.size) retryDelaysMillis[attempt] else retryDelaysMillis.lastOrNull() ?: 0L
            try {
                val response = post(signal, gzipped, timeoutMillis)
                when {
                    response.status in 200..299 -> {
                        keyAccepted()
                        return Outcome.DELIVERED
                    }
                    response.status == 401 || response.status == 403 -> {
                        keyRejected(response.status)
                        return Outcome.DROPPED
                    }
                    response.status == 429 || response.status == 408 || response.status >= 500 -> {
                        response.retryAfterMillis?.let { waitMillis = minOf(it, MAX_RETRY_AFTER_MILLIS) }
                    }
                    else -> {
                        Diagnostics.debug("Zharp refused a batch of ${signal.name.lowercase()} (HTTP ${response.status}); dropped")
                        return Outcome.DROPPED
                    }
                }
            } catch (error: IOException) {
                Diagnostics.debug("Sending ${signal.name.lowercase()} failed: $error")
            }
            if (attempt < attempts - 1) {
                try {
                    Thread.sleep(waitMillis)
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return Outcome.FAILED
                }
            }
        }
        return Outcome.FAILED
    }

    private fun keyRejected(status: Int) {
        val first: Boolean
        synchronized(keyState) {
            first = !keyRejected
            keyRejected = true
            retryKeyAt = clock() + REJECTED_KEY_BACKOFF_MILLIS
        }
        if (first) Diagnostics.warn("Zharp rejected the key (HTTP $status), so nothing is being sent. Check the key in Zharp.init.")
    }

    private fun keyAccepted() {
        val was: Boolean
        synchronized(keyState) {
            was = keyRejected
            keyRejected = false
        }
        if (was) Diagnostics.info("Zharp accepted the key again; sending resumed.")
    }

    class Response(val status: Int, val retryAfterMillis: Long?)

    /** One POST. Open for tests. */
    @Throws(IOException::class)
    open fun post(signal: Signal, gzipped: ByteArray, timeoutMillis: Int): Response {
        val connection = URL(base + signal.path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = timeoutMillis
            connection.readTimeout = timeoutMillis
            connection.doOutput = true
            connection.useCaches = false
            connection.setFixedLengthStreamingMode(gzipped.size)
            connection.setRequestProperty("Content-Type", "application/x-protobuf")
            connection.setRequestProperty("Content-Encoding", "gzip")
            // The key goes in a header, never the URL: URLs end up in proxy logs.
            connection.setRequestProperty("Authorization", "Bearer $key")
            connection.setRequestProperty("User-Agent", userAgent)
            connection.outputStream.use { it.write(gzipped) }
            val status = connection.responseCode
            drain(if (status >= 400) connection.errorStream else connection.inputStream)
            val retryAfter = connection.getHeaderField("Retry-After")?.trim()?.toLongOrNull()?.let { it * 1000 }
            return Response(status, retryAfter)
        } finally {
            connection.disconnect()
        }
    }

    private fun drain(input: InputStream?) {
        if (input == null) return
        try {
            input.use { stream ->
                val buffer = ByteArray(1024)
                while (stream.read(buffer) != -1) {
                    // discard: reading it out lets the connection be reused
                }
            }
        } catch (ignored: IOException) {
        }
    }

    companion object {
        const val DEFAULT_ENDPOINT: String = "https://api.zharp.io/api/v1/otlp"
        const val TIMEOUT_MILLIS: Int = 15_000
        const val REJECTED_KEY_BACKOFF_MILLIS: Long = 60 * 60 * 1000L
        private const val MAX_RETRY_AFTER_MILLIS = 30_000L

        fun normalizeEndpoint(endpoint: String?): String {
            var value = endpoint?.trim().orEmpty().ifEmpty { DEFAULT_ENDPOINT }
            while (value.endsWith("/")) value = value.dropLast(1)
            // Someone pasting a signal's full URL still gets both signals.
            for (signal in Signal.values()) if (value.endsWith(signal.path)) value = value.dropLast(signal.path.length)
            return value
        }
    }
}
