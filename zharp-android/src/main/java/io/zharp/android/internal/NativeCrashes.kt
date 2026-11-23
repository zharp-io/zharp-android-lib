package io.zharp.android.internal

import android.annotation.TargetApi
import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build

/**
 * What a native crash's report needs to know about the run it ended: the session and the screen.
 * Kept by Android with the process's exit record (`setProcessStateSummary`, 128 bytes at most),
 * since nothing of ours runs when a native crash kills the process.
 */
internal class ProcessState(val session: String?, val screen: String?) {
    fun encode(): String {
        val head = "$VERSION\n${session.orEmpty()}\n"
        val screen = screen.orEmpty().replace('\n', ' ')
        // Cut by bytes, never inside a character: the session always fits.
        val bytes = (head + screen).toByteArray(Charsets.UTF_8)
        if (bytes.size <= MAX_BYTES) return head + screen
        var cut = screen.length
        while (cut > 0 && (head + screen.substring(0, cut)).toByteArray(Charsets.UTF_8).size > MAX_BYTES) cut--
        if (cut > 0 && Character.isHighSurrogate(screen[cut - 1])) cut--
        return head + screen.substring(0, cut)
    }

    companion object {
        private const val VERSION = "z1"
        const val MAX_BYTES = 128

        fun decode(bytes: ByteArray?): ProcessState? {
            if (bytes == null || bytes.isEmpty()) return null
            val parts = String(bytes, Charsets.UTF_8).split('\n', limit = 3)
            if (parts.size < 2 || parts[0] != VERSION) return null
            return ProcessState(parts[1].takeIf { it.isNotEmpty() }, parts.getOrNull(2)?.takeIf { it.isNotEmpty() })
        }
    }
}

/**
 * Native (NDK) crashes, read on the next launch from Android's own record of how the app's
 * process ended (`ApplicationExitInfo`, `REASON_CRASH_NATIVE`). No signal handler and no native
 * code of ours: Android 12+ keeps the crash's tombstone with the record, and Android 11 records
 * that it happened. Older versions keep neither, so their native crashes aren't reported.
 *
 * Each crash is reported once: the newest exit already looked at is remembered. On the first run
 * with this SDK, exits from before it are left alone.
 */
internal object NativeCrashes {
    private const val KEY_LAST_EXIT = "native.lastExit"
    /** A process that crashed in a loop is reported a few times, not a hundred. */
    private const val MAX_PER_LAUNCH = 5

    /** Starts reading in the background; the app's launch never waits for it. */
    fun start(context: Context, store: KeyValueStore, runtime: () -> Runtime?) {
        if (Build.VERSION.SDK_INT < 30) return
        val thread = Thread({
            guarded(Unit, "Reading native crashes") { readExits(context, store, runtime) }
        }, "zharp-native-crashes")
        thread.isDaemon = true
        thread.start()
    }

    /** The process state writer for [Runtime]: Android 11+ keeps it with the exit record. */
    fun processStateWriter(context: Context): ((ByteArray) -> Unit)? {
        if (Build.VERSION.SDK_INT < 30) return null
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return null
        return { bytes -> setSummary(manager, bytes) }
    }

    @TargetApi(30)
    private fun setSummary(manager: ActivityManager, bytes: ByteArray) {
        manager.setProcessStateSummary(bytes)
    }

    @TargetApi(30)
    private fun readExits(context: Context, store: KeyValueStore, runtime: () -> Runtime?) {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
        val process = Application.getProcessName()
        val exits = manager.getHistoricalProcessExitReasons(context.packageName, 0, 0)
        val last = store.get(KEY_LAST_EXIT)?.toLongOrNull()
        val newest = exits.maxOfOrNull { it.timestamp } ?: 0L
        if (last == null) {
            // First run with native crash reporting: what happened before isn't ours to report.
            store.put(KEY_LAST_EXIT, maxOf(newest, System.currentTimeMillis()).toString())
            return
        }
        val crashes = exits
            .filter { it.reason == ApplicationExitInfo.REASON_CRASH_NATIVE && it.timestamp > last && it.processName == process }
            .sortedBy { it.timestamp }
            .takeLast(MAX_PER_LAUNCH)
        if (crashes.isEmpty()) {
            if (newest > last) store.put(KEY_LAST_EXIT, newest.toString())
            return
        }
        val current = runtime() ?: return
        for (exit in crashes) {
            val crash = read(exit)
            current.reportNativeCrash(crash, exit.timestamp, ProcessState.decode(exit.processStateSummary))
            // Saved to disk by now: moving the mark past it can't lose it.
            store.put(KEY_LAST_EXIT, exit.timestamp.toString())
            Diagnostics.debug("Reported a native crash from ${exit.timestamp}: ${crash.stacktrace.substringBefore('\n')}")
        }
        if (newest > crashes.last().timestamp) store.put(KEY_LAST_EXIT, newest.toString())
    }

    @TargetApi(30)
    private fun read(exit: ApplicationExitInfo): NativeCrash {
        if (Build.VERSION.SDK_INT >= 31) {
            val tombstone = guarded(null, "Reading a tombstone") {
                exit.traceInputStream?.use { Tombstone.parse(it.readBytes()) }
            }
            if (tombstone != null) return NativeCrash.fromTombstone(tombstone)
        }
        return NativeCrash.withoutStack(exit.status, Build.VERSION.SDK_INT)
    }
}
