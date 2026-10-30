package io.zharp.android.internal

import io.opentelemetry.exporter.internal.marshal.Marshaler
import io.opentelemetry.exporter.internal.otlp.logs.LogsRequestMarshaler
import io.opentelemetry.exporter.internal.otlp.traces.TraceRequestMarshaler
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.logs.data.LogRecordData
import io.opentelemetry.sdk.logs.export.LogRecordExporter
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SpanExporter
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.GZIPOutputStream

/**
 * Sends what OpenTelemetry's batch processors hand over: encoded with OpenTelemetry's own OTLP
 * protobuf encoder, gzipped, POSTed by [Transport]. A batch that fails after its retries goes to
 * the [Outbox]; each delivered batch then lets the outbox empty itself in the background.
 */
internal class Sender(
    private val transport: Transport,
    private val outbox: Outbox,
) {
    private val draining = AtomicBoolean()
    private val background: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "zharp-outbox").apply { isDaemon = true }
    }

    fun export(signal: Signal, marshal: () -> Marshaler): CompletableResultCode = guarded(CompletableResultCode.ofFailure(), "Exporting ${signal.name}") {
        val body = gzip(marshal())
        when (transport.send(signal, body)) {
            Outcome.DELIVERED -> {
                drainLater()
                CompletableResultCode.ofSuccess()
            }
            Outcome.DROPPED -> CompletableResultCode.ofSuccess()
            Outcome.FAILED -> {
                outbox.save(signal, body)
                CompletableResultCode.ofFailure()
            }
        }
    }

    /** For a crash: encode and save to disk first, synchronously; sending is tried after. */
    fun save(signal: Signal, marshal: () -> Marshaler) {
        guarded(Unit, "Saving a crash") { outbox.save(signal, gzip(marshal())) }
    }

    /** Send what the outbox holds, in the background, unless that's already happening. */
    fun drainLater() {
        if (outbox.isEmpty()) return
        guarded(Unit, "Scheduling the outbox") { background.execute { drain(Transport.TIMEOUT_MILLIS) } }
    }

    /**
     * Send the outbox's batches oldest first, on this thread; stops at the first failure.
     * True when it emptied, false when sending failed, null when another drain is running.
     */
    fun drain(timeoutMillis: Int, attempts: Int = 1): Boolean? {
        if (!draining.compareAndSet(false, true)) return null
        try {
            for (file in outbox.pending()) {
                val signal = outbox.signalOf(file) ?: continue
                val body = guarded(null, "Reading a saved batch") { file.readBytes() }
                if (body == null) {
                    outbox.delete(file)
                    continue
                }
                when (transport.send(signal, body, attempts, timeoutMillis)) {
                    Outcome.DELIVERED, Outcome.DROPPED -> outbox.delete(file)
                    Outcome.FAILED -> return false
                }
            }
            return true
        } catch (error: Throwable) {
            return false
        } finally {
            draining.set(false)
        }
    }

    /**
     * Drain on a fresh thread and wait at most [millis]: for the last moments of a crashing
     * process. If the background drain is mid-way, wait for it and go again.
     */
    fun drainWithin(millis: Long) {
        val deadline = System.currentTimeMillis() + millis
        val thread = Thread({
            while (drain(timeoutMillis = millis.toInt(), attempts = 1) == null && System.currentTimeMillis() < deadline) {
                Thread.sleep(20)
            }
        }, "zharp-crash")
        thread.isDaemon = true
        thread.start()
        try {
            thread.join(millis)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    fun shutdown() {
        background.shutdown()
        guarded(Unit, "Stopping") { background.awaitTermination(1, TimeUnit.SECONDS) }
    }

    private fun gzip(marshaler: Marshaler): ByteArray {
        val bytes = ByteArrayOutputStream(marshaler.binarySerializedSize / 3 + 64)
        GZIPOutputStream(bytes).use { marshaler.writeBinaryTo(it) }
        return bytes.toByteArray()
    }
}

internal class ZharpSpanExporter(private val sender: Sender) : SpanExporter {
    override fun export(spans: Collection<SpanData>): CompletableResultCode =
        sender.export(Signal.TRACES) { TraceRequestMarshaler.create(spans) }

    override fun flush(): CompletableResultCode = CompletableResultCode.ofSuccess()
    override fun shutdown(): CompletableResultCode = CompletableResultCode.ofSuccess()
}

internal class ZharpLogExporter(private val sender: Sender) : LogRecordExporter {
    override fun export(logs: Collection<LogRecordData>): CompletableResultCode =
        sender.export(Signal.LOGS) { LogsRequestMarshaler.create(logs) }

    override fun flush(): CompletableResultCode = CompletableResultCode.ofSuccess()
    override fun shutdown(): CompletableResultCode = CompletableResultCode.ofSuccess()
}

/** A crash's records go straight to disk on the crashing thread, never into a queue that may not drain in time. */
internal class CrashLogExporter(private val sender: Sender) : LogRecordExporter {
    override fun export(logs: Collection<LogRecordData>): CompletableResultCode {
        sender.save(Signal.LOGS) { LogsRequestMarshaler.create(logs) }
        return CompletableResultCode.ofSuccess()
    }

    override fun flush(): CompletableResultCode = CompletableResultCode.ofSuccess()
    override fun shutdown(): CompletableResultCode = CompletableResultCode.ofSuccess()
}
