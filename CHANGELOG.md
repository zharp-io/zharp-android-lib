# Changelog

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
