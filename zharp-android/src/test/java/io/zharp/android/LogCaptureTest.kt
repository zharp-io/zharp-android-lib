package io.zharp.android

import io.opentelemetry.api.common.Attributes
import io.opentelemetry.proto.logs.v1.LogRecord
import io.zharp.android.internal.Keys
import io.zharp.android.internal.LogCapture
import io.zharp.android.internal.LogcatCapture
import io.zharp.android.internal.MemoryStore
import io.zharp.android.internal.Runtime
import io.zharp.android.internal.Settings
import io.zharp.android.internal.Transport
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.io.PrintStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.logging.Logger

/** captureOutput: every way the app logs arrives once, with its level, and in its trace where that is known. */
class LogCaptureTest {
    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var collector: FakeCollector
    private lateinit var runtime: Runtime
    private lateinit var realOut: PrintStream
    private lateinit var realErr: PrintStream

    /** What the app's own System.out/System.err print to (on a device: Logcat). */
    private val screenOut = ByteArrayOutputStream()
    private val screenErr = ByteArrayOutputStream()

    private val logcat = FakeLogcat()

    @Before
    fun start() {
        realOut = System.out
        realErr = System.err
        System.setOut(PrintStream(screenOut, true, "UTF-8"))
        System.setErr(PrintStream(screenErr, true, "UTF-8"))
        collector = FakeCollector()
        val settings = Settings(
            key = "zpk_test", service = "shop-android", environment = "test", release = "2.3.0 (45)",
            endpoint = collector.endpoint, tracingOrigins = emptyList(), sampleRate = 1.0, scheduleDelayMillis = 100,
        )
        runtime = Runtime(
            settings, Attributes.builder().put(Keys.OS_NAME, "Android").build(), MemoryStore(), folder.newFolder(), Zharp.user,
            Transport(collector.endpoint, settings.key, Runtime.USER_AGENT, retryDelaysMillis = longArrayOf(10, 10)),
        )
        Zharp.startForTests(runtime)
    }

    @After
    fun stop() {
        LogCapture.uninstall()
        logcat.close()
        System.setOut(realOut)
        System.setErr(realErr)
        Zharp.startForTests(null)
        runtime.shutdown().join(5, TimeUnit.SECONDS)
        collector.stop()
    }

    /** As init does it, with `logs = true`. */
    private fun install(level: LogLevel = LogLevel.INFO, withLogcat: Boolean = false) {
        Zharp.captureLogs(
            ZharpOptions().apply {
                logs = true
                captureOutputLevel = level
                captureLogcat = withLogcat
            },
            logcat,
        )
    }

    private fun records(): List<LogRecord> {
        Zharp.flush()
        return collector.logRecords()
    }

    private fun arrived(count: Int): List<LogRecord> {
        assertTrue("expected $count records, got ${collector.logRecords().map { it.body.stringValue }}", eventually { records().size >= count })
        // Anything else would have come by now.
        Thread.sleep(700)
        return records()
    }

    private fun LogRecord.attr(key: String) = attrs(attributesList)[key]

    // ── System.out / System.err ──────────────────────────────────────────────────────────────

    @Test
    fun printedLinesArriveInTheirTraceAndStillPrint() {
        install()
        var traceId = ""
        Zharp.startSpan("checkout") { span ->
            traceId = span.spanContext.traceId
            println("Charging card for order A-1")
        }
        System.err.println("Retrying the payment")
        val logs = arrived(2)
        assertEquals(2, logs.size)

        val out = logs.single { it.body.stringValue == "Charging card for order A-1" }
        assertEquals(9, out.severityNumberValue)
        assertEquals("INFO", out.severityText)
        assertEquals("stdout", out.attr("log.source"))
        assertEquals(traceId, hex(out.traceId))
        assertNotNull(out.attr("session.id"))

        val err = logs.single { it.body.stringValue == "Retrying the payment" }
        assertEquals(13, err.severityNumberValue)
        assertEquals("stderr", err.attr("log.source"))

        // Printed exactly as before.
        assertEquals("Charging card for order A-1\n", screenOut.toString("UTF-8").replace("\r\n", "\n"))
        assertEquals("Retrying the payment\n", screenErr.toString("UTF-8").replace("\r\n", "\n"))
    }

