package io.zharp.android

import io.opentelemetry.api.logs.Severity
import io.zharp.android.internal.guarded

/**
 * Logs sent to Zharp, as `Zharp.log.info("Checkout opened", mapOf("items" to 3))`. Each carries
 * the session, the user, the screen and — when called inside a span — the trace, so a log line
 * leads to what the user was doing. Logcat is not read; only these are sent.
 */
public class ZharpLog internal constructor() {
    @JvmOverloads
    public fun debug(message: String, attributes: Map<String, Any?>? = null) {
        emit(Severity.DEBUG, message, attributes, null)
    }

    @JvmOverloads
    public fun info(message: String, attributes: Map<String, Any?>? = null) {
        emit(Severity.INFO, message, attributes, null)
    }

    @JvmOverloads
    public fun warn(message: String, attributes: Map<String, Any?>? = null) {
        emit(Severity.WARN, message, attributes, null)
    }

    public fun error(message: String) {
        emit(Severity.ERROR, message, null, null)
    }

    public fun error(message: String, attributes: Map<String, Any?>?) {
        emit(Severity.ERROR, message, attributes, null)
    }

    /** An error log with a caught exception: also reported as a handled error, with its stack. */
    @JvmOverloads
    public fun error(message: String, error: Throwable?, attributes: Map<String, Any?>? = null) {
        emit(Severity.ERROR, message, attributes, error)
    }

    private fun emit(severity: Severity, message: String, attributes: Map<String, Any?>?, error: Throwable?) {
        val runtime = Zharp.runtime ?: return
        guarded(Unit, "Logging") { runtime.log(severity, message, attributes, error) }
    }
}
