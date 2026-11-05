#!/bin/bash
# Builds what ASR Android needs for SROS2 (DDS Security) on arm64-v8a:
#   OpenSSL (static), linked into Cyclone DDS's auth, access control and crypto plugins.
#
# Downloads OpenSSL and Cyclone DDS source from their official GitHub repositories,
# and builds them in ~/asr_sros2_build. It doesn't change the app, this repository or Pixi.
#
# Usage: bash scripts/build_sros2_android.sh
set -euo pipefail

CYCLONE_TAG="11.0.1"   # Must match the libddsc.so bundled with the app.
OPENSSL_SERIES="openssl-3.5.*"
ANDROID_API=28
WORK="$HOME/asr_sros2_build"
NDK="${ANDROID_NDK_ROOT:-$HOME/Library/Android/sdk/ndk/28.2.13676358}"
HOST_IDLC="$HOME/ros2_lyrical/.pixi/envs/default/bin/idlc"
LOG="$WORK/build.log"

for tool in git cmake make perl; do
    command -v "$tool" >/dev/null || { echo "Missing tool: $tool"; exit 1; }
done
[ -d "$NDK" ] || { echo "Android NDK not found: $NDK"; exit 1; }
[ -x "$HOST_IDLC" ] || { echo "idlc not found: $HOST_IDLC"; exit 1; }

mkdir -p "$WORK"
cd "$WORK"
: > "$LOG"
echo "Build log: $LOG"

# The host's idlc is only read, through this one link, so nothing else from Pixi is on PATH.
mkdir -p "$WORK/hostbin"
ln -sf "$HOST_IDLC" "$WORK/hostbin/idlc"

echo "[1/4] Downloading OpenSSL"
OPENSSL_TAG="$(git ls-remote --tags --refs https://github.com/openssl/openssl.git "$OPENSSL_SERIES" \
    | sed 's#.*/##' | sort -V | tail -1)"
[ -n "$OPENSSL_TAG" ] || { echo "Could not find an OpenSSL 3.5 release"; exit 1; }
echo "      $OPENSSL_TAG"
[ -d openssl ] || git clone -q --depth 1 --branch "$OPENSSL_TAG" https://github.com/openssl/openssl.git openssl

echo "[2/4] Building OpenSSL (about 5 minutes)"
(
    cd openssl
    export ANDROID_NDK_ROOT="$NDK"
    export PATH="$NDK/toolchains/llvm/prebuilt/darwin-x86_64/bin:$PATH"
    ./Configure android-arm64 no-shared no-tests no-apps no-docs "-D__ANDROID_API__=$ANDROID_API" -fPIC \
        --prefix="$WORK/openssl-install" --libdir=lib
    make -j8 build_libs
    make install_dev
) >> "$LOG" 2>&1

echo "[3/4] Downloading Cyclone DDS $CYCLONE_TAG"
[ -d cyclonedds ] || git clone -q --depth 1 --branch "$CYCLONE_TAG" https://github.com/eclipse-cyclonedds/cyclonedds.git cyclonedds

echo "[4/4] Building the security plugins (about 3 minutes)"
(
    export PATH="$WORK/hostbin:$PATH"
    rm -rf cyclonedds-build
    cmake -S cyclonedds -B cyclonedds-build \
        -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
        -DANDROID_ABI=arm64-v8a \
        -DANDROID_PLATFORM="android-$ANDROID_API" \
        -DCMAKE_BUILD_TYPE=Release \
        -DCMAKE_INSTALL_PREFIX="$WORK/cyclonedds-install" \
        -DCMAKE_SHARED_LINKER_FLAGS="-Wl,-z,max-page-size=16384" \
        -DBUILD_SHARED_LIBS=ON \
        -DBUILD_IDLC=OFF \
        -DBUILD_DDSPERF=OFF \
        -DBUILD_EXAMPLES=OFF \
        -DBUILD_TESTING=OFF \
        -DENABLE_SECURITY=ON \
        -DENABLE_SSL=ON \
        -DENABLE_ICEORYX=OFF \
        -DENABLE_ICEORYX2=OFF \
        -DOPENSSL_ROOT_DIR="$WORK/openssl-install" \
        -DOPENSSL_USE_STATIC_LIBS=TRUE \
        -DCMAKE_FIND_ROOT_PATH="$WORK/openssl-install" \
        -DCMAKE_FIND_ROOT_PATH_MODE_PACKAGE=BOTH \
        -DCMAKE_FIND_ROOT_PATH_MODE_INCLUDE=BOTH \
        -DCMAKE_FIND_ROOT_PATH_MODE_LIBRARY=BOTH
    cmake --build cyclonedds-build -j8
    cmake --install cyclonedds-build
) >> "$LOG" 2>&1

echo
echo "Built libraries:"
find "$WORK/cyclonedds-install/lib" -maxdepth 1 \( -name 'libdds_security_*.so' -o -name 'libddsc.so' \) -exec ls -la {} \;
echo
echo "BUILD_OK"
