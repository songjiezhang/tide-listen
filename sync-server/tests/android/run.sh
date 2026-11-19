#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/../../.." && pwd)"
cd "$root"
testdir="$(mktemp -d)"
export SYNC_LOCAL_TEST=1 SYNC_BIND=127.0.0.1 SYNC_DATA="$testdir/server" SYNC_KEY_FILE="$testdir/secrets/key"
printf '%s\n' 'Android-Local-QA-2026' | node sync-server/server.mjs init owner
node sync-server/server.mjs &
service=$!
trap 'kill "$service"' EXIT
for attempt in {1..30}; do
  if curl -fsS http://127.0.0.1:8472/health >/dev/null; then break; fi
  sleep 0.3
done
node sync-server/tests/android/seed.mjs > "$testdir/codes"
mapfile -t pair < "$testdir/codes"
keytool -genkeypair -keystore "$testdir/debug.keystore" -storepass android -keypass android \
  -alias androiddebugkey -dname 'CN=Test,O=TideListen,C=CN' -keyalg RSA -keysize 2048 -validity 30 >/dev/null 2>&1
tool="$ANDROID_HOME/build-tools/35.0.0"
sdk="$ANDROID_HOME/platforms/android-35/android.jar"
classes="$root/packages/android/build/intermediates/javac/phoneRelease/compilePhoneReleaseJavaWithJavac/classes"
mkdir "$testdir/classes" "$testdir/dex"
javac -source 8 -target 8 -cp "$sdk:$classes" -d "$testdir/classes" sync-server/tests/android/SyncTest.java
"$tool/d8" --min-api 23 --lib "$sdk" --output "$testdir/dex" "$testdir/classes/io/github/anylisten/midnight/SyncTest.class"
adb reverse tcp:8472 tcp:8472
for role in phone car; do
  package=io.github.anylisten.midnight
  flavor=phone
  code="${pair[0]}"
  if [[ "$role" == car ]]; then
    package="$package.car"
    flavor=dashboard
    code="${pair[1]}"
  fi
  apk="$root/packages/android/build/outputs/apk/$flavor/release/TideListen-$flavor-release-unsigned.apk"
  cat > "$testdir/AndroidManifest.xml" <<XML
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="io.github.tidelisten.syncqa.$role">
  <uses-sdk android:minSdkVersion="23" android:targetSdkVersion="35"/>
  <application/>
  <instrumentation android:name="io.github.anylisten.midnight.SyncTest" android:targetPackage="$package"/>
</manifest>
XML
  "$tool/aapt" package -f -M "$testdir/AndroidManifest.xml" -I "$sdk" -F "$testdir/test-unsigned.apk"
  (cd "$testdir/dex" && "$tool/aapt" add "$testdir/test-unsigned.apk" classes.dex >/dev/null)
  "$tool/apksigner" sign --ks "$testdir/debug.keystore" --ks-key-alias androiddebugkey --ks-pass pass:android --out "$testdir/player.apk" "$apk"
  "$tool/apksigner" sign --ks "$testdir/debug.keystore" --ks-key-alias androiddebugkey --ks-pass pass:android --out "$testdir/test.apk" "$testdir/test-unsigned.apk"
  adb install --no-incremental -r "$testdir/player.apk"
  adb install --no-incremental -r "$testdir/test.apk"
  adb shell am instrument -w -e role "$role" -e code "$code" \
    "io.github.tidelisten.syncqa.$role/io.github.anylisten.midnight.SyncTest" | tee "$testdir/$role.log"
  grep -q "PASS $package " "$testdir/$role.log"
done
