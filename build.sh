#!/usr/bin/env bash
set -e

# ── Toolchain paths ────────────────────────────────────────────────────────
# Defaults match the Termux layout from README. On a desktop with an Android
# SDK, everything is auto-detected from ANDROID_SDK_ROOT / ANDROID_HOME /
# ~/Android/Sdk; each path can also be overridden in the environment.
ANDROID_JAR="${ANDROID_JAR:-${HOME}/.android/android.jar}"
FRAMEWORK_RES="${FRAMEWORK_RES:-${HOME}/.android/framework-res.apk}"

KEYSTORE="${KEYSTORE:-${HOME}/.android/debug.keystore}"
KEYSTORE_ALIAS="${KEYSTORE_ALIAS:-androiddebugkey}"
KEYSTORE_PASS="${KEYSTORE_PASS:-android}"
KEY_PASS="${KEY_PASS:-android}"

MIN_SDK=23
TARGET_SDK=35

SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-${HOME}/Android/Sdk}}"
if [ -d "$SDK_ROOT" ]; then
    # Newest build-tools onto PATH (aapt2, d8, apksigner).
    BT_DIR="$(ls -d "$SDK_ROOT"/build-tools/*/ 2>/dev/null | sort -V | tail -1)"
    [ -n "$BT_DIR" ] && export PATH="${BT_DIR%/}:$PATH"
    # A platform android.jar stands in for both jars when the Termux copies
    # are absent (aapt2 link accepts it as -I). Prefer the target SDK's
    # platform; otherwise the newest installed one.
    PLATFORM_JAR="$SDK_ROOT/platforms/android-$TARGET_SDK/android.jar"
    [ -f "$PLATFORM_JAR" ] || \
        PLATFORM_JAR="$(ls "$SDK_ROOT"/platforms/android-*/android.jar 2>/dev/null | sort -V | tail -1)"
    [ -f "$ANDROID_JAR" ]   || ANDROID_JAR="$PLATFORM_JAR"
    [ -f "$FRAMEWORK_RES" ] || FRAMEWORK_RES="$ANDROID_JAR"
fi

# Java compiler: ecj (Termux), or javac from PATH, JAVA_HOME, or Android Studio's bundled JBR.
JAVAC=""
if command -v ecj >/dev/null; then
    JAVAC=ecj
elif command -v javac >/dev/null; then
    JAVAC=javac
elif [ -x "${JAVA_HOME:-/nonexistent}/bin/javac" ]; then
    JAVAC="$JAVA_HOME/bin/javac"
else
    for cand in /snap/android-studio/current/jbr/bin/javac \
                /snap/android-studio/*/jbr/bin/javac \
                /opt/android-studio/jbr/bin/javac \
                "$HOME"/.local/share/JetBrains/Toolbox/apps/android-studio/jbr/bin/javac; do
        if [ -x "$cand" ]; then JAVAC="$cand"; break; fi
    done
fi
# d8 and apksigner are wrappers that exec `java`: make sure the JDK we found is on PATH.
case "$JAVAC" in
    */bin/javac)
        JDK_BIN="$(dirname "$JAVAC")"
        export JAVA_HOME="$(dirname "$JDK_BIN")"
        export PATH="$JDK_BIN:$PATH"
        ;;
esac

APK_OUT="F21BandsSwap.apk"
BUILD_DIR="build"
LIBS_DIR="libs"

# tukaani xz-java for streaming xz decompression / compression in pure Java.
XZ_VERSION="1.9"
XZ_JAR="$LIBS_DIR/xz-${XZ_VERSION}.jar"
XZ_URL="https://repo1.maven.org/maven2/org/tukaani/xz/${XZ_VERSION}/xz-${XZ_VERSION}.jar"

VERSION_FILE="VERSION"
if [ -f "$VERSION_FILE" ]; then
    VERSION_NAME="$(cat "$VERSION_FILE" | tr -d '[:space:]')"
else
    VERSION_NAME="1.0.0"
fi
VERSION_CODE=$(echo "$VERSION_NAME" | awk -F. '{ printf "%d%02d%02d", $1,$2,$3 }')

# ── Sanity checks ──────────────────────────────────────────────────────────
fail() { echo "✗ $1"; [ -n "$2" ] && echo "  $2"; exit 1; }

command -v aapt2     >/dev/null || fail "aapt2 not found"     "pkg install aapt2, or install Android SDK build-tools"
[ -n "$JAVAC" ]                 || fail "no Java compiler found" "pkg install ecj, or install a JDK / Android Studio"
command -v d8        >/dev/null || fail "d8 not found"        "pkg install d8, or install Android SDK build-tools"
command -v apksigner >/dev/null || fail "apksigner not found" "pkg install apksigner, or install Android SDK build-tools"
command -v zip       >/dev/null || fail "zip not found"       "pkg install zip"
[ -f "$ANDROID_JAR"   ] || fail "android.jar not found at: $ANDROID_JAR" "set ANDROID_JAR=..., or install an SDK platform"
[ -f "$FRAMEWORK_RES" ] || fail \
    "framework-res.apk not found at: $FRAMEWORK_RES" \
    "cp /system/framework/framework-res.apk ~/.android/ (Termux), or set FRAMEWORK_RES=..."
[ -f "$KEYSTORE"      ] || fail "Keystore not found at: $KEYSTORE"

