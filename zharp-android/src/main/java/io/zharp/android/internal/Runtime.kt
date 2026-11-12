package io.zharp.android.internal

import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.baggage.Baggage
import io.opentelemetry.api.baggage.propagation.W3CBaggagePropagator
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.logs.LoggerProvider
import io.opentelemetry.api.logs.Severity
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.api.trace.TracerProvider
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.context.Context
import io.opentelemetry.context.propagation.ContextPropagators
import io.opentelemetry.context.propagation.TextMapPropagator
import io.opentelemetry.sdk.common.Clock
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.logs.SdkLoggerProvider
import io.opentelemetry.sdk.logs.export.BatchLogRecordProcessor
import io.opentelemetry.sdk.logs.export.SimpleLogRecordProcessor
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor
import io.opentelemetry.sdk.trace.export.SpanExporter
import io.opentelemetry.sdk.trace.samplers.Sampler
import io.zharp.android.BuildConfig
import java.io.File
import java.util.concurrent.TimeUnit

/** Everything init() settled, with defaults applied. */
internal class Settings(
    val key: String,
    val service: String,
    val environment: String?,
    val release: String?,
    val endpoint: String?,
    val tracingOrigins: List<String>,
    val sampleRate: Double,
    val scheduleDelayMillis: Long = 5_000,
    val maxBatchSize: Int = 100,
    val maxQueueSize: Int = 1_000,
    /** false: logs only. No spans are made or sent and no trace headers are added. */
    val tracing: Boolean = true,
    /** The R8/ProGuard mapping's UUID from the Gradle plugin; on every error when set. */
    val proguardUuid: String? = null,
    /** `captureBodies`, or null when it is off. */
    val bodies: BodySettings? = null,
)

/**
 * One running SDK: OpenTelemetry's tracer and logger providers, configured the Zharp way.
 * Nothing Android-specific lives here, so the whole pipeline runs in a plain JVM test.
 */
