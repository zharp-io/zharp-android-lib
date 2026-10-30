package io.zharp.android.internal

import io.opentelemetry.api.common.AttributeKey

/** Attribute names, as the Zharp wire contract and OpenTelemetry semantic conventions spell them. */
internal object Keys {
    val SERVICE_NAME: AttributeKey<String> = AttributeKey.stringKey("service.name")
    val SERVICE_VERSION: AttributeKey<String> = AttributeKey.stringKey("service.version")
    val ENVIRONMENT: AttributeKey<String> = AttributeKey.stringKey("deployment.environment.name")
    val SDK_NAME: AttributeKey<String> = AttributeKey.stringKey("telemetry.sdk.name")
    val SDK_LANGUAGE: AttributeKey<String> = AttributeKey.stringKey("telemetry.sdk.language")
    val SDK_VERSION: AttributeKey<String> = AttributeKey.stringKey("telemetry.sdk.version")

    val OS_NAME: AttributeKey<String> = AttributeKey.stringKey("os.name")
    val OS_TYPE: AttributeKey<String> = AttributeKey.stringKey("os.type")
    val OS_VERSION: AttributeKey<String> = AttributeKey.stringKey("os.version")
    val ANDROID_API_LEVEL: AttributeKey<Long> = AttributeKey.longKey("android.os.api_level")
    val DEVICE_ID: AttributeKey<String> = AttributeKey.stringKey("device.id")
    val DEVICE_MODEL_NAME: AttributeKey<String> = AttributeKey.stringKey("device.model.name")
    val DEVICE_MODEL_IDENTIFIER: AttributeKey<String> = AttributeKey.stringKey("device.model.identifier")
    val DEVICE_MANUFACTURER: AttributeKey<String> = AttributeKey.stringKey("device.manufacturer")
    val APP_ID: AttributeKey<String> = AttributeKey.stringKey("app.id")

    val SESSION_ID: AttributeKey<String> = AttributeKey.stringKey("session.id")
    val USER_ID: AttributeKey<String> = AttributeKey.stringKey("enduser.id")
    val USER_EMAIL: AttributeKey<String> = AttributeKey.stringKey("enduser.email")
    val USER_NAME: AttributeKey<String> = AttributeKey.stringKey("enduser.name")
    val SCREEN_NAME: AttributeKey<String> = AttributeKey.stringKey("screen.name")
    val SCREEN_TYPE: AttributeKey<String> = AttributeKey.stringKey("screen.type")

    val EXCEPTION_TYPE: AttributeKey<String> = AttributeKey.stringKey("exception.type")
    val EXCEPTION_MESSAGE: AttributeKey<String> = AttributeKey.stringKey("exception.message")
    val EXCEPTION_STACKTRACE: AttributeKey<String> = AttributeKey.stringKey("exception.stacktrace")
    val HANDLED: AttributeKey<String> = AttributeKey.stringKey("zharp.handled")
    val ERROR_KIND: AttributeKey<String> = AttributeKey.stringKey("zharp.error.kind")
    val THREAD_NAME: AttributeKey<String> = AttributeKey.stringKey("thread.name")
    val ERROR_TYPE: AttributeKey<String> = AttributeKey.stringKey("error.type")
    val PROGUARD_UUID: AttributeKey<String> = AttributeKey.stringKey("zharp.proguard_uuid")

    val HTTP_METHOD: AttributeKey<String> = AttributeKey.stringKey("http.request.method")
    val HTTP_STATUS: AttributeKey<Long> = AttributeKey.longKey("http.response.status_code")
    val URL_FULL: AttributeKey<String> = AttributeKey.stringKey("url.full")
    val URL_PATH: AttributeKey<String> = AttributeKey.stringKey("url.path")
    val SERVER_ADDRESS: AttributeKey<String> = AttributeKey.stringKey("server.address")
    val SERVER_PORT: AttributeKey<Long> = AttributeKey.longKey("server.port")
}
