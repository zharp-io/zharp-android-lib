package io.zharp.android

import io.opentelemetry.api.common.Attributes
import io.opentelemetry.proto.trace.v1.Span as ProtoSpan
import io.opentelemetry.proto.trace.v1.Status
import io.zharp.android.internal.Keys
import io.zharp.android.internal.MemoryStore
import io.zharp.android.internal.Runtime
import io.zharp.android.internal.Settings
import io.zharp.android.internal.Transport
import io.zharp.android.internal.UserContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.TimeUnit

/** The whole pipeline in a JVM: Zharp's API → OpenTelemetry SDK → OTLP protobuf → a fake Zharp. */
class PipelineTest {
    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var collector: FakeCollector
    private lateinit var runtime: Runtime

    @Before
    fun start() {
        collector = FakeCollector()
        runtime = runtime(collector.endpoint, tracingOrigins = listOf("127.0.0.1"))
        Zharp.startForTests(runtime)
        Zharp.setUser(id = "user-42", email = "ada@example.com")
        Zharp.setTag("plan", "pro")
    }

    @After
    fun stop() {
        Zharp.clearUser()
        Zharp.setTag("plan", null)
        Zharp.user.screen = null
        Zharp.startForTests(null)
        runtime.shutdown().join(5, TimeUnit.SECONDS)
        collector.stop()
    }

    private fun runtime(
        endpoint: String,
        tracingOrigins: List<String> = emptyList(),
        sampleRate: Double = 1.0,
        tracing: Boolean = true,
    ): Runtime {
        val settings = Settings(
            key = "zpk_test",
            service = "shop-android",
            environment = "test",
            release = "2.3.0 (45)",
            endpoint = endpoint,
            tracingOrigins = tracingOrigins,
            sampleRate = sampleRate,
            scheduleDelayMillis = 100,
            tracing = tracing,
        )
        val device = Attributes.builder()
            .put(Keys.OS_NAME, "Android").put(Keys.OS_VERSION, "15")
            .put(Keys.DEVICE_ID, "device-1").put(Keys.DEVICE_MODEL_NAME, "Google Pixel 9")
            .build()
        return Runtime(
            settings, device, MemoryStore(), folder.newFolder(), Zharp.user,
            Transport(endpoint, settings.key, Runtime.USER_AGENT, retryDelaysMillis = longArrayOf(10, 10)),
        )
    }

    @Test
    fun resourceSaysWhoIsSending() {
        Zharp.startSpan("checkout") { }
        Zharp.flush()
        assertTrue(eventually { collector.spans().isNotEmpty() })
        val resource = collector.traceResource()
        assertEquals("shop-android", resource["service.name"])
        assertEquals("2.3.0 (45)", resource["service.version"])
        assertEquals("test", resource["deployment.environment.name"])
        assertEquals("zharp", resource["telemetry.sdk.name"])
        assertEquals("java", resource["telemetry.sdk.language"])
        assertEquals(Zharp.VERSION, resource["telemetry.sdk.version"])
        assertEquals("Android", resource["os.name"])
        assertEquals("device-1", resource["device.id"])

        val request = collector.requests.first()
        assertEquals("Bearer zpk_test", request.headers["authorization"])
        assertEquals("application/x-protobuf", request.headers["content-type"])
        assertEquals("gzip", request.headers["content-encoding"])
    }

    @Test
    fun spansAndLogsCarrySessionUserAndTags() {
        Zharp.startSpan("checkout") { Zharp.log.info("Paying", mapOf("items" to 3)) }
        Zharp.flush()
        assertTrue(eventually { collector.spans().isNotEmpty() && collector.logRecords().isNotEmpty() })

        val span = collector.spans().single()
        val spanAttrs = attrs(span.attributesList)
        assertEquals(Zharp.sessionId, spanAttrs["session.id"])
        assertEquals("user-42", spanAttrs["enduser.id"])
        assertEquals("ada@example.com", spanAttrs["enduser.email"])
        assertEquals("pro", spanAttrs["plan"])

        val log = collector.logRecords().single()
        val logAttrs = attrs(log.attributesList)
        assertEquals("Paying", log.body.stringValue)
        assertEquals(9, log.severityNumberValue)
        assertEquals("INFO", log.severityText)
        assertEquals("3", logAttrs["items"])
        assertEquals(Zharp.sessionId, logAttrs["session.id"])
        assertEquals("user-42", logAttrs["enduser.id"])
        // Logged inside the span: it belongs to that trace.
        assertEquals(hex(span.traceId), hex(log.traceId))
        assertEquals(hex(span.spanId), hex(log.spanId))
    }

