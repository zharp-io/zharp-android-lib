package io.zharp.android.internal

import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.logs.Severity
import java.util.logging.Formatter
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogManager
import java.util.logging.LogRecord
import java.util.logging.Logger

/**
 * java.util.logging as logs: a handler on the root logger, beside Android's own (which prints to
 * Logcat). Its loggers' levels are left as they are: what was published before is what is sent.
 * OpenTelemetry's and Zharp's own records are not sent.
 */
internal class JulCapture {
    private val handler = ZharpHandler()

    fun install() {
        val root = root()
        // Has the logging configuration make its handlers now, while System.err is still the original.
        root.handlers
        root.addHandler(handler)
    }

    fun uninstall() {
        root().removeHandler(handler)
    }

    private fun root(): Logger = LogManager.getLogManager().getLogger("") ?: Logger.getLogger("")

    private class ZharpHandler : Handler() {
        private val format = object : Formatter() {
            override fun format(record: LogRecord): String = formatMessage(record)
        }

        init {
            level = Level.ALL
        }

        override fun publish(record: LogRecord?) {
            if (record == null) return
            try {
                val name = record.loggerName.orEmpty()
                val message = format.format(record) ?: return
                if (name.startsWith("io.opentelemetry") || name.startsWith("io.zharp") || LogCapture.isSdkThread()) {
                    // Not the app's; Android prints it to Logcat too, where it is skipped as well.
                    LogCapture.skip(message)
                    return
                }
                val severity = severity(record.level)
                if (!LogCapture.accepts(severity)) return
                val attributes = Attributes.builder().put(LogCapture.SOURCE, "java.util.logging")
                if (name.isNotEmpty()) attributes.put(LogCapture.LOGGER, name)
                LogCapture.emit(severity, message, attributes.build(), record.thrown)
            } catch (ignored: Throwable) {
                // A record that could not be copied is not worth an exception in the caller.
            }
        }

        override fun flush() {}

        override fun close() {}

        private fun severity(level: Level): Severity {
            val value = level.intValue()
            return when {
                value >= Level.SEVERE.intValue() -> Severity.ERROR
                value >= Level.WARNING.intValue() -> Severity.WARN
                value >= Level.INFO.intValue() -> Severity.INFO
                value >= Level.FINE.intValue() -> Severity.DEBUG
                else -> Severity.TRACE
            }
        }
    }
}
