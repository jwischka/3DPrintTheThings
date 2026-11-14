#!/usr/bin/env bash
# Build pinned Android dependencies in an isolated directory, never in jniLibs.
set -euo pipefail
export LC_ALL=C TZ=UTC SOURCE_DATE_EPOCH=1704067200
here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
ndk=${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME to NDK 26.1.10909125}
work=${NATIVE_WORK_DIR:?Set NATIVE_WORK_DIR to an isolated build directory}
abi=${ANDROID_ABI:-arm64-v8a}
jobs=${NATIVE_JOBS:-2}
component=${1:-all}
[[ $jobs =~ ^[1-9][0-9]*$ ]] || { echo 'Invalid NATIVE_JOBS' >&2; exit 1; }
grep -Eq '^Pkg.Revision[[:space:]]*=[[:space:]]*26\.1\.10909125[[:space:]]*$' "$ndk/source.properties"
case "$abi" in
    arm64-v8a) target=aarch64-linux-android ;;
    x86_64) target=x86_64-linux-android ;;
    *) echo "Unsupported ABI: $abi" >&2; exit 1 ;;
esac
host="$ndk/toolchains/llvm/prebuilt/linux-x86_64"
toolchain="$ndk/build/cmake/android.toolchain.cmake"
prefix="$work/prefix/$abi"
mkdir -p "$work/sources" "$work/downloads" "$work/build/$abi" "$prefix"
work=$(cd "$work" && pwd)
prefix="$work/prefix/$abi"
host=$(cd "$host" && pwd)
toolchain=$(cd "$(dirname "$toolchain")" && pwd)/android.toolchain.cmake
src="$work/sources"
build="$work/build/$abi"

fetch() {
    local name=$1 kind version hash url dir marker archive
    read -r kind _ version hash url < <(awk -v n="$name" '$1 !~ /^#/ && $2 == n {print; exit}' "$here/sources.lock")
    [[ -n ${url:-} ]] || { echo "No source lock for $name" >&2; exit 1; }
    dir="$src/$name"; marker="$dir/.source-lock"
    if [[ -e $dir ]]; then
        [[ -f $marker && $(cat "$marker") == "$hash" ]] || { echo "Unverified existing source: $dir" >&2; exit 1; }
        return
    fi
    mkdir "$dir"
    if [[ $kind == archive ]]; then
        archive="$work/downloads/${name}-${version}.${url##*.}"
        curl --fail --location --retry 3 --output "$archive" "$url"
        echo "$hash  $archive" | sha256sum --check --status || { echo "Checksum failed: $name" >&2; exit 1; }
        tar -xf "$archive" --strip-components=1 -C "$dir"
    elif [[ $kind == git ]]; then
        git -C "$dir" init -q
        git -C "$dir" remote add origin "$url"
        git -C "$dir" fetch --depth=1 origin "$hash"
        git -C "$dir" checkout --detach FETCH_HEAD
        [[ $(git -C "$dir" rev-parse HEAD) == "$hash" ]]
    else
        echo "Invalid source type for $name" >&2; exit 1
    fi
    printf '%s\n' "$hash" > "$marker"
}

configure() {
    local name=$1 source=$2
    shift 2
    cmake -S "$source" -B "$build/$name" -G Ninja \
        -DCMAKE_TOOLCHAIN_FILE="$toolchain" -DANDROID_ABI="$abi" \
        -DANDROID_PLATFORM=android-26 -DANDROID_STL=c++_shared \
        -DCMAKE_BUILD_TYPE=Release -DCMAKE_POSITION_INDEPENDENT_CODE=ON "$@"
    cmake --build "$build/$name" --parallel "$jobs"
}

copy_library() {
    local name=$1 filename=$2 file
    file=$(find "$build/$name" -type f -name "$filename" -print -quit)
    [[ -n $file ]] || { echo "Missing $name library: $filename" >&2; exit 1; }
    mkdir -p "$prefix/$name/lib/$abi"
    cp "$file" "$prefix/$name/lib/$abi/"
}

build_zlib() {
    fetch zlib
    configure zlib "$src/zlib" -DBUILD_SHARED_LIBS=OFF -DZLIB_BUILD_TESTING=OFF
    mkdir -p "$prefix/zlib/include"
    cp "$src/zlib/zlib.h" "$build/zlib/zconf.h" "$prefix/zlib/include/"
    copy_library zlib libz.a
}

build_expat() {
    fetch expat
    configure expat "$src/expat/expat" -DEXPAT_BUILD_TESTS=OFF -DEXPAT_BUILD_EXAMPLES=OFF \
        -DEXPAT_BUILD_TOOLS=OFF -DEXPAT_BUILD_FUZZERS=OFF -DEXPAT_SHARED_LIBS=OFF
    mkdir -p "$prefix/expat/include"
    cp "$src/expat/expat/lib/expat.h" "$src/expat/expat/lib/expat_external.h" "$prefix/expat/include/"
    copy_library expat libexpat.a
}

