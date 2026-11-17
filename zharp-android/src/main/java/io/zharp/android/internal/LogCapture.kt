package io.zharp.android.internal

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.logs.Severity
import io.opentelemetry.context.Context
import io.zharp.android.LogLevel
import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Logs the app writes some other way than `Zharp.log`, as log records: `captureOutput`.
 *
 * The exact captures see a line where it is written, with its level and the trace it was written
 * in: a Timber tree, a java.util.logging handler, `System.out`/`System.err`, and the
 * `io.zharp.android.Log` drop-in. Reading the app's own Logcat is the net under them, for
 * `android.util.Log`, libraries and native code; those lines carry no trace.
 *
 * Every one of them still prints exactly as before, and a line is sent once: what an exact capture
 * sent is remembered for a few seconds, and Logcat's copy of it is then skipped.
 */
internal object LogCapture {
    @JvmField
    val SOURCE: AttributeKey<String> = AttributeKey.stringKey("log.source")
    @JvmField
    val LOGGER: AttributeKey<String> = AttributeKey.stringKey("log.logger")
    @JvmField
    val IOSTREAM: AttributeKey<String> = AttributeKey.stringKey("log.iostream")
    @JvmField
    val THREAD_ID: AttributeKey<Long> = AttributeKey.longKey("thread.id")

    const val MAX_LINE: Int = 32 * 1024

    class Config(val minLevel: LogLevel, val logcat: Boolean)

    @Volatile
    var config: Config? = null
        private set

    @Volatile
    private var runtime: () -> Runtime? = { null }

    private var output: OutputCapture? = null
    private var jul: JulCapture? = null
    private var timber: Any? = null
    private var logcat: LogcatCapture? = null

