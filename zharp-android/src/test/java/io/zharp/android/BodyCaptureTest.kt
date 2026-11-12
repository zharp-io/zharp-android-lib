package io.zharp.android

import io.opentelemetry.api.common.Attributes
import io.zharp.android.internal.BodySettings
import io.zharp.android.internal.MemoryStore
import io.zharp.android.internal.Runtime
import io.zharp.android.internal.Settings
import io.zharp.android.internal.Transport
import okhttp3.FormBody
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSink
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream

/** captureBodies through ZharpOkHttpInterceptor, against an in-memory server, read back from a fake Zharp. */
class BodyCaptureTest {
    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var collector: FakeCollector
    private lateinit var runtime: Runtime

    /** What the server got, as the app's request left the interceptor. */
    private val received = mutableListOf<String>()

    private fun start(bodies: BodySettings?, tracingOrigins: List<String> = emptyList()) {
        collector = FakeCollector()
        val settings = Settings(
            key = "zpk_test", service = "shop-android", environment = "test", release = "1",
            endpoint = collector.endpoint, tracingOrigins = tracingOrigins, sampleRate = 1.0,
            scheduleDelayMillis = 100, bodies = bodies,
        )
        runtime = Runtime(
            settings, Attributes.empty(), MemoryStore(), folder.newFolder(), Zharp.user,
            Transport(collector.endpoint, settings.key, Runtime.USER_AGENT, retryDelaysMillis = longArrayOf(10, 10)),
        )
        Zharp.startForTests(runtime)
    }

    @After
    fun stop() {
        Zharp.startForTests(null)
        runtime.shutdown().join(5, TimeUnit.SECONDS)
        collector.stop()
    }

    private fun bodies(all: Boolean = false, maxSize: Int = 1000, urls: List<String> = emptyList()) =
        BodySettings(all = all, maxSize = maxSize, extraKeys = listOf("dateOfBirth"), urls = urls)

    /** A server in memory: answers by path, after reading the request body as a network would. */
    private val server = Interceptor { chain ->
        val request = chain.request()
        request.body?.let {
            val sink = Buffer()
            it.writeTo(sink)
            received += sink.readUtf8()
        }
        fun answer(code: Int, type: String?, bytes: ByteArray, vararg headers: Pair<String, String>): Response =
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("X")
                .apply { headers.forEach { (k, v) -> header(k, v) } }
                .body(bytes.toResponseBody(type?.toMediaType()))
                .build()
        when (request.url.encodedPath) {
            "/login" -> answer(401, "application/json", """{"error":"bad credentials","token":"leaked","user":{"apiKey":"k-1","email":"a@b.test"}}""".toByteArray())
            "/ok" -> answer(200, "application/json", """{"saved":true}""".toByteArray())
            "/big" -> answer(500, "text/plain", "x".repeat(200_000).toByteArray())
            "/image" -> answer(500, "image/png", ByteArray(3000))
            "/events" -> answer(500, "text/event-stream", "data: 1\n\n".toByteArray())
            "/gzipped" -> answer(500, "application/json", gzip("""{"error":"declined","cvv":"123"}"""), "Content-Encoding" to "gzip")
            "/empty" -> answer(500, null, ByteArray(0))
            else -> answer(500, "application/json", """{"ok":false}""".toByteArray())
        }
    }

    private val client = OkHttpClient.Builder().addInterceptor(ZharpOkHttpInterceptor()).addInterceptor(server).build()

    private fun call(path: String, body: RequestBody? = null, host: String = "127.0.0.1"): Pair<Int, String> =
        client.newCall(Request.Builder().url("http://$host:8080$path").apply { if (body != null) post(body) }.build()).execute().use {
            it.code to it.body!!.string()
        }

    private fun spans(count: Int): Map<String, Map<String, String>> {
        Zharp.flush()
        assertTrue(eventually { collector.spans().size >= count })
        return collector.spans().associate { s -> attrs(s.attributesList).let { it["url.path"]!! to it } }
    }

    private fun json(text: String) = text.toRequestBody("application/json".toMediaType())

    @Test
    fun errorsModeKeepsBodiesOfFailedCallsOnlyWithSecretsFiltered() {
        start(bodies())
        val (code, text) = call("/login", json("""{"email":"a@b.test","password":"hunter2","dateOfBirth":"1990-01-01","remember":true}"""))
        assertEquals(401, code)
        assertTrue("the app still reads the whole response", text.contains("\"token\":\"leaked\""))
        assertEquals("the request went out as the app made it", """{"email":"a@b.test","password":"hunter2","dateOfBirth":"1990-01-01","remember":true}""", received.single())
        call("/ok", json("""{"name":"Ada"}"""))

        val all = spans(2)
        val login = all.getValue("/login")
        assertEquals("""{"email":"a@b.test","password":"[Filtered]","dateOfBirth":"[Filtered]","remember":true}""", login["http.request.body"])
        assertEquals("application/json; charset=utf-8", login["http.request.header.content-type"])
        assertEquals("""{"error":"bad credentials","token":"[Filtered]","user":{"apiKey":"[Filtered]","email":"a@b.test"}}""", login["http.response.body"])
        assertEquals("application/json", login["http.response.header.content-type"])
        assertNull(all.getValue("/ok")["http.request.body"])
        assertNull(all.getValue("/ok")["http.response.body"])
    }

