#!/usr/bin/env bash
# Independent Linux reference using the pinned upstream build and real host libs.
set -euo pipefail
export LC_ALL=C TZ=UTC SOURCE_DATE_EPOCH=1704067200
repo=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
work=${HOST_REFERENCE_DIR:?Set HOST_REFERENCE_DIR to a disposable build directory}
jobs=${NATIVE_JOBS:-2}
[[ $jobs =~ ^[1-9][0-9]*$ ]] || exit 2
pin=$(git -C "$repo" ls-tree HEAD app/src/main/cpp/orcaslicer | awk '{print $3}')
[[ $pin =~ ^[0-9a-f]{40}$ ]] || exit 2
mkdir -p "$work"
work=$(cd "$work" && pwd)
fetch() {
    local directory=$1 url=$2 commit=$3
    if [[ ! -e $directory ]]; then
        git init -q "$directory"
        git -C "$directory" remote add origin "$url"
        git -C "$directory" fetch --depth=1 origin "$commit"
        git -C "$directory" checkout --detach FETCH_HEAD
    fi
    [[ $(git -C "$directory" rev-parse HEAD) == "$commit" ]]
}
fetch "$work/source" https://github.com/taylormadearmy/OrcaSlicer.git "$pin"
noise_pin=$(awk '$1 == "libnoise" {print $3}' "$repo/scripts/native/extra-sources.lock")
fetch "$work/libnoise" https://github.com/SoftFever/Orca-deps-libnoise.git "$noise_pin"
cmake -S "$work/libnoise" -B "$work/noise-build" -G Ninja \
    -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX="$work/prefix" \
    -DCMAKE_POSITION_INDEPENDENT_CODE=ON -DBUILD_SHARED_LIBS=OFF
cmake --build "$work/noise-build" --parallel "$jobs"
cmake --install "$work/noise-build"

# Only build-system integration is added; no upstream algorithm source changes.
if ! grep -q 'PARITY_REPO}/tools/parity/host/reference.cmake' "$work/source/CMakeLists.txt"; then
    printf '\ninclude("${PARITY_REPO}/tools/parity/host/reference.cmake")\n' >> "$work/source/CMakeLists.txt"
fi
mkdir -p "$work/output"
git -C "$work/source" diff -- CMakeLists.txt > "$work/output/build-integration.patch"
printf '%s\n' "$pin" > "$work/output/engine-source.txt"
dpkg-query -W > "$work/output/host-packages.txt"
cmake -S "$work/source" -B "$work/build" -G Ninja \
    -DPARITY_REPO="$repo" -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_PREFIX_PATH="$work/prefix" -DCMAKE_CXX_FLAGS_RELEASE='-O1 -DNDEBUG' \
    -DCMAKE_C_COMPILER_LAUNCHER=ccache -DCMAKE_CXX_COMPILER_LAUNCHER=ccache \
    -DSLIC3R_GUI=OFF -DSLIC3R_STATIC=OFF -DSLIC3R_PCH=OFF \
    -DSLIC3R_SENTRY=OFF -DBUILD_TESTS=OFF -DORCA_TOOLS=OFF \
    -DCMAKE_DISABLE_FIND_PACKAGE_OpenVDB=ON
cmake --build "$work/build" --target preset-reference --parallel "$jobs"
cp "$work/build/preset-reference" "$work/output/"
sha256sum "$work/output/preset-reference" > "$work/output/reference-sha256.txt"
ldd "$work/output/preset-reference" > "$work/output/runtime-libraries.txt"
