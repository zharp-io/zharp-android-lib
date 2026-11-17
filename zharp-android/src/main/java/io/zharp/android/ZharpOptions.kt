package io.zharp.android

/**
 * Settings for [Zharp.init]. In Kotlin, set them in the init block:
 *
 * ```kotlin
 * Zharp.init(this) {
 *     key = "zpk_…"
 *     service = "shop-android"
 *     environment = "production"
 *     tracingOrigins = listOf("api.example.com")
 * }
 * ```
 *
 * In Java, make one, set what you need and pass it: `Zharp.init(this, options)`.
 */
public class ZharpOptions {
    /** The app key from Zharp (`zpk_…`). Required: without it nothing starts. */
    public var key: String? = null

    /** Your app's service name, e.g. `shop-android`. Defaults to the package name. */
    public var service: String? = null

    /** e.g. `production`, `staging`. */
    public var environment: String? = null

    /** The release this build is, e.g. `2.3.0`. Defaults to the app's `versionName (versionCode)`. */
    public var release: String? = null

    /** Where to send. Defaults to Zharp's cloud; set it for a self-hosted or local Zharp. */
    public var endpoint: String? = null

    /**
     * Hosts whose requests carry `traceparent` and `baggage` headers, so traces continue into
     * your backend: `"api.example.com"`, `"api.example.com:8443"`, `"*.example.com"`, or a URL
     * prefix such as `"https://example.com/api"`. Empty (the default) sends them nowhere.
     */
    public var tracingOrigins: List<String> = emptyList()

    /**
     * `false` is logs only: logs, errors, crashes, ANRs, the user, session, device and tags still
     * go to Zharp, but no spans are made or sent: no screen-view or HTTP spans, nothing from
     * [Zharp.startSpan] (its block still runs), and no `traceparent`/`baggage` headers added to
     * requests, whatever [tracingOrigins] says.
     */
    public var tracing: Boolean = true

    /**
     * Request and response bodies on HTTP spans. `null` (the default) is off; [BodyCapture.errors]
     * keeps them for failed calls, [BodyCapture.all] for every call. See [BodyCapture] for what is
     * sent and what is filtered. Secret-looking query values in URLs are filtered either way.
     */
    public var captureBodies: BodyCapture? = null

    /**
     * Logs the app writes some other way than [Zharp.log] are sent too, each still printed
     * exactly as before: Timber (a tree is planted when the app has Timber), java.util.logging,
     * `System.out`/`System.err` (a `printStackTrace()` as one record), [io.zharp.android.Log] and,
     * with [captureLogcat], everything else in the app's own Logcat. A line two of these see is
     * sent once. `false` sends only [Zharp.log].
     */
    public var captureOutput: Boolean = true

    /**
     * With [captureOutput]: also read the app's own Logcat (Android 7.0+), the net for
     * `android.util.Log`, libraries and native code. These lines carry the session, user and
     * screen but not the trace they were written in; for that, log through Timber, [Zharp.log]
     * or [io.zharp.android.Log] (a drop-in for `android.util.Log`).
     */
    public var captureLogcat: Boolean = true

    /** The lowest level [captureOutput] sends. Default [LogLevel.INFO]; [Zharp.log] is always sent. */
    public var captureOutputLevel: LogLevel = LogLevel.INFO

    /** Share of traces to keep, 0.0–1.0. Errors, crashes, ANRs and logs are always sent. */
    public var sampleRate: Double = 1.0

    /** Report uncaught exceptions (crashes). */
    public var captureCrashes: Boolean = true

    /** Report ANRs: the main thread blocked for [anrThresholdMillis] or more. */
    public var detectAnrs: Boolean = true

    public var anrThresholdMillis: Long = 5_000

    /** A span for each Activity and AndroidX Fragment shown. */
    public var trackScreens: Boolean = true

    /** `false` turns the SDK off entirely, e.g. in debug builds. */
    public var enabled: Boolean = true

    /** Explain in Logcat (tag `Zharp`) what the SDK does and why something wasn't sent. */
    public var debug: Boolean = false
}
