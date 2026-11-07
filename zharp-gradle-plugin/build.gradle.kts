import com.vanniktech.maven.publish.GradlePlugin
import com.vanniktech.maven.publish.JavadocJar

plugins {
    `kotlin-dsl`
    id("com.vanniktech.maven.publish") version "0.34.0"
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

// Published to Maven Central (not the Gradle Plugin Portal): the implementation as
// io.zharp:zharp-gradle-plugin and the plugin marker io.zharp.android-gradle:io.zharp.android-gradle.gradle.plugin,
// so `id("io.zharp.android-gradle")` resolves wherever pluginManagement has mavenCentral() (the
// Android default). Credentials and the signing key come from ~/.gradle/gradle.properties.
mavenPublishing {
    publishToMavenCentral()
    if (providers.gradleProperty("signing.keyId").isPresent || providers.gradleProperty("signingInMemoryKey").isPresent) {
        signAllPublications()
    }
    coordinates(project.group.toString(), "zharp-gradle-plugin", project.version.toString())
    configure(GradlePlugin(javadocJar = JavadocJar.Empty(), sourcesJar = true))

    pom {
        name.set("Zharp for Android Gradle plugin")
        description.set("Readable stack traces in Zharp for R8/ProGuard-minified Android apps: tags each build with a UUID and uploads its mapping.txt.")
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