    @Test
    fun aLinesLevelIsReadFromTheLine() {
        install(LogLevel.DEBUG)
        println("ERROR payment declined")
        println("[warn] slow response")
        println("""{"level":"error","msg":"from a JSON logger","order":"A-1"}""")
        println("""{"level":20,"message":"pino debug"}""")
        println("E/Payments: declined")
        print("\u001b[32mcoloured\u001b[0m\n")
        val logs = arrived(6).associateBy { it.body.stringValue }
        assertEquals(17, logs.getValue("ERROR payment declined").severityNumberValue)
        assertEquals(13, logs.getValue("[warn] slow response").severityNumberValue)
        assertEquals(17, logs.getValue("from a JSON logger").severityNumberValue)
        assertEquals(5, logs.getValue("pino debug").severityNumberValue)
        assertEquals(17, logs.getValue("E/Payments: declined").severityNumberValue)
        assertEquals(9, logs.getValue("coloured").severityNumberValue)
    }

    @Test
    fun aPrintedStackTraceIsOneRecord() {
        install()
        IllegalStateException("Card declined", RuntimeException("gateway timeout")).printStackTrace()
        val log = arrived(1).single()
        val body = log.body.stringValue
        assertTrue(body.startsWith("java.lang.IllegalStateException: Card declined"))
        assertTrue(body.contains("\tat io.zharp.android.LogCaptureTest"))
        assertTrue(body.contains("Caused by: java.lang.RuntimeException: gateway timeout"))
        assertEquals(13, log.severityNumberValue)
    }

    @Test
    fun belowTheLevelIsNotSent() {
        install(LogLevel.WARN)
        println("just info")
        println("WARN disk almost full")
        val logs = arrived(1)
        assertEquals(listOf("WARN disk almost full"), logs.map { it.body.stringValue })
    }

    @Test
    fun sdkLinesAreNotSentAndStopRestoresTheStreams() {
        install()
        val teed = System.out
        println("[Zharp] about the SDK")
        println("the app's")
        assertEquals(listOf("the app's"), arrived(1).map { it.body.stringValue })
        LogCapture.uninstall()
        assertFalse(System.out === teed)
        println("after stop")
        Thread.sleep(500)
        assertEquals(1, records().size)
        assertTrue(screenOut.toString("UTF-8").contains("after stop"))
    }

    // ── Timber, java.util.logging, the Log drop-in ───────────────────────────────────────────

    @Test
    fun timberLogsArriveWithTagLevelTraceAndError() {
        install()
        var traceId = ""
        Zharp.startSpan("checkout") { span ->
            traceId = span.spanContext.traceId
            Timber.tag("Checkout").i("Paid %d items", 3)
        }
        Timber.tag("Checkout").e(IllegalStateException("Card declined"), "Payment failed")
        Timber.d("below the level")
        val logs = arrived(2)
        assertEquals(2, logs.size)

        val info = logs.single { it.body.stringValue == "Paid 3 items" }
        assertEquals(9, info.severityNumberValue)
        assertEquals("timber", info.attr("log.source"))
        assertEquals("Checkout", info.attr("log.logger"))
        assertEquals(traceId, hex(info.traceId))

        val error = logs.single { it.body.stringValue == "Payment failed" }
        assertEquals(17, error.severityNumberValue)
        assertEquals("java.lang.IllegalStateException", error.attr("exception.type"))
        assertTrue(error.attr("exception.stacktrace")!!.contains("Card declined"))
    }