build_tbb() {
    fetch tbb
    configure tbb "$src/tbb" -DTBB_TEST=OFF -DTBB_EXAMPLES=OFF -DBUILD_SHARED_LIBS=OFF
    mkdir -p "$prefix/tbb/include"
    cp -R "$src/tbb/include/tbb" "$src/tbb/include/oneapi" "$prefix/tbb/include/"
    copy_library tbb libtbb.a
    copy_library tbb libtbbmalloc.a
}

build_clipper2() {
    fetch clipper2
    configure clipper2 "$src/clipper2/CPP" -DCLIPPER2_EXAMPLES=OFF -DCLIPPER2_TESTS=OFF \
        -DCLIPPER2_UTILS=OFF -DBUILD_SHARED_LIBS=OFF
    mkdir -p "$prefix/clipper2/include"
    cp -R "$src/clipper2/CPP/Clipper2Lib/include/." "$prefix/clipper2/include/"
    copy_library clipper2 libClipper2.a
}

build_headers() {
    local name
    for name in eigen cgal cereal nlohmann; do fetch "$name"; mkdir -p "$prefix/$name/include"; done
    cp -R "$src/eigen/Eigen" "$src/eigen/unsupported" "$prefix/eigen/include/"
    cp -R "$src/cgal/include/CGAL" "$prefix/cgal/include/"
    cp -R "$src/cereal/include/cereal" "$prefix/cereal/include/"
    cp -R "$src/nlohmann/include/nlohmann" "$prefix/nlohmann/include/"
}

build_gmp_mpfr() {
    fetch gmp; fetch mpfr
    mkdir -p "$build/gmp" "$build/mpfr"
    (
        cd "$build/gmp"
        CC="$host/bin/${target}26-clang" CXX="$host/bin/${target}26-clang++" \
        AR="$host/bin/llvm-ar" RANLIB="$host/bin/llvm-ranlib" \
        "$src/gmp/configure" --host="$target" --disable-shared --enable-static --enable-cxx \
            --disable-assembly --with-pic --prefix="$prefix/gmp"
        make -j"$jobs"; make install
    )
    (
        cd "$build/mpfr"
        CC="$host/bin/${target}26-clang" AR="$host/bin/llvm-ar" RANLIB="$host/bin/llvm-ranlib" \
        "$src/mpfr/configure" --host="$target" --disable-shared --enable-static --with-pic \
            --with-gmp="$prefix/gmp" --prefix="$prefix/mpfr"
        make -j"$jobs"; make install
    )
    mkdir -p "$prefix/gmp/lib/$abi" "$prefix/mpfr/lib/$abi"
    cp "$prefix/gmp/lib/libgmp.a" "$prefix/gmp/lib/libgmpxx.a" "$prefix/gmp/lib/$abi/"
    cp "$prefix/mpfr/lib/libmpfr.a" "$prefix/mpfr/lib/$abi/"
}

build_boost() {
    fetch boost
    (cd "$src/boost" && ./bootstrap.sh --with-toolset=gcc)
    cat > "$build/boost-user-config.jam" <<EOF
using clang : android : $host/bin/${target}26-clang++ : <archiver>$host/bin/llvm-ar <ranlib>$host/bin/llvm-ranlib <compileflags>-fPIC ;
EOF
    (cd "$src/boost" && ./b2 --user-config="$build/boost-user-config.jam" \
        --build-dir="$build/boost" --stagedir="$prefix/boost" --layout=system \
        toolset=clang-android target-os=android threading=multi link=static runtime-link=shared \
        variant=release --with-log --with-filesystem --with-thread --with-regex --with-iostreams \
        --with-nowide --with-chrono --with-atomic --with-system --with-date_time --with-program_options \
        -j"$jobs" stage)
    mkdir -p "$prefix/boost/include" "$prefix/boost/lib/$abi"
    cp -R "$src/boost/boost" "$prefix/boost/include/"
    cp "$prefix/boost/lib/"*.a "$prefix/boost/lib/$abi/"
}

build_occt() {
    fetch occt
    configure occt "$src/occt" -DCMAKE_INSTALL_PREFIX="$prefix/occt" \
        -DBUILD_LIBRARY_TYPE=Static -DUSE_FREETYPE=OFF -DUSE_FREEIMAGE=OFF -DUSE_TCL=OFF \
        -DUSE_TK=OFF -DUSE_VTK=OFF -DUSE_TBB=OFF -DBUILD_MODULE_Draw=OFF \
        -DINSTALL_DIR_INCLUDE=include -DINSTALL_DIR_LIB="lib/$abi"
    cmake --install "$build/occt"
}

case "$component" in
    zlib|expat|tbb|clipper2|headers|gmp_mpfr|boost|occt) "build_$component" ;;
    all) build_headers; build_zlib; build_expat; build_clipper2; build_tbb; build_gmp_mpfr; build_boost; build_occt ;;
    *) echo "Unknown dependency component: $component" >&2; exit 1 ;;
esac
echo "Built dependency component $component for $abi under $prefix (not an engine build)."
