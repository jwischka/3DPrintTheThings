#!/usr/bin/env bash
# Audits the pinned source and NDK; this does not build or validate a slicing engine.
set -euo pipefail

repo=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
ndk=${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME to NDK 26.1.10909125}
work=${NATIVE_AUDIT_DIR:?Set NATIVE_AUDIT_DIR to a disposable directory outside the checkout}
source_dir="$work/orca-source"
gitlink=$(git -C "$repo" ls-tree HEAD app/src/main/cpp/orcaslicer | awk '{print $3}')
[[ $gitlink =~ ^[0-9a-f]{40}$ ]] || { echo 'Invalid pinned engine gitlink' >&2; exit 1; }
grep -Eq '^Pkg.Revision[[:space:]]*=[[:space:]]*26\.1\.10909125[[:space:]]*$' "$ndk/source.properties"
mkdir -p "$work"
[[ ! -e "$source_dir" ]] || { echo 'Audit source directory already exists' >&2; exit 1; }
git init -q "$source_dir"
git -C "$source_dir" remote add origin https://github.com/taylormadearmy/OrcaSlicer.git
git -C "$source_dir" fetch --depth=1 --filter=blob:none origin "$gitlink"
git -C "$source_dir" checkout --detach FETCH_HEAD
[[ $(git -C "$source_dir" rev-parse HEAD) == "$gitlink" ]]
test -f "$source_dir/src/libslic3r/Print.cpp"
test -d "$source_dir/deps_src"
echo "Engine source: $gitlink"
echo "NDK revision: 26.1.10909125"
for abi in aarch64-linux-android26 x86_64-linux-android26; do
    compiler="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/${abi}-clang++"
    test -x "$compiler"
    echo "Toolchain $abi: $($compiler --version | head -n 1)"
done
for name in libbgcode jpeg nlopt libpng qhull opencv freetype openssl openvdb; do
    path="$repo/app/src/main/cpp/extern/${name}_stub/include"
    if [[ -d $path ]]; then echo "Stub $name: present"; else echo "Stub $name: missing"; fi
done
echo 'Audit only: no native dependency bundle or slicing engine was built.'
