import java.util.Properties

plugins {
    id("com.android.application")
}

// Your ingest-only key, from local.properties (zharp.apiKey=…), never committed.
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
        buildConfigField("String", "ZHARP_API_KEY", "\"${local.getProperty("zharp.apiKey", "")}\"")
        buildConfigField("String", "ZHARP_ENDPOINT", "\"${local.getProperty("zharp.endpoint", "")}\"")
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        // Plain Java 8, as an older app would be.
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-Xlint:-options")
}

dependencies {
    implementation(project(":zharp-logs"))
}
