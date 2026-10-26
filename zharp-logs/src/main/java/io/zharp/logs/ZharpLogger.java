package io.zharp.logs;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;

/**
 * Sends your Android app's logs and crashes to Zharp. Works the same from Kotlin and Java.
 *
 * <p>Start it once, in {@code Application.onCreate()}:
 *
 * <pre>{@code
 * ZharpLogger.init(this, "your-ingest-only-key", "shop-app");
 * }</pre>
 *
 * <p>Then log from anywhere:
 *
 * <pre>{@code
 * ZharpLogger.info("Checkout opened");
 * ZharpLogger.warn("Slow network", Collections.singletonMap("latencyMs", 3200));
 * ZharpLogger.error("Payment failed", e);
 * }</pre>
 *
 * Logs are queued and sent in batches from a background thread, every 5 seconds; {@code error}
 * and {@code fatal} go at once. Nothing here blocks or throws into the calling code.
 */
public final class ZharpLogger {
    /** This library's version. */
    public static final String VERSION = BuildConfig.VERSION_NAME;

    static final String TAG = "Zharp";
    /** What requests from this SDK say they are, in the {@code X-Zharp-SDK} header. */
    static final String SDK_NAME = "android-logs/" + VERSION;
    /** How long a crashing app waits for its last logs to go out before it ends. */
    private static final long CRASH_FLUSH_TIMEOUT_MS = 2_000;
    private static final Pattern TRUE = Pattern.compile("^(true|1|yes|on)$", Pattern.CASE_INSENSITIVE);

    private static volatile Setup setup;
    private static volatile String sessionId = newSessionId();
    private static volatile JSONObject extraContext = new JSONObject();

    private static boolean crashHandlerInstalled;
    private static boolean lifecycleRegistered;
    private static PendingStore pendingStore;

    private ZharpLogger() {
    }

    // ── Starting ─────────────────────────────────────────────────────────────────────────────

    /**
     * Start with the key and service set in {@code AndroidManifest.xml}:
     *
     * <pre>{@code
     * <meta-data android:name="io.zharp.API_KEY" android:value="your-ingest-only-key" />
     * <meta-data android:name="io.zharp.SERVICE" android:value="shop-app" />
     * }</pre>
     */
    public static void init(@NonNull Context context) {
        init(context, new ZharpConfig.Builder().build());
    }

    /** Start with an API key and a service name; everything else as default. */
    public static void init(@NonNull Context context, @Nullable String apiKey, @Nullable String service) {
        init(context, new ZharpConfig.Builder().apiKey(apiKey).service(service).build());
    }

    /**
     * Start sending logs. Call once, as early as possible — {@code Application.onCreate()}.
     *
     * <p>Anything the config leaves out is read from the manifest's {@code <meta-data>}
     * ({@code io.zharp.API_KEY}, {@code io.zharp.SERVICE}, {@code io.zharp.ENVIRONMENT},
     * {@code io.zharp.VERSION}, {@code io.zharp.SOURCE}, {@code io.zharp.ENDPOINT}). With no API
     * key anywhere, nothing starts and a hint goes to Logcat. {@code io.zharp.DISABLED=true}
     * turns it off whatever the code says.
     *
     * <p>On success it writes one line to Logcat:
     * {@code [Zharp] Logging active — service "shop-app", environment "production"}.
     *
     * <p>Calling it again replaces the running setup; what the old one had queued is still sent.
     */
    public static synchronized void init(@NonNull Context context, @NonNull ZharpConfig config) {
        Context app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        Setup previous = setup;
        if (previous != null) {
            setup = null;
            previous.pipeline.close();
        }

        AppInfo info = AppInfo.of(app);
        String disabled = info.manifest("DISABLED");
        if (disabled != null && TRUE.matcher(disabled).matches()) {
            announce("Logging disabled by io.zharp.DISABLED — nothing will be sent");
            return;
        }

        String key = pick(config.apiKey, info.manifest("API_KEY"));
        if (key == null) {
            announce("No API key found — pass one to ZharpLogger.init(), or set io.zharp.API_KEY in AndroidManifest.xml");
            return;
        }

        String service = pick(config.service, info.manifest("SERVICE"), info.packageName);
        if (service == null) service = "unknown-service";

        Transport transport = new Transport(
                key,
                Transport.normalizeEndpoint(pick(config.endpoint, info.manifest("ENDPOINT"))),
                SDK_NAME,
                1_000);
        Setup next = new Setup(
                service,
                pick(config.environment, info.manifest("ENVIRONMENT")),
                pick(config.appVersion, info.manifest("VERSION"), info.versionName),
                pick(config.source, info.manifest("SOURCE")),
                config.logcat != null ? config.logcat : info.debuggable,
                Json.toJsonMap(info.context()),
                new Pipeline(transport, config.flushIntervalMillis, config.maxBatchSize));
        setup = next;

        // A crash saved by the last run goes out first.
        pendingStore = new PendingStore(storageDir(app));
        List<JSONObject> saved = pendingStore.take();
        if (!saved.isEmpty()) {
            next.pipeline.pushFirst(saved);
            next.pipeline.flush();
        }

        if (config.captureCrashes) installCrashHandler();
        if (config.flushOnBackground && app instanceof Application) registerLifecycle((Application) app);

        announce("Logging active — service \"" + next.service + "\", "
                + (next.environment != null ? "environment \"" + next.environment + "\"" : "no environment set"));
    }