    @Test
    fun captureErrorInsideASpanIsAnExceptionEventOnIt() {
        Zharp.startSpan("checkout") { Zharp.captureError(IllegalStateException("Card declined"), mapOf("order" to "A-1")) }
        Zharp.flush()
        assertTrue(eventually { collector.spans().isNotEmpty() })
        val span = collector.spans().single()
        assertEquals(Status.StatusCode.STATUS_CODE_ERROR, span.status.code)
        val event = span.eventsList.single { it.name == "exception" }
        val e = attrs(event.attributesList)
        assertEquals("java.lang.IllegalStateException", e["exception.type"])
        assertEquals("Card declined", e["exception.message"])
        assertTrue(e["exception.stacktrace"]!!.startsWith("java.lang.IllegalStateException: Card declined"))
        assertTrue(e["exception.stacktrace"]!!.contains("\tat io.zharp.android.PipelineTest"))
        assertEquals("true", e["zharp.handled"])
        assertEquals("A-1", e["order"])
        // Exactly once: not also as a log.
        Thread.sleep(300)
        assertTrue(collector.logRecords().none { attrs(it.attributesList).containsKey("exception.type") })
    }

    @Test
    fun captureErrorOutsideASpanIsAnErrorLog() {
        Zharp.captureError(IllegalArgumentException("bad input"))
        Zharp.flush()
        assertTrue(eventually { collector.logRecords().isNotEmpty() })
        val log = collector.logRecords().single()
        val a = attrs(log.attributesList)
        assertEquals(17, log.severityNumberValue)
        assertEquals("java.lang.IllegalArgumentException", a["exception.type"])
        assertEquals("true", a["zharp.handled"])
    }

    @Test
    fun crashIsSavedToDiskFirstThenSent() {
        collector.status.set(503)
        runtime.reportCrash(Thread.currentThread(), RuntimeException("boom"))
        // Zharp was down: the crash waits on disk until sending works again.
        collector.status.set(200)
        collector.requests.clear()
        runtime.sender.drain(1_000)
        val log = collector.logRecords().single()
        val a = attrs(log.attributesList)
        assertEquals(21, log.severityNumberValue)
        assertEquals("java.lang.RuntimeException", a["exception.type"])
        assertEquals("false", a["zharp.handled"])
        assertEquals("crash", a["zharp.error.kind"])
        assertEquals("user-42", a["enduser.id"])
        assertNotNull(a["session.id"])

        // Sent once: nothing left to send again.
        collector.requests.clear()
        runtime.sender.drain(1_000)
        assertTrue(collector.requests.isEmpty())
    }

    @Test
    fun anrIsAnUnhandledError() {
        runtime.reportAnr(Thread.currentThread().stackTrace, 5_000)
        assertTrue(eventually { collector.logRecords().isNotEmpty() })
        val a = attrs(collector.logRecords().single().attributesList)
        assertEquals("ApplicationNotResponding", a["exception.type"])
        assertEquals("false", a["zharp.handled"])
        assertTrue(a["exception.stacktrace"]!!.startsWith("ApplicationNotResponding: The main thread was blocked for 5.0 s\n\tat "))
    }

    @Test
    fun screenViewIsARootSpan() {
        Zharp.startSpan("outer") { runtime.screenView("CheckoutActivity", "activity", 1_000_000_000L, 1_250_000_000L) }
        Zharp.flush()
        assertTrue(eventually { collector.spans().size == 2 })
        val screen = collector.spans().single { it.name == "screen CheckoutActivity" }
        assertTrue(screen.parentSpanId.isEmpty)
        assertEquals(ProtoSpan.SpanKind.SPAN_KIND_INTERNAL, screen.kind)
        assertEquals(250_000_000L, screen.endTimeUnixNano - screen.startTimeUnixNano)
        assertEquals("activity", attrs(screen.attributesList)["screen.type"])
        // Everything after is tagged with the screen.
        Zharp.log.info("after")
        Zharp.flush()
        assertTrue(eventually { collector.logRecords().isNotEmpty() })
        assertEquals("CheckoutActivity", attrs(collector.logRecords().single().attributesList)["screen.name"])
    }

