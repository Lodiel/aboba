#!/usr/bin/env bash
# Builds the APK WITHOUT the Android SDK / Gradle, using only:
#   - android.jar (platform stubs, e.g. from github.com/Sable/android-platforms)
#   - aapt2 (e.g. extracted from apktool.jar: prebuilt/linux/aapt2_64)
#   - dalvik-dx (com.jakewharton.android.repackaged:dalvik-dx from Maven Central)
#   - apksig (com.android.tools.build:apksig from Maven Central) + JDK 17+
# Usage: tools/build-apk-nosdk.sh <android.jar> <aapt2> <dalvik-dx.jar> <apksig.jar> <out.apk>
set -euo pipefail
ANDROID_JAR=$1; AAPT2=$2; DX_JAR=$3; APKSIG_JAR=$4; OUT=$5
ROOT=$(cd "$(dirname "$0")/.." && pwd)
APP=$ROOT/app/src/main
BUILD=${BUILD_DIR:-$ROOT/app/build/nosdk}
rm -rf "$BUILD"; mkdir -p "$BUILD/gen" "$BUILD/classes" "$BUILD/dex"

echo "[1/6] aapt2 compile"
"$AAPT2" compile --dir "$APP/res" -o "$BUILD/res.zip"

echo "[2/6] aapt2 link"
# AGP 8 forbids a package attribute in the source manifest (it uses the Gradle namespace),
# while aapt2 requires one: inject it into a temporary copy.
sed 's|<manifest xmlns:android="http://schemas.android.com/apk/res/android"|<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.aboba.starlinkreconnect"|' \
  "$APP/AndroidManifest.xml" > "$BUILD/AndroidManifest.xml"
grep -q 'package="com.aboba.starlinkreconnect"' "$BUILD/AndroidManifest.xml"
"$AAPT2" link -o "$BUILD/unsigned-nodex.apk" -I "$ANDROID_JAR" \
  --manifest "$BUILD/AndroidManifest.xml" \
  --min-sdk-version 26 --target-sdk-version 28 \
  --version-code "${VERSION_CODE:-1}" --version-name "${VERSION_NAME:-1.0}" \
  --java "$BUILD/gen" --auto-add-overlay "$BUILD/res.zip"

echo "[3/6] javac (Java 8 bytecode for dx)"
javac -nowarn -Xlint:-options --release 8 -encoding UTF-8 \
  -cp "$ANDROID_JAR" -d "$BUILD/classes" \
  $(find "$BUILD/gen" "$APP/java" -name '*.java')

echo "[4/6] dx -> classes.dex"
java -cp "$DX_JAR" com.android.dx.command.Main --dex --min-sdk-version=26 \
  --output="$BUILD/dex/classes.dex" "$BUILD/classes"

echo "[5/6] package"
cp "$BUILD/unsigned-nodex.apk" "$BUILD/unsigned.apk"
(cd "$BUILD/dex" && zip -q -X "$BUILD/unsigned.apk" classes.dex)

echo "[6/6] sign (APK Signature Scheme v2)"
KS=$ROOT/app/keystore/starlink-reconnect.jks
[ -f "$KS" ] || base64 -d "$ROOT/app/keystore/starlink-reconnect.jks.base64" > "$KS"
mkdir -p "$BUILD/signer"
javac -nowarn -cp "$APKSIG_JAR" -d "$BUILD/signer" "$ROOT/tools/ApkSign.java"
java --add-exports java.base/sun.security.x509=ALL-UNNAMED --add-exports java.base/sun.security.pkcs=ALL-UNNAMED \
  -cp "$APKSIG_JAR:$BUILD/signer" ApkSign "$BUILD/unsigned.apk" "$OUT" "$KS" starlink starlink starlink
echo "done: $OUT"
