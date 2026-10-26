#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"
: "${ANDROID_HOME:?ANDROID_HOME must point to the Android SDK}"
BUILD_TOOLS_VERSION="${BUILD_TOOLS_VERSION:-35.0.0}"
PLATFORM_VERSION="${PLATFORM_VERSION:-35}"
BT="$ANDROID_HOME/build-tools/$BUILD_TOOLS_VERSION"
ANDROID_JAR="$ANDROID_HOME/platforms/android-$PLATFORM_VERSION/android.jar"
WORK="$ROOT/.build"
LIB="$WORK/lib"
CLASSES="$WORK/classes"
DEX="$WORK/dex"
mkdir -p "$LIB" "$CLASSES" "$DEX" "$ROOT/dist"
for tool in aapt d8 zipalign apksigner; do
  test -x "$BT/$tool" || { echo "Missing SDK tool: $BT/$tool" >&2; exit 2; }
done
test -f "$ANDROID_JAR" || { echo "Missing platform: $ANDROID_JAR" >&2; exit 2; }

AAR_URL="https://jitpack.io/com/github/mik3y/usb-serial-for-android/3.8.1/usb-serial-for-android-3.8.1.aar"
curl --fail --location --retry 3 "$AAR_URL" -o "$LIB/usb-serial.aar"
unzip -p "$LIB/usb-serial.aar" classes.jar > "$LIB/usb-serial.jar"

"$BT/aapt" package -f -M app/src/main/AndroidManifest.xml -I "$ANDROID_JAR" \
  --min-sdk-version 29 --target-sdk-version 35 -F "$WORK/base.apk"

find app/src/main/java -name '*.java' -print0 | xargs -0 javac \
  -source 8 -target 8 -encoding UTF-8 \
  -classpath "$ANDROID_JAR:$LIB/usb-serial.jar" -d "$CLASSES"

jar cf "$WORK/app-classes.jar" -C "$CLASSES" .
"$BT/d8" --lib "$ANDROID_JAR" --output "$DEX" "$WORK/app-classes.jar" "$LIB/usb-serial.jar"
cp "$WORK/base.apk" "$WORK/unsigned.apk"
(cd "$DEX" && zip -q "$WORK/unsigned.apk" classes.dex)
"$BT/zipalign" -f 4 "$WORK/unsigned.apk" "$WORK/aligned.apk"
KEYSTORE="$WORK/debug.keystore"
if [ ! -f "$KEYSTORE" ]; then
  keytool -genkeypair -keystore "$KEYSTORE" -storepass android -keypass android \
    -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1
fi
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-pass pass:android --key-pass pass:android \
  --out dist/Serial-to-Termux-debug.apk "$WORK/aligned.apk"
"$BT/apksigner" verify --verbose dist/Serial-to-Termux-debug.apk
echo "APK: $ROOT/dist/Serial-to-Termux-debug.apk"