    @Test
    fun javaUtilLoggingArrivesButOpenTelemetrysOwnDoesNot() {
        install()
        var traceId = ""
        Zharp.startSpan("sync") { span ->
            traceId = span.spanContext.traceId
            Logger.getLogger("com.shop.Sync").warning("Disk almost full")
        }
        Logger.getLogger("io.opentelemetry.sdk.Something").severe("exporter trouble")
        val log = arrived(1).single()
        assertEquals("Disk almost full", log.body.stringValue)
        assertEquals(13, log.severityNumberValue)
        assertEquals("java.util.logging", log.attr("log.source"))
        assertEquals("com.shop.Sync", log.attr("log.logger"))
        assertEquals(traceId, hex(log.traceId))
    }

    @Test
    fun theLogDropInSendsInItsTrace() {
        install()
        var traceId = ""
        Zharp.startSpan("checkout") { span ->
            traceId = span.spanContext.traceId
            Log.w("Checkout", "Coupon expired")
        }
        Log.d("Checkout", "below the level")
        val log = arrived(1).single()
        assertEquals("Coupon expired", log.body.stringValue)
        assertEquals(13, log.severityNumberValue)
        assertEquals("Checkout", log.attr("log.logger"))
        assertEquals(traceId, hex(log.traceId))
    }

    @Test
    fun byDefaultNothingTheAppLogsIsReadButErrorsAndZharpLogAreSent() {
        val out = System.out
        val err = System.err
        val julHandlers = Logger.getLogger("").handlers.toList()
        val options = ZharpOptions()
        assertFalse(options.logs)
        Zharp.captureLogs(options, logcat)

        // Nothing started: no Logcat reader, streams as they were, no JUL handler, no Timber tree.
        assertTrue(LogCapture.config == null)
        assertTrue(System.out === out)
        assertTrue(System.err === err)
        assertEquals(julHandlers, Logger.getLogger("").handlers.toList())
        assertEquals(0, Timber.treeCount)

        println("printed")
        System.err.println("ERROR printed")
        Timber.e("timber")
        Log.e("Tag", "drop-in")
        Logger.getLogger("com.shop").severe("jul")
        logcat.entry(System.currentTimeMillis(), 'E', "Native", "only in logcat")
        Zharp.log.info("explicit")
        Zharp.captureError(IllegalStateException("caught"))
        val logs = arrived(2)
        assertEquals(listOf("caught", "explicit"), logs.map { it.body.stringValue }.sorted())
        assertEquals(0, logcat.opens)
        // The app's output is untouched.
        assertTrue(screenOut.toString("UTF-8").contains("printed"))
        assertTrue(screenErr.toString("UTF-8").contains("ERROR printed"))
    }

    @Test
    fun withCaptureOutputOffNothingIsSent() {
        Zharp.captureLogs(ZharpOptions().apply { logs = true; captureOutput = false }, logcat)
        assertTrue(LogCapture.config == null)
        println("printed")
        Timber.i("timber")
        Log.i("Tag", "drop-in")
        Logger.getLogger("com.shop").warning("jul")
        Zharp.log.info("explicit")
        val logs = arrived(1)
        assertEquals(listOf("explicit"), logs.map { it.body.stringValue })
        assertTrue(screenOut.toString("UTF-8").contains("printed"))
    }

