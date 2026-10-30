package io.zharp.android.internal

import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicLong

/**
 * Batches that couldn't be sent, kept on disk (gzipped OTLP, exactly what would have been POSTed)
 * so they survive going offline, the app being killed, and crashes. Sent oldest first once
 * sending works again. Capped by count, total size and age; when full, the oldest go.
 */
internal class Outbox(
    private val dir: File,
    private val maxFiles: Int = 200,
    private val maxBytes: Long = 8L * 1024 * 1024,
    private val maxAgeMillis: Long = 3L * 24 * 60 * 60 * 1000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val sequence = AtomicLong()

    /** Save a batch; returns its file, or null if it couldn't be written. */
    @Synchronized
    fun save(signal: Signal, gzipped: ByteArray): File? = guarded(null, "Saving a batch") {
        if (!dir.isDirectory && !dir.mkdirs()) return@guarded null
        val name = String.format(java.util.Locale.ROOT, "%013d-%06d.%s", clock(), sequence.incrementAndGet() % 1_000_000, signal.name.lowercase())
        val temp = File(dir, "$name.tmp")
        FileOutputStream(temp).use { out ->
            out.write(gzipped)
            out.fd.sync()
        }
        val file = File(dir, name)
        if (!temp.renameTo(file)) {
            temp.delete()
            return@guarded null
        }
        trim()
        file
    }

    /** What's waiting, oldest first, with anything over the caps already deleted. */
    @Synchronized
    fun pending(): List<File> = guarded(emptyList(), "Reading the outbox") {
        trim()
        files()
    }

    fun signalOf(file: File): Signal? = Signal.values().firstOrNull { file.name.endsWith("." + it.name.lowercase()) }

    fun delete(file: File) {
        guarded(Unit, "Deleting a sent batch") { file.delete() }
    }

    fun isEmpty(): Boolean = guarded(true, "Reading the outbox") { files().isEmpty() }

    private fun files(): List<File> =
        (dir.listFiles() ?: emptyArray()).filter { it.isFile && !it.name.endsWith(".tmp") && signalOf(it) != null }.sortedBy { it.name }

    private fun trim() {
        val now = clock()
        // Leftovers of a write the process didn't live to finish.
        (dir.listFiles() ?: emptyArray()).filter { it.name.endsWith(".tmp") && now - it.lastModified() > 60_000 }.forEach { it.delete() }

        val all = files().toMutableList()
        val iterator = all.iterator()
        while (iterator.hasNext()) {
            val file = iterator.next()
            val savedAt = file.name.substringBefore('-').toLongOrNull() ?: 0L
            if (now - savedAt > maxAgeMillis) {
                file.delete()
                iterator.remove()
            }
        }
        var total = all.sumOf { it.length() }
        while (all.isNotEmpty() && (all.size > maxFiles || total > maxBytes)) {
            val oldest = all.removeAt(0)
            total -= oldest.length()
            oldest.delete()
        }
    }
}
