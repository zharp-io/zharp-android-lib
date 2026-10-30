package io.zharp.gradle

import com.sun.net.httpserver.HttpServer
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.ZipFile

/** Builds a real minified app with AGP + the plugin (Gradle TestKit). Needs ANDROID_HOME. */
class PluginFunctionalTest {
    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var project: File
    private lateinit var server: HttpServer
    private val bodies = CopyOnWriteArrayList<String>()
    private val androidHome = System.getenv("ANDROID_HOME").orEmpty()

    @Before
    fun setUp() {
        assumeTrue("ANDROID_HOME not set", androidHome.isNotEmpty() && File(androidHome).isDirectory)
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            bodies += "${exchange.requestURI.path}\n${exchange.requestHeaders.getFirst("Authorization")}\n" +
                exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            exchange.sendResponseHeaders(201, 2)
            exchange.responseBody.use { it.write("{}".toByteArray()) }
        }
        server.start()
        project = folder.newFolder("app")
        writeApp(project, "http://127.0.0.1:${server.address.port}")
    }

    @After
    fun tearDown() {
        if (::server.isInitialized) server.stop(0)
    }

    private fun runner(vararg args: String, token: String? = null): GradleRunner {
        val environment = System.getenv().toMutableMap()
        environment.remove("ZHARP_AUTH_TOKEN")
        environment.remove("ZHARP_APP_ID")
        environment.remove("ZHARP_URL")
        if (token != null) environment["ZHARP_AUTH_TOKEN"] = token
        return GradleRunner.create()
            .withProjectDir(project)
            .withPluginClasspath()
            .withEnvironment(environment)
            .withArguments(*args, "--stacktrace")
            .forwardOutput()
    }

    @Test
    fun tagsMinifiedBuildsAndDryRunsWithoutAToken() {
        val result = runner("assembleRelease", "assembleDebug").build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":generateZharpReleaseProguardUuid")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":uploadZharpReleaseMapping")?.outcome)
        // Debug isn't minified: nothing to deobfuscate, so no UUID and no upload.
        assertNull(result.task(":generateZharpDebugProguardUuid"))
        assertNull(result.task(":uploadZharpDebugMapping"))

        val uuid = uuidIn(apk("release"))
        assertNotNull("the release APK carries the UUID asset", uuid)
        assertNull("the debug APK has none", uuidIn(apk("debug")))

        val mapping = File(project, "build/outputs/mapping/release/mapping.txt")
        assertTrue(mapping.isFile)
        assertTrue("the mapping names the app's classes", mapping.readText().contains("com.example.app."))
        assertTrue(result.output, result.output.contains("Zharp: dry run"))
        assertTrue(result.output, result.output.contains("debugId=$uuid"))
        assertTrue(result.output, result.output.contains("release=\"2.0.0 (7)\""))
        assertTrue(result.output, result.output.contains("$mapping"))
        assertTrue("nothing sent without a token", bodies.isEmpty())
    }

    @Test
    fun uploadsTheMappingUnderTheApksUuid() {
        val result = runner("assembleRelease", token = "zh_test_token").build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":uploadZharpReleaseMapping")?.outcome)
        val uuid = uuidIn(apk("release"))!!

        val body = bodies.single()
        assertTrue(body, body.startsWith("/api/v1/applications/42/artifacts\nBearer zh_test_token\n"))
        assertTrue(body, body.contains("name=\"kind\"\r\n\r\nproguard\r\n"))
        assertTrue(body, body.contains("name=\"debugId\"\r\n\r\n$uuid\r\n"))
        assertTrue(body, body.contains("name=\"release\"\r\n\r\n2.0.0 (7)\r\n"))
        assertTrue(body, body.contains(File(project, "build/outputs/mapping/release/mapping.txt").readText()))
        assertTrue(result.output.contains("Zharp: uploaded mapping.txt for ProGuard UUID $uuid"))

        // The next build is a new build: a new UUID, uploaded again.
        runner("assembleRelease", token = "zh_test_token").build()
        val next = uuidIn(apk("release"))!!
        assertTrue(next != uuid)
        assertTrue(bodies.last().contains("name=\"debugId\"\r\n\r\n$next\r\n"))
    }

    private fun apk(variant: String): File =
        File(project, "build/outputs/apk/$variant").listFiles { file -> file.name.endsWith(".apk") }!!.single()

    private fun uuidIn(apk: File): String? = ZipFile(apk).use { zip ->
        val entry = zip.getEntry("assets/zharp-debug-meta.properties") ?: return null
        GenerateProguardUuidTask.read(zip.getInputStream(entry).readBytes().toString(Charsets.UTF_8))
    }

    private fun writeApp(dir: File, url: String) {
        File(dir, "settings.gradle.kts").writeText(
            """
            pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
            dependencyResolutionManagement { repositories { google(); mavenCentral() } }
            rootProject.name = "app"
            """.trimIndent(),
        )
        File(dir, "gradle.properties").writeText("org.gradle.jvmargs=-Xmx2g\n")
        File(dir, "local.properties").writeText("sdk.dir=${androidHome.replace("\\", "\\\\").replace(":", "\\:")}\n")
        File(dir, "build.gradle.kts").writeText(
            """
            plugins {
                id("com.android.application")
                id("io.zharp.android-gradle")
            }

            android {
                namespace = "com.example.app"
                compileSdk = 36
                defaultConfig {
                    applicationId = "com.example.app"
                    minSdk = 24
                    targetSdk = 36
                    versionCode = 7
                    versionName = "2.0.0"
                }
                buildTypes {
                    release {
                        isMinifyEnabled = true
                        proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
                        signingConfig = signingConfigs.getByName("debug")
                    }
                }
            }

            zharp {
                appId = "42"
                url = "$url"
            }
            """.trimIndent(),
        )
        File(dir, "src/main").mkdirs()
        File(dir, "src/main/AndroidManifest.xml").writeText(
            """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                <application android:label="app">
                    <activity android:name=".MainActivity" android:exported="true">
                        <intent-filter>
                            <action android:name="android.intent.action.MAIN" />
                            <category android:name="android.intent.category.LAUNCHER" />
                        </intent-filter>
                    </activity>
                </application>
            </manifest>
            """.trimIndent(),
        )
        val java = File(dir, "src/main/java/com/example/app").apply { mkdirs() }
        File(java, "MainActivity.java").writeText(
            """
            package com.example.app;

            public class MainActivity extends android.app.Activity {
                @Override protected void onCreate(android.os.Bundle state) {
                    super.onCreate(state);
                    new Checkout().pay(getIntent().getIntExtra("cents", 1));
                }
            }
            """.trimIndent(),
        )
        File(java, "Checkout.java").writeText(
            """
            package com.example.app;

            public class Checkout {
                public void pay(int cents) {
                    if (cents < 0) throw new IllegalStateException("Card declined: " + cents);
                    android.util.Log.i("app", "paid " + cents);
                }
            }
            """.trimIndent(),
        )
    }
}
