package io.zharp.android.internal

import android.os.Build
import android.os.SystemClock
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.context.Context
import io.zharp.android.LogLevel
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.util.Calendar
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * The app's own Logcat as logs: the net under the exact captures, for `android.util.Log`,
 * libraries and native code.
 *
 * Runs `logcat -v long --pid=<this process> *:<level>` (Android 7.0+, for `--pid`). Since
 * Android 4.1 an app without READ_LOGS (which only the system and adb can grant) sees only its
 * own UID's lines, and that needs no permission and shows the user nothing; Android 13's
 * log-access prompt is for apps that do hold READ_LOGS. The process sleeps in a blocking read
 * until there is a line, and filters by level itself, so a quiet app costs nothing but the idle
 * process; each line costs a parse and a lookup.
 *
 * Lines from Logcat carry the session, user and screen but not the trace they were written in.
 * Lines an exact capture already sent, the SDK's own, and `System.out`/`System.err` (sent by
 * their own capture) are skipped. A message of several lines (`Log.e(tag, msg, error)`'s stack)
 * is one record.
 */
internal class LogcatCapture(
    private val source: Source,
    private val minLevel: LogLevel,
    private val skipStdio: Boolean,
) {
    /** Where the lines come from: the logcat process, or a test's. */
    interface Source {
        val pid: Int

        /** When this process started, so what an earlier process with the same PID logged is not sent. */
        val processStartMillis: Long

        fun open(level: Char): Opened
    }

    class Opened(val input: InputStream, val close: () -> Unit)

    class Entry(val epochMillis: Long, val pid: Int, val tid: Long, val priority: Char, val tag: String, val message: String) {
        val receivedAt: Long = System.nanoTime()
    }

    private val stopped = AtomicBoolean(false)

    @Volatile
    private var opened: Opened? = null

    /** The newest line read, so a restarted logcat (which replays its buffer) sends only what is new. */
    @Volatile
    private var lastSeen: Long = maxOf(source.processStartMillis, stoppedAt)

    private val pending = ConcurrentLinkedQueue<Entry>()
    private val queued = AtomicInteger()
    private val draining = AtomicBoolean(false)

    fun start() {
        Thread({ run() }, "zharp-logcat").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }.start()
    }

    fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        stoppedAt = System.currentTimeMillis()
        opened?.let { guarded(Unit, "Stopping logcat") { it.close() } }
        opened = null
    }

    private fun run() {
        var attempts = 0
        var since = lastSeen
        var inclusive = true
        while (!stopped.get() && attempts < MAX_STARTS) {
            attempts++
            try {
                val next = source.open(minLevel.letter)
                opened = next
                if (stopped.get()) {
                    next.close()
                    return
                }
                read(next.input, since, inclusive)
                next.close()
            } catch (error: Throwable) {
                Diagnostics.debug("Reading Logcat failed: $error")
            }
            opened = null
            if (stopped.get()) return
            // logcat ended (killed, or the buffer was cleared): start again, from where it was.
            since = lastSeen
            inclusive = false
            try {
                Thread.sleep(RESTART_DELAY_MILLIS * attempts)
            } catch (interrupted: InterruptedException) {
                return
            }
        }
        if (!stopped.get()) Diagnostics.debug("Logcat stopped; its lines are no longer sent")
    }

    private fun read(input: InputStream, since: Long, inclusive: Boolean) {
        val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8))
        val parser = Parser { entry ->
            val fresh = if (inclusive) entry.epochMillis >= since else entry.epochMillis > since
            if (fresh && entry.pid == source.pid) {
                lastSeen = maxOf(lastSeen, entry.epochMillis)
                enqueue(entry)
            }
        }
        while (!stopped.get()) {
            val line = reader.readLine() ?: break
            parser.line(line)
            // An entry ends with a blank line; with nothing more waiting, that one was the end.
            if (!reader.ready()) parser.idle()
        }
        parser.end()
    }

    /**
     * Held a moment before it is looked at, so an exact capture of the same line (a Timber tree
     * planted after the app's DebugTree) has said it sent it.
     */
    private fun enqueue(entry: Entry) {
        if (queued.get() >= MAX_PENDING) return
        queued.incrementAndGet()
        pending.add(entry)
        scheduleDrain(HOLD_MILLIS)
    }

    private fun scheduleDrain(delayMillis: Long) {
        if (!draining.compareAndSet(false, true)) return
        try {
            LogCapture.scheduler.schedule({ drain() }, delayMillis, TimeUnit.MILLISECONDS)
        } catch (error: Throwable) {
            draining.set(false)
        }
    }

    private fun drain() {
        try {
            val holdNanos = TimeUnit.MILLISECONDS.toNanos(HOLD_MILLIS)
            while (true) {
                val head = pending.peek() ?: break
                val waited = System.nanoTime() - head.receivedAt
                if (waited < holdNanos) {
                    draining.set(false)
                    scheduleDrain(TimeUnit.NANOSECONDS.toMillis(holdNanos - waited) + 1)
                    return
                }
                pending.poll()
                queued.decrementAndGet()
                if (!stopped.get()) handle(entry = head)
            }
        } catch (ignored: Throwable) {
        }
        draining.set(false)
        if (pending.isNotEmpty()) scheduleDrain(HOLD_MILLIS)
    }

    private fun handle(entry: Entry) {
        val tag = entry.tag
        if (tag == Diagnostics.TAG || tag == "chatty") return
        if (skipStdio && (tag == "System.out" || tag == "System.err")) return
        val message = LogCapture.stripAnsi(entry.message).trimEnd()
        if (message.isBlank() || message.startsWith("[Zharp]")) return
        if (LogCapture.Recent.alreadySent(message)) return
        val attributes = Attributes.builder()
            .put(LogCapture.SOURCE, "logcat")
            .put(LogCapture.THREAD_ID, entry.tid)
        if (tag.isNotEmpty()) attributes.put(LogCapture.LOGGER, tag)
        // Read later on another thread: whatever trace it was written in is unknown.
        LogCapture.emit(
            LogCapture.priorityLetter(entry.priority), message, attributes.build(),
            context = Context.root(), epochMillis = entry.epochMillis, remember = false,
        )
    }

    /**
     * `logcat -v long`: `[ 11-17 09:00:00.123  1234: 1250 I/Tag ]`, the message's lines, a blank
     * line. An entry ends at the next header, or at a blank line with nothing more waiting to be read.
     */
    class Parser(private val onEntry: (Entry) -> Unit) {
        private var header: MatchResult? = null
        private val lines = StringBuilder()
        private var blank = false

        fun line(raw: String) {
            blank = raw.isEmpty()
            val match = HEADER.find(raw)
            if (match != null) {
                end()
                header = match
                return
            }
            if (header == null) return
            if (lines.length > LogCapture.MAX_LINE) return
            if (lines.isNotEmpty() || raw.isNotEmpty()) {
                if (lines.isNotEmpty()) lines.append('\n')
                lines.append(raw)
            }
        }

        /** Nothing more to read for now: the entry is complete if its closing blank line came. */
        fun idle() {
            if (blank) end()
        }

        fun end() {
            val match = header ?: return
            header = null
            val message = lines.toString().trimEnd()
            lines.setLength(0)
            val (time, pid, tid, priority, tag) = match.destructured
            val millis = parseTime(time) ?: return
            onEntry(Entry(millis, pid.toInt(), tid.toLongOrNull() ?: 0, priority[0], tag.trim(), message))
        }
    }

    companion object {
        private const val MAX_STARTS = 3
        private const val RESTART_DELAY_MILLIS = 10_000L
        private const val HOLD_MILLIS = 300L
        private const val MAX_PENDING = 2_000

        /** When the last reader stopped: a reader started after it sends only what is newer. */
        @Volatile
        private var stoppedAt: Long = 0

        private val HEADER = Regex("^\\[ (\\d\\d-\\d\\d \\d\\d:\\d\\d:\\d\\d\\.\\d+)\\s+(\\d+):\\s*(\\d+)\\s+([VDIWEFA])/(.*?)\\s*]$")
        private val TIME = Regex("^(\\d\\d)-(\\d\\d) (\\d\\d):(\\d\\d):(\\d\\d)\\.(\\d+)$")

        /** Logcat's `MM-DD hh:mm:ss.mmm`, in the device's time zone, in the last twelve months. */
        fun parseTime(text: String, now: Long = System.currentTimeMillis()): Long? {
            val m = TIME.find(text) ?: return null
            val (month, day, hour, minute, second, fraction) = m.destructured
            val calendar = Calendar.getInstance()
            calendar.timeInMillis = now
            calendar.set(Calendar.MONTH, month.toInt() - 1)
            calendar.set(Calendar.DAY_OF_MONTH, day.toInt())
            calendar.set(Calendar.HOUR_OF_DAY, hour.toInt())
            calendar.set(Calendar.MINUTE, minute.toInt())
            calendar.set(Calendar.SECOND, second.toInt())
            calendar.set(Calendar.MILLISECOND, fraction.take(3).padEnd(3, '0').toInt())
            // 12-31 23:59 read on 01-01 is last year.
            if (calendar.timeInMillis > now + TimeUnit.DAYS.toMillis(1)) calendar.add(Calendar.YEAR, -1)
            return calendar.timeInMillis
        }

        /** The real logcat, for this process; null below Android 7.0, whose logcat has no `--pid`. */
        fun processSource(): Source? {
            if (Build.VERSION.SDK_INT < 24) return null
            val pid = android.os.Process.myPid()
            val started = System.currentTimeMillis() - (SystemClock.elapsedRealtime() - android.os.Process.getStartElapsedRealtime())
            return object : Source {
                override val pid: Int = pid
                override val processStartMillis: Long = started

                override fun open(level: Char): Opened {
                    val process = ProcessBuilder("logcat", "-v", "long", "--pid=$pid", "*:$level")
                        .redirectErrorStream(true)
                        .start()
                    guarded(Unit, "Closing logcat's input") { process.outputStream.close() }
                    return Opened(process.inputStream) { process.destroy() }
                }
            }
        }
    }
}
