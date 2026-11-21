#!/usr/bin/env bash
# Builds a static OpenSSL (arm64-v8a) for FFmpeg's https support.
set -euo pipefail
OPENSSL_VERSION="${OPENSSL_VERSION:-3.0.15}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
SDK_DIR="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$(awk -F= '/^sdk.dir=/{print $2}' "${REPO_ROOT}/local.properties")}}"
NDK_VERSION="${NDK_VERSION:-30.0.15729638}"
NDK="${SDK_DIR}/ndk/${NDK_VERSION}"
if [[ -d "${NDK}/toolchains/llvm/prebuilt/linux-x86_64" ]]; then
    HOST_TAG="linux-x86_64"
else
    HOST_TAG="$(basename ${NDK}/toolchains/llvm/prebuilt/darwin-*)"
fi
TOOLCHAIN="${NDK}/toolchains/llvm/prebuilt/${HOST_TAG}"
BUILD_DIR="${TMPDIR:-/tmp}/openssl-build-tune-android-${OPENSSL_VERSION}"
INSTALL="${BUILD_DIR}/install-arm64"
mkdir -p "${BUILD_DIR}"
if [[ ! -f "${BUILD_DIR}/openssl.tar.gz" ]]; then
    echo "==> Downloading OpenSSL ${OPENSSL_VERSION}..."
    curl --fail --location --retry 3 \
        "https://github.com/openssl/openssl/releases/download/openssl-${OPENSSL_VERSION}/openssl-${OPENSSL_VERSION}.tar.gz" \
        -o "${BUILD_DIR}/openssl.tar.gz"
fi
SRC="${BUILD_DIR}/src-arm64"
rm -rf "${SRC}" "${INSTALL}"
mkdir -p "${SRC}" "${INSTALL}"
tar -xzf "${BUILD_DIR}/openssl.tar.gz" -C "${SRC}" --strip-components=1
pushd "${SRC}" >/dev/null
export ANDROID_NDK_ROOT="${NDK}"
export PATH="${TOOLCHAIN}/bin:${PATH}"
./Configure android-arm64 no-shared no-tests no-ui-console \
    -fPIC --prefix="${INSTALL}" \
    -D__ANDROID_API__=31
make -j"$(sysctl -n hw.ncpu 2>/dev/null || nproc 2>/dev/null || echo 4)" build_libs
make install_dev
popd >/dev/null
echo "==> OpenSSL installed to ${INSTALL}"
