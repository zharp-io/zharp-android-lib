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
