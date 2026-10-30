package io.zharp.sample.monitoring

import android.app.Application
import io.zharp.android.Zharp
import io.zharp.android.ZharpOkHttpInterceptor
import okhttp3.OkHttpClient

class SampleApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Zharp.init(this) {
            key = BuildConfig.ZHARP_KEY
            service = "android-sample"
            environment = "development"
            endpoint = BuildConfig.ZHARP_ENDPOINT.ifEmpty { null }
            tracingOrigins = listOf("10.0.2.2", "localhost")
            debug = true
        }
        Zharp.setUser(id = "sample-user", email = "sample@example.com")
    }

    companion object {
        val http: OkHttpClient by lazy {
            OkHttpClient.Builder().addInterceptor(ZharpOkHttpInterceptor()).build()
        }
    }
}