internal class Runtime(
    val settings: Settings,
    deviceAttributes: Attributes,
    store: KeyValueStore,
    outboxDir: File,
    val user: UserContext,
    transport: Transport = Transport(settings.endpoint ?: Transport.DEFAULT_ENDPOINT, settings.key, USER_AGENT),
) {
    val session = SessionManager(store)
    val origins = TracingOrigins(settings.tracingOrigins)

    /** Which calls have their bodies captured: `captureBodies.urls`, else the tracing origins, else all. */
    val bodyUrls: TracingOrigins? = settings.bodies?.let { b ->
        val list = b.urls.ifEmpty { settings.tracingOrigins }
        if (list.isEmpty()) null else TracingOrigins(list)
    }
    val sender = Sender(transport, Outbox(outboxDir))

    val resource: Resource = Resource.create(
        Attributes.builder()
            .putAll(deviceAttributes)
            .put(Keys.SERVICE_NAME, settings.service)
            .apply { settings.release?.let { put(Keys.SERVICE_VERSION, it) } }
            .apply { settings.environment?.let { put(Keys.ENVIRONMENT, it) } }
            .put(Keys.SDK_NAME, "zharp")
            .put(Keys.SDK_LANGUAGE, "java")
            .put(Keys.SDK_VERSION, VERSION)
            .build(),
    )

    private val stamp = ContextStamp(session, user)

    /** Null when tracing is off (logs only): nothing makes spans, so nothing sends them. */
    val tracerProvider: SdkTracerProvider? = if (!settings.tracing) null else SdkTracerProvider.builder()
        .setResource(resource)
        .setSampler(Sampler.parentBased(Sampler.traceIdRatioBased(settings.sampleRate.coerceIn(0.0, 1.0))))
        .addSpanProcessor(ContextSpanProcessor(stamp))
        .addSpanProcessor(
            BatchSpanProcessor.builder(withBuildId(ZharpSpanExporter(sender)))
                .setScheduleDelay(settings.scheduleDelayMillis, TimeUnit.MILLISECONDS)
                .setMaxExportBatchSize(settings.maxBatchSize)
                .setMaxQueueSize(settings.maxQueueSize)
                .setExporterTimeout(60, TimeUnit.SECONDS)
                .build(),
        )
        .build()

    val loggerProvider: SdkLoggerProvider = SdkLoggerProvider.builder()
        .setResource(resource)
        .addLogRecordProcessor(ContextLogProcessor(stamp))
        .apply { settings.proguardUuid?.let { addLogRecordProcessor(BuildIdLogProcessor(it)) } }
        .addLogRecordProcessor(
            BatchLogRecordProcessor.builder(ZharpLogExporter(sender))
                .setScheduleDelay(settings.scheduleDelayMillis, TimeUnit.MILLISECONDS)
                .setMaxExportBatchSize(settings.maxBatchSize)
                .setMaxQueueSize(settings.maxQueueSize)
                .setExporterTimeout(60, TimeUnit.SECONDS)
                .build(),
        )
        .build()

    /** Writes straight to disk, synchronously: for the crash, while the process still lives. */
    private val crashLoggerProvider: SdkLoggerProvider = SdkLoggerProvider.builder()
        .setResource(resource)
        .addLogRecordProcessor(ContextLogProcessor(stamp))
        .apply { settings.proguardUuid?.let { addLogRecordProcessor(BuildIdLogProcessor(it)) } }
        .addLogRecordProcessor(SimpleLogRecordProcessor.create(CrashLogExporter(sender)))
        .build()

    val tracer: Tracer = tracerProvider?.tracerBuilder(SCOPE)?.setInstrumentationVersion(VERSION)?.build()
        ?: TracerProvider.noop().get(SCOPE)
    private val logger = loggerProvider.loggerBuilder(SCOPE).setInstrumentationVersion(VERSION).build()
    private val crashLogger = crashLoggerProvider.loggerBuilder(SCOPE).setInstrumentationVersion(VERSION).build()

    val openTelemetry: OpenTelemetry = ZharpOpenTelemetry(tracerProvider ?: TracerProvider.noop(), loggerProvider)

    private fun withBuildId(exporter: SpanExporter): SpanExporter =
        settings.proguardUuid?.let { BuildIdSpanExporter(it, exporter) } ?: exporter

    /** Send what earlier runs left in the outbox (a crash, an offline spell). */
    fun start() {
        sender.drainLater()
    }

    fun log(severity: Severity, message: String, attributes: Map<String, Any?>?, error: Throwable?) {
        val all = if (error != null) Errors.attributes(error, handled = true) else Attributes.builder()
        all.putAll(attributes)
        logger.logRecordBuilder()
            .setTimestamp(Clock.getDefault().now(), TimeUnit.NANOSECONDS)
            .setSeverity(severity)
            .setSeverityText(severityText(severity))
            .setBody(message)
            .setAllAttributes(all.build())
            .emit()
    }

    /**
     * A caught error, reported once: as an `exception` event on the active span when there is
     * a recorded one (so it shows in its trace), otherwise as an ERROR log record.
     */
    fun captureError(error: Throwable, attributes: Map<String, Any?>?) {
        val span = Span.current()
        if (span.isRecording) {
            span.recordException(error, Attributes.builder().putAll(attributes).put(Keys.HANDLED, "true").build())
            span.setStatus(StatusCode.ERROR, error.javaClass.name)
        } else {
            log(Severity.ERROR, error.message ?: error.toString(), attributes, error)
        }
    }

    /**
     * An uncaught exception: a FATAL log record written to disk at once, on the crashing thread;
     * then a short, bounded try at sending it and what was queued. Whatever didn't make it goes
     * on the next launch.
     */
    fun reportCrash(thread: Thread, error: Throwable) {
        session.save()
        crashLogger.logRecordBuilder()
            .setTimestamp(Clock.getDefault().now(), TimeUnit.NANOSECONDS)
            .setSeverity(Severity.FATAL)
            .setSeverityText("FATAL")
            .setBody(error.toString())
            .setAllAttributes(
                Errors.attributes(error, handled = false)
                    .put(Keys.ERROR_KIND, "crash")
                    .put(Keys.THREAD_NAME, thread.name)
                    .build(),
            )
            .emit()
        sender.drainWithin(CRASH_SEND_MILLIS)
        flush().join(CRASH_FLUSH_MILLIS, TimeUnit.MILLISECONDS)
    }

    /** The main thread didn't answer for [blockedMillis]: an error with the main thread's stack. */
    fun reportAnr(frames: Array<StackTraceElement>, blockedMillis: Long) {
        val message = "The main thread was blocked for ${blockedMillis / 1000.0} s"
        logger.logRecordBuilder()
            .setTimestamp(Clock.getDefault().now(), TimeUnit.NANOSECONDS)
            .setSeverity(Severity.ERROR)
            .setSeverityText("ERROR")
            .setBody("Application Not Responding: $message")
            .setAllAttributes(
                Attributes.builder()
                    .put(Keys.EXCEPTION_TYPE, ANR_TYPE)
                    .put(Keys.EXCEPTION_MESSAGE, message)
                    .put(Keys.EXCEPTION_STACKTRACE, Errors.stackTrace(ANR_TYPE, message, frames))
                    .put(Keys.HANDLED, "false")
                    .put(Keys.ERROR_KIND, "anr")
                    .put(Keys.THREAD_NAME, "main")
                    .build(),
            )
            .emit()
        flush()
    }

    /** A root span for one screen shown: from when it was created or brought back to its first frame. */
    fun screenView(name: String, type: String, startNanos: Long, endNanos: Long) {
        user.screen = name
        if (!settings.tracing) return
        tracer.spanBuilder("screen $name")
            .setNoParent()
            .setSpanKind(SpanKind.INTERNAL)
            .setStartTimestamp(startNanos, TimeUnit.NANOSECONDS)
            .setAttribute(Keys.SCREEN_NAME, name)
            .setAttribute(Keys.SCREEN_TYPE, type)
            .startSpan()
            .end(maxOf(endNanos, startNanos), TimeUnit.NANOSECONDS)
    }

    /** The baggage sent with traceparent: who and which session, for the backend's spans and logs. */
    fun baggage(): Baggage {
        val builder = Baggage.builder().put(Keys.SESSION_ID.key, session.current())
        user.user?.id?.let { builder.put(Keys.USER_ID.key, it) }
        return builder.build()
    }

    fun flush(): CompletableResultCode =
        CompletableResultCode.ofAll(listOfNotNull(tracerProvider?.forceFlush(), loggerProvider.forceFlush()))

    fun shutdown(): CompletableResultCode {
        val done = CompletableResultCode.ofAll(listOfNotNull(tracerProvider?.shutdown(), loggerProvider.shutdown(), crashLoggerProvider.shutdown()))
        done.whenComplete { sender.shutdown() }
        return done
    }

    private class ZharpOpenTelemetry(
        private val tracers: TracerProvider,
        private val loggers: LoggerProvider,
    ) : OpenTelemetry {
        override fun getTracerProvider(): TracerProvider = tracers
        override fun getLogsBridge(): LoggerProvider = loggers
        override fun getPropagators(): ContextPropagators = PROPAGATORS
    }

    companion object {
        val VERSION: String = BuildConfig.VERSION_NAME
        val USER_AGENT: String = "zharp-android/$VERSION"
        const val SCOPE: String = "io.zharp.android"
        const val ANR_TYPE: String = "ApplicationNotResponding"
        private const val CRASH_SEND_MILLIS = 2_000L
        private const val CRASH_FLUSH_MILLIS = 1_000L

        val PROPAGATORS: ContextPropagators = ContextPropagators.create(
            TextMapPropagator.composite(W3CTraceContextPropagator.getInstance(), W3CBaggagePropagator.getInstance()),
        )

        fun severityText(severity: Severity): String = when (severity) {
            Severity.FATAL, Severity.FATAL2, Severity.FATAL3, Severity.FATAL4 -> "FATAL"
            Severity.ERROR, Severity.ERROR2, Severity.ERROR3, Severity.ERROR4 -> "ERROR"
            Severity.WARN, Severity.WARN2, Severity.WARN3, Severity.WARN4 -> "WARN"
            Severity.INFO, Severity.INFO2, Severity.INFO3, Severity.INFO4 -> "INFO"
            Severity.DEBUG, Severity.DEBUG2, Severity.DEBUG3, Severity.DEBUG4 -> "DEBUG"
            else -> "TRACE"
        }

        /** A context for injecting headers: the span plus [baggage]. */
        fun contextFor(span: Span, baggage: Baggage): Context = Context.current().with(span).with(baggage)
    }
}
