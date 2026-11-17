package io.zharp.android

import io.opentelemetry.api.logs.Severity

/** A log level, as Logcat names them: the lowest one [ZharpOptions.captureOutput] sends. */
public enum class LogLevel(internal val severity: Severity, internal val letter: Char) {
    VERBOSE(Severity.TRACE, 'V'),
    DEBUG(Severity.DEBUG, 'D'),
    INFO(Severity.INFO, 'I'),
    WARN(Severity.WARN, 'W'),
    ERROR(Severity.ERROR, 'E'),
}