    /** One daemon thread for what isn't sent on the writing thread: grouped output lines, Logcat. */
    val scheduler: ScheduledExecutorService by lazy {
        Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "zharp-logs").apply {
                isDaemon = true
                priority = Thread.MIN_PRIORITY
            }
        }
    }

    @Synchronized
    fun install(minLevel: LogLevel, logcat: Boolean, runtime: () -> Runtime?, logcatSource: LogcatCapture.Source? = null) {
        if (config != null) return
        this.runtime = runtime
        config = Config(minLevel, logcat)
        // java.util.logging first: a console handler it makes prints to the System.err it finds then.
        jul = guarded(null, "Capturing java.util.logging") { JulCapture().also { it.install() } }
        output = guarded(null, "Capturing System.out") { OutputCapture().also { it.install() } }
        timber = guarded(null, "Planting a Timber tree") { if (TimberSupport.available()) ZharpTimberTree.plantNew() else null }
        if (logcat) {
            val source = logcatSource ?: LogcatCapture.processSource()
            this.logcat = source?.let { guarded(null, "Reading Logcat") { LogcatCapture(it, minLevel, skipStdio = output != null).also { c -> c.start() } } }
        }
        Diagnostics.debug(
            "Capturing logs at ${minLevel.name} and above from" +
                (if (timber != null) " Timber," else "") + " java.util.logging, System.out/err" +
                (if (this.logcat != null) " and Logcat" else if (logcat) " (Logcat needs Android 7.0+)" else ""),
        )
    }

    @Synchronized
    fun uninstall() {
        if (config == null) return
        guarded(Unit, "Stopping Logcat") { logcat?.stop() }
        guarded(Unit, "Restoring System.out") { output?.uninstall() }
        guarded(Unit, "Removing the java.util.logging handler") { jul?.uninstall() }
        guarded(Unit, "Uprooting the Timber tree") { timber?.let { ZharpTimberTree.uproot(it) } }
        logcat = null
        output = null
        jul = null
        timber = null
        config = null
        Recent.clear()
    }

    // ── Sending ──────────────────────────────────────────────────────────────────────────────

    /** Set while a capture sends, so whatever that prints is not captured again. */
    private val sending = object : ThreadLocal<Boolean>() {
        override fun initialValue(): Boolean = false
    }

    /**
     * Whether output on this thread is the SDK's own: printed while sending a record, or on the
     * SDK's threads (OpenTelemetry's export workers, Zharp's own).
     */
    fun isSdkThread(): Boolean {
        if (sending.get() == true) return true
        val name = Thread.currentThread().name
        return name.startsWith("zharp-") || name.startsWith("BatchLogRecordProcessor") || name.startsWith("BatchSpanProcessor")
    }

    fun accepts(severity: Severity): Boolean {
        val min = config?.minLevel ?: return false
        return severity.severityNumber >= min.severity.severityNumber
    }

    /**
     * Send one captured line. [remember]: it will also reach Logcat (it was printed), so Logcat's
     * copy is to be skipped. Never throws.
     */
    fun emit(
        severity: Severity,
        body: String,
        attributes: Attributes,
        error: Throwable? = null,
        context: Context? = null,
        epochMillis: Long? = null,
        remember: Boolean = true,
    ) {
        if (!accepts(severity)) return
        val current = runtime() ?: return
        val text = if (body.length > MAX_LINE) body.substring(0, MAX_LINE) else body
        if (text.isBlank() || text.startsWith("[Zharp]")) return
        if (remember) Recent.remember(text)
        sending.set(true)
        try {
            current.capture(severity, text, attributes, error, context, epochMillis)
        } catch (ignored: Throwable) {
            // A line that could not be copied is not worth an exception in the caller.
        } finally {
            sending.set(false)
        }
    }

    /** Say a line was handled without sending it (the SDK's own), so Logcat's copy is skipped too. */
    fun skip(body: String) {
        Recent.remember(body)
    }

    // ── Sent once ────────────────────────────────────────────────────────────────────────────

    /** Bodies the exact captures sent in the last few seconds, so Logcat's copies are skipped. */
    object Recent {
        private const val WINDOW_MILLIS = 5_000L
        private const val MAX = 500

        /** A Logcat line this long or longer is also a copy when it is part of a sent body (a long message split up). */
        private const val MIN_PART = 16

        private class Entry(val at: Long, val text: String)

        private val entries = ArrayDeque<Entry>()

        private fun normalise(text: String) = text.replace(WHITESPACE, " ").trim()

        @Synchronized
        fun remember(body: String) {
            val text = normalise(body)
            if (text.length < 4) return
            entries.addLast(Entry(System.currentTimeMillis(), text))
            while (entries.size > MAX) entries.removeFirst()
        }

        /** Whether [line] is a copy of something sent: it contains a recent body, or is a long part of one. */
        @Synchronized
        fun alreadySent(line: String): Boolean {
            val now = System.currentTimeMillis()
            while (entries.isNotEmpty() && now - entries.first.at > WINDOW_MILLIS) entries.removeFirst()
            if (entries.isEmpty()) return false
            val text = normalise(line)
            if (text.isEmpty()) return false
            val iterator = entries.descendingIterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (text.contains(entry.text)) {
                    iterator.remove()
                    return true
                }
                if (text.length >= MIN_PART && entry.text.contains(text)) return true
            }
            return false
        }

        @Synchronized
        fun clear() = entries.clear()

        private val WHITESPACE = Regex("\\s+")
    }

    // ── A line's level ───────────────────────────────────────────────────────────────────────

    class Parsed(val severity: Severity?, val body: String)

    private val ANSI = Regex("\u001b\\[[0-9;?]*[ -/]*[@-~]")
    private val JSON_LEVEL = Regex("\"(?:level|severity|lvl)\"\\s*:\\s*(?:\"([^\"]*)\"|(\\d+))")
    private val JSON_MESSAGE = Regex("\"(?:msg|message|event)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")

    /** The first level word near the start of a line: `ERROR`, `[warn]`, `level=error`, `E/Tag:`. */
    private val WORD = Regex(
        "(?:^|[\\s\\[(|:=])(trace|verbose|debug|info|notice|warn(?:ing)?|err(?:or)?|crit(?:ical)?|fatal|emerg|alert)(?=$|[\\s\\]):|,])",
        RegexOption.IGNORE_CASE,
    )
    private val PRIORITY = Regex("^([VDIWEF])/[^:\\s]+:")

    fun stripAnsi(line: String): String = line.replace(ANSI, "")

    /** A printed line's level, from the line itself; null when it names none. JSON lines give their own message. */
    fun parseLine(raw: String): Parsed {
        val line = stripAnsi(raw).trimEnd()
        if (line.startsWith("{") && line.endsWith("}")) {
            val level = JSON_LEVEL.find(line)
            if (level != null) {
                val severity = level.groupValues[1].takeIf { it.isNotEmpty() }?.let { severityOf(it) }
                    ?: level.groupValues[2].toIntOrNull()?.let { numericSeverity(it) }
                val message = JSON_MESSAGE.find(line)?.groupValues?.get(1)?.let { unescape(it) }
                return Parsed(severity, message ?: line)
            }
        }
        PRIORITY.find(line)?.let { return Parsed(priorityLetter(it.groupValues[1][0]), line) }
        val word = WORD.find(line.take(120))
        return Parsed(word?.let { severityOf(it.groupValues[1]) }, line)
    }

    fun severityOf(word: String): Severity? = when (word.lowercase()) {
        "trace" -> Severity.TRACE
        "verbose", "debug" -> Severity.DEBUG
        "log", "info", "notice" -> Severity.INFO
        "warn", "warning" -> Severity.WARN
        "err", "error" -> Severity.ERROR
        "crit", "critical", "fatal", "emerg", "alert" -> Severity.FATAL
        else -> null
    }

    /** pino and bunyan write their level as a number. */
    private fun numericSeverity(n: Int): Severity = when {
        n >= 60 -> Severity.FATAL
        n >= 50 -> Severity.ERROR
        n >= 40 -> Severity.WARN
        n >= 30 -> Severity.INFO
        n >= 20 -> Severity.DEBUG
        else -> Severity.TRACE
    }

    /** Logcat's priority letters, and android.util.Log's priorities. */
    fun priorityLetter(letter: Char): Severity = when (letter) {
        'V' -> Severity.TRACE
        'D' -> Severity.DEBUG
        'I' -> Severity.INFO
        'W' -> Severity.WARN
        'E' -> Severity.ERROR
        'F', 'A' -> Severity.FATAL
        else -> Severity.INFO
    }

    fun priority(priority: Int): Severity = when (priority) {
        2 -> Severity.TRACE
        3 -> Severity.DEBUG
        4 -> Severity.INFO
        5 -> Severity.WARN
        6 -> Severity.ERROR
        7 -> Severity.FATAL
        else -> Severity.INFO
    }

    private fun unescape(text: String): String {
        if (!text.contains('\\')) return text
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\\' && i + 1 < text.length) {
                when (val next = text[i + 1]) {
                    'n' -> out.append('\n')
                    't' -> out.append('\t')
                    'r' -> out.append('\r')
                    else -> out.append(next)
                }
                i += 2
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }
}

/** Timber, when the app has it. */
internal object TimberSupport {
    fun available(): Boolean = try {
        Class.forName("timber.log.Timber\$Tree")
        true
    } catch (missing: Throwable) {
        false
    }
}
