package io.zharp.gradle

import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class ArtifactUploaderTest {
    @get:Rule
    val folder = TemporaryFolder()

    class Received(val path: String, val headers: Map<String, String>, val body: String)

    private lateinit var server: HttpServer
    private val received = CopyOnWriteArrayList<Received>()
    private val statuses = ArrayDeque<Int>()
    private val calls = AtomicInteger()
    private val url get() = "http://127.0.0.1:${server.address.port}"

    @Before
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            calls.incrementAndGet()
            val body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            received += Received(
                exchange.requestURI.path,
                exchange.requestHeaders.entries.associate { it.key.lowercase() to it.value.first() },
                body,
            )
            val status = synchronized(statuses) { statuses.removeFirstOrNull() } ?: 201
            val answer = if (status < 300) """{"id":"a1"}""" else """{"message":"nope $status"}"""
            exchange.sendResponseHeaders(status, answer.length.toLong())
            exchange.responseBody.use { it.write(answer.toByteArray()) }
        }
        server.start()
    }

    @After
    fun stop() = server.stop(0)

    private fun mapping() = folder.newFile("mapping.txt").apply {
        writeText("com.example.Checkout -> a.a:\n    void pay() -> a\n")
    }

    @Test
    fun postsTheMappingAsMultipartWithItsFields() {
        val file = mapping()
        ArtifactUploader(url, "zh_secret").upload(
            appId = "42",
            fields = mapOf("kind" to "proguard", "debugId" to "1b4e28ba-2fa1-4d3b-a3f5-ef19b5a7633b", "name" to "mapping.txt", "release" to "1.0.0 (1)"),
            file = file,
            fileName = "mapping.txt",
            contentType = "text/plain",
        )
        val request = received.single()
        assertEquals("/api/v1/applications/42/artifacts", request.path)
        assertEquals("Bearer zh_secret", request.headers["authorization"])
        val type = request.headers.getValue("content-type")
        assertTrue(type, type.startsWith("multipart/form-data; boundary="))
        val boundary = type.substringAfter("boundary=")

        val parts = request.body.split("--$boundary").drop(1).dropLast(1).associate { part ->
            val (head, value) = part.removePrefix("\r\n").split("\r\n\r\n", limit = 2)
            Regex("name=\"([^\"]+)\"").find(head)!!.groupValues[1] to value.removeSuffix("\r\n")
        }
        assertEquals("proguard", parts["kind"])
        assertEquals("1b4e28ba-2fa1-4d3b-a3f5-ef19b5a7633b", parts["debugId"])
        assertEquals("mapping.txt", parts["name"])
        assertEquals("1.0.0 (1)", parts["release"])
        assertEquals(file.readText(), parts["file"])
        assertTrue(request.body.contains("name=\"file\"; filename=\"mapping.txt\""))
        assertTrue(request.body.endsWith("--$boundary--\r\n"))
    }

    @Test
    fun retriesServerErrorsThenSucceeds() {
        synchronized(statuses) { statuses.addAll(listOf(503, 500)) }
        ArtifactUploader(url, "zh_secret", retryDelaysMillis = longArrayOf(10, 10))
            .upload("42", mapOf("kind" to "proguard"), mapping(), "mapping.txt", "text/plain")
        assertEquals(3, calls.get())
    }

    @Test
    fun doesNotRetryAMissingScopeAndSaysWhy() {
        synchronized(statuses) { statuses.add(403) }
        try {
            ArtifactUploader(url, "zh_secret", retryDelaysMillis = longArrayOf(10, 10))
                .upload("42", mapOf("kind" to "proguard"), mapping(), "mapping.txt", "text/plain")
            fail("expected a failure")
        } catch (error: ArtifactUploader.Failed) {
            val message = error.message.orEmpty()
            assertTrue(message, message.contains("artifacts:write"))
            assertTrue(message, message.contains("nope 403"))
        }
        assertEquals(1, calls.get())
    }

    @Test
    fun uuidAssetRoundTrips() {
        val uuid = "1b4e28ba-2fa1-4d3b-a3f5-ef19b5a7633b"
        assertEquals(uuid, GenerateProguardUuidTask.read(GenerateProguardUuidTask.contents(uuid)))
        assertEquals(null, GenerateProguardUuidTask.read("# nothing\n"))
    }
}
