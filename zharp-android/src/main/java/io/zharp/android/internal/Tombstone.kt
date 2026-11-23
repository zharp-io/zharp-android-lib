package io.zharp.android.internal

import java.io.IOException

/**
 * A native crash as Android 12+ records it: the protobuf tombstone that
 * `ApplicationExitInfo.getTraceInputStream()` returns for `REASON_CRASH_NATIVE`
 * (AOSP `system/core/debuggerd/proto/tombstone.proto`). Only what a crash report needs is read;
 * memory dumps, registers, open files, the log buffers and the memory map are skipped.
 *
 * Read with a small protobuf reader of our own, so the SDK carries no protobuf runtime.
 */
internal class Tombstone(
    val arch: Int,
    val buildFingerprint: String,
    val pid: Int,
    val tid: Int,
    val signal: Signal?,
    val abortMessage: String,
    val causes: List<String>,
    val threads: Map<Int, Thread>,
) {
    class Signal(
        val number: Int,
        val name: String,
        val code: Int,
        val codeName: String,
        val hasFaultAddress: Boolean,
        val faultAddress: Long,
    )

    class Thread(val id: Int, val name: String, val frames: List<Frame>)

    class Frame(
        /** The address as an offset into its ELF file (what symbols are looked up by). */
        val relPc: Long,
        val pc: Long,
        /** Named by the device from the file's own symbol table (often mangled), or "". */
        val functionName: String,
        val functionOffset: Long,
        /** "/data/app/~~…/lib/arm64/libshop.so", "/apex/com.android.runtime/lib64/bionic/libc.so", or "". */
        val fileName: String,
        val buildId: String,
    )

    /** The thread that crashed, or the first one there is. */
    val crashedThread: Thread?
        get() = threads[tid] ?: threads.values.firstOrNull()

    val archName: String
        get() = when (arch) {
            ARCH_ARM32 -> "arm"
            ARCH_ARM64 -> "arm64"
            ARCH_X86 -> "x86"
            ARCH_X86_64 -> "x86_64"
            ARCH_RISCV64 -> "riscv64"
            else -> ""
        }

    companion object {
        const val ARCH_ARM32 = 0
        const val ARCH_ARM64 = 1
        const val ARCH_X86 = 2
        const val ARCH_X86_64 = 3
        const val ARCH_RISCV64 = 4

        /** Reads a tombstone; throws [IOException] when the bytes aren't one. */
        fun parse(bytes: ByteArray): Tombstone {
            var arch = -1
            var fingerprint = ""
            var pid = 0
            var tid = 0
            var signal: Signal? = null
            var abort = ""
            val causes = ArrayList<String>()
            val threads = LinkedHashMap<Int, Thread>()
            val reader = ProtoReader(bytes, 0, bytes.size)
            while (reader.hasMore()) {
                val tag = reader.tag()
                when (tag ushr 3) {
                    1 -> arch = reader.int(tag)
                    2 -> fingerprint = reader.string(tag)
                    5 -> pid = reader.int(tag)
                    6 -> tid = reader.int(tag)
                    10 -> signal = parseSignal(reader.message(tag))
                    14 -> abort = reader.string(tag)
                    15 -> parseCause(reader.message(tag))?.let { causes += it }
                    16 -> parseThreadEntry(reader.message(tag))?.let { threads[it.id] = it }
                    else -> reader.skip(tag)
                }
            }
            if (threads.isEmpty() && signal == null) throw IOException("Not a tombstone: no threads and no signal")
            return Tombstone(arch, fingerprint, pid, tid, signal, abort, causes, threads)
        }

        private fun parseSignal(reader: ProtoReader): Signal {
            var number = 0
            var name = ""
            var code = 0
            var codeName = ""
            var hasFault = false
            var fault = 0L
            while (reader.hasMore()) {
                val tag = reader.tag()
                when (tag ushr 3) {
                    1 -> number = reader.int(tag)
                    2 -> name = reader.string(tag)
                    3 -> code = reader.int(tag)
                    4 -> codeName = reader.string(tag)
                    8 -> hasFault = reader.int(tag) != 0
                    9 -> fault = reader.long(tag)
                    else -> reader.skip(tag)
                }
            }
            return Signal(number, name, code, codeName, hasFault, fault)
        }

        private fun parseCause(reader: ProtoReader): String? {
            var text: String? = null
            while (reader.hasMore()) {
                val tag = reader.tag()
                if (tag ushr 3 == 1) text = reader.string(tag) else reader.skip(tag)
            }
            return text?.takeIf { it.isNotBlank() }
        }

        /** One entry of `map<uint32, Thread> threads`: key 1, value 2. */
        private fun parseThreadEntry(reader: ProtoReader): Thread? {
            var thread: Thread? = null
            while (reader.hasMore()) {
                val tag = reader.tag()
                if (tag ushr 3 == 2) thread = parseThread(reader.message(tag)) else reader.skip(tag)
            }
            return thread
        }

        private fun parseThread(reader: ProtoReader): Thread {
            var id = 0
            var name = ""
            val frames = ArrayList<Frame>()
            while (reader.hasMore()) {
                val tag = reader.tag()
                when (tag ushr 3) {
                    1 -> id = reader.int(tag)
                    2 -> name = reader.string(tag)
                    4 -> frames += parseFrame(reader.message(tag))
                    else -> reader.skip(tag)
                }
            }
            return Thread(id, name, frames)
        }

        private fun parseFrame(reader: ProtoReader): Frame {
            var relPc = 0L
            var pc = 0L
            var function = ""
            var functionOffset = 0L
            var file = ""
            var buildId = ""
            while (reader.hasMore()) {
                val tag = reader.tag()
                when (tag ushr 3) {
                    1 -> relPc = reader.long(tag)
                    2 -> pc = reader.long(tag)
                    4 -> function = reader.string(tag)
                    5 -> functionOffset = reader.long(tag)
                    6 -> file = reader.string(tag)
                    8 -> buildId = reader.string(tag)
                    else -> reader.skip(tag)
                }
            }
            return Frame(relPc, pc, function, functionOffset, file, buildId)
        }
    }
}

