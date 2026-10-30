import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    // Tags each minified build with a UUID and uploads its mapping.txt, so crashes read as source.
    id("io.zharp.android-gradle")
}

// Your app key (zpk_…) and, optionally, a local Zharp, from local.properties — never committed:
//   zharp.appKey=zpk_…
//   zharp.endpoint=http://10.0.2.2:3000/api/v1/otlp
//   zharp.appId=123            (the application's ID, for the mapping upload)
val local = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "io.zharp.sample.monitoring"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.zharp.sample.monitoring"
        minSdk = 21
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        buildConfigField("String", "ZHARP_KEY", "\"${local.getProperty("zharp.appKey", "")}\"")
        buildConfigField("String", "ZHARP_ENDPOINT", "\"${local.getProperty("zharp.endpoint", "")}\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            // Shrunk as a real app ships, so the SDK's R8 rules and size are what users get.
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        // OpenTelemetry needs this below API 26.
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// The release build's mapping.txt goes to Zharp when ZHARP_AUTH_TOKEN (an API key with the
// artifacts:write scope) is set, e.g. ZHARP_URL=http://localhost:3000 for a local Zharp; without a
// token the upload is a dry run that prints what it would send.
zharp {
    local.getProperty("zharp.appId")?.let { appId = it }
}

dependencies {
    implementation(project(":zharp-android"))
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
}
