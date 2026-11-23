package io.zharp.android

import io.opentelemetry.api.common.Attributes
import io.zharp.android.internal.Keys
import io.zharp.android.internal.MemoryStore
import io.zharp.android.internal.NativeCrash
import io.zharp.android.internal.ProcessState
import io.zharp.android.internal.Runtime
import io.zharp.android.internal.Settings
import io.zharp.android.internal.Tombstone
import io.zharp.android.internal.Transport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Android 12+ tombstones (protobuf) read into a native crash report, and that report sent. */
class NativeCrashTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val app = "/data/app/~~Xy12==/io.zharp.sample-AbC==/lib/arm64/libshop.so"
    private val appId = "4F1C2A3B5D6E7F8091A2B3C4D5E6F7081920A1B2"

    /** A tombstone as debuggerd writes it, built with a small protobuf writer. */
    private fun tombstone(
        signal: ByteArray? = Proto().int(1, 11).string(2, "SIGSEGV").int(3, 1).string(4, "SEGV_MAPERR").int(8, 1).long(9, 0).bytes(),
        abort: String? = null,
        cause: String? = "null pointer dereference",
    ): ByteArray {
        val crashed = Proto().int(1, 4242).string(2, "Thread-2")
            .message(4, frame(0x1a2b4, "", app, appId))
            .message(4, frame(0x1a2f0, "Java_io_zharp_sample_Native_crash", app, appId))
            .message(4, frame(0x2f4c, "", "/data/app/~~Xy12==/io.zharp.sample-AbC==/base.apk!libcrypto.so", "aa11"))
            .message(4, frame(0x3a1d40, "art_quick_generic_jni_trampoline", "/apex/com.android.art/lib64/libart.so", "bb22"))
            .message(4, frame(0x5c2d4, "", "/apex/com.android.runtime/lib64/bionic/libcrypto.so", "cc33"))
            .message(4, frame(0x1000, "io.zharp.sample.MainActivity.onClick", "/data/app/~~Xy12==/io.zharp.sample-AbC==/oat/arm64/base.odex", "dd44"))
            .bytes()
        val main = Proto().int(1, 4200).string(2, "io.zharp.sample")
            .message(4, frame(0x9c5e8, "__epoll_pwait", "/apex/com.android.runtime/lib64/bionic/libc.so", "ee55"))
            .bytes()
        val out = Proto().int(1, 1).string(2, "google/sdk_gphone64_arm64/emu64a:14/UE1A/123:user/release-keys")
            .int(5, 4200).int(6, 4242)
        if (signal != null) out.message(10, signal)
        if (abort != null) out.string(14, abort)
        if (cause != null) out.message(15, Proto().string(1, cause).bytes())
        out.message(16, Proto().int(1, 4200).message(2, main).bytes())
        out.message(16, Proto().int(1, 4242).message(2, crashed).bytes())
        // Fields the reader skips: a memory mapping, a log buffer, an unknown fixed32.
        out.message(17, Proto().long(1, 0x7000).string(7, app).bytes())
        out.message(18, Proto().string(1, "main").bytes())
        out.fixed32(99, 7)
        return out.bytes()
    }

    private fun frame(relPc: Long, function: String, file: String, buildId: String): ByteArray =
        Proto().long(1, relPc).long(2, 0x7000000000L + relPc).string(4, function).long(5, 16).string(6, file).string(8, buildId).bytes()

    @Test
    fun readsATombstone() {
        val t = Tombstone.parse(tombstone())
        assertEquals("arm64", t.archName)
        assertEquals(4242, t.tid)
        assertEquals("SIGSEGV", t.signal!!.name)
        assertEquals("SEGV_MAPERR", t.signal!!.codeName)
        assertTrue(t.signal!!.hasFaultAddress)
        assertEquals(listOf("null pointer dereference"), t.causes)
        assertEquals(2, t.threads.size)
        val crashed = t.crashedThread!!
        assertEquals("Thread-2", crashed.name)
        assertEquals(6, crashed.frames.size)
        assertEquals(0x1a2b4L, crashed.frames[0].relPc)
        assertEquals(appId, crashed.frames[0].buildId)
        assertEquals("Java_io_zharp_sample_Native_crash", crashed.frames[1].functionName)
    }

    @Test
    fun theCrashedThreadInTheNativeFrameStyle() {
        val crash = NativeCrash.fromTombstone(Tombstone.parse(tombstone()))
        assertEquals("SIGSEGV", crash.type)
        assertEquals("SEGV_MAPERR at 0x0 (null pointer dereference)", crash.message)
        assertEquals("Thread-2", crash.threadName)
        assertEquals(
            listOf(
                "SIGSEGV: SEGV_MAPERR at 0x0 (null pointer dereference)",
                "#0  libshop.so +0x1a2b4",
                "#1  Java_io_zharp_sample_Native_crash [libshop.so +0x1a2f0]",
                // Loaded straight from the APK: named by what follows the "!".
                "#2  libcrypto.so +0x2f4c",
                "#3  art_quick_generic_jni_trampoline [libart.so +0x3a1d40]",
                // The system's libcrypto.so, which the app also has one of: its full path.
                "#4  /apex/com.android.runtime/lib64/bionic/libcrypto.so +0x5c2d4",
                "#5  io.zharp.sample.MainActivity.onClick [base.odex +0x1000]",
            ),
            crash.stacktrace.lines(),
        )
        val a = crash.attributes
        // Only the app's own libraries: the backend looks their symbols up by these.
        assertEquals("${appId.lowercase()} libshop.so\naa11 libcrypto.so", a["zharp.native_build_ids"])
        assertEquals("0x0", a["native.crash_address"])
        assertEquals("arm64", a["native.cpu"])
        assertEquals("Thread 4200 (io.zharp.sample)\n#0  __epoll_pwait [libc.so +0x9c5e8]", a["native.threads"])
        assertTrue(a["native.modules"]!!.lines().contains("$app  $appId"))
        assertTrue(a["android.build.fingerprint"]!!.startsWith("google/sdk_gphone64_arm64"))
    }

    @Test
    fun anAbortSaysItsMessage() {
        val abort = Proto().int(1, 6).string(2, "SIGABRT").int(3, -1).string(4, "SI_TKILL").bytes()
        val withMessage = NativeCrash.fromTombstone(Tombstone.parse(tombstone(signal = abort, abort = "Cart total went negative: -3", cause = null)))
        assertEquals("SIGABRT", withMessage.type)
        assertEquals("Cart total went negative: -3", withMessage.message)
        assertFalse(withMessage.attributes.containsKey("native.crash_address"))
        val plain = NativeCrash.fromTombstone(Tombstone.parse(tombstone(signal = abort, cause = null)))
        assertEquals("The process aborted (abort() or an uncaught C++ exception)", plain.message)
    }

    @Test
    fun aNullPointerWithoutACauseIsSaidSo() {
        val crash = NativeCrash.fromTombstone(Tombstone.parse(tombstone(cause = null)))
        assertEquals("SEGV_MAPERR at 0x0 (null pointer)", crash.message)
    }

    @Test
    fun android11HasTheSignalOnly() {
        val crash = NativeCrash.withoutStack(11, 30)
        assertEquals("SIGSEGV", crash.type)
        assertTrue(crash.message.startsWith("No stack: Android 11"))
        assertEquals("SIGSEGV: ${crash.message}", crash.stacktrace)
        assertEquals("NativeCrash", NativeCrash.withoutStack(0, 30).type)
    }

    @Test
    fun whatIsNotATombstoneIsRefused() {
        for (bytes in listOf(ByteArray(0), byteArrayOf(0x0a, 0x7f, 0x01), "plain text".toByteArray())) {
            try {
                Tombstone.parse(bytes)
                fail("parsed ${bytes.size} bytes")
            } catch (expected: IOException) {
            }
        }
    }

    @Test
    fun processStateRoundTripsAndFitsAndroidsLimit() {
        val state = ProcessState("0123456789abcdef0123456789abcdef", "CheckoutActivity")
        val back = ProcessState.decode(state.encode().toByteArray())!!
        assertEquals("0123456789abcdef0123456789abcdef", back.session)
        assertEquals("CheckoutActivity", back.screen)

        val long = ProcessState("0123456789abcdef0123456789abcdef", "Écran ".repeat(40)).encode()
        assertTrue(long.toByteArray().size <= ProcessState.MAX_BYTES)
        assertEquals("0123456789abcdef0123456789abcdef", ProcessState.decode(long.toByteArray())!!.session)
        assertNull(ProcessState.decode(null))
        assertNull(ProcessState.decode("something an app set itself".toByteArray()))
        assertNull(ProcessState.decode(ProcessState("abc", null).encode().toByteArray())!!.screen)
    }

    @Test
    fun aNativeCrashIsSentDatedAndInItsOwnSession() {
        val collector = FakeCollector()
        val written = mutableListOf<String>()
        val settings = Settings(
            key = "zpk_test", service = "shop-android", environment = "test", release = "2.3.0 (45)",
            endpoint = collector.endpoint, tracingOrigins = emptyList(), sampleRate = 1.0, scheduleDelayMillis = 100,
        )
        val runtime = Runtime(
            settings, Attributes.builder().put(Keys.OS_NAME, "Android").build(), MemoryStore(), folder.newFolder(), Zharp.user,
            Transport(collector.endpoint, settings.key, Runtime.USER_AGENT, retryDelaysMillis = longArrayOf(10, 10)),
            processState = { written += String(it) },
        )
        try {
            runtime.start()
            runtime.screenView("CartActivity", "activity", 1L, 2L)
            runtime.screenView("CartActivity", "activity", 3L, 4L)
            // Handed to Android once per change: at start, then for the new screen.
            assertEquals(2, written.size)
            assertEquals("CartActivity", ProcessState.decode(written.last().toByteArray())!!.screen)

            val crashedAt = 1_763_000_000_000L
            runtime.reportNativeCrash(
                NativeCrash.fromTombstone(Tombstone.parse(tombstone())), crashedAt,
                ProcessState("feedfacefeedfacefeedfacefeedface", "CheckoutActivity"),
            )
            assertTrue(eventually { collector.logRecords().isNotEmpty() })
            val log = collector.logRecords().single()
            val a = attrs(log.attributesList)
            assertEquals(21, log.severityNumberValue)
            assertEquals(TimeUnit.MILLISECONDS.toNanos(crashedAt), log.timeUnixNano)
            assertEquals("SIGSEGV: SEGV_MAPERR at 0x0 (null pointer dereference)", log.body.stringValue)
            assertEquals("SIGSEGV", a["exception.type"])
            assertEquals("false", a["zharp.handled"])
            assertEquals("crash", a["zharp.error.kind"])
            assertEquals("Thread-2", a["thread.name"])
            assertEquals("feedfacefeedfacefeedfacefeedface", a["session.id"])
            assertEquals("CheckoutActivity", a["screen.name"])
            assertTrue(a["exception.stacktrace"]!!.contains("\n#1  Java_io_zharp_sample_Native_crash [libshop.so +0x1a2f0]"))
            assertTrue(a["zharp.native_build_ids"]!!.startsWith(appId.lowercase()))
        } finally {
            runtime.shutdown().join(5, TimeUnit.SECONDS)
            collector.stop()
        }
    }

    /** Just enough of the protobuf wire format to write a tombstone. */
    private class Proto {
        private val out = ByteArrayOutputStream()

        private fun varint(value: Long) {
            var v = value
            while (true) {
                if (v and 0x7fL.inv() == 0L) {
                    out.write(v.toInt())
                    return
                }
                out.write(((v and 0x7f) or 0x80).toInt())
                v = v ushr 7
            }
        }

        fun long(field: Int, value: Long) = apply { varint((field shl 3).toLong()); varint(value) }
        fun int(field: Int, value: Int) = long(field, value.toLong())
        fun fixed32(field: Int, value: Int) = apply {
            varint(((field shl 3) or 5).toLong())
            for (i in 0 until 4) out.write((value ushr (8 * i)) and 0xff)
        }

        fun message(field: Int, bytes: ByteArray) = apply {
            varint(((field shl 3) or 2).toLong())
            varint(bytes.size.toLong())
            out.write(bytes)
        }

        fun string(field: Int, text: String) = message(field, text.toByteArray(Charsets.UTF_8))
        fun bytes(): ByteArray = out.toByteArray()
    }
}
