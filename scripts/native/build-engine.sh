#!/usr/bin/env bash
# Build the real pinned Orca-derived JNI engine into a disposable directory.
# Never writes app/src/main/jniLibs or the checked-out submodule.
set -euo pipefail
export LC_ALL=C TZ=UTC SOURCE_DATE_EPOCH=1704067200
repo=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
ndk=${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME to NDK 26.1.10909125}
work=${NATIVE_ENGINE_DIR:?Set NATIVE_ENGINE_DIR to a fresh disposable directory}
prefix=${NATIVE_DEPS_PREFIX:?Set NATIVE_DEPS_PREFIX to the built dependency bundle}
extras=${NATIVE_EXTRA_DEPS_PREFIX:?Set NATIVE_EXTRA_DEPS_PREFIX to the real extra dependency bundle}
abi=${ANDROID_ABI:-arm64-v8a}
case "$abi" in arm64-v8a|x86_64) ;; *) echo 'Unsupported ABI' >&2; exit 1 ;; esac
grep -Eq '^Pkg.Revision[[:space:]]*=[[:space:]]*26\.1\.10909125[[:space:]]*$' "$ndk/source.properties"
test -d "$prefix/boost/include/boost"
test -f "$prefix/zlib/lib/$abi/libz.a"
test -f "$prefix/occt/lib/$abi/libTKernel.a"
test -f "$extras/nlopt/include/nlopt.hpp"
test -f "$extras/jpeg/lib/libjpeg.a"
test -f "$extras/libpng/include/png.h"
test -f "$extras/openssl/lib/libcrypto.a"
test -f "$extras/libnoise/lib/liblibnoise_static.a"
gitlink=$(git -C "$repo" ls-tree HEAD app/src/main/cpp/orcaslicer | awk '{print $3}')
[[ $gitlink =~ ^[0-9a-f]{40}$ ]] || { echo 'Invalid pinned engine gitlink' >&2; exit 1; }
repo_head=$(git -C "$repo" rev-parse HEAD)
if [[ ${NATIVE_ENGINE_PREPARED:-0} != 1 ]]; then
    [[ ! -e $work ]] || { echo 'Engine build directory must be fresh' >&2; exit 1; }
    mkdir -p "$work/cpp" "$work/output" "$work/extern"
    work=$(cd "$work" && pwd)
    prefix=$(cd "$prefix" && pwd)
    extras=$(cd "$extras" && pwd)
    tar -C "$repo/app/src/main/cpp" --exclude='./orcaslicer' -cf - . | tar -C "$work/cpp" -xf -
    git init -q "$work/cpp/orcaslicer"
    git -C "$work/cpp/orcaslicer" remote add origin https://github.com/taylormadearmy/OrcaSlicer.git
    git -C "$work/cpp/orcaslicer" fetch --depth=1 --filter=blob:none origin "$gitlink"
    git -C "$work/cpp/orcaslicer" checkout --detach FETCH_HEAD
    [[ $(git -C "$work/cpp/orcaslicer" rev-parse HEAD) == "$gitlink" ]]
    for dependency in "$prefix"/*; do
        [[ -e $dependency ]] || continue
        [[ ${dependency##*/} == tbb_serial ]] && continue
        ln -s "$dependency" "$work/extern/${dependency##*/}"
    done
    cp -R "$work/cpp/extern/tbb_serial" "$work/extern/tbb_serial"
    printf '%s %s\n' "$repo_head" "$abi" > "$work/prepared-for"
    [[ ${NATIVE_ENGINE_PREPARE_ONLY:-0} != 1 ]] || exit 0
else
    work=$(cd "$work" && pwd)
    prefix=$(cd "$prefix" && pwd)
    extras=$(cd "$extras" && pwd)
    [[ $(cat "$work/prepared-for") == "$repo_head $abi" ]]
    [[ $(git -C "$work/cpp/orcaslicer" rev-parse HEAD) == "$gitlink" ]]
fi
launcher=()
if command -v ccache >/dev/null 2>&1; then
    export CCACHE_BASEDIR="$work/cpp" CCACHE_COMPILERCHECK=content
    launcher=(-DCMAKE_C_COMPILER_LAUNCHER=ccache -DCMAKE_CXX_COMPILER_LAUNCHER=ccache)
fi

cmake -S "$work/cpp" -B "$work/build" -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="$ndk/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$abi" -DANDROID_PLATFORM=android-26 -DANDROID_STL=c++_static \
    -DSLICER_BACKEND=orca -DEXTERN_DIR="$work/extern" -DNATIVE_EXTRA_DEPS_ROOT="$extras" -DBOOST_SUFFIX= \
    -DCMAKE_BUILD_TYPE=Release -DCMAKE_CXX_FLAGS_RELEASE='-O2 -DNDEBUG' \
    -DCMAKE_SHARED_LINKER_FLAGS='-Wl,-z,max-page-size=16384,-z,common-page-size=16384' "${launcher[@]}"
cmake --build "$work/build" --parallel 1

tools="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin"
candidate="$work/build/libprusaslicer-jni.so"
test -f "$candidate"
"$tools/llvm-strip" --strip-unneeded "$candidate" -o "$work/output/libprusaslicer-jni.so"
output="$work/output/libprusaslicer-jni.so"
python3 "$repo/scripts/native/verify_engine_binary.py" --abi "$abi" --library "$output"
"$tools/llvm-readelf" -h "$output" | tee "$work/output/elf-header.txt"
"$tools/llvm-readelf" -lW "$output" | awk '$1 == "LOAD" {print $NF}' | sort -u > "$work/output/load-alignments.txt"
[[ $(cat "$work/output/load-alignments.txt") == 0x4000 ]]
"$tools/llvm-readelf" --dyn-syms --wide "$output" | awk '$NF ~ /^Java_com_u1_slicer_NativeLibrary_/ {print $NF}' | sort -u > "$work/output/jni-symbols.txt"
"$tools/llvm-readelf" --dyn-syms --wide "$repo/app/src/main/jniLibs/arm64-v8a/libprusaslicer-jni.so" | awk '$NF ~ /^Java_com_u1_slicer_NativeLibrary_/ {print $NF}' | sort -u > "$work/output/reference-jni-symbols.txt"
test "$(wc -l < "$work/output/reference-jni-symbols.txt")" -eq 60
diff -u "$work/output/reference-jni-symbols.txt" "$work/output/jni-symbols.txt"
"$tools/llvm-readelf" -p .comment "$output" | grep -F 'clang version 17.0.2'
echo "Engine size: $(stat -c %s "$output") bytes (diagnostic only)"
grep -aqF 'orca-native-presets-v2' "$output"
printf '%s\n' 'orca-native-presets-v2' > "$work/output/full-preset-schema.txt"
printf '%s\n' 'c++_static' > "$work/output/cxx-runtime.txt"
sha256sum "$output" "$work/output/cxx-runtime.txt" "$work/output/full-preset-schema.txt" > "$work/output/sha256.txt"
echo "Experimental $abi engine built from $gitlink; hardware slicing has not been verified."
