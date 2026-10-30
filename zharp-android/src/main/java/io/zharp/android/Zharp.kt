package io.zharp.android

import android.app.Application
import android.content.Context
import android.os.Build
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.StatusCode
import io.zharp.android.internal.AndroidPlatform
import io.zharp.android.internal.AnrWatchdog
import io.zharp.android.internal.CrashHandler
import io.zharp.android.internal.Diagnostics
import io.zharp.android.internal.Keys
import io.zharp.android.internal.Lifecycle
import io.zharp.android.internal.Runtime
import io.zharp.android.internal.Settings
import io.zharp.android.internal.UserContext
import io.zharp.android.internal.guarded

/**
 * Zharp Application Monitoring for Android: crashes, ANRs, caught errors, logs, screen views and
 * HTTP traces, sent to Zharp as OpenTelemetry.
 *
 * Start it once, in `Application.onCreate()`:
 *
 * ```kotlin
 * Zharp.init(this) {
 *     key = "zpk_…"
 *     service = "shop-android"
 *     environment = "production"
 * }
 * Zharp.setUser(id = user.id, email = user.email)
 * ```
 *
 * Nothing here throws into the app or blocks the calling thread: telemetry is batched and sent
 * from background threads, kept on disk when offline, and dropped quietly if Zharp can't take it.
 */
public object Zharp {
    /** This SDK's version. */
    public const val VERSION: String = BuildConfig.VERSION_NAME

    /** `Zharp.log.info("…", mapOf(…))` — logs sent to Zharp. */
    @JvmField
    public val log: ZharpLog = ZharpLog()

    @Volatile
    internal var runtime: Runtime? = null
        private set

    internal val user = UserContext()
    private var lifecycleRegistered = false
    private var anrWatchdog: AnrWatchdog? = null

    // ── Starting ─────────────────────────────────────────────────────────────────────────────

    /** Start with options set in a Kotlin block: `Zharp.init(this) { key = "…"; service = "…" }`. */
    @JvmStatic
    public fun init(context: Context, configure: ZharpOptions.() -> Unit) {
        val options = ZharpOptions()
        try {
            options.configure()
        } catch (error: Throwable) {
            Diagnostics.warn("Zharp.init's options block threw; not started", error)
            return
        }
        init(context, options)
    }

    /**
     * Start sending. Call once, as early as possible: `Application.onCreate()`. A second call
     * while running is ignored. Logs one line to Logcat saying whether it started.
     */
    @JvmStatic
    @Synchronized
    public fun init(context: Context, options: ZharpOptions) {
        try {
            if (runtime != null) {
                Diagnostics.warn("Zharp.init was called again; the first setup stays")
                return
            }
            Diagnostics.debug = options.debug
            if (!options.enabled) {
                Diagnostics.info("Disabled (enabled = false); nothing will be sent")
                return
            }
            val key = options.key?.trim().orEmpty()
            if (key.isEmpty()) {
                Diagnostics.warn("No key: pass your app key (zpk_…) to Zharp.init. Nothing will be sent.")
                return
            }
            if (!desugaringAvailable()) {
                Diagnostics.warn(
                    "Not started: on Android ${Build.VERSION.RELEASE} the app needs core library desugaring " +
                        "(isCoreLibraryDesugaringEnabled = true, plus coreLibraryDesugaring(\"com.android.tools:desugar_jdk_libs:2.1.5\")).",
                )
                return
            }

            val app = context.applicationContext ?: context
            val platform = AndroidPlatform(app)
            val settings = Settings(
                key = key,
                service = options.service?.trim()?.takeIf { it.isNotEmpty() } ?: platform.packageName,
                environment = options.environment?.trim()?.takeIf { it.isNotEmpty() },
                release = options.release?.trim()?.takeIf { it.isNotEmpty() } ?: platform.versionName,
                endpoint = options.endpoint?.trim()?.takeIf { it.isNotEmpty() },
                tracingOrigins = options.tracingOrigins,
                sampleRate = options.sampleRate,
                tracing = options.tracing,
            )
            val next = Runtime(settings, platform.deviceAttributes(), platform.store, platform.outboxDir, user)
            runtime = next
            next.start()

            if (options.captureCrashes) CrashHandler.install { runtime }
            if (!lifecycleRegistered && app is Application) {
                lifecycleRegistered = true
                app.registerActivityLifecycleCallbacks(Lifecycle({ runtime }, options.trackScreens && options.tracing))
            }
            if (options.detectAnrs && anrWatchdog == null) {
                anrWatchdog = AnrWatchdog({ runtime }, options.anrThresholdMillis.coerceAtLeast(1_000)).also { it.start() }
            }

            Diagnostics.info(
                "Application Monitoring active: service \"${settings.service}\"" +
                    (settings.environment?.let { ", environment \"$it\"" } ?: "") +
                    (if (settings.tracing) "" else " (logs only: tracing = false)"),
            )
        } catch (error: Throwable) {
            runtime = null
            Diagnostics.warn("Zharp couldn't start; the app is unaffected", error)
        }
    }

