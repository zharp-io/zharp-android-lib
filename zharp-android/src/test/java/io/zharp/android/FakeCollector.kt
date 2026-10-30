package io.zharp.android

import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest
import io.opentelemetry.proto.common.v1.KeyValue
import java.io.BufferedInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPInputStream

/** A stand-in for Zharp's OTLP endpoint (just enough HTTP): decodes what arrives; answers with [status]. */
class FakeCollector {
    class Received(val path: String, val headers: Map<String, String>, val body: ByteArray)

    val requests = CopyOnWriteArrayList<Received>()
    val status = AtomicInteger(200)
    private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))

    init {
        Thread({
            while (!socket.isClosed) {
                try {
                    socket.accept().use { serve(it) }
                } catch (ignored: Exception) {
                }
            }
        }, "fake-collector").apply { isDaemon = true }.start()
    }

    private fun serve(client: Socket) {
        val input = BufferedInputStream(client.getInputStream())
        val requestLine = readLine(input)
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = readLine(input)
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }
        val length = headers["content-length"]?.toInt() ?: 0
        val raw = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(raw, read, length - read)
            if (n < 0) break
            read += n
        }
        val body = if (headers["content-encoding"] == "gzip") GZIPInputStream(raw.inputStream()).readBytes() else raw
        val path = requestLine.split(' ').getOrElse(1) { "/" }.substringBefore('?')
        requests += Received(path, headers, body)
        val out = client.getOutputStream()
        out.write("HTTP/1.1 ${status.get()} X${CRLF}Content-Length: 0${CRLF}Connection: close${CRLF}${CRLF}".toByteArray())
        out.flush()
    }

    private fun readLine(input: BufferedInputStream): String {
        val line = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0 || c == LF) break
            if (c != CR) line.append(c.toChar())
        }
        return line.toString()
    }

    val port: Int get() = socket.localPort
    val endpoint: String get() = "http://127.0.0.1:$port/api/v1/otlp"

    fun stop() = socket.close()

    fun traces(): List<ExportTraceServiceRequest> =
        requests.filter { it.path.endsWith("/v1/traces") }.map { ExportTraceServiceRequest.parseFrom(it.body) }

    fun logs(): List<ExportLogsServiceRequest> =
        requests.filter { it.path.endsWith("/v1/logs") }.map { ExportLogsServiceRequest.parseFrom(it.body) }

    fun spans() = traces().flatMap { r -> r.resourceSpansList.flatMap { rs -> rs.scopeSpansList.flatMap { it.spansList } } }
    fun logRecords() = logs().flatMap { r -> r.resourceLogsList.flatMap { rl -> rl.scopeLogsList.flatMap { it.logRecordsList } } }
    fun traceResource(): Map<String, String> = attrs(traces().first().resourceSpansList.first().resource.attributesList)

    private companion object {
        const val CR = 13
        const val LF = 10
        val CRLF = String(charArrayOf(CR.toChar(), LF.toChar()))
    }
}

fun attrs(list: List<KeyValue>): Map<String, String> = list.associate { kv ->
    val v = kv.value
    kv.key to when {
        v.hasStringValue() -> v.stringValue
        v.hasIntValue() -> v.intValue.toString()
        v.hasBoolValue() -> v.boolValue.toString()
        v.hasDoubleValue() -> v.doubleValue.toString()
        else -> v.toString()
    }
}

fun hex(bytes: com.google.protobuf.ByteString): String = bytes.toByteArray().joinToString("") { "%02x".format(it) }

/** Poll until [condition] or [millis] pass. */
fun eventually(millis: Long = 5_000, condition: () -> Boolean): Boolean {
    val deadline = System.currentTimeMillis() + millis
    while (System.currentTimeMillis() < deadline) {
        if (condition()) return true
        Thread.sleep(25)
    }
    return condition()
}
