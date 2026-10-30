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
    implementation("io.zharp:android:1.0.0")
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
random `device.id`. Logcat is not read.

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
    id("io.zharp.android-gradle") version "1.0.0"
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
| `failOnUploadError` | | `true` |

Uploading yourself instead (with `uploadMappings = false`): take the UUID from the build output
and run `npx @zharp/cli proguard upload app/build/outputs/mapping/release/mapping.txt --app 123 --uuid <uuid>`.

## Samples

- `samples/monitoring-app` (Kotlin): everything on — screens, OkHttp traces, errors, logs.
- `samples/java-app` (plain Java 8): logs only (`tracing = false`), a button for each kind of log
  and one that crashes.

Put your app key (and optionally a local Zharp) in `local.properties`, never committed, and run
either from Android Studio:

```
zharp.appKey=zpk_…
zharp.endpoint=http://10.0.2.2:3000/api/v1/otlp
```

## Releasing (maintainers)

Published to Maven Central as `io.zharp:android` (namespace `io.zharp`, verified on
[central.sonatype.com](https://central.sonatype.com)).

### Each release

1. Bump `ZHARP_ANDROID_VERSION` in `gradle.properties` and add the changes to `CHANGELOG.md`.
2. Upload:
   ```bash
   ./gradlew :zharp-android:publishToMavenCentral
   ```
3. On central.sonatype.com → **Publishing Settings** → **Deployments**, wait for the new version to
   show **Validated**, then click **Publish**. It's installable 10–30 minutes later.
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

Without a signing key, `./gradlew :zharp-android:publishToMavenLocal` still works (unsigned), for
trying a build in another project locally.

## License

MIT © [Zharp](https://zharp.io)
