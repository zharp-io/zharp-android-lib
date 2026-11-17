package io.zharp.android.internal

import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.logs.Severity
import io.opentelemetry.context.Context
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.io.PrintStream
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit

/**
 * `System.out` and `System.err` as logs. Each is replaced with a stream that writes everything
 * on to the one it replaced (on Android, Logcat's `System.out`/`System.err` tags) and copies
 * each line: in the trace of the thread that printed it, its level read from the line (a JSON
 * level, a level word), else info for `System.out` and a warning for `System.err`.
 *
 * Lines are gathered per thread, and a stack trace's `at …`/`Caused by:` lines join the line
 * before them, so a `printStackTrace()` is one record.
 */
internal class OutputCapture {
    private var stdout: Tee? = null
    private var stderr: Tee? = null

    fun install() {
        val out = System.out
        val err = System.err
        if (out != null && out !is Tee) stdout = Tee(out, "stdout", Severity.INFO).also { System.setOut(it) }
        if (err != null && err !is Tee) stderr = Tee(err, "stderr", Severity.WARN).also { System.setErr(it) }
    }

    fun uninstall() {
        // Lines still gathering (200 ms at most) are dropped: the SDK is stopping.
        stdout?.let { if (System.out === it) System.setOut(it.original) }
        stderr?.let { if (System.err === it) System.setErr(it.original) }
        stdout = null
        stderr = null
    }

    class Tee(val original: PrintStream, name: String, fallback: Severity) :
        PrintStream(Copier(original, name, fallback), true, CHARSET.name())

    /** Passes every byte on, and copies complete lines. */
    private class Copier(private val original: PrintStream, private val name: String, private val fallback: Severity) : OutputStream() {
        private val attributes = Attributes.of(LogCapture.SOURCE, name, LogCapture.IOSTREAM, name)
        private val lines = object : ThreadLocal<Lines>() {
            override fun initialValue(): Lines = Lines()
        }

        override fun write(b: Int) {
            original.write(b)
            copy { it.take(byteArrayOf(b.toByte()), 0, 1) }
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            original.write(b, off, len)
            copy { it.take(b, off, len) }
        }

        override fun flush() {
            original.flush()
        }

        private inline fun copy(block: (Lines) -> Unit) {
            try {
                if (LogCapture.config == null || LogCapture.isSdkThread()) return
                block(lines.get()!!)
            } catch (ignored: Throwable) {
                // A line that could not be copied is not worth an exception in the caller.
            }
        }

        /** One thread's output: the line being written, and the record it may still join. */
        inner class Lines {
            private val bytes = ByteArrayOutputStream()
            private var lineContext: Context? = null
            private var record: StringBuilder? = null
            private var recordContext: Context? = null
            private var scheduled = false

            @Synchronized
            fun take(b: ByteArray, off: Int, len: Int) {
                var start = off
                val end = off + len
                for (i in off until end) {
                    if (b[i] == NEWLINE) {
                        if (bytes.size() == 0) lineContext = Context.current()
                        bytes.write(b, start, i - start)
                        line()
                        start = i + 1
                    }
                }
                if (start < end) {
                    // A line belongs to the trace it started in.
                    if (bytes.size() == 0) lineContext = Context.current()
                    bytes.write(b, start, end - start)
                    if (bytes.size() > LogCapture.MAX_LINE) line()
                }
            }

            private fun line() {
                val text = String(bytes.toByteArray(), CHARSET).trimEnd('\r')
                val context = lineContext
                bytes.reset()
                lineContext = null
                val current = record
                if (current != null && CONTINUATION.containsMatchIn(text)) {
                    if (current.length < LogCapture.MAX_LINE) current.append('\n').append(text)
                    return
                }
                send()
                record = StringBuilder(text)
                recordContext = context
                if (!scheduled) {
                    scheduled = true
                    LogCapture.scheduler.schedule({ flush() }, GATHER_MILLIS, TimeUnit.MILLISECONDS)
                }
            }

            @Synchronized
            fun flush() {
                scheduled = false
                send()
            }

            private fun send() {
                val text = record?.toString() ?: return
                val context = recordContext
                record = null
                recordContext = null
                // Blank, or printed by a logger whose records another capture already sent.
                if (text.isBlank() || LogCapture.Recent.alreadySent(text)) return
                val parsed = LogCapture.parseLine(text)
                // Not remembered: Logcat's System.out/System.err lines are skipped while this runs.
                LogCapture.emit(parsed.severity ?: fallback, parsed.body, attributes, null, context, remember = false)
            }
        }
    }

    private companion object {
        const val NEWLINE: Byte = '\n'.code.toByte()
        const val GATHER_MILLIS = 200L
        val CHARSET: Charset = Charset.defaultCharset()

        /** The lines of a printed stack trace after its first. */
        val CONTINUATION = Regex("^(\\s+at |\\s*Caused by: |\\s+\\.\\.\\. \\d+ more|\\s+Suppressed: )")
    }
}