    // ── Logcat ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun logcatLinesArriveWithPriorityTagAndTime() {
        install(withLogcat = true)
        val at = System.currentTimeMillis() - 1_000
        logcat.entry(at, 'I', "Payments", "Charged 12.50")
        logcat.entry(at, 'E', "Payments", "Payment failed\njava.lang.IllegalStateException: declined\n\tat com.shop.Pay.run(Pay.java:10)")
        logcat.entry(at, 'W', "OkHttp", "slow call")
        val logs = arrived(3).associateBy { it.body.stringValue.lineSequence().first() }
        assertEquals(3, logs.size)
        val info = logs.getValue("Charged 12.50")
        assertEquals(9, info.severityNumberValue)
        assertEquals("logcat", info.attr("log.source"))
        assertEquals("Payments", info.attr("log.logger"))
        assertEquals("1250", info.attr("thread.id"))
        assertEquals(at, TimeUnit.NANOSECONDS.toMillis(info.timeUnixNano))
        // Read on another thread: no trace, but still the session.
        assertTrue(info.traceId.isEmpty)
        assertNotNull(info.attr("session.id"))
        // A message of several lines (a stack) is one record.
        val error = logs.getValue("Payment failed")
        assertEquals(17, error.severityNumberValue)
        assertTrue(error.body.stringValue.contains("\tat com.shop.Pay.run(Pay.java:10)"))
        assertEquals(13, logs.getValue("slow call").severityNumberValue)
    }

    @Test
    fun logcatSkipsWhatWasAlreadySentTheSdksOwnOldLinesAndOtherProcesses() {
        install(withLogcat = true)
        Timber.tag("Checkout").i("Paid 3 items")
        Log.w("Checkout", "Coupon expired")
        println("printed line")
        Logger.getLogger("com.shop").warning("from jul")
        val now = System.currentTimeMillis()
        // Their copies in Logcat, as Timber's DebugTree, Android's System.out and its JUL handler print them.
        logcat.entry(now, 'I', "Checkout", "Paid 3 items")
        logcat.entry(now, 'W', "Checkout", "Coupon expired")
        logcat.entry(now, 'I', "System.out", "printed line")
        logcat.entry(now, 'W', "shop", "from jul")
        // The SDK's own lines, a line from before this process, and another process's.
        logcat.entry(now, 'I', "Zharp", "[Zharp] Application Monitoring active")
        logcat.entry(now - TimeUnit.MINUTES.toMillis(10), 'E', "Old", "from an earlier process")
        logcat.entry(now, 'E', "Other", "another process", pid = 999)
        // And one only Logcat saw.
        logcat.entry(now, 'E', "Native", "only in logcat")

        val logs = arrived(5)
        assertEquals(
            listOf("Coupon expired", "Paid 3 items", "from jul", "only in logcat", "printed line"),
            logs.map { it.body.stringValue }.sorted(),
        )
        assertEquals("logcat", logs.single { it.body.stringValue == "only in logcat" }.attr("log.source"))
        assertEquals("timber", logs.single { it.body.stringValue == "Paid 3 items" }.attr("log.source"))
    }

    @Test
    fun aRepeatedLineIsSentEachTime() {
        install(withLogcat = true)
        val now = System.currentTimeMillis()
        Timber.i("tick tock")
        Timber.i("tick tock")
        logcat.entry(now, 'I', "Clock", "tick tock")
        logcat.entry(now, 'I', "Clock", "tick tock")
        logcat.entry(now, 'I', "Clock", "tick tock")
        // Two from Timber; Logcat's first two copies are theirs, the third is new.
        val logs = arrived(3)
        assertEquals(3, logs.size)
        assertEquals(2, logs.count { it.attr("log.source") == "timber" })
    }

    @Test
    fun logcatTimeIsReadInTheLastYear() {
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).parse("2027-01-01 00:00:05.000")!!.time
        val read = LogcatCapture.parseTime("12-31 23:59:59.250", now)!!
        assertEquals("2026-12-31 23:59:59.250", SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(read)))
    }

    /** Lines as `logcat -v long --pid` prints them, written when the test says. */
    private class FakeLogcat : LogcatCapture.Source {
        private val sink = PipedOutputStream()
        private val input = PipedInputStream(sink, 1 shl 16)
        private val format = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

        override val pid: Int = 4242
        override val processStartMillis: Long = System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(1)

        @Volatile
        var opens = 0

        override fun open(level: Char): LogcatCapture.Opened {
            opens++
            return LogcatCapture.Opened(input) { close() }
        }

        @Synchronized
        fun entry(at: Long, priority: Char, tag: String, message: String, pid: Int = this.pid) {
            val text = "[ ${format.format(Date(at))}  $pid: 1250 $priority/$tag ]\n$message\n\n"
            sink.write(text.toByteArray(Charsets.UTF_8))
            sink.flush()
        }

        fun close() {
            try {
                sink.close()
            } catch (ignored: Exception) {
            }
        }
    }
}