    /** Whether {@link #init} succeeded and logs are being sent. */
    public static boolean isActive() {
        return setup != null;
    }

    // ── Logging ──────────────────────────────────────────────────────────────────────────────

    public static void debug(@Nullable Object message) {
        log(LogLevel.DEBUG, message, null, null, null);
    }

    public static void debug(@Nullable Object message, @Nullable Map<String, ?> metadata) {
        log(LogLevel.DEBUG, message, metadata, null, null);
    }

    public static void info(@Nullable Object message) {
        log(LogLevel.INFO, message, null, null, null);
    }

    public static void info(@Nullable Object message, @Nullable Map<String, ?> metadata) {
        log(LogLevel.INFO, message, metadata, null, null);
    }

    public static void warn(@Nullable Object message) {
        log(LogLevel.WARN, message, null, null, null);
    }

    public static void warn(@Nullable Object message, @Nullable Map<String, ?> metadata) {
        log(LogLevel.WARN, message, metadata, null, null);
    }

    public static void warn(@Nullable String message, @Nullable Throwable error) {
        log(LogLevel.WARN, message, null, error, null);
    }

    /** Logs at {@code error} and sends at once. A Throwable can be the message: its stack goes with it. */
    public static void error(@Nullable Object message) {
        log(LogLevel.ERROR, message, null, null, null);
    }

    public static void error(@Nullable Object message, @Nullable Map<String, ?> metadata) {
        log(LogLevel.ERROR, message, metadata, null, null);
    }

    /** A caught exception with your own message; its type and stack are sent with it. */
    public static void error(@Nullable String message, @Nullable Throwable error) {
        log(LogLevel.ERROR, message, null, error, null);
    }

    public static void error(@Nullable String message, @Nullable Throwable error, @Nullable Map<String, ?> metadata) {
        log(LogLevel.ERROR, message, metadata, error, null);
    }

    /** Logs at {@code fatal} and sends at once. */
    public static void fatal(@Nullable Object message) {
        log(LogLevel.FATAL, message, null, null, null);
    }

    public static void fatal(@Nullable Object message, @Nullable Map<String, ?> metadata) {
        log(LogLevel.FATAL, message, metadata, null, null);
    }

    public static void fatal(@Nullable String message, @Nullable Throwable error) {
        log(LogLevel.FATAL, message, null, error, null);
    }

    /** Log a caught exception as an {@code error}, with its type and stack. */
    public static void captureException(@NonNull Throwable error) {
        log(LogLevel.ERROR, error, null, error, null);
    }

    public static void captureException(@NonNull Throwable error, @Nullable Map<String, ?> metadata) {
        log(LogLevel.ERROR, error, metadata, error, null);
    }

