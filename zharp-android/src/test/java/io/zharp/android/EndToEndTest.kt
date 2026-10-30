package io.zharp.android

import io.opentelemetry.api.common.Attributes
import io.zharp.android.internal.AndroidPlatform
import io.zharp.android.internal.Keys
import io.zharp.android.internal.MemoryStore
import io.zharp.android.internal.Runtime
import io.zharp.android.internal.Settings
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Sends a span with a caught exception, a log inside it, and a crash to a real Zharp. Runs only
 * with `ZHARP_E2E_KEY` set (an app key; never put one in a file), against `ZHARP_E2E_ENDPOINT`
 * (default: the local dev stack). Prints what to look for in ClickHouse.
 */
class EndToEndTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun sendsToARealZharp() {
        val key = System.getenv("ZHARP_E2E_KEY").orEmpty()
        assumeTrue("ZHARP_E2E_KEY not set", key.isNotEmpty())
        val endpoint = System.getenv("ZHARP_E2E_ENDPOINT").orEmpty().ifEmpty { "http://localhost:3000/api/v1/otlp" }
        val marker = UUID.randomUUID().toString().substring(0, 8)

        val store = MemoryStore()
        val device = Attributes.builder()
            .put(Keys.OS_NAME, "Android").put(Keys.OS_VERSION, "15").put(Keys.ANDROID_API_LEVEL, 35L)
            .put(Keys.DEVICE_ID, AndroidPlatform.deviceId(store))
            .put(Keys.DEVICE_MODEL_NAME, "Google Pixel 9")
            .build()
        val runtime = Runtime(
            Settings(key, "android-e2e", "development", "1.0.0 (1)", endpoint, emptyList(), 1.0, scheduleDelayMillis = 200),
            device, store, folder.newFolder(), Zharp.user,
        )
        Zharp.startForTests(runtime)
        try {
            Zharp.setUser(id = "e2e-user-$marker", email = "e2e-$marker@example.com")
            var traceId = ""
            Zharp.startSpan("e2e checkout $marker") { span ->
                traceId = span.spanContext.traceId
                Zharp.log.info("e2e log $marker", mapOf("marker" to marker))
                Zharp.captureError(IllegalStateException("e2e handled error $marker"))
            }
            assertTrue("flush delivered", runtime.flush().join(30, TimeUnit.SECONDS).isSuccess)
            runtime.reportCrash(Thread.currentThread(), RuntimeException("e2e crash $marker"))

            println("E2E marker=$marker trace_id=$traceId session_id=${Zharp.sessionId} user_id=e2e-user-$marker")
        } finally {
            Zharp.clearUser()
            Zharp.startForTests(null)
            runtime.shutdown().join(10, TimeUnit.SECONDS)
        }
    }
}
