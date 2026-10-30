package io.zharp.android.internal

import io.opentelemetry.context.Context
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.logs.LogRecordProcessor
import io.opentelemetry.sdk.logs.ReadWriteLogRecord
import io.opentelemetry.sdk.trace.data.DelegatingSpanData
import io.opentelemetry.sdk.trace.data.EventData
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SpanExporter
import java.io.InputStream
import java.util.Properties

/**
 * The build's ProGuard/R8 UUID, which Zharp's Gradle plugin (`io.zharp.android-gradle`) writes
 * into the app as the asset [ASSET] and uploads with that build's `mapping.txt`. Every error then
 * says which mapping turns its obfuscated frames back into the original classes, methods and lines.
 */
internal object BuildIds {
    const val ASSET = "zharp-debug-meta.properties"
    const val PROGUARD_UUID_PROPERTY = "io.zharp.proguard.uuid"

    /** The UUID from the asset's text, or null when there's none (no plugin, or not minified). */
    fun proguardUuid(asset: InputStream): String? {
        val properties = Properties()
        asset.use { properties.load(it) }
        return properties.getProperty(PROGUARD_UUID_PROPERTY)?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun isError(has: (io.opentelemetry.api.common.AttributeKey<String>) -> Boolean): Boolean =
        has(Keys.EXCEPTION_TYPE) || has(Keys.EXCEPTION_STACKTRACE)
}

/** `zharp.proguard_uuid` on every log record that is an error (caught errors, crashes, ANRs, error logs). */
internal class BuildIdLogProcessor(private val proguardUuid: String) : LogRecordProcessor {
    override fun onEmit(context: Context, logRecord: ReadWriteLogRecord) {
        guarded(Unit, "Stamping an error's build ID") {
            if (BuildIds.isError { logRecord.getAttribute(it) != null }) {
                logRecord.setAttribute(Keys.PROGUARD_UUID, proguardUuid)
            }
        }
    }
}

/**
 * `zharp.proguard_uuid` on every `exception` event, however it was recorded: Zharp.captureError
 * or the app's own `span.recordException(…)` through [io.zharp.android.Zharp.openTelemetry].
 */
internal class BuildIdSpanExporter(private val proguardUuid: String, private val next: SpanExporter) : SpanExporter {
    override fun export(spans: Collection<SpanData>): CompletableResultCode =
        next.export(spans.map { span -> if (span.events.any { it.name == EXCEPTION_EVENT }) Stamped(span) else span })

    override fun flush(): CompletableResultCode = next.flush()
    override fun shutdown(): CompletableResultCode = next.shutdown()

    private inner class Stamped(span: SpanData) : DelegatingSpanData(span) {
        private val events: List<EventData> = span.events.map { event ->
            if (event.name != EXCEPTION_EVENT) {
                event
            } else {
                val attributes = event.attributes.toBuilder().put(Keys.PROGUARD_UUID, proguardUuid).build()
                EventData.create(event.epochNanos, event.name, attributes, maxOf(event.totalAttributeCount, attributes.size()))
            }
        }

        override fun getEvents(): List<EventData> = events
    }

    private companion object {
        const val EXCEPTION_EVENT = "exception"
    }
}