    /**
     * Log at any level. {@code error} adds a Throwable's type and stack; {@code service} files
     * this one log under another service than the one set in {@link #init}. Before init (or
     * after a failed one) logs are dropped.
     */
    public static void log(@NonNull LogLevel level, @Nullable Object message, @Nullable Map<String, ?> metadata,
                           @Nullable Throwable error, @Nullable String service) {
        Setup current = setup;
        if (current == null) return;
        try {
            if (error == null && message instanceof Throwable) error = (Throwable) message;
            if (error == null) error = Json.firstError(metadata);
            JSONObject entry = entry(current, level, message, metadata, error, service, System.currentTimeMillis());
            if (current.logcat) {
                String text = Json.toMessage(message);
                Log.println(level.logcatPriority(), TAG, error != null ? text + "\n" + Json.stackOf(error) : text);
            }
            current.pipeline.push(entry);
            if (level == LogLevel.ERROR || level == LogLevel.FATAL) current.pipeline.flush();
        } catch (RuntimeException ignored) {
            // Logging never takes the app down.
        }
    }

    // ── Session and context ──────────────────────────────────────────────────────────────────

    /** The ID every log of this run carries, so one session's logs can be found together. */
    @NonNull
    public static String getSessionId() {
        return sessionId;
    }

    /** Start a new session ID, e.g. when a different user signs in. */
    public static void resetSessionId() {
        sessionId = newSessionId();
    }

    /**
     * Extra fields added to every log's {@code context}, e.g. a signed-in user's ID. Replaces
     * what was set before; pass an empty map to clear it.
     */
    public static void setContext(@Nullable Map<String, ?> context) {
        extraContext = Json.toJsonMap(context);
    }

    // ── Sending ──────────────────────────────────────────────────────────────────────────────

    /**
     * Send everything queued now, in the background. Normally not needed: logs go out every few
     * seconds, errors at once, and everything when the app goes to the background.
     */
    public static void flush() {
        Setup current = setup;
        if (current != null) current.pipeline.flushAll();
    }

    /** Send everything queued and stop. Logs after this are dropped until {@link #init} again. */
    public static synchronized void close() {
        Setup current = setup;
        setup = null;
        if (current != null) current.pipeline.close();
    }

    // ── Internals ────────────────────────────────────────────────────────────────────────────

    /** One running configuration. */
    static final class Setup {
        final String service;
        final String environment;
        final String appVersion;
        final String source;
        final boolean logcat;
        final JSONObject deviceContext;
        final Pipeline pipeline;

        Setup(String service, String environment, String appVersion, String source, boolean logcat,
              JSONObject deviceContext, Pipeline pipeline) {
            this.service = service;
            this.environment = environment;
            this.appVersion = appVersion;
            this.source = source;
            this.logcat = logcat;
            this.deviceContext = deviceContext;
            this.pipeline = pipeline;
        }
    }

    /** The entry as Zharp takes it — the same shape every Zharp SDK sends. */
    static JSONObject entry(Setup setup, LogLevel level, Object message, Map<String, ?> metadata,
                            Throwable error, String service, long timeMillis) {
        JSONObject entry = new JSONObject();
        Json.put(entry, "service", service == null || service.trim().isEmpty() ? setup.service : service.trim());
        Json.put(entry, "level", level.wireName());
        Json.put(entry, "message", Json.toMessage(message));
        Json.put(entry, "timestamp", Json.iso(timeMillis));
        // The tags the log viewer filters by, top level where the backend reads them; absent
        // rather than empty when not configured.
        if (setup.environment != null) Json.put(entry, "environment", setup.environment);
        if (setup.appVersion != null) Json.put(entry, "version", setup.appVersion);
        if (setup.source != null) Json.put(entry, "source", setup.source);

        // An error brings its type and stack, under the keys the log viewer shows; a stack the
        // caller gave wins.
        JSONObject meta = new JSONObject();
        if (error != null) {
            Json.put(meta, "name", error.getClass().getName());
            Json.put(meta, "stack", Json.stackOf(error));
        }
        JSONObject own = Json.toJsonMap(metadata);
        for (java.util.Iterator<String> keys = own.keys(); keys.hasNext(); ) {
            String key = keys.next();
            Json.put(meta, key, own.opt(key));
        }
        Json.put(meta, "sessionId", sessionId);
        if (setup.environment != null) Json.put(meta, "environment", setup.environment);

        JSONObject context = new JSONObject();
        copyInto(context, setup.deviceContext);
        copyInto(context, extraContext);
        if (setup.appVersion != null) Json.put(context, "appVersion", setup.appVersion);
        Json.put(meta, "context", context);
        Json.put(entry, "metadata", meta);
        return entry;
    }

