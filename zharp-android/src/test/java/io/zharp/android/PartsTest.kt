package io.zharp.android

import io.opentelemetry.exporter.internal.otlp.logs.LogsRequestMarshaler
import io.opentelemetry.exporter.internal.otlp.traces.TraceRequestMarshaler
import io.zharp.android.internal.AndroidPlatform
import io.zharp.android.internal.MemoryStore
import io.zharp.android.internal.Outbox
import io.zharp.android.internal.Outcome
import io.zharp.android.internal.Sender
import io.zharp.android.internal.SessionManager
import io.zharp.android.internal.Signal
import io.zharp.android.internal.TracingOrigins
import io.zharp.android.internal.Transport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class TracingOriginsTest {
    private fun matches(entries: List<String>, url: String): Boolean {
        val u = java.net.URI(url)
        val port = if (u.port != -1) u.port else if (u.scheme == "https") 443 else 80
        return TracingOrigins(entries).matches(url, u.host, port)
    }

    @Test
    fun hostsPortsWildcardsAndPrefixes() {
        assertTrue(matches(listOf("api.example.com"), "https://api.example.com/v1/orders"))
        assertTrue(matches(listOf("API.example.com"), "http://api.example.com:8080/"))
        assertFalse(matches(listOf("api.example.com"), "https://cdn.example.com/"))
        assertFalse(matches(listOf("example.com"), "https://evil-example.com/"))

        assertTrue(matches(listOf("api.example.com:8443"), "https://api.example.com:8443/x"))
        assertFalse(matches(listOf("api.example.com:8443"), "https://api.example.com/x"))

        assertTrue(matches(listOf("*.example.com"), "https://a.b.example.com/"))
        assertTrue(matches(listOf("*.example.com"), "https://example.com/"))
        assertFalse(matches(listOf("*.example.com"), "https://example.com.evil.net/"))

        assertTrue(matches(listOf("https://example.com/api"), "https://example.com/api/orders"))
        assertFalse(matches(listOf("https://example.com"), "https://example.com.evil.net/"))
        assertFalse(matches(listOf("https://example.com/api"), "http://example.com/api"))
    }

    @Test
    fun nothingByDefault() {
        assertTrue(TracingOrigins(emptyList()).isEmpty)
        assertFalse(matches(emptyList(), "https://api.example.com/"))
    }
}

class SessionTest {
    private var now = 1_000_000L
    private val store = MemoryStore()
    private fun manager() = SessionManager(store, { now })

    @Test
    fun newSessionAfterThirtyMinutesInTheBackground() {
        val sessions = manager()
        val first = sessions.current()
        assertEquals(32, first.length)

        sessions.onBackground()
        now += 29 * 60_000
        sessions.onForeground()
        assertEquals(first, sessions.current())

        sessions.onBackground()
        now += 30 * 60_000
        sessions.onForeground()
        assertNotEquals(first, sessions.current())
    }

    @Test
    fun backgroundWorkAfterTheTimeoutAlsoGetsANewSession() {
        val sessions = manager()
        val first = sessions.current()
        sessions.onBackground()
        now += 31 * 60_000
        assertNotEquals(first, sessions.current())
    }

    @Test
    fun aProcessRestartedSoonKeepsTheSessionALaterOneDoesNot() {
        val first = manager().apply { onBackground() }.current()
        now += 5 * 60_000
        assertEquals(first, manager().current())
        now += 31 * 60_000
        assertNotEquals(first, manager().current())
    }

    @Test
    fun rotate() {
        val sessions = manager()
        val first = sessions.current()
        sessions.rotate()
        assertNotEquals(first, sessions.current())
    }

    @Test
    fun deviceIdIsStableForTheInstall() {
        val id = AndroidPlatform.deviceId(store)
        assertEquals(32, id.length)
        assertEquals(id, AndroidPlatform.deviceId(store))
        assertNotEquals(id, AndroidPlatform.deviceId(MemoryStore()))
    }

    @Test
    fun modelName() {
        assertEquals("Google Pixel 8", AndroidPlatform.modelName("Google", "Pixel 8"))
        assertEquals("Samsung SM-S911B", AndroidPlatform.modelName("samsung", "SM-S911B"))
        assertEquals("OnePlus 12", AndroidPlatform.modelName("OnePlus", "OnePlus 12"))
    }
}

class TransportTest {
    @get:Rule
    val folder = TemporaryFolder()

    /** A transport whose answers are scripted; counts what it sent. */
    private class Scripted(vararg answers: Int) :
        Transport("http://unused", "k", "test", longArrayOf(1, 1), { 0L }) {
        val queue = ArrayDeque(answers.toList())
        val sent = AtomicInteger()
        override fun post(signal: Signal, gzipped: ByteArray, timeoutMillis: Int): Response {
            sent.incrementAndGet()
            val status = queue.removeFirstOrNull() ?: 200
            if (status == -1) throw IOException("offline")
            return Response(status, null)
        }
    }

    @Test
    fun retriesServerErrorsAndNetworkFailures() {
        val t = Scripted(503, -1, 200)
        assertEquals(Outcome.DELIVERED, t.send(Signal.LOGS, ByteArray(1)))
        assertEquals(3, t.sent.get())
    }

    @Test
    fun givesUpAfterThreeAttempts() {
        val t = Scripted(503, 429, 500)
        assertEquals(Outcome.FAILED, t.send(Signal.LOGS, ByteArray(1)))
        assertEquals(3, t.sent.get())
    }

    @Test
    fun badPayloadIsDroppedNotRetried() {
        val t = Scripted(400)
        assertEquals(Outcome.DROPPED, t.send(Signal.TRACES, ByteArray(1)))
        assertEquals(1, t.sent.get())
    }

    @Test
    fun aRejectedKeyStopsSendingForAnHour() {
        val t = Scripted(401)
        assertEquals(Outcome.DROPPED, t.send(Signal.TRACES, ByteArray(1)))
        assertEquals(Outcome.DROPPED, t.send(Signal.TRACES, ByteArray(1)))
        assertEquals(1, t.sent.get())
    }

    @Test
    fun endpointNormalised() {
        assertEquals(Transport.DEFAULT_ENDPOINT, Transport.normalizeEndpoint(null))
        assertEquals("http://localhost:3000/api/v1/otlp", Transport.normalizeEndpoint(" http://localhost:3000/api/v1/otlp/ "))
        assertEquals("http://localhost:3000/api/v1/otlp", Transport.normalizeEndpoint("http://localhost:3000/api/v1/otlp/v1/traces"))
    }

    @Test
    fun failedBatchesWaitOnDiskAndGoOldestFirst() {
        val outbox = Outbox(folder.newFolder())
        val t = Scripted(-1, -1, -1, -1, -1, -1)
        val sender = Sender(t, outbox)
        val result = sender.export(Signal.LOGS) { LogsRequestMarshaler.create(emptyList()) }
        assertFalse(result.isSuccess)
        sender.export(Signal.TRACES) { TraceRequestMarshaler.create(emptyList()) }
        assertEquals(2, outbox.pending().size)

        assertTrue(sender.drain(1_000) == true)
        assertTrue(outbox.pending().isEmpty())
    }

    @Test
    fun outboxIsCapped() {
        var now = 10_000_000L
        val outbox = Outbox(folder.newFolder(), maxFiles = 3, maxBytes = 1_000_000, maxAgeMillis = 1_000, clock = { now })
        repeat(5) { outbox.save(Signal.LOGS, ByteArray(10)) }
        assertEquals(3, outbox.pending().size)
        now += 2_000
        assertTrue(outbox.pending().isEmpty())
    }
}

