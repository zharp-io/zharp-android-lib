package io.zharp.sample.java;

import android.app.Application;

import io.zharp.android.Zharp;
import io.zharp.android.ZharpOptions;

/** A plain-Java app sending logs and errors only: tracing is off, so no spans and no trace headers. */
public class SampleApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        // The key comes from local.properties via BuildConfig.
        ZharpOptions options = new ZharpOptions();
        options.setKey(BuildConfig.ZHARP_KEY);
        options.setEndpoint(BuildConfig.ZHARP_ENDPOINT.isEmpty() ? null : BuildConfig.ZHARP_ENDPOINT);
        options.setService("android-java-sample");
        options.setEnvironment("development");
        options.setTracing(false); // logs only
        options.setDebug(true);
        Zharp.init(this, options);
    }
}
