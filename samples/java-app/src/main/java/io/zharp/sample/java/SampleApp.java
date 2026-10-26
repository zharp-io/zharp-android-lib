package io.zharp.sample.java;

import android.app.Application;

import io.zharp.logs.ZharpConfig;
import io.zharp.logs.ZharpLogger;

public class SampleApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        // Configured in code. The key comes from local.properties via BuildConfig.
        ZharpLogger.init(this, new ZharpConfig.Builder()
                .apiKey(BuildConfig.ZHARP_API_KEY)
                .endpoint(BuildConfig.ZHARP_ENDPOINT)
                .service("android-java-sample")
                .environment("development")
                .build());
    }
}
