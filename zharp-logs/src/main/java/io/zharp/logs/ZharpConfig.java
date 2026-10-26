package io.zharp.logs;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Settings for {@link ZharpLogger#init(android.content.Context, ZharpConfig)}.
 *
 * <pre>{@code
 * ZharpConfig config = new ZharpConfig.Builder()
 *         .apiKey("your-ingest-only-key")
 *         .service("shop-app")
 *         .environment("production")
 *         .build();
 * }</pre>
 *
 * Anything left unset is read from the app's {@code AndroidManifest.xml} {@code <meta-data>}
 * ({@code io.zharp.API_KEY}, {@code io.zharp.SERVICE}, {@code io.zharp.ENVIRONMENT},
 * {@code io.zharp.VERSION}, {@code io.zharp.SOURCE}, {@code io.zharp.ENDPOINT}), then defaulted.
 */
public final class ZharpConfig {
    @Nullable final String apiKey;
    @Nullable final String service;
    @Nullable final String environment;
    @Nullable final String appVersion;
    @Nullable final String source;
    @Nullable final String endpoint;
    final long flushIntervalMillis;
    final int maxBatchSize;
    final boolean captureCrashes;
    final boolean flushOnBackground;
    @Nullable final Boolean logcat;

    private ZharpConfig(Builder b) {
        apiKey = b.apiKey;
        service = b.service;
        environment = b.environment;
        appVersion = b.appVersion;
        source = b.source;
        endpoint = b.endpoint;
        flushIntervalMillis = b.flushIntervalMillis;
        maxBatchSize = Math.max(1, b.maxBatchSize);
        captureCrashes = b.captureCrashes;
        flushOnBackground = b.flushOnBackground;
        logcat = b.logcat;
    }

    /** Builds a {@link ZharpConfig}. Every setting is optional. */
    public static final class Builder {
        private String apiKey;
        private String service;
        private String environment;
        private String appVersion;
        private String source;
        private String endpoint;
        private long flushIntervalMillis = 5_000;
        private int maxBatchSize = 50;
        private boolean captureCrashes = true;
        private boolean flushOnBackground = true;
        private Boolean logcat;

        /** An ingest-only API key from Zharp → Integrations &amp; API → Main API keys. */
        @NonNull
        public Builder apiKey(@Nullable String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        /** The name logs are filed under. Defaults to the app's package name. */
        @NonNull
        public Builder service(@Nullable String service) {
            this.service = service;
            return this;
        }

        /** Tag every log carries, e.g. {@code "production"}. Not guessed when unset. */
        @NonNull
        public Builder environment(@Nullable String environment) {
            this.environment = environment;
            return this;
        }

        /** Tag every log carries. Defaults to the app's {@code versionName}. */
        @NonNull
        public Builder appVersion(@Nullable String appVersion) {
            this.appVersion = appVersion;
            return this;
        }

        /** Tag every log carries, e.g. {@code "play-store"}. Not set unless given. */
        @NonNull
        public Builder source(@Nullable String source) {
            this.source = source;
            return this;
        }

        /** Where logs are sent. Defaults to {@code https://api.zharp.io/api/v1}. */
        @NonNull
        public Builder endpoint(@Nullable String endpoint) {
            this.endpoint = endpoint;
            return this;
        }

        /** How long logs wait to be sent together. 5 seconds unless set. */
        @NonNull
        public Builder flushIntervalMillis(long millis) {
            this.flushIntervalMillis = Math.max(100, millis);
            return this;
        }

        /** Most logs sent in one request. 50 unless set. */
        @NonNull
        public Builder maxBatchSize(int size) {
            this.maxBatchSize = size;
            return this;
        }

        /**
         * Send uncaught exceptions as {@code fatal} logs with their stack (on by default). The app
         * still crashes as it would have; the crash is saved first and sent on the next launch if
         * it couldn't go out before the process ended.
         */
        @NonNull
        public Builder captureCrashes(boolean capture) {
            this.captureCrashes = capture;
            return this;
        }

        /** Send what's queued when the app leaves the foreground (on by default). */
        @NonNull
        public Builder flushOnBackground(boolean flush) {
            this.flushOnBackground = flush;
            return this;
        }

        /** Also write each log to Logcat. On in debuggable builds unless set. */
        @NonNull
        public Builder logcat(boolean logcat) {
            this.logcat = logcat;
            return this;
        }

        @NonNull
        public ZharpConfig build() {
            return new ZharpConfig(this);
        }
    }
}
