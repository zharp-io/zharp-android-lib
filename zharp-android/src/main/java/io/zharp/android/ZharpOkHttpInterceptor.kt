package io.zharp.android

import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.context.propagation.TextMapSetter
import io.opentelemetry.sdk.common.Clock
import io.zharp.android.internal.Bodies
import io.zharp.android.internal.BodySettings
import io.zharp.android.internal.HttpBodies
import io.zharp.android.internal.Keys
import io.zharp.android.internal.Runtime
import io.zharp.android.internal.guarded
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * A CLIENT span for every OkHttp call, and W3C `traceparent` + `baggage` (session and user) on
 * calls to your `tracingOrigins`, so the trace continues into your backend:
 *
 * ```kotlin
 * val client = OkHttpClient.Builder()
 *     .addInterceptor(ZharpOkHttpInterceptor())
 *     .build()
 * ```
 *
 * Add it with `addInterceptor` (one span per call, retries and redirects included). Requests are
 * never changed for other hosts. Secret-looking query values (`token`, `key`, `sig`, `password`, …)
 * are recorded as `[Filtered]`. Retrofit, Coil and anything else built on that client are covered
 * too. With `tracing = false` it does nothing.
 *
 * With [ZharpOptions.captureBodies] set, the span also carries the request and response bodies
 * (redacted on the device; see [BodyCapture]). The response is read with `peekBody`, so the app
 * still reads all of it; the span keeps the time the response arrived.
 */
public class ZharpOkHttpInterceptor : Interceptor {
    @Throws(IOException::class)
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val runtime = Zharp.runtime ?: return chain.proceed(request)
        // Logs only: no span, and the request goes out untouched.
        if (!runtime.settings.tracing) return chain.proceed(request)
        val span = guarded(null, "Starting an HTTP span") { startSpan(runtime, request) } ?: return chain.proceed(request)

        val bodies = guarded(null, "Checking body capture") { bodiesFor(runtime, request) }
        val scope = guarded(null, "Activating an HTTP span") { span.makeCurrent() }
        var arrived = 0L
        try {
            val outgoing = guarded(request, "Adding trace headers") { withTraceHeaders(runtime, request, span) }
            val response = chain.proceed(outgoing)
            arrived = Clock.getDefault().now()
            guarded(Unit, "Recording an HTTP response") {
                span.setAttribute(Keys.HTTP_STATUS, response.code.toLong())
                if (response.code >= 400) {
                    span.setAttribute(Keys.ERROR_TYPE, response.code.toString())
                    span.setStatus(StatusCode.ERROR)
                }
            }
            if (bodies != null && bodies.wanted(response.code)) {
                guarded(Unit, "Recording request and response bodies") {
                    setAll(span, HttpBodies.requestAttributes(request, bodies))
                    setAll(span, HttpBodies.responseAttributes(response, bodies))
                }
            }
            return response
        } catch (error: Throwable) {
            arrived = Clock.getDefault().now()
            // A failed request is the app's to handle: marked on the span, not reported as an error.
            guarded(Unit, "Recording an HTTP failure") {
                span.setAttribute(Keys.ERROR_TYPE, error.javaClass.name)
                span.setStatus(StatusCode.ERROR, error.javaClass.simpleName)
            }
            if (bodies != null) {
                guarded(Unit, "Recording a request body") { setAll(span, HttpBodies.requestAttributes(request, bodies)) }
            }
            throw error
        } finally {
            guarded(Unit, "Ending an HTTP span") {
                scope?.close()
                if (arrived > 0) span.end(arrived, TimeUnit.NANOSECONDS) else span.end()
            }
        }
    }

    private fun startSpan(runtime: Runtime, request: Request): Span {
        val url = request.url
        val method = request.method
        val query = url.encodedQuery?.let { "?" + Bodies.redactQuery(it) }.orEmpty()
        val fullUrl = url.scheme + "://" + url.host + portSuffix(url.scheme, url.port) + url.encodedPath + query
        return runtime.tracer.spanBuilder("$method ${url.host}")
            .setSpanKind(SpanKind.CLIENT)
            .setAttribute(Keys.HTTP_METHOD, method)
            .setAttribute(Keys.URL_FULL, fullUrl)
            .setAttribute(Keys.URL_PATH, url.encodedPath)
            .setAttribute(Keys.SERVER_ADDRESS, url.host)
            .setAttribute(Keys.SERVER_PORT, url.port.toLong())
            .startSpan()
    }

    /** `captureBodies` when it covers this call, else null. */
    private fun bodiesFor(runtime: Runtime, request: Request): BodySettings? {
        val bodies = runtime.settings.bodies ?: return null
        val urls = runtime.bodyUrls ?: return bodies
        val url = request.url
        return if (urls.matches(url.toString(), url.host, url.port)) bodies else null
    }

    private fun setAll(span: Span, attributes: Map<String, Any>) {
        for ((key, value) in attributes) {
            when (value) {
                is Boolean -> span.setAttribute(key, value)
                is Long -> span.setAttribute(key, value)
                else -> span.setAttribute(key, value.toString())
            }
        }
    }

    private fun withTraceHeaders(runtime: Runtime, request: Request, span: Span): Request {
        val url = request.url
        if (!runtime.origins.matches(url.toString(), url.host, url.port)) return request
        val builder = request.newBuilder()
        Runtime.PROPAGATORS.textMapPropagator.inject(Runtime.contextFor(span, runtime.baggage()), builder, SETTER)
        return builder.build()
    }

    private companion object {
        val SETTER = TextMapSetter<Request.Builder> { carrier, key, value -> carrier?.header(key, value) }

        fun portSuffix(scheme: String, port: Int): String =
            if ((scheme == "https" && port == 443) || (scheme == "http" && port == 80)) "" else ":$port"
    }
}