    @Test
    fun okHttpCallsAreClientSpansWithTraceHeadersOnlyToTracingOrigins() {
        val client = OkHttpClient.Builder().addInterceptor(ZharpOkHttpInterceptor()).build()
        val target = FakeCollector()
        try {
            // 127.0.0.1 is a tracing origin; "localhost" is not.
            client.newCall(Request.Builder().url("http://127.0.0.1:${target.port}/orders?token=secret").build()).execute().close()
            client.newCall(Request.Builder().url("http://localhost:${target.port}/other").build()).execute().close()
            val traced = target.requests.single { it.path == "/orders" }
            val untraced = target.requests.single { it.path == "/other" }
            val traceparent = traced.headers["traceparent"]
            assertNotNull(traceparent)
            assertTrue(traceparent!!, traceparent.matches(Regex("00-[0-9a-f]{32}-[0-9a-f]{16}-0[13]")))
            val baggage = traced.headers["baggage"]!!
            assertTrue(baggage, baggage.contains("session.id=${Zharp.sessionId}"))
            assertTrue(baggage, baggage.contains("enduser.id=user-42"))
            assertFalse(untraced.headers.containsKey("traceparent"))
            assertFalse(untraced.headers.containsKey("baggage"))

            Zharp.flush()
            assertTrue(eventually { collector.spans().size == 2 })
            val span = collector.spans().single { attrs(it.attributesList)["url.path"] == "/orders" }
            val a = attrs(span.attributesList)
            assertEquals(ProtoSpan.SpanKind.SPAN_KIND_CLIENT, span.kind)
            assertEquals("GET", a["http.request.method"])
            assertEquals("200", a["http.response.status_code"])
            assertFalse("query strings are left out", a["url.full"]!!.contains("token"))
            assertEquals(traceparent.split('-')[1], hex(span.traceId))
            assertEquals(traceparent.split('-')[2], hex(span.spanId))
        } finally {
            target.stop()
        }
    }

    @Test
    fun clearUserStartsANewSession() {
        val before = Zharp.sessionId
        Zharp.clearUser()
        assertTrue(before != Zharp.sessionId)
    }

    @Test
    fun sampledOutTracesStillSendErrors() {
        Zharp.startForTests(null)
        runtime.shutdown().join(5, TimeUnit.SECONDS)
        runtime = runtime(collector.endpoint, sampleRate = 0.0)
        Zharp.startForTests(runtime)
        Zharp.startSpan("checkout") { Zharp.captureError(IllegalStateException("still reported")) }
        Zharp.flush()
        assertTrue(eventually { collector.logRecords().isNotEmpty() })
        assertEquals("still reported", attrs(collector.logRecords().single().attributesList)["exception.message"])
        assertTrue(collector.spans().isEmpty())
    }

    @Test
    fun tracingOffSendsLogsAndErrorsButNoSpansOrTraceHeaders() {
        Zharp.startForTests(null)
        runtime.shutdown().join(5, TimeUnit.SECONDS)
        runtime = runtime(collector.endpoint, tracingOrigins = listOf("127.0.0.1"), tracing = false)
        Zharp.startForTests(runtime)

        val client = OkHttpClient.Builder().addInterceptor(ZharpOkHttpInterceptor()).build()
        val target = FakeCollector()
        try {
            // startSpan still runs its block and returns its value.
            val result = Zharp.startSpan("checkout") {
                Zharp.log.info("Paying", mapOf("items" to 3))
                Zharp.captureError(IllegalStateException("Card declined"))
                // A tracing origin, but tracing is off: the request goes out untouched.
                client.newCall(Request.Builder().url("http://127.0.0.1:${target.port}/orders").build()).execute().close()
                "done"
            }
            assertEquals("done", result)
            runtime.screenView("CheckoutActivity", "activity", 1_000_000_000L, 1_250_000_000L)
            Zharp.flush()
            assertTrue(eventually { collector.logRecords().size == 2 })

            val sent = target.requests.single { it.path == "/orders" }
            assertFalse(sent.headers.containsKey("traceparent"))
            assertFalse(sent.headers.containsKey("baggage"))

            val info = collector.logRecords().single { it.body.stringValue == "Paying" }
            val i = attrs(info.attributesList)
            assertEquals("3", i["items"])
            assertEquals(Zharp.sessionId, i["session.id"])
            assertEquals("user-42", i["enduser.id"])
            assertEquals("pro", i["plan"])
            assertTrue("no trace to belong to", info.traceId.isEmpty)

            val error = collector.logRecords().single { attrs(it.attributesList).containsKey("exception.type") }
            val e = attrs(error.attributesList)
            assertEquals(17, error.severityNumberValue)
            assertEquals("java.lang.IllegalStateException", e["exception.type"])
            assertEquals("Card declined", e["exception.message"])
            assertEquals("true", e["zharp.handled"])
            assertEquals("user-42", e["enduser.id"])
            // The screen is still remembered for later logs, though no screen span was made.
            assertEquals("CheckoutActivity", Zharp.user.screen)

            Thread.sleep(300)
            assertTrue(collector.spans().isEmpty())
            assertTrue("nothing sent to the traces endpoint", collector.traces().isEmpty())
        } finally {
            target.stop()
        }
    }

    @Test
    fun nothingThrowsBeforeInit() {
        Zharp.startForTests(null)
        Zharp.log.error("dropped", RuntimeException())
        Zharp.captureError(RuntimeException())
        Zharp.flush()
        assertEquals(42, Zharp.startSpan("x") { 42 })
        Zharp.trackScreen("Home")
    }
}
