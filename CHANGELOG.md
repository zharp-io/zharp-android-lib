# Changelog

## 0.1.0

- First release: `ZharpLogger` for Kotlin and Java apps, Android 5.0 and up, no dependencies.
- Logs batched from a background thread; `error` and `fatal` sent at once; everything sent when
  the app goes to the background.
- Caught exceptions carry their type and full stack automatically.
- Crashes sent as `fatal` logs, saved first and sent on the next launch if they couldn't go out.
- Settings in code (`ZharpConfig`) or the manifest (`io.zharp.*` meta-data).
