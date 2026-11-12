package io.zharp.android

/**
 * Request and response bodies on the spans of [ZharpOkHttpInterceptor] calls, set as
 * [ZharpOptions.captureBodies]. Off unless you set one:
 *
 * ```kotlin
 * Zharp.init(this) {
 *     key = "zpk_…"
 *     captureBodies = BodyCapture.errors()            // failed calls only
 *     // or: BodyCapture.all().apply { redactKeys = listOf("dateOfBirth") }
 * }
 * ```
 *
 * ```java
 * options.setCaptureBodies(BodyCapture.errors());
 * ```
 *
 * What is sent: text (JSON, forms, XML, plain text) up to [maxSize]; every field name, with values
 * under secret-looking keys (`password`, `token`, `authorization`, `card`, …) replaced with
 * `[Filtered]` on the device; a file in a multipart upload as `[file photo.png · image/png · 240 KB]`;
 * an inline image or other long base64 value as `[image/png · 24 KB]`; and a body that is not text
 * as `[binary · image/png · 24 KB]` or `[event stream]`. Other personal data, such as names and email
 * addresses, is sent as it is: add those keys to [redactKeys].
 */
public class BodyCapture {
    public enum class Mode {
        /** Only calls that failed: status 400 and above, or no response at all. */
        ERRORS,

        /** Every call. */
        ALL,
    }

    /** Which calls keep their bodies. Default [Mode.ERRORS]. */
    public var mode: Mode = Mode.ERRORS

    /** Characters kept per body. Default 32768 (32 KB), at most 131072 (128 KB). */
    public var maxSize: Int = 32 * 1024

    /**
     * More keys whose values are replaced with `[Filtered]`, on top of the built-in ones. Matched
     * ignoring case, `-` and `_`: `"accountNumber"` also covers `account_number`.
     */
    public var redactKeys: List<String> = emptyList()

    /**
     * Which calls have their bodies captured, matched like [ZharpOptions.tracingOrigins]. Empty (the
     * default): your tracing origins when you have set some, else every call through the client.
     */
    public var urls: List<String> = emptyList()

    public companion object {
        /** Bodies of failed calls only. */
        @JvmStatic
        public fun errors(): BodyCapture = BodyCapture()

        /** Bodies of every call. */
        @JvmStatic
        public fun all(): BodyCapture = BodyCapture().apply { mode = Mode.ALL }
    }
}