# ── Fetch xz-java if missing ───────────────────────────────────────────────
mkdir -p "$LIBS_DIR"
# An empty or truncated jar (from an interrupted download) counts as missing.
if [ -f "$XZ_JAR" ] && ! unzip -tq "$XZ_JAR" >/dev/null 2>&1; then
    echo "Discarding broken $XZ_JAR"
    rm -f "$XZ_JAR"
fi
if [ ! -f "$XZ_JAR" ]; then
    echo "Downloading xz-java ${XZ_VERSION}..."
    XZ_TMP="$XZ_JAR.part"
    rm -f "$XZ_TMP"
    if command -v curl >/dev/null; then
        curl -L --fail -o "$XZ_TMP" "$XZ_URL" || { rm -f "$XZ_TMP"; fail "Failed to download xz-java"; }
    elif command -v wget >/dev/null; then
        wget -O "$XZ_TMP" "$XZ_URL" || { rm -f "$XZ_TMP"; fail "Failed to download xz-java"; }
    else
        fail "Neither curl nor wget available; install one to fetch xz-java"
    fi
    unzip -tq "$XZ_TMP" >/dev/null 2>&1 || { rm -f "$XZ_TMP"; fail "Downloaded xz-java is not a valid jar"; }
    mv "$XZ_TMP" "$XZ_JAR"
fi

echo "════════════════════════════════════"
echo "  Building F21BandsSwap v${VERSION_NAME} (code ${VERSION_CODE})"
echo "════════════════════════════════════"
echo "  javac: $JAVAC"
echo "  android.jar: $ANDROID_JAR"

# ── 0. Sync AndroidManifest.xml versions from VERSION ──────────────────────
MANIFEST="AndroidManifest.xml"
if [ -f "$MANIFEST" ]; then
    sed -i \
        -e "s@android:versionCode=\"[0-9][0-9]*\"@android:versionCode=\"${VERSION_CODE}\"@" \
        -e "s@android:versionName=\"[^\"]*\"@android:versionName=\"${VERSION_NAME}\"@" \
        "$MANIFEST"
fi

# ── 0b. Clean ──────────────────────────────────────────────────────────────
rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR/gen" "$BUILD_DIR/classes" "$BUILD_DIR/dex"

# ── 1. Compile resources ───────────────────────────────────────────────────
echo "[1/5] Compiling resources..."
aapt2 compile --dir res/ -o "$BUILD_DIR/resources.zip"

# ── 2. Link resources ──────────────────────────────────────────────────────
echo "[2/5] Linking resources..."
LINK_ASSETS=()
[ -d assets ] && LINK_ASSETS=(-A assets)
aapt2 link \
    -o "$BUILD_DIR/app_res.apk" \
    --manifest AndroidManifest.xml \
    -I "$FRAMEWORK_RES" \
    --java "$BUILD_DIR/gen" \
    --min-sdk-version "$MIN_SDK" \
    --target-sdk-version "$TARGET_SDK" \
    --version-code "$VERSION_CODE" \
    --version-name "$VERSION_NAME" \
    "${LINK_ASSETS[@]}" \
    "$BUILD_DIR/resources.zip"

# ── 3. Compile Java ────────────────────────────────────────────────────────
echo "[3/5] Compiling Java..."
find src/ "$BUILD_DIR/gen/" -name "*.java" > "$BUILD_DIR/sources.txt"
if [ "$JAVAC" = ecj ]; then
    ecj \
        -cp "$ANDROID_JAR:$XZ_JAR" \
        -d "$BUILD_DIR/classes" \
        @"$BUILD_DIR/sources.txt"
else
    # Java 8 bytecode: that's what d8 desugars for minSdk 23. -Xlint:-options
    # silences the "source 8 is obsolete" notice on recent JDKs.
    "$JAVAC" \
        -source 8 -target 8 -Xlint:-options \
        -bootclasspath "$ANDROID_JAR" \
        -cp "$XZ_JAR" \
        -d "$BUILD_DIR/classes" \
        @"$BUILD_DIR/sources.txt"
fi

# ── 4. Dex ─────────────────────────────────────────────────────────────────
echo "[4/5] Dexing..."
CLASS_FILES=$(find "$BUILD_DIR/classes" -name "*.class" | tr '\n' ' ')
d8 \
    --output "$BUILD_DIR/dex" \
    --lib "$ANDROID_JAR" \
    --min-api "$MIN_SDK" \
    $CLASS_FILES \
    "$XZ_JAR"

# ── 5. Pack + sign ─────────────────────────────────────────────────────────
echo "[5/5] Packaging and signing..."
cp "$BUILD_DIR/app_res.apk" "$BUILD_DIR/app_unsigned.apk"
(cd "$BUILD_DIR/dex" && zip -j "../app_unsigned.apk" classes.dex)

apksigner sign \
    --ks "$KEYSTORE" \
    --ks-key-alias "$KEYSTORE_ALIAS" \
    --ks-pass "pass:$KEYSTORE_PASS" \
    --key-pass "pass:$KEY_PASS" \
    --out "$APK_OUT" \
    "$BUILD_DIR/app_unsigned.apk"

rm -f "${APK_OUT}.idsig"

echo ""
echo "════════════════════════════════════"
echo "  ✓  ${APK_OUT}"
echo "════════════════════════════════════"
SIZE=$(stat -c%s "$APK_OUT" 2>/dev/null || stat -f%z "$APK_OUT")
echo "Size: $((SIZE/1024)) KB"
echo "Install via ADB:   adb install -r ${APK_OUT}"
echo "Install locally:   cp ${APK_OUT} /sdcard/"
