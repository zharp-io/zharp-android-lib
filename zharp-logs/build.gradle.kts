plugins {
    id("com.android.library")
    id("maven-publish")
}

group = providers.gradleProperty("GROUP").get()
version = providers.gradleProperty("VERSION_NAME").get()

android {
    namespace = "io.zharp.logs"
    compileSdk = 36

    defaultConfig {
        // Android 5.0: what old Java apps still ship to.
        minSdk = 21
        consumerProguardFiles("consumer-rules.pro")
        buildConfigField("String", "VERSION_NAME", "\"${project.version}\"")
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        // Java 8 bytecode, so it builds into any app, Java or Kotlin, old toolchain or new.
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
            withJavadocJar()
        }
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-Xlint:-options")
}

dependencies {
    // Nullability for Kotlin callers, at compile time only: nothing is added to the app.
    compileOnly("androidx.annotation:annotation:1.9.1")

    testImplementation("junit:junit:4.13.2")
    // The real org.json for JVM tests; on a device it's part of Android.
    testImplementation("org.json:json:20240303")
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = project.group.toString()
            artifactId = "zharp-logs-android"
            version = project.version.toString()
            afterEvaluate { from(components["release"]) }
            pom {
                name.set("Zharp Logs for Android")
                description.set("Send your Android app's logs and crashes to Zharp. Works from Kotlin and Java.")
                url.set("https://zharp.io/docs/sdks/logs")
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/licenses/MIT")
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
                }
            }
        }
    }
}
