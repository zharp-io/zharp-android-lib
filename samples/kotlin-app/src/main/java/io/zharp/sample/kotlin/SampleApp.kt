package io.zharp.sample.kotlin

import android.app.Application
import io.zharp.logs.ZharpLogger

class SampleApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Key, service and environment come from the <meta-data> in AndroidManifest.xml.
        ZharpLogger.init(this)
    }
}
