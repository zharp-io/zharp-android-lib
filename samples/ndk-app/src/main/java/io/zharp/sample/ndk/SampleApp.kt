package io.zharp.sample.ndk

import android.app.Application
import io.zharp.android.Zharp

class SampleApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Zharp.init(this) {
            key = BuildConfig.ZHARP_KEY
            service = "android-ndk-sample"
            environment = "development"
            endpoint = BuildConfig.ZHARP_ENDPOINT.ifEmpty { null }
            debug = true
        }
        Zharp.setUser(id = "sample-user")
    }
}
