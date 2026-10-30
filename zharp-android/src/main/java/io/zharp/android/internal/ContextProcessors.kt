package io.zharp.android.internal

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.context.Context
import io.opentelemetry.sdk.logs.LogRecordProcessor
import io.opentelemetry.sdk.logs.ReadWriteLogRecord
import io.opentelemetry.sdk.trace.ReadWriteSpan
import io.opentelemetry.sdk.trace.ReadableSpan
import io.opentelemetry.sdk.trace.SpanProcessor

/**
 * Puts the session, the user, the current screen and the app's tags on every span and log record
 * as it starts, so each one says who and where on its own, whichever batch it travels in.
 */
internal class ContextStamp(private val session: SessionManager, private val context: UserContext) {
    fun stamp(set: (AttributeKey<String>, String) -> Unit, has: (AttributeKey<String>) -> Boolean) {
        for ((key, value) in context.tags) {
            val attribute = AttributeKey.stringKey(key)
            if (!has(attribute)) set(attribute, value)
        }
        set(Keys.SESSION_ID, session.current())
        context.user?.let { user ->
            user.id?.let { set(Keys.USER_ID, it) }
            user.email?.let { set(Keys.USER_EMAIL, it) }
            user.name?.let { set(Keys.USER_NAME, it) }
        }
        val screen = context.screen
        if (screen != null && !has(Keys.SCREEN_NAME)) set(Keys.SCREEN_NAME, screen)
    }
}

internal class ContextSpanProcessor(private val stamp: ContextStamp) : SpanProcessor {
    override fun onStart(parentContext: Context, span: ReadWriteSpan) {
        guarded(Unit, "Stamping a span") {
            stamp.stamp({ key, value -> span.setAttribute(key, value) }, { key -> span.getAttribute(key) != null })
        }
    }

    override fun isStartRequired(): Boolean = true
    override fun onEnd(span: ReadableSpan) {}
    override fun isEndRequired(): Boolean = false
}

internal class ContextLogProcessor(private val stamp: ContextStamp) : LogRecordProcessor {
    override fun onEmit(context: Context, logRecord: ReadWriteLogRecord) {
        guarded(Unit, "Stamping a log") {
            stamp.stamp({ key, value -> logRecord.setAttribute(key, value) }, { key -> logRecord.getAttribute(key) != null })
        }
    }
}
