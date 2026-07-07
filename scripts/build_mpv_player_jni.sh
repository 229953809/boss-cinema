#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC_DIR="$ROOT_DIR/app/src/main/cpp/mpvplayer"
MIN_SDK="${MIN_SDK:-23}"

find_ndk() {
    if [[ -n "${ANDROID_NDK_HOME:-}" && -d "${ANDROID_NDK_HOME:-}" ]]; then
        printf '%s\n' "$ANDROID_NDK_HOME"
        return
    fi
    if [[ -n "${ANDROID_NDK_ROOT:-}" && -d "${ANDROID_NDK_ROOT:-}" ]]; then
        printf '%s\n' "$ANDROID_NDK_ROOT"
        return
    fi
    local sdk_dir="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
    if [[ -z "$sdk_dir" && -f "$ROOT_DIR/local.properties" ]]; then
        sdk_dir="$(sed -n 's/^sdk.dir=//p' "$ROOT_DIR/local.properties" | tail -1)"
    fi
    for base in \
        "$sdk_dir/ndk" \
        "/Users/macbookpro/Downloads/bizhi/android-sdk/ndk" \
        "$HOME/Library/Android/sdk/ndk"; do
        [[ -d "$base" ]] || continue
        find "$base" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -1
        return
    done
}

NDK_HOME="$(find_ndk)"
if [[ -z "${NDK_HOME:-}" || ! -d "$NDK_HOME" ]]; then
    echo "Android NDK not found. Set ANDROID_NDK_HOME." >&2
    exit 1
fi

TOOLCHAIN="$(find "$NDK_HOME/toolchains/llvm/prebuilt" -mindepth 1 -maxdepth 1 -type d | head -1)"
if [[ -z "${TOOLCHAIN:-}" || ! -d "$TOOLCHAIN" ]]; then
    echo "LLVM toolchain not found in $NDK_HOME." >&2
    exit 1
fi

SOURCES=(
    "$SRC_DIR/main.cpp"
    "$SRC_DIR/render.cpp"
    "$SRC_DIR/log.cpp"
    "$SRC_DIR/jni_utils.cpp"
    "$SRC_DIR/property.cpp"
    "$SRC_DIR/event.cpp"
    "$SRC_DIR/thumbnail.cpp"
)

CXXFLAGS=(
    -std=c++11
    -fPIC
    -DANDROID
    -Werror
    -I"$SRC_DIR/include"
)

build_abi() {
    local abi="$1"
    local target="$2"
    local asset_dir="$3"
    local cxx="$TOOLCHAIN/bin/${target}${MIN_SDK}-clang++"
    local strip="$TOOLCHAIN/bin/llvm-strip"
    if [[ ! -x "$cxx" ]]; then
        echo "Compiler not found: $cxx" >&2
        exit 1
    fi
    if [[ ! -d "$asset_dir" ]]; then
        echo "MPV asset dir missing for $abi: $asset_dir" >&2
        exit 1
    fi
    "$cxx" \
        "${CXXFLAGS[@]}" \
        "${SOURCES[@]}" \
        -L"$asset_dir" \
        -Wl,-soname,libplayer.so \
        -Wl,--no-undefined \
        -l:libmwscale.so \
        -l:libmvcodec.so \
        -l:libmpv.so \
        -llog \
        -latomic \
        -lm \
        -ldl \
        -shared \
        -o "$asset_dir/libplayer.so"
    "$strip" --strip-unneeded "$asset_dir/libplayer.so"
    echo "built $asset_dir/libplayer.so"
}

build_abi "arm64-v8a" "aarch64-linux-android" "$ROOT_DIR/app/src/arm64_v8a/assets/mpv-libs/arm64-v8a"
build_abi "armeabi-v7a" "armv7a-linux-androideabi" "$ROOT_DIR/app/src/armeabi_v7a/assets/mpv-libs/armeabi-v7a"
