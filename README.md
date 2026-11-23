# Zharp for Android

Application Monitoring for Android apps — crashes, ANRs, caught errors, logs, screen views and
OkHttp traces — sent to [Zharp](https://zharp.io) as OpenTelemetry. One library, `io.zharp:android`
(module `zharp-android`), for Kotlin and Java apps. Android 5.0 (API 21) and up.

Full documentation: [zharp.io/docs/sdks/android](https://zharp.io/docs/sdks/android)

> It replaces the logs-only `io.zharp:zharp-logs-android`. For logs without tracing, see
> [Logs only](#logs-only).

## Install and start

```kotlin
// app/build.gradle.kts
dependencies {
    implementation("io.zharp:android:1.4.0")
}
```

```kotlin
class ShopApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Zharp.init(this) {
            key = "zpk_…"                          // the app key from Zharp
            service = "shop-android"
            environment = "production"
            tracingOrigins = listOf("api.example.com") // your API: traces continue into it
        }
    }
}

// After sign-in / sign-out:
Zharp.setUser(id = user.id, email = user.email)
Zharp.clearUser()                                 // also starts a new session

Zharp.log.info("Checkout opened", mapOf("items" to 3))
Zharp.captureError(e, mapOf("orderId" to 99))      // a caught exception, with its stack
Zharp.startSpan("load orders") { /* … */ }
Zharp.setTag("plan", "pro")

val client = OkHttpClient.Builder().addInterceptor(ZharpOkHttpInterceptor()).build()
```

Java: `ZharpOptions options = new ZharpOptions(); options.setKey("zpk_…"); Zharp.init(this, options);`,
then `Zharp.log.info(…)`, `Zharp.captureError(e)` and so on.

**Automatic:** uncaught exceptions (written to disk first, sent at once or on the next launch),
ANRs (main thread blocked 5 s), a span per Activity / AndroidX Fragment shown, sessions (new after
30 minutes in the background or on `clearUser`), device and OS on everything, an install-scoped
random `device.id`. With `logs = true`, also every log the app writes ([below](#every-log-the-app-writes)).

**Sending:** OTLP/protobuf, gzipped, batched every 5 s or 100 records; retried with backoff;
batches that still fail wait on disk (capped at 200 files / 8 MB / 3 days) and go when the network
is back. A rejected key is said once in Logcat and sending pauses for an hour. Nothing throws into
the app.

**Requirements:** Android 5.0 (API 21)+. Built on the OpenTelemetry Java SDK, which needs core
library desugaring when the app's `minSdk` is below 26:

```kotlin
android { compileOptions { isCoreLibraryDesugaringEnabled = true } }
dependencies { coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5") }
```

Without it, on Android 7.1 and older Zharp stays off (one Logcat line) rather than fail. R8 rules
ship with the library. `Zharp.openTelemetry` hands out the underlying OpenTelemetry instance for
anything else.

## Every log the app writes

Off by default: the SDK reads nothing the app logs or prints (no Timber tree, no stream tee, no
java.util.logging handler, no Logcat reader). Crashes, errors, traces, sessions, screens and
`Zharp.log` calls are sent either way. Turn it on with `logs = true` and whatever the app logs is
sent too, each line still printed exactly as before, and each line sent once however many of
these see it:

| How the app logs | How it is caught | Trace it was written in |
|---|---|---|
| Timber (4.x or 5.x) | a tree planted at init when the app has Timber | yes |
| `System.out` / `System.err` | the streams are teed; `printStackTrace()` is one record | yes |
| java.util.logging | a handler on the root logger | yes |
| `io.zharp.android.Log` | a drop-in for `android.util.Log`: change the import | yes |
| `android.util.Log`, libraries, native code | the app's own Logcat (`captureLogcat`, Android 7.0+) | no: session, user and screen only |

```kotlin
Zharp.init(this) {
    key = "zpk_…"
    logs = true                          // Java: options.setLogs(true)
    captureOutputLevel = LogLevel.DEBUG  // default INFO; Zharp.log is always sent
    captureLogcat = false                // keep the exact captures, skip reading Logcat
    // captureOutput = false             // only Zharp.log
}
```

With `logs` off, `io.zharp.android.Log` only forwards to `android.util.Log`.

- **Level:** Timber, `Log` and Logcat give their own (V/D/I/W/E, `wtf`/F as fatal);
  java.util.logging's maps SEVERE → error, WARNING → warn, INFO → info, FINE/CONFIG → debug. A
  printed line's level is read from the line: a JSON record's `level`/`severity` (pino's numbers
  too) with its `msg`/`message` as the body, `E/Tag:`, or a level word near the start (`ERROR`,
  `[warn]`, `level=error`); otherwise `System.out` is info and `System.err` a warning.
- **Logcat:** the SDK runs `logcat -v long --pid=<the app> *:<level>`. An app sees only its own
  Logcat lines and needs no permission for that (Android 4.1+; Android 13's log-access prompt is
  only for apps holding READ_LOGS). The logcat process waits in a blocking read, so a quiet app
  costs nothing but that idle process. Logcat lines are read on another thread after they were
  written, so they carry the session, user and screen but not the span; log through Timber,
  `Zharp.log` or `io.zharp.android.Log` where the trace matters. The app's lines from before
  `init` (same process) are sent once; Logcat's own `chatty` lines and the SDK's are not.
- **Once:** what an exact capture sent is remembered for 5 seconds and its Logcat copy skipped;
  `System.out`/`System.err` lines in Logcat are left to their own capture.
- Lines over 32 KB are cut; colour codes are removed. Nothing here throws into the app or blocks
  it: Logcat is read and gathered lines are sent on low-priority background threads.

## Request and response bodies

Off by default. Set `captureBodies` to see what a call sent and what came back, on its span:

```kotlin
Zharp.init(this) {
    key = "zpk_…"
    captureBodies = BodyCapture.errors()     // failed calls only (400+, or no response)
    // or: BodyCapture.all().apply { redactKeys = listOf("dateOfBirth"); maxSize = 64 * 1024 }
}
```

Java: `options.setCaptureBodies(BodyCapture.errors());`

- Your tracing origins only when you have set some (or `urls`), else every call through the client.
- Text is kept up to 32 KB (`maxSize`, at most 128 KB). Every field name stays; values under
  secret-looking keys (`password`, `token`, `authorization`, `card`, …) become `[Filtered]` on the
  device. Files in multipart uploads show as `[file photo.png · image/png · 240 KB]`, inline
  images as `[image/png · 24 KB]`, other bodies as `[binary · image/png · 24 KB]` or `[event stream]`.
- The request is never changed and one-shot bodies are never written twice; the response is read
  with `peekBody`, so the app still reads all of it.

Secret-looking query values (`?token=`, `key`, `sig`, …) are recorded as `[Filtered]` whether or
not bodies are on.

## Logs only

Turn tracing off to send logs and errors without traces:

```kotlin
Zharp.init(this) {
    key = "zpk_…"
    service = "shop-android"
    tracing = false
}
```

Java: `options.setTracing(false);`

Logs (`Zharp.log.*`), caught errors (`captureError`, with `exception.*` and the handled flag),
crashes, ANRs, and the user, session, device and tags on all of them still go to Zharp. No spans
are made or sent: no screen-view spans (the screen name is still put on logs), no HTTP spans —
`ZharpOkHttpInterceptor` does nothing — and no `traceparent`/`baggage` headers are added to any
request, whatever `tracingOrigins` says. `Zharp.startSpan("…") { … }` still runs its block, with a
no-op span; `captureError` inside it is sent as an error log.

## R8 / ProGuard

Nothing to add: the library ships its own consumer rules.

### Readable stack traces from minified builds

Add Zharp's Gradle plugin to the app module and Zharp shows crashes and errors from R8/ProGuard
builds with the original classes, methods, files and lines (grouped by them too):

```kotlin
// app/build.gradle.kts
plugins {
    id("com.android.application")
    id("io.zharp.android-gradle") version "1.2.0"
}

zharp {
    appId = "123"   // the application's ID in Zharp
}
```

For each minified variant the plugin gives the build a new UUID, writes it into the app (the
asset `zharp-debug-meta.properties`, which the SDK puts on every error as `zharp.proguard_uuid`),
and after `minify<Variant>WithR8` uploads that build's `mapping.txt` under it — no Node needed.

The upload needs a workspace API key with the **artifacts:write** scope. It's a secret: set
`ZHARP_AUTH_TOKEN` in CI, or `zharp.authToken=zh_…` in `~/.gradle/gradle.properties`; never in
the repo. Without one the upload is a dry run that prints what it would send, so local builds
work as before.

| Option | Also from | Default |
|---|---|---|
| `appId` | `ZHARP_APP_ID`, `-Pzharp.appId` | — |
| `authToken` | `ZHARP_AUTH_TOKEN`, `-Pzharp.authToken` | none: dry run |
| `url` | `ZHARP_URL`, `-Pzharp.url` | `https://api.zharp.io` |
| `uploadMappings` | | `true` (`false` still writes the UUID and prints it) |
| `includeSources` | `ZHARP_INCLUDE_SOURCES`, `-Pzharp.includeSources` | `true`: release builds upload the app's sources |
| `uploadNativeSymbols` | `ZHARP_UPLOAD_NATIVE_SYMBOLS`, `-Pzharp.uploadNativeSymbols` | `true`: release builds upload native library symbols |
| `dumpSyms` | `ZHARP_DUMP_SYMS`, `-Pzharp.dumpSyms` | `dump_syms` on PATH, else downloaded once |
| `failOnUploadError` | | `true` |

Uploading yourself instead (with `uploadMappings = false`): take the UUID from the build output
and run `npx @zharp/cli proguard upload app/build/outputs/mapping/release/mapping.txt --app 123 --uuid <uuid>`.

## Native (NDK) crashes

Crashes in C and C++ code (the app's `.so` libraries, and the libraries and engines it uses) are
reported on the next launch, from Android's own record of how the process ended
(`ApplicationExitInfo`, `REASON_CRASH_NATIVE`). There is no signal handler and no native code in
the SDK.

- **Android 12+:** the tombstone Android keeps with the record (protobuf): the signal, the abort
  message, the crashed thread frame by frame, the other threads and the modules. Frames are sent
  in Zharp's native style (`#0  Cart::total() const [libshop.so +0x83f]`) with each app
  library's GNU build ID (`zharp.native_build_ids`).
- **Android 11:** the signal only; Android keeps no stack.
- **Android 10 and older:** not reported.

Each crash is reported once (the newest exit seen is remembered; on the first run, earlier exits
are left alone), dated when it happened, with the session and screen it happened on: the SDK keeps
those with Android's record (`setProcessStateSummary`). `captureNativeCrashes = false` turns it off.

**Readable frames.** Release libraries are stripped, so the Gradle plugin uploads their symbols:
`uploadZharp<Variant>NativeSymbols` runs after `merge<Variant>NativeLibs` in release builds, on the
libraries before Android strips them, and converts each to a Breakpad `.sym` with Mozilla's
[dump_syms](https://github.com/mozilla/dump_syms) (MIT / Apache-2.0): `dumpSyms` / `ZHARP_DUMP_SYMS`,
else `dump_syms` on PATH, else its v2.3.9 release, downloaded once into
`~/.gradle/caches/zharp/` and checked against a pinned SHA-256. Each `.sym` is uploaded as
`breakpad-sym` under the debug ID its build ID maps to; with `includeSources`, the project's C and
C++ files it names go along as a source bundle. Zharp names the frames on the server.

## Samples

- `samples/monitoring-app` (Kotlin): everything on — screens, OkHttp traces, errors, logs.
- `samples/java-app` (plain Java 8): logs only (`tracing = false`), a button for each kind of log
  and one that crashes.
- `samples/ndk-app` (Kotlin + C++): two buttons that crash in C++ (a null read, an abort with a
  message). `.github/workflows/ndk-e2e.yml` builds it in Release, uploads its symbols to a
  stand-in Zharp, and crashes it on Android 11, 12 and 15 emulators.

Put your app key in `local.properties`, never committed, and run either from Android Studio:

```
zharp.appKey=zpk_…
```

## Releasing (maintainers)

Both go to Maven Central (namespace `io.zharp`, verified on
[central.sonatype.com](https://central.sonatype.com)):

- the library, `io.zharp:android` (module `zharp-android`);
- the Gradle plugin, `io.zharp:zharp-gradle-plugin` plus its plugin marker
  `io.zharp.android-gradle:io.zharp.android-gradle.gradle.plugin` (the included build
  `zharp-gradle-plugin`). It is not on the Gradle Plugin Portal: `id("io.zharp.android-gradle")`
  resolves from `mavenCentral()` in `pluginManagement`, which new Android projects already have.

### Each release

1. Bump `ZHARP_ANDROID_VERSION` in `gradle.properties` and `version` in
   `zharp-gradle-plugin/build.gradle.kts` (they ship together), and add the changes to `CHANGELOG.md`.
2. Upload both:
   ```bash
   ./gradlew :zharp-android:publishToMavenCentral
   ./gradlew -p zharp-gradle-plugin publishToMavenCentral
   ```
3. On central.sonatype.com → **Publishing Settings** → **Deployments**, wait for both new
   deployments to show **Validated**, then click **Publish** on each. They're installable 10–30
   minutes later.
4. Tag it: `git tag -a v1.0.0 -m "1.0.0" && git push origin v1.0.0`.

### One-time setup on a new machine

Credentials never go in this repo; they live in `~/.gradle/gradle.properties`:

```properties
# central.sonatype.com → avatar → View Account → Generate User Token
mavenCentralUsername=
mavenCentralPassword=

# The LAST 8 characters of the signing key's ID (gpg --list-secret-keys --keyid-format long)
signing.keyId=
signing.password=
signing.secretKeyRingFile=C:/Users/<you>/.gnupg/secring.gpg
```

The signing key (GPG, the `sec rsa4096/…` line of `gpg --list-secret-keys --keyid-format long`):

- **Moving the existing key** to another machine: `gpg --export-secret-keys --armor <KEY_ID> > key.asc`,
  then `gpg --import key.asc` there. Keep `key.asc` somewhere safe and never commit it.
- **Making a new one:**
  ```bash
  gpg --quick-gen-key "Zharp <email>" rsa4096 sign 3y
  gpg --list-secret-keys --keyid-format long        # the 16 characters after rsa4096/
  gpg --keyserver hkps://keyserver.ubuntu.com --send-keys <KEY_ID>
  ```
- **Either way**, write the file Gradle reads:
  `gpg --export-secret-keys -o ~/.gnupg/secring.gpg <KEY_ID>`.

Maven Central checks signatures against public key servers. If a deployment fails with *"Could not
find a public key by the key fingerprint"*, the public key isn't on a server yet: send it as
above, wait until `https://keyserver.ubuntu.com/pks/lookup?op=get&search=0x<FINGERPRINT>` finds it,
then **Drop** the failed deployment and upload again. (keys.openpgp.org hides a key's name until
its email is confirmed, so Maven Central may not accept it from there.)

Without a signing key, `./gradlew :zharp-android:publishToMavenLocal` and
`./gradlew -p zharp-gradle-plugin publishToMavenLocal` still work (unsigned), for trying a build in
another project locally (add `mavenLocal()` to its `pluginManagement` and dependency repositories).

## License

MIT © [Zharp](https://zharp.io)