/** The protobuf wire format, read-only: varints, fixed 32/64-bit values, length-delimited fields. */
internal class ProtoReader(private val bytes: ByteArray, private var position: Int, private val end: Int) {
    fun hasMore(): Boolean = position < end

    fun tag(): Int {
        val tag = varint().toInt()
        if (tag ushr 3 == 0) throw IOException("Field number 0 at $position")
        return tag
    }

    fun long(tag: Int): Long = when (tag and 7) {
        WIRE_VARINT -> varint()
        WIRE_FIXED64 -> fixed(8)
        WIRE_FIXED32 -> fixed(4)
        else -> throw IOException("Expected a number, wire type ${tag and 7}")
    }

    fun int(tag: Int): Int = long(tag).toInt()

    fun string(tag: Int): String {
        val length = length(tag)
        val text = String(bytes, position, length, Charsets.UTF_8)
        position += length
        return text
    }

    fun message(tag: Int): ProtoReader {
        val length = length(tag)
        val inner = ProtoReader(bytes, position, position + length)
        position += length
        return inner
    }

    fun skip(tag: Int) {
        when (tag and 7) {
            WIRE_VARINT -> varint()
            WIRE_FIXED64 -> advance(8)
            WIRE_LENGTH -> advance(length(tag))
            WIRE_FIXED32 -> advance(4)
            else -> throw IOException("Unsupported wire type ${tag and 7}")
        }
    }

    private fun length(tag: Int): Int {
        if (tag and 7 != WIRE_LENGTH) throw IOException("Expected a length-delimited field, wire type ${tag and 7}")
        val length = varint()
        if (length < 0 || length > end - position) throw IOException("Truncated field: $length bytes at $position")
        return length.toInt()
    }

    private fun advance(count: Int) {
        if (count > end - position) throw IOException("Truncated at $position")
        position += count
    }

    private fun varint(): Long {
        var result = 0L
        var shift = 0
        while (shift < 64) {
            if (position >= end) throw IOException("Truncated varint")
            val b = bytes[position++].toInt()
            result = result or ((b and 0x7f).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
        }
        throw IOException("Malformed varint")
    }

    private fun fixed(count: Int): Long {
        advance(count)
        var result = 0L
        for (i in 0 until count) result = result or ((bytes[position - count + i].toLong() and 0xff) shl (8 * i))
        return result
    }

    private companion object {
        const val WIRE_VARINT = 0
        const val WIRE_FIXED64 = 1
        const val WIRE_LENGTH = 2
        const val WIRE_FIXED32 = 5
    }
}
