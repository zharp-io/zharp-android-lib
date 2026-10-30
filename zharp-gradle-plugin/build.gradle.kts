plugins {
    `kotlin-dsl`
}

group = "io.zharp"
version = "1.0.0"

val agp = "8.11.1"

// AGP at test time, on the same classpath as the plugin under test (TestKit injects it), so the
// test app applies com.android.application by id and both plugins see the same AGP classes.
val testAgp: Configuration by configurations.creating

dependencies {
    // The app's own AGP is what runs; the plugin compiles against its public Variant API only.
    compileOnly("com.android.tools.build:gradle-api:$agp")
    testAgp("com.android.tools.build:gradle:$agp")

    testImplementation(gradleTestKit())
    testImplementation("junit:junit:4.13.2")
}

// AGP 8 runs on JDK 17+, so the plugin's bytecode targets 17 whichever JDK builds it.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

gradlePlugin {
    plugins {
        create("zharpAndroid") {
            id = "io.zharp.android-gradle"
            implementationClass = "io.zharp.gradle.ZharpAndroidPlugin"
            displayName = "Zharp for Android"
            description = "Readable stack traces in Zharp for R8/ProGuard-minified Android apps: tags each build with a UUID and uploads its mapping.txt."
        }
    }
}

tasks.pluginUnderTestMetadata {
    pluginClasspath.from(testAgp)
}

tasks.test {
    // The functional test builds a real minified app: it needs the Android SDK.
    environment("ANDROID_HOME", System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT") ?: "")
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
