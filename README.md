# Zharp Logs for Android

Send your Android app's logs and crashes to [Zharp](https://zharp.io). One library for Kotlin and
Java apps alike: it's written in Java 8 with no dependencies, so it adds nothing to an older Java
app and reads naturally from Kotlin. Android 5.0 (API 21) and up.

Full documentation: [zharp.io/docs/sdks/android](https://zharp.io/docs/sdks/android)

---

## 1. Install

In your app's `build.gradle.kts`:

```kotlin
dependencies {
    implementation("io.zharp:zharp-logs-android:0.1.1")
}
```

Or `build.gradle` (Groovy):

```groovy
dependencies {
    implementation 'io.zharp:zharp-logs-android:0.1.1'
}
```

It comes from Maven Central, which new projects already list in `settings.gradle`. The library
declares the `INTERNET` permission itself.

## 2. Get an API key

1. In Zharp, open **Integrations & API** and choose **Main API keys**.
2. Click **Create API key**, give it a name, and tick **Ingest only**. That key can send logs but
   can't read anything, so it's safe inside an app.
3. Click **Create key** and copy the key — it's shown only once.

<p>
  <img src="https://www.zharp.io/images/docs/api-key-create.png" alt="Create API key, with Ingest only ticked" width="48%">
  <img src="https://www.zharp.io/images/docs/api-key-created.png" alt="The new key, shown once with a Copy button" width="48%">
</p>

## 3. Start it

Call `init` once, in your `Application` class's `onCreate`.

**Kotlin:**

```kotlin
class ShopApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ZharpLogger.init(this, "your-ingest-only-key", "shop-app")
    }
}
```

**Java:**

```java
public class ShopApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        ZharpLogger.init(this, "your-ingest-only-key", "shop-app");
    }
}
```

(Register the class in `AndroidManifest.xml` with `<application android:name=".ShopApp" …>` if
you don't have one yet.)

**More settings** go through `ZharpConfig`:

```java
ZharpLogger.init(this, new ZharpConfig.Builder()
        .apiKey("your-ingest-only-key")
        .service("shop-app")
        .environment("production")
        .build());
```

**From the manifest** instead of code — handy for a different key per build type:

```xml
<application …>
    <meta-data android:name="io.zharp.API_KEY" android:value="${zharpApiKey}" />
    <meta-data android:name="io.zharp.SERVICE" android:value="shop-app" />
    <meta-data android:name="io.zharp.ENVIRONMENT" android:value="production" />
</application>
```

```kotlin
// build.gradle.kts
android {
    defaultConfig {
        manifestPlaceholders["zharpApiKey"] = "your-ingest-only-key"
    }
}
```

```kotlin
ZharpLogger.init(this)
```

When it starts, it writes one line to Logcat so you know it's working:

```
I Zharp: [Zharp] Logging active — service "shop-app", environment "production"
```

With no API key anywhere it says how to give it one and sends nothing.

## 4. Log

**Kotlin:**

```kotlin
ZharpLogger.info("User signed in", mapOf("userId" to 42))
ZharpLogger.warn("Slow network detected", mapOf("latencyMs" to 3200))

try {
    charge(order)
} catch (e: Exception) {
    ZharpLogger.error("Payment failed", e, mapOf("orderId" to order.id))
}
```

**Java:**

```java
Map<String, Object> metadata = new HashMap<>();
metadata.put("userId", 42);
ZharpLogger.info("User signed in", metadata);

try {
    charge(order);
} catch (Exception e) {
    ZharpLogger.error("Payment failed", e);
}
```

`debug` and `fatal` are there too. The map is optional and is kept with the log. A caught
exception brings its type and full stack (causes included) by itself — you never pass a stack.
You can also log it on its own: `ZharpLogger.error(e)` or `ZharpLogger.captureException(e)`.

Logs are sent from a background thread in batches every 5 seconds; `error` and `fatal` go at
once, and everything waiting goes when the app moves to the background. Logging never blocks the
calling thread and never throws.

## 5. See your logs

Sign in at [zharp.io](https://zharp.io/login), open **Logs**, and pick your service in the
service dropdown.

![The Logs page on zharp.io](https://www.zharp.io/images/docs/logs-dashboard.png)

---

## Crashes

Uncaught exceptions are sent as `fatal` logs with their stack and the thread they happened on.
The app still crashes as it would have, and any other crash reporter you use still sees it.

A crashing app often ends before a request can finish, so the crash is saved first. If it
couldn't be sent within a couple of seconds, it goes out the next time the app starts, with the
time it actually happened.

Turn it off with `.captureCrashes(false)`.

## Options

| `ZharpConfig.Builder` | Manifest `<meta-data>` | Default |
|---|---|---|
| `apiKey` | `io.zharp.API_KEY` | — |
| `service` | `io.zharp.SERVICE` | the app's package name |
| `environment` | `io.zharp.ENVIRONMENT` | none |
| `appVersion` | `io.zharp.VERSION` | the app's `versionName` |
| `source` | `io.zharp.SOURCE` | none |
| `endpoint` | `io.zharp.ENDPOINT` | `https://api.zharp.io/api/v1` |
| `captureCrashes` | — | on |
| `flushOnBackground` | — | on |
| `logcat` | — | on in debuggable builds |
| `flushIntervalMillis` | — | 5000 |
| `maxBatchSize` | — | 50 |

A value set in code wins over the manifest. `io.zharp.DISABLED=true` in the manifest turns the
library off.

## What's sent automatically

Every log carries a session ID (the same for one run of your app) and where it ran: platform,
Android version, API level, device manufacturer and model, package name, version name and code,
and whether the build is debuggable.

Add your own fields to every log with `ZharpLogger.setContext(mapOf("userId" to user.id))`, and
start a new session when someone else signs in with `ZharpLogger.resetSessionId()`.

With `logcat` on (the default in debug builds), each log is also written to Logcat under the tag
`Zharp`, so you still see your logs while developing.

## Already using Timber?

Plant a tree that forwards to Zharp:

```kotlin
class ZharpTree : Timber.Tree() {
    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        val level = when (priority) {
            Log.VERBOSE, Log.DEBUG -> LogLevel.DEBUG
            Log.INFO -> LogLevel.INFO
            Log.WARN -> LogLevel.WARN
            Log.ERROR -> LogLevel.ERROR
            else -> LogLevel.FATAL
        }
        ZharpLogger.log(level, message, tag?.let { mapOf("tag" to it) }, t, null)
    }
}

Timber.plant(ZharpTree())
```

Turn off `logcat` in the config if a `DebugTree` already writes to Logcat, or you'll see each line
twice.

## R8 / ProGuard

Nothing to add: the library uses no reflection. Stacks from a minified release build are
obfuscated as usual; retrace them with your `mapping.txt`.

## Samples

`samples/kotlin-app` and `samples/java-app` are small apps with a button for each kind of log and
one that crashes. Put your ingest-only key in `local.properties` and run either from Android
Studio:

```
zharp.apiKey=your-ingest-only-key
```

## Releasing (maintainers)

Published to Maven Central as `io.zharp:zharp-logs-android` (namespace `io.zharp`, verified on
[central.sonatype.com](https://central.sonatype.com)).

### Each release

1. Bump `VERSION_NAME` in `gradle.properties` and add the changes to `CHANGELOG.md`.
2. Upload:
   ```bash
   ./gradlew :zharp-logs:publishToMavenCentral
   ```
3. On central.sonatype.com → **Publishing Settings** → **Deployments**, wait for the new version to
   show **Validated**, then click **Publish**. It's installable 10–30 minutes later.
4. Tag it: `git tag -a v0.1.1 -m "0.1.1" && git push origin v0.1.1`.

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

Without a signing key, `./gradlew :zharp-logs:publishToMavenLocal` still works (unsigned), for
trying a build in another project locally.

## License

MIT © [Zharp](https://zharp.io)
