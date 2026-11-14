#!/usr/bin/env bash
# Real libraries required by included Orca sources; not a slicing-engine build.
set -euo pipefail
export LC_ALL=C TZ=UTC SOURCE_DATE_EPOCH=1704067200
here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
ndk=${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME}
work=${NATIVE_WORK_DIR:?Set NATIVE_WORK_DIR}
abi=${ANDROID_ABI:-arm64-v8a}
jobs=${NATIVE_JOBS:-2}
component=${1:-all}
[[ $jobs =~ ^[1-9][0-9]*$ ]] || { echo 'Invalid NATIVE_JOBS' >&2; exit 1; }
grep -Eq '^Pkg.Revision[[:space:]]*=[[:space:]]*26\.1\.10909125[[:space:]]*$' "$ndk/source.properties"
case "$abi" in arm64-v8a) openssl_target=android-arm64 ;; x86_64) openssl_target=android-x86_64 ;; *) echo 'Unsupported ABI' >&2; exit 1 ;; esac
host="$ndk/toolchains/llvm/prebuilt/linux-x86_64"
mkdir -p "$work/extra-sources" "$work/extra-build/$abi" "$work/extra/$abi"
work=$(cd "$work" && pwd)
prefix="$work/extra/$abi"
build="$work/extra-build/$abi"
src="$work/extra-sources"
toolchain="$ndk/build/cmake/android.toolchain.cmake"

fetch() {
    local name=$1 version hash url dir
    read -r _ version hash url < <(awk -v n="$name" '$1 !~ /^#/ && $1 == n {print; exit}' "$here/extra-sources.lock")
    [[ -n ${url:-} ]] || { echo "No locked source for $name" >&2; exit 1; }
    dir="$src/$name"
    if [[ -e $dir ]]; then
        [[ -f "$dir/.source-lock" && $(cat "$dir/.source-lock") == "$hash" ]] || { echo "Unverified existing source: $name" >&2; exit 1; }
        return
    fi
    mkdir "$dir"
    git -C "$dir" init -q
    git -C "$dir" remote add origin "$url"
    git -C "$dir" fetch --depth=1 origin "$hash"
    git -C "$dir" checkout --detach FETCH_HEAD
    [[ $(git -C "$dir" rev-parse HEAD) == "$hash" ]]
    printf '%s\n' "$hash" > "$dir/.source-lock"
}

cmake_build() {
    local name=$1
    shift
    cmake -S "$src/$name" -B "$build/$name" -G Ninja \
        -DCMAKE_TOOLCHAIN_FILE="$toolchain" -DANDROID_ABI="$abi" \
        -DANDROID_PLATFORM=android-26 -DANDROID_STL=c++_shared -DCMAKE_BUILD_TYPE=Release \
        -DCMAKE_POSITION_INDEPENDENT_CODE=ON -DCMAKE_INSTALL_PREFIX="$prefix/$name" \
        -DCMAKE_INSTALL_LIBDIR=lib "$@"
    cmake --build "$build/$name" --parallel "$jobs"
    cmake --install "$build/$name"
}

build_nlopt() {
    fetch nlopt
    cmake_build nlopt -DBUILD_SHARED_LIBS=OFF -DNLOPT_CXX=ON -DNLOPT_TESTS=OFF \
        -DNLOPT_PYTHON=OFF -DNLOPT_OCTAVE=OFF -DNLOPT_MATLAB=OFF -DNLOPT_GUILE=OFF -DNLOPT_SWIG=OFF
    test -f "$prefix/nlopt/include/nlopt.hpp"
    test -f "$prefix/nlopt/lib/libnlopt.a"
}

build_jpeg() {
    fetch jpeg
    cmake_build jpeg -DENABLE_SHARED=OFF -DENABLE_STATIC=ON -DWITH_SIMD=OFF -DWITH_TURBOJPEG=OFF
    test -f "$prefix/jpeg/include/jpeglib.h"
    test -f "$prefix/jpeg/lib/libjpeg.a"
}

build_libpng() {
    local zlib="$work/prefix/$abi/zlib"
    if [[ ! -f "$zlib/lib/$abi/libz.a" ]]; then bash "$here/build-deps.sh" zlib; fi
    fetch libpng
    cmake_build libpng -DPNG_SHARED=OFF -DPNG_STATIC=ON -DPNG_TESTS=OFF \
        -DZLIB_INCLUDE_DIR="$zlib/include" -DZLIB_LIBRARY="$zlib/lib/$abi/libz.a"
    test -f "$prefix/libpng/include/png.h"
    test -f "$prefix/libpng/lib/libpng16.a"
}

build_openssl() {
    fetch openssl
    # OpenSSL's Configure is in-tree, so each ABI has an independent source tree.
    if [[ ! -d "$build/openssl" ]]; then cp -R "$src/openssl" "$build/openssl"; fi
    (
        cd "$build/openssl"
        export ANDROID_NDK_ROOT="$ndk" PATH="$host/bin:$PATH"
        ./Configure "$openssl_target" -D__ANDROID_API__=26 no-shared no-tests no-apps \
            --prefix="$prefix/openssl" --libdir=lib
        make -j"$jobs" build_libs
        make install_dev
    )
    test -f "$prefix/openssl/include/openssl/md5.h"
    test -f "$prefix/openssl/lib/libcrypto.a"
}

case "$component" in
    nlopt|jpeg|libpng|openssl) "build_$component" ;;
    all) build_nlopt; build_jpeg; build_libpng; build_openssl ;;
    *) echo "Unknown component: $component" >&2; exit 1 ;;
esac
echo "Built extra real dependencies ($component) for $abi under $prefix; no engine was built."
