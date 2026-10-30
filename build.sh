#!/usr/bin/env bash
# Builds ps5UI and visionUI (one app, io.freemytv.cinema) without Gradle: aapt2 -> javac -> d8 -> zipalign -> apksigner.
# Needs a JDK 17+ and the Android SDK (build-tools 34, platform android-34). Set ANDROID_HOME, or have the SDK at its
# usual place. Makes out/ps5UI.apk, signed with a key of your own (ps5ui.keystore, made on the first build: keep it,
# updates must be signed with the same one).
set -euo pipefail
cd "$(dirname "$0")"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
BT="$SDK/build-tools/34.0.0"
AJ="$SDK/platforms/android-34/android.jar"
[ -f "$AJ" ] || { echo "Android SDK not found: set ANDROID_HOME (needs build-tools;34.0.0 and platforms;android-34)"; exit 1; }
EXE=""; case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) EXE=".bat"; export MSYS_NO_PATHCONV=1;; esac
OUT=out
KS=ps5ui.keystore
rm -rf "$OUT" && mkdir -p "$OUT/gen" "$OUT/classes"
"$BT/aapt2" compile --dir res -o "$OUT/res.zip"
"$BT/aapt2" link -o "$OUT/base.apk" -I "$AJ" --manifest AndroidManifest.xml --java "$OUT/gen" \
  --min-sdk-version 26 --target-sdk-version 34 --version-code 2 --version-name 1.1 "$OUT/res.zip"
javac -nowarn -source 17 -target 17 -classpath "$AJ" -encoding UTF-8 -d "$OUT/classes" \
  $(find src "$OUT/gen" -name '*.java')
jar -c -f "$OUT/app-classes.jar" -C "$OUT/classes" .
"$BT/d8$EXE" --release --min-api 26 --lib "$AJ" --output "$OUT" "$OUT/app-classes.jar"
jar -u -M -f "$OUT/base.apk" -C "$OUT" classes.dex
"$BT/zipalign" -f -p 4 "$OUT/base.apk" "$OUT/aligned.apk"
[ -f "$KS" ] || keytool -genkeypair -keystore "$KS" -alias ps5ui -keyalg RSA -keysize 2048 -validity 20000 \
  -storepass ps5ui-local -keypass ps5ui-local -dname "CN=ps5UI" >/dev/null 2>&1
"$BT/apksigner$EXE" sign --ks "$KS" --ks-pass pass:ps5ui-local --key-pass pass:ps5ui-local \
  --out "$OUT/ps5UI.apk" "$OUT/aligned.apk"
echo "Built $OUT/ps5UI.apk. Install: adb install -r $OUT/ps5UI.apk"