    /** Whether [init] succeeded and telemetry is being sent. */
    @JvmStatic
    public val isActive: Boolean
        get() = runtime != null

    // ── Who is using the app ─────────────────────────────────────────────────────────────────

    /**
     * The signed-in user, on everything from now on (and in the `baggage` sent to your backend):
     * `enduser.id`, and `enduser.email` / `enduser.name` when given. Can be called before init.
     */
    @JvmStatic
    @JvmOverloads
    public fun setUser(id: String?, email: String? = null, name: String? = null) {
        user.user = UserContext.User(id?.takeIf { it.isNotBlank() }, email?.takeIf { it.isNotBlank() }, name?.takeIf { it.isNotBlank() })
    }

    /** Signed out: forget the user and start a new session. */
    @JvmStatic
    public fun clearUser() {
        user.user = null
        guarded(Unit, "Starting a new session") { runtime?.session?.rotate() }
    }

    /** An attribute on every span and log from now on, e.g. `setTag("plan", "pro")`. `null` removes it. */
    @JvmStatic
    public fun setTag(key: String, value: String?) {
        if (key.isBlank()) return
        if (value == null) user.tags.remove(key) else user.tags[key] = value
    }

    /** The current session's ID, or null before init. */
    @JvmStatic
    public val sessionId: String?
        get() = guarded(null, "Reading the session") { runtime?.session?.current() }

    // ── Errors ───────────────────────────────────────────────────────────────────────────────

    /**
     * Report a caught exception as a handled error, with its stack. Inside a span it's recorded
     * on that span; otherwise it's sent as an error log.
     */
    @JvmStatic
    @JvmOverloads
    public fun captureError(error: Throwable, attributes: Map<String, Any?>? = null) {
        val current = runtime ?: return
        guarded(Unit, "captureError") { current.captureError(error, attributes) }
    }

    // ── Traces ───────────────────────────────────────────────────────────────────────────────

    /**
     * Run [block] inside a span named [name], the current span while it runs (so HTTP calls and
     * logs made inside belong to it). An exception thrown by [block] marks the span failed and is
     * rethrown untouched; report it where you catch it (or let it crash, and it's reported then).
     * With `tracing = false` no span is made; [block] still runs (with a no-op span).
     */
    @JvmStatic
    public fun <T> startSpan(name: String, block: (Span) -> T): T {
        val span = startSpan(name)
        val scope = guarded(null, "Activating a span") { span.makeCurrent() }
        try {
            return block(span)
        } catch (error: Throwable) {
            guarded(Unit, "Marking a span failed") {
                span.setStatus(StatusCode.ERROR, error.javaClass.name)
                span.setAttribute(Keys.ERROR_TYPE, error.javaClass.name)
            }
            throw error
        } finally {
            guarded(Unit, "Ending a span") {
                scope?.close()
                span.end()
            }
        }
    }

    /**
     * Start a span yourself; end it with `span.end()`. Make it current with `span.makeCurrent()`
     * (and close that scope) for work inside it to belong to it.
     */
    @JvmStatic
    public fun startSpan(name: String): Span =
        guarded(Span.getInvalid(), "Starting a span") { runtime?.tracer?.spanBuilder(name)?.startSpan() ?: Span.getInvalid() }

    /**
     * Record a screen view yourself, for screens that aren't an Activity or Fragment (Jetpack
     * Compose destinations, custom navigation). Also the screen everything after is tagged with.
     */
    @JvmStatic
    public fun trackScreen(name: String) {
        if (name.isBlank()) return
        user.screen = name
        val current = runtime ?: return
        guarded(Unit, "trackScreen") {
            val now = Lifecycle.now()
            current.screenView(name, "manual", now, now)
        }
    }

    /**
     * The OpenTelemetry instance Zharp runs on, for anything the helpers here don't cover: more
     * tracers, other instrumentation libraries. A no-op one before init.
     */
    @JvmStatic
    public val openTelemetry: OpenTelemetry
        get() = runtime?.openTelemetry ?: OpenTelemetry.noop()

    // ── Sending ──────────────────────────────────────────────────────────────────────────────

    /**
     * Send what's queued now, in the background. Rarely needed: batches go every few seconds and
     * when the app goes to the background.
     */
    @JvmStatic
    public fun flush() {
        guarded(Unit, "flush") { runtime?.flush() }
    }

    /** Send what's queued and stop. Telemetry after this is dropped until [init] is called again. */
    @JvmStatic
    @Synchronized
    public fun shutdown() {
        val current = runtime ?: return
        runtime = null
        guarded(Unit, "shutdown") { current.shutdown() }
    }

    /**
     * OpenTelemetry Java uses java.time and java.util.function, which Android has from API 26;
     * below that the app's build must desugar them (they're then renamed to j$.*).
     */
    private fun desugaringAvailable(): Boolean {
        if (Build.VERSION.SDK_INT >= 26) return true
        return try {
            Class.forName("j\$.time.Duration")
            true
        } catch (missing: Throwable) {
            false
        }
    }

    /** For tests: run with this runtime, as if init had made it. */
    internal fun startForTests(next: Runtime?) {
        runtime = next
    }
}
