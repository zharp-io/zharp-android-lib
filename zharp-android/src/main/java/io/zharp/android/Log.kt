package io.zharp.android

import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.logs.Severity
import io.zharp.android.internal.LogCapture

/**
 * A drop-in for `android.util.Log`: change `import android.util.Log` to `import io.zharp.android.Log`
 * and every call still goes to Logcat as before, and is also sent to Zharp in the trace it was
 * written in (Logcat's own copy, read with [ZharpOptions.captureLogcat], carries no trace).
 * Follows [ZharpOptions.captureOutput] and [ZharpOptions.captureOutputLevel].
 */
public object Log {
    public const val VERBOSE: Int = 2
    public const val DEBUG: Int = 3
    public const val INFO: Int = 4
    public const val WARN: Int = 5
    public const val ERROR: Int = 6
    public const val ASSERT: Int = 7

    @JvmStatic
    public fun v(tag: String?, msg: String): Int = send(VERBOSE, tag, msg, null) { android.util.Log.v(tag, msg) }

    @JvmStatic
    public fun v(tag: String?, msg: String?, tr: Throwable?): Int = send(VERBOSE, tag, msg, tr) { android.util.Log.v(tag, msg, tr) }

    @JvmStatic
    public fun d(tag: String?, msg: String): Int = send(DEBUG, tag, msg, null) { android.util.Log.d(tag, msg) }

    @JvmStatic
    public fun d(tag: String?, msg: String?, tr: Throwable?): Int = send(DEBUG, tag, msg, tr) { android.util.Log.d(tag, msg, tr) }

    @JvmStatic
    public fun i(tag: String?, msg: String): Int = send(INFO, tag, msg, null) { android.util.Log.i(tag, msg) }

    @JvmStatic
    public fun i(tag: String?, msg: String?, tr: Throwable?): Int = send(INFO, tag, msg, tr) { android.util.Log.i(tag, msg, tr) }

    @JvmStatic
    public fun w(tag: String?, msg: String): Int = send(WARN, tag, msg, null) { android.util.Log.w(tag, msg) }

    @JvmStatic
    public fun w(tag: String?, msg: String?, tr: Throwable?): Int = send(WARN, tag, msg, tr) { android.util.Log.w(tag, msg, tr) }

    @JvmStatic
    public fun w(tag: String?, tr: Throwable?): Int = send(WARN, tag, null, tr) { android.util.Log.w(tag, tr) }

    @JvmStatic
    public fun e(tag: String?, msg: String): Int = send(ERROR, tag, msg, null) { android.util.Log.e(tag, msg) }

    @JvmStatic
    public fun e(tag: String?, msg: String?, tr: Throwable?): Int = send(ERROR, tag, msg, tr) { android.util.Log.e(tag, msg, tr) }

    @JvmStatic
    public fun wtf(tag: String?, msg: String?): Int = send(ASSERT, tag, msg, null) { android.util.Log.wtf(tag, msg) }

    @JvmStatic
    public fun wtf(tag: String?, tr: Throwable): Int = send(ASSERT, tag, null, tr) { android.util.Log.wtf(tag, tr) }

    @JvmStatic
    public fun wtf(tag: String?, msg: String?, tr: Throwable?): Int = send(ASSERT, tag, msg, tr) { android.util.Log.wtf(tag, msg, tr) }

    @JvmStatic
    public fun println(priority: Int, tag: String?, msg: String): Int = send(priority, tag, msg, null) { android.util.Log.println(priority, tag, msg) }

    @JvmStatic
    public fun isLoggable(tag: String?, level: Int): Boolean = android.util.Log.isLoggable(tag, level)

    @JvmStatic
    public fun getStackTraceString(tr: Throwable?): String = android.util.Log.getStackTraceString(tr)

    /** Sent first (it may be `wtf`, which can end the process), then printed as before. */
    private inline fun send(priority: Int, tag: String?, msg: String?, tr: Throwable?, print: () -> Int): Int {
        try {
            val severity = LogCapture.priority(priority)
            if (LogCapture.accepts(severity) && !LogCapture.isSdkThread()) {
                val body = msg?.takeIf { it.isNotEmpty() } ?: tr?.toString() ?: ""
                val attributes = Attributes.builder().put(LogCapture.SOURCE, "android.util.Log")
                if (!tag.isNullOrEmpty()) attributes.put(LogCapture.LOGGER, tag)
                LogCapture.emit(if (priority == ASSERT) Severity.FATAL else severity, body, attributes.build(), tr)
            }
        } catch (ignored: Throwable) {
            // A line that could not be copied is not worth an exception in the caller.
        }
        return print()
    }
}
