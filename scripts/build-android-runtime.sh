#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd "$(dirname "$0")/.." && pwd)"
export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-${ANDROID_SDK_ROOT:?ANDROID_SDK_ROOT is required}/ndk/27.2.12479018}"
revision="$(git -C "$project_root/vendor/pocketjs" rev-parse HEAD)"

cd "$project_root"
PODJS_POCKETJS_REVISION="$revision" cargo ndk \
  --platform 30 \
  -t armeabi-v7a \
  -t arm64-v8a \
  build --release -p podjs-runtime

mkdir -p platforms/android/runtime/prebuilt/armeabi-v7a platforms/android/runtime/prebuilt/arm64-v8a
cp target/armv7-linux-androideabi/release/libpodjs_runtime.a platforms/android/runtime/prebuilt/armeabi-v7a/
cp target/aarch64-linux-android/release/libpodjs_runtime.a platforms/android/runtime/prebuilt/arm64-v8a/
printf 'PodJS Android runtime: %s\n' "$revision"