    @Test
    fun allModeUploadsFormsBinariesStreamsAndLongBodies() {
        start(bodies(all = true))
        val upload = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("title", "Holiday")
            .addFormDataPart("password", "form-secret")
            .addFormDataPart("photo", "photo.png", ByteArray(2048).toRequestBody("image/png".toMediaType()))
            .addFormDataPart("after", "still-here")
            .build()
        call("/upload", upload)
        call("/form", FormBody.Builder().add("user", "ada").add("password", "p4ss").build())
        call("/ok", json("""{"name":"Ada"}"""))
        call("/image")
        call("/events")
        val (_, big) = call("/big")
        assertEquals("the app still gets every byte", 200_000, big.length)
        call("/gzipped")
        call("/empty")

        val all = spans(8)
        val up = all.getValue("/upload")
        assertEquals("""{"title":"Holiday","password":"[Filtered]","photo":"[file photo.png · image/png · 2 KB]","after":"still-here"}""", up["http.request.body"])
        assertEquals("multipart/form-data", up["http.request.header.content-type"])
        assertEquals("user=ada&password=[Filtered]", all.getValue("/form")["http.request.body"])
        assertEquals("""{"name":"Ada"}""", all.getValue("/ok")["http.request.body"])
        assertEquals("""{"saved":true}""", all.getValue("/ok")["http.response.body"])
        assertEquals("[binary · image/png · 3 KB]", all.getValue("/image")["http.response.body"])
        assertEquals("[event stream]", all.getValue("/events")["http.response.body"])
        assertEquals(1000, all.getValue("/big")["http.response.body"]!!.length)
        assertEquals("true", all.getValue("/big")["http.response.body.truncated"])
        assertEquals("""{"error":"declined","cvv":"[Filtered]"}""", all.getValue("/gzipped")["http.response.body"])
        assertNull(all.getValue("/empty")["http.response.body"])
    }

    @Test
    fun oneShotBodiesAreDescribedNeverWrittenTwice() {
        start(bodies())
        var writes = 0
        val oneShot = object : RequestBody() {
            override fun contentType() = "application/json".toMediaType()
            override fun contentLength() = 9L
            override fun isOneShot() = true
            override fun writeTo(sink: BufferedSink) {
                writes++
                sink.writeUtf8("""{"n":"1"}""")
            }
        }
        call("/fail", oneShot)
        assertEquals(1, writes)
        assertEquals("[binary · application/json · 9 B]", spans(1).getValue("/fail")["http.request.body"])
    }

    @Test
    fun aCallThatNeverGotAResponseKeepsItsRequestBody() {
        start(bodies())
        val failing = OkHttpClient.Builder().addInterceptor(ZharpOkHttpInterceptor()).addInterceptor(Interceptor { throw IOException("offline") }).build()
        try {
            failing.newCall(Request.Builder().url("http://127.0.0.1:8080/offline").post(json("""{"pin":"1234","item":"tea"}""")).build()).execute()
        } catch (expected: IOException) {
        }
        val span = spans(1).getValue("/offline")
        assertEquals("""{"pin":"[Filtered]","item":"tea"}""", span["http.request.body"])
        assertNull(span["http.response.body"])
    }

    @Test
    fun onlyTheTracingOriginsWhenSetOrTheGivenUrls() {
        start(bodies(), tracingOrigins = listOf("127.0.0.1"))
        call("/fail", json("""{"a":1}"""))
        call("/fail-elsewhere", json("""{"b":2}"""), host = "localhost")
        val all = spans(2)
        assertEquals("""{"a":1}""", all.getValue("/fail")["http.request.body"])
        assertNull(all.getValue("/fail-elsewhere")["http.request.body"])
    }

    @Test
    fun offByDefaultButUrlSecretsAreAlwaysFiltered() {
        start(null)
        call("/login?token=url-secret&page=2", json("""{"password":"x"}"""))
        val span = spans(1).getValue("/login")
        assertNull(span["http.request.body"])
        assertNull(span["http.response.body"])
        assertEquals("http://127.0.0.1:8080/login?token=[Filtered]&page=2", span["url.full"])
    }

    @Test
    fun theSpanEndsWhenTheResponseArrived() {
        start(bodies(all = true))
        val before = System.currentTimeMillis()
        call("/big")
        Zharp.flush()
        assertTrue(eventually { collector.spans().isNotEmpty() })
        val span = collector.spans().single()
        assertTrue(span.endTimeUnixNano >= span.startTimeUnixNano)
        assertTrue(span.endTimeUnixNano / 1_000_000 >= before)
    }

    private fun gzip(text: String): ByteArray = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(text.toByteArray()) } }.toByteArray()
}
