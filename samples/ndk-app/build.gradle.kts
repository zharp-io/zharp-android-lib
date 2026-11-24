import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    // Uploads the release build's native symbols (and its R8 mapping), so native crashes read as source.
    id("io.zharp.android-gradle")
}

// As in samples/monitoring-app: the key and a local Zharp from local.properties or -P, never committed.
val local = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
fun setting(name: String): String? = (findProperty(name) as String?) ?: local.getProperty(name)

android {
    namespace = "io.zharp.sample.ndk"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.zharp.sample.ndk"
        minSdk = 21
        targetSdk = 36
        versionCode = 3
        versionName = "1.0.0"
        buildConfigField("String", "ZHARP_KEY", "\"${setting("zharp.appKey") ?: ""}\"")
        buildConfigField("String", "ZHARP_ENDPOINT", "\"${setting("zharp.endpoint") ?: ""}\"")
        ndk {
            // -Pzharp.abis=x86_64 for an emulator-only build.
            abiFilters += (setting("zharp.abis") ?: "arm64-v8a,x86_64").split(',').map { it.trim() }
        }
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
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

zharp {
    setting("zharp.appId")?.let { appId = it }
}

dependencies {
    implementation(project(":zharp-android"))
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
}
