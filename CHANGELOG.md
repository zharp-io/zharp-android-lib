# Changelog

## Unreleased

- `logs = true` (Java: `setLogs(true)`; off by default): every log the app writes is sent, not
  only `Zharp.log`, each still printed as before and sent once. Off, nothing the app logs or
  prints is read; crashes, errors, traces, sessions, screens and `Zharp.log` are sent either way.
- With `logs` on: Timber (a tree planted when the app has it), `System.out` / `System.err` (a
  `printStackTrace()` is one record), java.util.logging and the new `io.zharp.android.Log`
  drop-in for `android.util.Log` arrive in the trace they were written in; `captureLogcat`
  (default true, Android 7.0+) reads the app's own Logcat for `android.util.Log`, libraries and
  native code, without the trace. `captureOutput` (default true) switches all of these;
  `captureOutputLevel` (default `LogLevel.INFO`) sets the lowest level sent. Levels come from the
  logger, else from the line (JSON `level`, `E/Tag:`, `ERROR`, `[warn]`), else info for stdout
  and warn for stderr.

## io.zharp:android 1.1.0

- `captureBodies` (Java: `setCaptureBodies`): request and response bodies on OkHttp spans, off by
  default. `BodyCapture.errors()` for failed calls, `BodyCapture.all()` for every call; `maxSize`
  (32 KB default, 128 KB at most), `redactKeys`, `urls`. Same rules as every Zharp SDK: field
  names kept, secret values `[Filtered]` on the device, multipart files / inline images / binary
  bodies described, never sent.
- `url.full` now includes the query string, with secret-looking values (`token`, `key`, `sig`,
  `password`, …) recorded as `[Filtered]`; it used to drop the query string entirely.

## io.zharp:android 1.0.0

- New module `zharp-android`: Application Monitoring on OpenTelemetry Java — crashes, ANRs,
  `captureError`, `Zharp.log.*`, Activity/Fragment screen views, OkHttp CLIENT spans with W3C
  `traceparent` + `baggage` to `tracingOrigins` only, sessions, device context, disk outbox.
- `tracing = false` (Java: `setTracing(false)`): logs only. Logs, errors, crashes, ANRs, user,
  session, device and tags still go out; no spans are made or sent, `ZharpOkHttpInterceptor` does
  nothing, and no `traceparent`/`baggage` headers are added. `startSpan` still runs its block.
- Readable stack traces from minified builds: the new Gradle plugin `io.zharp.android-gradle`
  (module `zharp-gradle-plugin`) writes a UUID into each R8/ProGuard build and uploads its
  `mapping.txt` to Zharp (`POST /api/v1/applications/:id/artifacts`, kind `proguard`); the SDK
  puts that UUID on every error — exception events, error logs, crashes, ANRs — as
  `zharp.proguard_uuid`.
- Replaces `io.zharp:zharp-logs-android`: the `zharp-logs` module and the Kotlin logs sample are
  removed; `samples/java-app` now uses `zharp-android` with `tracing = false`. The entries below
  are that library's history.

## 0.1.1

- When Zharp rejects the API key (401/403) — deleted, revoked or mistyped — logging says so once
  in Logcat and stops sending, instead of sending every batch forever. It tries again after an
  hour and says when the key works again. Nothing piles up meanwhile, and the app is unaffected.

## 0.1.0

- First release: `ZharpLogger` for Kotlin and Java apps, Android 5.0 and up, no dependencies.
- Logs batched from a background thread; `error` and `fatal` sent at once; everything sent when
  the app goes to the background.
- Caught exceptions carry their type and full stack automatically.
- Crashes sent as `fatal` logs, saved first and sent on the next launch if they couldn't go out.
- Settings in code (`ZharpConfig`) or the manifest (`io.zharp.*` meta-data).
