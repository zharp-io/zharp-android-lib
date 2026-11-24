#!/usr/bin/env bash
# Native (NDK) crashes on the Android emulator, run by the android-emulator-runner action (ubuntu,
# KVM): the sample crashes in C++, and the next launch reports it from Android's exit record.
set -uo pipefail
OUT="$RUNNER_TEMP/sent"
API="$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
APP=io.zharp.sample.ndk
FAILED=0
mkdir -p "$OUT"
echo "API level $API"
adb install -r samples/ndk-app/build/outputs/apk/release/ndk-app-release.apk || exit 1

python3 e2e/mock_zharp.py "$OUT" > "$RUNNER_TEMP/mock.log" 2>&1 &
MOCK=$!
sleep 1

run() { # extra seconds
  adb shell am force-stop "$APP"
  adb shell am start -n "$APP/.MainActivity" ${1:+-e crash "$1"} > /dev/null
  sleep "$2"
  echo "  --- logcat (${1:-launch})"
  adb logcat -d | grep -E "Zharp|DEBUG|libc |AndroidRuntime" | tail -25 | sed 's/^/    /'
  adb logcat -c
}
logs() { python3 e2e/otlp_logs.py "$OUT" > "$OUT/logs.json" && cat "$OUT/logs.json"; }
fail() { echo "::error::API $API: $1"; FAILED=1; }
count() { # exception.type
  python3 -c "import json,sys; print(sum(1 for r in json.load(open('$OUT/logs.json')) if r['attributes'].get('exception.type') == sys.argv[1]))" "$1"
}
check() { # exception.type, python expression on record r that must hold
  python3 - "$1" "$2" <<'PY' || fail "check on $1: $2"
import json, sys
kind, condition = sys.argv[1], sys.argv[2]
records = [r for r in json.load(open(__import__("os").environ["OUT"] + "/logs.json")) if r["attributes"].get("exception.type") == kind]
assert records, "no %s record" % kind
r = records[-1]
a = r["attributes"]
assert eval(condition), "%s\n%s" % (condition, a.get("exception.stacktrace"))
PY
}
export OUT

adb logcat -c
run "" 8         # first launch with the SDK: earlier exits aren't reported
run segv 8       # crashes in C++
run "" 15        # reports it
logs > /dev/null
[ "$(count SIGSEGV)" = 1 ] || fail "expected one SIGSEGV report, got $(count SIGSEGV)"
check SIGSEGV "r['severity_number'] == 21 and a['zharp.handled'] == 'false' and a['zharp.error.kind'] == 'crash'"
check SIGSEGV "a.get('session.id') and a.get('screen.name') == 'MainActivity' and a.get('enduser.id') == 'sample-user'"
if [ "$API" -ge 31 ]; then
  check SIGSEGV "a['exception.message'].startswith('SEGV_MAPERR at 0x')"
  check SIGSEGV "'libshop.so +0x' in a['exception.stacktrace']"
  check SIGSEGV "'Java_io_zharp_sample_ndk_NativeShop_checkout [libshop.so +0x' in a['exception.stacktrace']"
  check SIGSEGV "' libshop.so' in a['zharp.native_build_ids'] and 'libart.so' not in a['zharp.native_build_ids']"
  check SIGSEGV "a.get('native.cpu') == 'x86_64' and a.get('native.threads')"
else
  check SIGSEGV "a['exception.message'].startswith('No stack: Android 11')"
fi

run abort 8
run "" 15
logs > /dev/null
[ "$(count SIGABRT)" = 1 ] || fail "expected one SIGABRT report, got $(count SIGABRT)"
if [ "$API" -ge 31 ]; then
  check SIGABRT "a['exception.message'] == 'Cart total 250, expected 999'"
fi

run "" 10        # nothing new: nothing sent again
logs
[ "$(count SIGSEGV)" = 1 ] && [ "$(count SIGABRT)" = 1 ] || fail "a crash was reported twice"

# The tombstones themselves, for the backend's tests (google_apis images allow adb root).
if [ "$API" -ge 31 ]; then
  adb root > /dev/null 2>&1; sleep 3
  mkdir -p "$OUT/tombstones"
  for f in $(adb shell ls /data/tombstones/ | tr -d '\r' | grep '\.pb$'); do adb pull "/data/tombstones/$f" "$OUT/tombstones/$f" > /dev/null; done
  for f in $(adb shell ls /data/tombstones/ | tr -d '\r' | grep -v '\.pb$'); do adb pull "/data/tombstones/$f" "$OUT/tombstones/$f" > /dev/null; done
  ls -la "$OUT/tombstones"
fi

kill $MOCK
cat "$RUNNER_TEMP/mock.log"
exit $FAILED
