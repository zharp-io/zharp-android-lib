package io.zharp.android.internal

import android.os.Debug
import android.os.Handler
import android.os.Looper

/**
 * Uncaught exceptions: reported (written to disk first, then a bounded try at sending), then
 * handed to the handler that was there before, so the app crashes exactly as it would have and
 * other crash reporters still see it. Installed once per process; it asks for the running SDK at
 * crash time.
 */
internal object CrashHandler {
    private var installed = false

    @Volatile
    private var reporting = false

    @Synchronized
    fun install(runtime: () -> Runtime?) {
        if (installed) return
        installed = true
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // A crash inside the report (or a second thread crashing meanwhile) goes straight on.
            if (!reporting) {
                reporting = true
                try {
                    runtime()?.reportCrash(thread, error)
                } catch (ignored: Throwable) {
                }
            }
            if (previous != null) {
                previous.uncaughtException(thread, error)
            } else {
                android.os.Process.killProcess(android.os.Process.myPid())
                System.exit(10)
            }
        }
    }
}

/**
 * ANR detection, the way Android's own watchdog sees it: a task posted to the main thread that
 * hasn't run within [thresholdMillis] means the UI is frozen. Reported once per freeze, with the
 * main thread's stack at that moment. Skipped while a debugger is attached (breakpoints freeze
 * the main thread too).
 */
internal class AnrWatchdog(
    private val runtime: () -> Runtime?,
    private val thresholdMillis: Long,
) : Thread("zharp-anr") {
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var answered = true

    init {
        isDaemon = true
    }

    override fun run() {
        val pong = Runnable { answered = true }
        try {
            while (!isInterrupted) {
                answered = false
                main.post(pong)
                sleep(thresholdMillis)
                if (answered) continue
                if (Debug.isDebuggerConnected() || Debug.waitingForDebugger()) continue
                val frames = Looper.getMainLooper().thread.stackTrace
                guarded(Unit, "Reporting an ANR") { runtime()?.reportAnr(frames, thresholdMillis) }
                // Once per freeze: wait for the main thread to come back before watching again.
                while (!answered && !isInterrupted) sleep(POLL_MILLIS)
            }
        } catch (stopped: InterruptedException) {
            // Shutting down.
        } catch (error: Throwable) {
            Diagnostics.debug("ANR detection stopped: $error")
        }
    }

    companion object {
        private const val POLL_MILLIS = 500L
    }
}
