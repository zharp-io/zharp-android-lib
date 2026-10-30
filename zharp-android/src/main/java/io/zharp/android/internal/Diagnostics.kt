package io.zharp.android.internal

import android.util.Log

/**
 * The SDK's own Logcat lines, under one tag and prefix so they can be searched for. Startup and
 * a rejected key are always said; everything else only with `debug = true`.
 */
internal object Diagnostics {
    const val TAG: String = "Zharp"

    @Volatile
    var debug: Boolean = false

    fun info(message: String) {
        safely { Log.i(TAG, "[Zharp] $message") }
    }

    fun warn(message: String, error: Throwable? = null) {
        safely { if (error != null) Log.w(TAG, "[Zharp] $message", error) else Log.w(TAG, "[Zharp] $message") }
    }

    fun debug(message: String) {
        if (debug) safely { Log.d(TAG, "[Zharp] $message") }
    }

    private inline fun safely(block: () -> Unit) {
        try {
            block()
        } catch (ignored: Throwable) {
            // Logcat itself failing (or absent, in a JVM test) is no reason to fail.
        }
    }
}

/** Run [block], swallowing anything it throws: the SDK never takes the app down. */
internal inline fun <T> guarded(fallback: T, what: String, block: () -> T): T =
    try {
        block()
    } catch (error: Throwable) {
        Diagnostics.debug("$what failed: $error")
        fallback
    }
