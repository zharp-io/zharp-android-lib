package io.zharp.android.internal

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.common.AttributesBuilder
import java.io.PrintWriter
import java.io.StringWriter

internal object Errors {
    /** Zharp stores 16 KB of a stack; a little more leaves room for the server to cut it. */
    private const val MAX_STACK_CHARS = 32 * 1024

    /**
     * The JVM's own stack text: first line `java.lang.IllegalStateException: message`, then
     * `\tat …` frames and the `Caused by:` chain, which is what Zharp groups issues by.
     */
    fun stackTrace(error: Throwable): String {
        val text = try {
            val writer = StringWriter()
            PrintWriter(writer).use { error.printStackTrace(it) }
            writer.toString()
        } catch (failed: Throwable) {
            error.toString()
        }
        return if (text.length > MAX_STACK_CHARS) text.substring(0, MAX_STACK_CHARS) else text
    }

    /** exception.* plus zharp.handled, as the server turns a log record into an error event. */
    fun attributes(error: Throwable, handled: Boolean): AttributesBuilder =
        Attributes.builder()
            .put(Keys.EXCEPTION_TYPE, error.javaClass.name)
            .put(Keys.EXCEPTION_MESSAGE, error.message ?: "")
            .put(Keys.EXCEPTION_STACKTRACE, stackTrace(error))
            .put(Keys.HANDLED, handled.toString())

    /** A stack captured from another thread (an ANR's main thread), in the same text shape. */
    fun stackTrace(type: String, message: String, frames: Array<StackTraceElement>): String {
        val text = StringBuilder("$type: $message")
        for (frame in frames) text.append("\n\tat ").append(frame)
        return if (text.length > MAX_STACK_CHARS) text.substring(0, MAX_STACK_CHARS) else text.toString()
    }
}

/** A caller's `Map<String, Any?>` as OpenTelemetry attributes; anything unusual becomes its text. */
internal fun AttributesBuilder.putAll(values: Map<String, Any?>?): AttributesBuilder {
    if (values == null) return this
    for ((key, value) in values) {
        if (key.isBlank() || value == null) continue
        when (value) {
            is String -> put(key, value)
            is Boolean -> put(key, value)
            is Int -> put(key, value.toLong())
            is Long -> put(key, value)
            is Short -> put(key, value.toLong())
            is Byte -> put(key, value.toLong())
            is Double -> put(key, value)
            is Float -> put(key, value.toDouble())
            is Collection<*> -> put(AttributeKey.stringArrayKey(key), value.map { it.toString() })
            is Array<*> -> put(AttributeKey.stringArrayKey(key), value.map { it.toString() })
            else -> put(key, value.toString())
        }
    }
    return this
}

internal fun attributesOf(values: Map<String, Any?>?): Attributes = Attributes.builder().putAll(values).build()
