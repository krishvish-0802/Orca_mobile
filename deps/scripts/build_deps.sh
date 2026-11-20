#!/usr/bin/env bash
# Builds OrcaSlicer's third-party dependencies for Android.
#
# Usage: deps/scripts/build_deps.sh [--abi arm64-v8a|x86_64] [--api 29]
#                                   [--build-dir DIR] [--prefix DIR]
# Environment: ANDROID_NDK_HOME (or ANDROID_NDK_LATEST_HOME / ANDROID_NDK_ROOT).
#
# The build directory must be outside any git work tree (upstream patch steps
# use `git apply`, which resolves paths against an enclosing repository).
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
abi="arm64-v8a"
api="29"
build_dir="${HOME}/.cache/orca-mobile/deps-build"
prefix=""

while [[ $# -gt 0 ]]; do
    case "$1" in
        --abi) abi="$2"; shift 2 ;;
        --api) api="$2"; shift 2 ;;
        --build-dir) build_dir="$2"; shift 2 ;;
        --prefix) prefix="$2"; shift 2 ;;
        *) echo "Unknown argument: $1" >&2; exit 2 ;;
    esac
done

build_dir="${build_dir}/${abi}"
prefix="${prefix:-${build_dir}/install}"

ndk="${ANDROID_NDK_HOME:-${ANDROID_NDK_LATEST_HOME:-${ANDROID_NDK_ROOT:-}}}"
if [[ -z "${ndk}" || ! -f "${ndk}/build/cmake/android.toolchain.cmake" ]]; then
    echo "Android NDK not found. Set ANDROID_NDK_HOME." >&2
    exit 1
fi
ndk_major="$(sed -n 's/^Pkg.Revision *= *\([0-9]*\).*/\1/p' "${ndk}/source.properties")"
if (( ndk_major < 28 )); then
    echo "NDK r28 or newer is required for 16 KB page alignment (found ${ndk_major})." >&2
    exit 1
fi

mkdir -p "${build_dir}"
if git -C "${build_dir}" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    echo "Build directory ${build_dir} is inside a git work tree; choose another --build-dir." >&2
    exit 1
fi

echo "NDK ${ndk} (r${ndk_major}), ABI ${abi}, API ${api}"
echo "Build dir ${build_dir}, prefix ${prefix}"

cmake -S "${repo_root}/deps" -B "${build_dir}" -G Ninja \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_TOOLCHAIN_FILE="${ndk}/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="${abi}" \
    -DANDROID_PLATFORM="android-${api}" \
    -DANDROID_STL=c++_shared \
    -DDESTDIR="${prefix}" \
    -DORCA_SOURCE_DIR="${ORCA_SOURCE_DIR:-${repo_root}/third_party/OrcaSlicer}"

cmake --build "${build_dir}" --target deps
