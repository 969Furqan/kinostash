#!/usr/bin/env bash
# Builds libstremionode.so (the launcher) for each ABI.
#   NDK=/path/to/android-ndk  NODE_LIBS=/path/to/nodejs-mobile/bin  ./build.sh  <out jniLibs dir>
set -euo pipefail
OUT=${1:?output jniLibs dir}
TC=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin
for abi in arm64-v8a armeabi-v7a; do
  case $abi in
    arm64-v8a) target=aarch64-linux-android24 ;;
    armeabi-v7a) target=armv7a-linux-androideabi24 ;;
  esac
  mkdir -p "$OUT/$abi"
  "$TC/clang" --target=$target -O2 -fPIE -pie \
    -Wl,-z,max-page-size=16384 -Wl,-rpath,'$ORIGIN' \
    -o "$OUT/$abi/libstremionode.so" "$(dirname "$0")/launcher.c" \
    -L"$NODE_LIBS/$abi" -lnode
  "$TC/llvm-strip" "$OUT/$abi/libstremionode.so"
done