    private static void copyInto(JSONObject target, JSONObject source) {
        for (java.util.Iterator<String> keys = source.keys(); keys.hasNext(); ) {
            String key = keys.next();
            Json.put(target, key, source.opt(key));
        }
    }

    /**
     * Uncaught exceptions are logged as {@code fatal}, saved, and sent if that can be done in a
     * couple of seconds; then the handler that was there before runs, so the app crashes as it
     * would have (and other crash reporters still see it).
     */
    private static void installCrashHandler() {
        if (crashHandlerInstalled) return;
        crashHandlerInstalled = true;
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(@NonNull Thread thread, @NonNull Throwable error) {
                try {
                    reportCrash(thread, error);
                } catch (Throwable ignored) {
                    // Whatever happens here, the crash goes on to the previous handler.
                }
                if (previous != null) {
                    previous.uncaughtException(thread, error);
                } else {
                    android.os.Process.killProcess(android.os.Process.myPid());
                    System.exit(10);
                }
            }
        });
    }

    private static void reportCrash(Thread thread, Throwable error) {
        final Setup current = setup;
        final PendingStore store = pendingStore;
        if (current == null) return;
        Map<String, Object> metadata = new java.util.HashMap<>();
        metadata.put("type", "uncaughtException");
        metadata.put("thread", thread.getName());
        current.pipeline.push(entry(current, LogLevel.FATAL, error, metadata, error, null, System.currentTimeMillis()));
        if (current.logcat) Log.e(TAG, "Uncaught exception, sending to Zharp", error);

        final List<JSONObject> unsent = current.pipeline.drain();
        if (store != null) store.save(unsent);
        boolean sent = Pipeline.runWithin(new Callable<Boolean>() {
            @Override
            public Boolean call() {
                return current.pipeline.transport.send(unsent);
            }
        }, CRASH_FLUSH_TIMEOUT_MS);
        if (sent && store != null) store.clear();
    }

    /** Sends what's queued when the app's last activity stops: a backgrounded app can be killed at any time. */
    private static void registerLifecycle(Application application) {
        if (lifecycleRegistered) return;
        lifecycleRegistered = true;
        application.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            private int started;

            @Override
            public void onActivityStarted(@NonNull Activity activity) {
                started++;
            }

            @Override
            public void onActivityStopped(@NonNull Activity activity) {
                started = Math.max(0, started - 1);
                // A rotation stops and restarts the activity; that's not leaving the app.
                if (started == 0 && !activity.isChangingConfigurations()) flush();
            }

            @Override
            public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle savedInstanceState) {
            }

            @Override
            public void onActivityResumed(@NonNull Activity activity) {
            }

            @Override
            public void onActivityPaused(@NonNull Activity activity) {
            }

            @Override
            public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) {
            }

            @Override
            public void onActivityDestroyed(@NonNull Activity activity) {
            }
        });
    }

    /** Kept out of backups: a restored crash from another device would be sent as this one's. */
    private static File storageDir(Context context) {
        File dir = Build.VERSION.SDK_INT >= 21 ? context.getNoBackupFilesDir() : context.getFilesDir();
        return dir != null ? dir : context.getCacheDir();
    }

    /** The first value that is non-blank text — explicit option, then each fallback in turn. */
    static String pick(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) return value.trim();
        }
        return null;
    }

    private static String newSessionId() {
        return UUID.randomUUID().toString();
    }

    /** Startup output. One prefix, so it can be searched for in Logcat. */
    private static void announce(String message) {
        Log.i(TAG, "[Zharp] " + message);
    }

    /** For tests: run with this setup, as if init had made it. */
    static void startForTests(Setup next) {
        setup = next;
    }

    /** For tests: start from nothing. */
    static void resetForTests() {
        setup = null;
        extraContext = new JSONObject();
        sessionId = newSessionId();
    }
}
