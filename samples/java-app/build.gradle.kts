import java.util.Properties

plugins {
    id("com.android.application")
}

// Your app key (zpk_…) and, optionally, a local Zharp, from local.properties — never committed:
//   zharp.appKey=zpk_…
//   zharp.endpoint=http://10.0.2.2:3000/api/v1/otlp
val local = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "io.zharp.sample.java"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.zharp.sample.java"
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

    compileOptions {
        // Plain Java 8, as an older app would be.
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
        // OpenTelemetry needs this below API 26.
        isCoreLibraryDesugaringEnabled = true
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-Xlint:-options")
}

dependencies {
    implementation(project(":zharp-android"))
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
}
