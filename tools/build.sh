#!/usr/bin/env bash
# Cross-compile mmc_probe for arm64 Android using the NDK already installed
# under $ANDROID_HOME/ndk. Picks the highest-versioned NDK, targets API 30+.
set -e

DIR="$(cd "$(dirname "$0")" && pwd)"

if [ -z "$ANDROID_HOME" ]; then
    echo "ANDROID_HOME is not set; please point it at your Android SDK install." >&2
    exit 1
fi

NDK_ROOT="$ANDROID_HOME/ndk"
if [ ! -d "$NDK_ROOT" ]; then
    echo "No NDK found at $NDK_ROOT. Install one via 'sdkmanager --install \"ndk;<ver>\"'." >&2
    exit 1
fi

# pick highest installed NDK
NDK_VER=$(ls -1 "$NDK_ROOT" | sort -V | tail -1)
CC="$NDK_ROOT/$NDK_VER/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android30-clang"

if [ ! -x "$CC" ]; then
    echo "Expected $CC but it is not executable." >&2
    exit 1
fi

echo "Building with $CC"
"$CC" -O2 -o "$DIR/mmc_probe" "$DIR/mmc_probe.c"
STRIP="$NDK_ROOT/$NDK_VER/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip"
[ -x "$STRIP" ] && "$STRIP" "$DIR/mmc_probe"
file "$DIR/mmc_probe"
echo "Built: $DIR/mmc_probe"
