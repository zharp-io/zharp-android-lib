package io.zharp.android.internal

import java.util.Locale

/**
 * A native (NDK) crash as Zharp reads it: the signal as the exception type, the crashed thread
 * in the `native` frame style the backend parses and symbolicates (`#0  fn [libshop.so +0x1a2b4]`,
 * or `#0  libshop.so +0x1a2b4` without a name), and each app module's GNU build ID, by which the
 * backend finds the `.sym` file the Gradle plugin uploaded for that build of the library.
 */
internal class NativeCrash(
    val type: String,
    val message: String,
    /** The headline (`SIGSEGV: SEGV_MAPERR at 0x0`), then the crashed thread's frames. */
    val stacktrace: String,
    val threadName: String?,
    /** Context: other threads, modules, CPU, the fault address, and `zharp.native_build_ids`. */
    val attributes: Map<String, String>,
) {
    companion object {
        /** Large stacks (deep recursion) and many threads are cut, as for minidumps. */
        private const val MAX_FRAMES = 100
        private const val MAX_THREADS = 50
        private const val MAX_THREAD_FRAMES = 30
        /** Attribute values are capped at 16 KB at ingest; these stay under it. */
        private const val MAX_ATTRIBUTE = 16 * 1024 - 64
        private const val MAX_MESSAGE = 1_000

        /** Where a module lives says whose it is: the app's own folder (or APK), or the system's. */
        private val SYSTEM_PATH = Regex("^/(system|system_ext|apex|vendor|product|odm)/")
        private val SHARED_LIBRARY = Regex("\\.so(\\.|$)")

        /** From an Android 12+ tombstone. */
        fun fromTombstone(tombstone: Tombstone): NativeCrash {
            val signal = tombstone.signal
            val type = signalName(signal?.name, signal?.number ?: 0)
            val message = message(tombstone, type)
            val headline = if (message.isEmpty()) type else "$type: $message"
            val crashed = tombstone.crashedThread
            val names = ModuleNames(tombstone.threads.values.flatMap { thread -> thread.frames.map { it.fileName } })

            val stack = StringBuilder(headline)
            crashed?.frames?.take(MAX_FRAMES)?.forEachIndexed { index, frame -> stack.append('\n').append(line(index, frame, names)) }

            val attributes = LinkedHashMap<String, String>()
            if (signal != null && signal.hasFaultAddress) attributes["native.crash_address"] = hex(signal.faultAddress)
            tombstone.archName.takeIf { it.isNotEmpty() }?.let { attributes["native.cpu"] = it }
            val others = tombstone.threads.values
                .filter { it !== crashed }
                .take(MAX_THREADS)
                .joinToString("\n\n") { thread ->
                    val title = "Thread ${thread.id}" + (thread.name.takeIf { it.isNotEmpty() }?.let { " ($it)" } ?: "")
                    (listOf(title) + thread.frames.take(MAX_THREAD_FRAMES).mapIndexed { index, frame -> line(index, frame, names) }).joinToString("\n")
                }
            if (others.isNotEmpty()) attributes["native.threads"] = cap(others)

            // Every module a frame is in, with its build ID: the Advanced fold's list.
            val modules = LinkedHashMap<String, String>()
            for (thread in tombstone.threads.values) {
                for (frame in thread.frames) if (frame.fileName.isNotEmpty() && frame.fileName !in modules) modules[frame.fileName] = frame.buildId
            }
            if (modules.isNotEmpty()) attributes["native.modules"] = cap(modules.entries.joinToString("\n") { (path, id) -> if (id.isEmpty()) path else "$path  $id" })

            // The app's own libraries, by the name its frames use: what the backend symbolicates.
            // (Its compiled Java, base.odex and the like, has no symbols to upload.)
            val buildIds = modules.entries
                .filter { (path, id) -> id.isNotEmpty() && isAppModule(path) && SHARED_LIBRARY.containsMatchIn(names.of(path)) }
                .map { (path, id) -> "${id.lowercase(Locale.ROOT)} ${names.of(path)}" }
                .distinct()
            if (buildIds.isNotEmpty()) attributes[BUILD_IDS] = cap(buildIds.joinToString("\n"))
            tombstone.buildFingerprint.takeIf { it.isNotEmpty() }?.let { attributes["android.build.fingerprint"] = it }

            return NativeCrash(type, message, stack.toString(), crashed?.name?.takeIf { it.isNotEmpty() }, attributes)
        }

        /**
         * A crash Android recorded without its tombstone (always on Android 11, which keeps none):
         * the signal, so these crashes still count and group by what killed the process.
         */
        fun withoutStack(signalNumber: Int, apiLevel: Int): NativeCrash {
            val type = signalName(null, signalNumber)
            val message = if (apiLevel < 31) {
                "No stack: Android 11 doesn't record one for native crashes (Android 12 and later do)"
            } else {
                "No stack: Android kept no tombstone for this crash"
            }
            return NativeCrash(type, message, "$type: $message", null, emptyMap())
        }

        const val BUILD_IDS = "zharp.native_build_ids"

        private fun message(tombstone: Tombstone, type: String): String {
            val signal = tombstone.signal
            val abort = tombstone.abortMessage.trim()
            val text = when {
                abort.isNotEmpty() -> abort
                type == "SIGABRT" -> "The process aborted (abort() or an uncaught C++ exception)"
                else -> {
                    val cause = tombstone.causes.firstOrNull()
                    val nullPointer = signal != null && signal.hasFaultAddress && signal.faultAddress in 0 until 0x1000
                    listOfNotNull(
                        signal?.codeName?.takeIf { it.isNotEmpty() },
                        if (signal != null && signal.hasFaultAddress) "at ${hex(signal.faultAddress)}" else null,
                        cause?.let { "($it)" } ?: if (nullPointer) "(null pointer)" else null,
                    ).joinToString(" ")
                }
            }
            return if (text.length > MAX_MESSAGE) text.substring(0, MAX_MESSAGE - 1) + "…" else text
        }

        private fun line(index: Int, frame: Tombstone.Frame, names: ModuleNames): String {
            val number = "#$index".padEnd(3)
            val module = if (frame.fileName.isEmpty()) "???" else names.of(frame.fileName)
            val offset = hex(frame.relPc)
            val function = frame.functionName.trim()
            return if (function.isEmpty()) "$number $module +$offset" else "$number $function [$module +$offset]"
        }

        fun isAppModule(path: String): Boolean = !SYSTEM_PATH.containsMatchIn(path) && (path.startsWith("/data/") || path.contains(".apk!") || path.startsWith("/mnt/"))

        private val SIGNALS = mapOf(
            1 to "SIGHUP", 2 to "SIGINT", 3 to "SIGQUIT", 4 to "SIGILL", 5 to "SIGTRAP", 6 to "SIGABRT", 7 to "SIGBUS",
            8 to "SIGFPE", 9 to "SIGKILL", 11 to "SIGSEGV", 13 to "SIGPIPE", 15 to "SIGTERM", 31 to "SIGSYS",
        )

        fun signalName(name: String?, number: Int): String =
            name?.trim()?.takeIf { it.isNotEmpty() } ?: SIGNALS[number] ?: if (number > 0) "Signal $number" else "NativeCrash"

        /** "0x0", "0x1a2b4": as the backend's native frames write offsets. */
        fun hex(value: Long): String = "0x" + java.lang.Long.toHexString(value)

        private fun cap(text: String): String = if (text.length > MAX_ATTRIBUTE) text.substring(0, MAX_ATTRIBUTE - 1) + "…" else text
    }

    /**
     * How frames name their module: the file name (`libshop.so`, `libc.so`), or what follows the
     * `!` of a library loaded straight from the APK. A system library whose file name an app
     * library also has keeps its full path, so the two are never confused for each other.
     */
    private class ModuleNames(paths: List<String>) {
        private val appNames: Set<String> = paths.filter { isAppModule(it) }.map { short(it) }.toSet()

        fun of(path: String): String {
            val name = short(path)
            return if (!isAppModule(path) && name in appNames) path else name
        }

        private fun short(path: String): String {
            val inApk = path.substringAfterLast('!', "")
            if (inApk.isNotEmpty()) return inApk.substringAfterLast('/')
            if (path.startsWith("[")) return path
            return path.substringAfterLast('/').ifEmpty { path }
        }
    }
}
