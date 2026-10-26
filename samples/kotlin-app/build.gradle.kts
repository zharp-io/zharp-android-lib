import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Your ingest-only key, from local.properties (zharp.apiKey=…), never committed.
val local = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "io.zharp.sample.kotlin"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.zharp.sample.kotlin"
        minSdk = 21
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        manifestPlaceholders["zharpApiKey"] = local.getProperty("zharp.apiKey", "")
        manifestPlaceholders["zharpEndpoint"] = local.getProperty("zharp.endpoint", "")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":zharp-logs"))
}
