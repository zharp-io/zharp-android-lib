import com.vanniktech.maven.publish.AndroidSingleVariantLibrary
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.vanniktech.maven.publish")
}

group = providers.gradleProperty("GROUP").get()
version = providers.gradleProperty("ZHARP_ANDROID_VERSION").get()

val otel = "1.67.0"

android {
    namespace = "io.zharp.android"
    compileSdk = 36

    defaultConfig {
        // OpenTelemetry Java needs core library desugaring below API 26; init() checks for it and
        // stays off (with one Logcat line) rather than fail later.
        minSdk = 21
        consumerProguardFiles("consumer-rules.pro")
        buildConfigField("String", "VERSION_NAME", "\"${project.version}\"")
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    explicitApi()
    // Kotlin 2.0 metadata and stdlib, which compilers from Kotlin 1.9 on can read: apps don't
    // have to be on the newest Kotlin to use it.
    coreLibrariesVersion = "2.0.21"
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_1_8)
        apiVersion.set(KotlinVersion.KOTLIN_2_0)
        languageVersion.set(KotlinVersion.KOTLIN_2_0)
    }
}

dependencies {
    // The OpenTelemetry API is part of ours: Zharp.openTelemetry hands it out for custom spans.
    api("io.opentelemetry:opentelemetry-api:$otel")
    implementation("io.opentelemetry:opentelemetry-sdk-trace:$otel")
    implementation("io.opentelemetry:opentelemetry-sdk-logs:$otel")
    // OTLP protobuf encoding only; the HTTP sending is ours (no OkHttp, no metrics SDK).
    implementation("io.opentelemetry:opentelemetry-exporter-otlp-common:$otel")

    // Used only when the app has them: ZharpOkHttpInterceptor, and Fragment screen views.
    compileOnly("com.squareup.okhttp3:okhttp:4.12.0")
    compileOnly("androidx.fragment:fragment:1.8.9")
    compileOnly("androidx.annotation:annotation:1.9.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("io.opentelemetry.proto:opentelemetry-proto:1.11.1-alpha")
}

// Published to Maven Central as io.zharp:android. Credentials and the signing key are never in
// this repo: they're read from ~/.gradle/gradle.properties (or ORG_GRADLE_PROJECT_* in CI).
mavenPublishing {
    publishToMavenCentral()
    if (providers.gradleProperty("signing.keyId").isPresent || providers.gradleProperty("signingInMemoryKey").isPresent) {
        signAllPublications()
    }
    coordinates(project.group.toString(), "android", project.version.toString())
    configure(AndroidSingleVariantLibrary(variant = "release", sourcesJar = true, publishJavadocJar = true))

    pom {
        name.set("Zharp for Android")
        description.set("Application Monitoring for Android apps: crashes, ANRs, errors, logs, screen views and HTTP traces, sent to Zharp over OpenTelemetry.")
        inceptionYear.set("2026")
        url.set("https://github.com/zharp-io/zharp-android-lib")
        licenses {
            license {
                name.set("MIT License")
                url.set("https://opensource.org/licenses/MIT")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("zharp")
                name.set("Zharp")
                url.set("https://zharp.io")
            }
        }
        scm {
            url.set("https://github.com/zharp-io/zharp-android-lib")
            connection.set("scm:git:https://github.com/zharp-io/zharp-android-lib.git")
            developerConnection.set("scm:git:ssh://git@github.com/zharp-io/zharp-android-lib.git")
        }
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-Xlint:-options")
}

tasks.withType<Test>().configureEach {
    // The end-to-end test reads its key and endpoint from the environment; never from a file.
    environment("ZHARP_E2E_KEY", System.getenv("ZHARP_E2E_KEY") ?: "")
    environment("ZHARP_E2E_ENDPOINT", System.getenv("ZHARP_E2E_ENDPOINT") ?: "")
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
    }
}
