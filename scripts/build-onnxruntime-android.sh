#!/usr/bin/env bash
# Builds a size-trimmed ONNX Runtime Android native library + Java binding for
# Tune's local AutoSync lyric alignment (`OnnxCtcEngine` & `ZhAlignmentEngine`).
#
# Trimming profile (keeps full `.onnx` runtime loading + `OptLevel.ALL_OPT`):
#   - CPU Execution Provider only (no NNAPI / XNNPACK / QNN / GPU)
#   - `--no_telemetry`, `--disable_ml_ops`, `--disable_generation_ops`
#   - `--disable_types float4 float8 optional sparsetensor string`
#   - `--include_ops_by_config scripts/onnxruntime-required-ops.config`
#   - `--enable_reduced_operator_type_support`
#   - `--config MinSizeRel --enable_lto` + `llvm-strip --strip-unneeded`
#
# Output layout (picked up automatically by `androidApp/build.gradle.kts`):
#   androidApp/build/onnxruntime/jniLibs/<abi>/{libonnxruntime.so,libonnxruntime4j_jni.so}
#   androidApp/build/onnxruntime/libs/onnxruntime-android.jar
#
# Usage: bash scripts/build-onnxruntime-android.sh [arm64-v8a|armeabi-v7a|x86_64|all]
set -euo pipefail

ORT_VERSION="1.32.0"
ORT_TARBALL_URL="https://sourceforge.net/projects/onnx-runtime.mirror/files/v${ORT_VERSION}/ONNX%20Runtime%20v${ORT_VERSION}%20source%20code.tar.gz/download"
ORT_TARBALL_SHA256="c8752f1f7d79a624341826d0a23a9c06f3a7ee0201f1927023355264d04137e9"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
ANDROID_DIR="${REPO_ROOT}/androidApp"
OPS_CONFIG="${SCRIPT_DIR}/onnxruntime-required-ops.config"
NDK_VERSION="${ORT_NDK_VERSION:-30.0.15729638}"
API=31

if [[ -d "/opt/homebrew/bin" && ":${PATH}:" != *":/opt/homebrew/bin:"* ]]; then
    export PATH="/opt/homebrew/bin:${PATH}"
fi

detect_sdk_dir() {
    if [[ -n "${ANDROID_SDK_ROOT:-}" ]]; then echo "${ANDROID_SDK_ROOT}"; return; fi
    if [[ -n "${ANDROID_HOME:-}" ]]; then echo "${ANDROID_HOME}"; return; fi
    if [[ -f "${REPO_ROOT}/local.properties" ]]; then
        awk -F= '/^sdk.dir=/{print $2}' "${REPO_ROOT}/local.properties"
        return
    fi
    echo ""
}

detect_java_home() {
    if [[ -n "${JAVA_HOME:-}" && -x "${JAVA_HOME}/bin/javac" ]]; then
        echo "${JAVA_HOME}"; return
    fi
    for cand in \
        "${HOME}"/.gradle/jdks/*/Contents/Home \
        "${HOME}"/.local/jdk-* \
        "${HOME}"/.local/jdk-*/Contents/Home; do
        if [[ -x "${cand}/bin/javac" ]]; then
            echo "${cand}"; return
        fi
    done
    if command -v javac >/dev/null 2>&1; then
        "$(cd "$(dirname "$(command -v javac)")/.." && pwd)" >/dev/null 2>&1 || true
        dirname "$(dirname "$(readlink -f "$(command -v javac)" 2>/dev/null || command -v javac)")"
        return
    fi
    echo ""
}

detect_python() {
    for py in python3.12 python3.11 python3.10 python3; do
        if command -v "${py}" >/dev/null 2>&1; then
            if "${py}" -c 'import sys; raise SystemExit(0 if sys.version_info >= (3, 10) else 1)' 2>/dev/null; then
                command -v "${py}"
                return
            fi
        fi
    done
    echo ""
}

sha256_file() {
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$1" | awk '{print $1}'
    else
        shasum -a 256 "$1" | awk '{print $1}'
    fi
}

SDK_DIR="$(detect_sdk_dir)"
[[ -n "${SDK_DIR}" ]] || { echo "Android SDK not found (set ANDROID_SDK_ROOT or sdk.dir in local.properties)" >&2; exit 1; }
NDK="${SDK_DIR}/ndk/${NDK_VERSION}"
if [[ ! -d "${NDK}" ]]; then
    NDK_CANDIDATE="$(echo "${SDK_DIR}"/ndk/30.0.*)"
    if [[ -d "${NDK_CANDIDATE%% *}" ]]; then
        NDK="${NDK_CANDIDATE%% *}"
    else
        echo "Android NDK ${NDK_VERSION} is required under ${SDK_DIR}/ndk" >&2; exit 1
    fi
fi

if [[ -d "${NDK}/toolchains/llvm/prebuilt/linux-x86_64" ]]; then
    HOST_TAG="linux-x86_64"
else
    DARWIN_CANDIDATE="$(echo "${NDK}"/toolchains/llvm/prebuilt/darwin-*)"
    if [[ -d "${DARWIN_CANDIDATE%% *}" ]]; then
        HOST_TAG="$(basename "${DARWIN_CANDIDATE%% *}")"
    else
        echo "No NDK LLVM prebuilt toolchain found under ${NDK}" >&2; exit 1
    fi
fi
TOOLCHAIN="${NDK}/toolchains/llvm/prebuilt/${HOST_TAG}"
[[ -x "${TOOLCHAIN}/bin/clang" ]] || { echo "NDK clang not found at ${TOOLCHAIN}/bin/clang" >&2; exit 1; }

JAVA_HOME="$(detect_java_home)"
[[ -n "${JAVA_HOME}" && -x "${JAVA_HOME}/bin/javac" ]] || { echo "JDK (javac) not found; set JAVA_HOME" >&2; exit 1; }
export JAVA_HOME

PYTHON_BIN="$(detect_python)"
[[ -n "${PYTHON_BIN}" ]] || { echo "Python >= 3.10 is required" >&2; exit 1; }
command -v cmake >/dev/null 2>&1 || { echo "cmake (>= 3.26) is required" >&2; exit 1; }
command -v ninja >/dev/null 2>&1 || { echo "ninja is required" >&2; exit 1; }

WORK="${TMPDIR:-/tmp}/ort-build-tune-android"
TARBALL="${WORK}/onnxruntime-${ORT_VERSION}.tar.gz"
SRC="${WORK}/src"
VENV="${WORK}/venv"
OUT_ROOT="${ANDROID_DIR}/build/onnxruntime"
OUT_JAR="${OUT_ROOT}/libs/onnxruntime-android.jar"
HEADERS_DIR="${WORK}/jni-headers"

prepare_source_and_venv() {
    mkdir -p "${WORK}"
    if [[ -f "${TARBALL}" ]]; then
        local existing_sha
        existing_sha="$(sha256_file "${TARBALL}")"
        if [[ "${existing_sha}" != "${ORT_TARBALL_SHA256}" ]]; then
            echo "==> Cached tarball checksum mismatch; re-downloading..."
            rm -f "${TARBALL}"
        fi
    fi
    if [[ ! -f "${TARBALL}" ]]; then
        echo "==> Downloading ONNX Runtime v${ORT_VERSION}..."
        curl --fail --location --retry 3 "${ORT_TARBALL_URL}" -o "${TARBALL}"
        local got_sha
        got_sha="$(sha256_file "${TARBALL}")"
        if [[ "${got_sha}" != "${ORT_TARBALL_SHA256}" ]]; then
            echo "Checksum mismatch for ${TARBALL}: got ${got_sha}, expected ${ORT_TARBALL_SHA256}" >&2
            rm -f "${TARBALL}"
            exit 1
        fi
    fi

    if [[ ! -f "${SRC}/tools/ci_build/build.py" ]]; then
        echo "==> Extracting ONNX Runtime v${ORT_VERSION}..."
        rm -rf "${SRC}"
        mkdir -p "${SRC}"
        tar -xzf "${TARBALL}" -C "${SRC}" --strip-components=1
    fi

    if [[ ! -x "${VENV}/bin/python" ]]; then
        echo "==> Creating Python venv for ORT build..."
        "${PYTHON_BIN}" -m venv "${VENV}"
    fi
    if ! "${VENV}/bin/python" -c 'import packaging, flatbuffers' >/dev/null 2>&1; then
        "${VENV}/bin/pip" install --quiet packaging flatbuffers
    fi
}

build_java_bindings() {
    local classes_dir="${WORK}/java-classes"
    local sources_list="${WORK}/java-sources.txt"
    echo "==> Compiling ONNX Runtime Android Java bindings & JNI headers..."
    rm -rf "${classes_dir}" "${HEADERS_DIR}"
    mkdir -p "${classes_dir}" "${HEADERS_DIR}" "$(dirname "${OUT_JAR}")"
    find "${SRC}/java/src/main/java" "${SRC}/java/src/main/android" -name "*.java" | sort > "${sources_list}"
    "${JAVA_HOME}/bin/javac" --release 8 -Xlint:none \
        -h "${HEADERS_DIR}" \
        -d "${classes_dir}" \
        @"${sources_list}"
    "${JAVA_HOME}/bin/jar" cf "${OUT_JAR}" -C "${classes_dir}" .
}

build_arch() {
    local ABI="$1" TRIPLE EXTRA_ABI_CFLAGS=""
    case "${ABI}" in
        arm64-v8a) TRIPLE="aarch64-linux-android" ;;
        armeabi-v7a) TRIPLE="armv7a-linux-androideabi"; EXTRA_ABI_CFLAGS="-march=armv7-a -mfloat-abi=softfp -mfpu=neon" ;;
        x86_64) TRIPLE="x86_64-linux-android" ;;
        *) echo "Unsupported ABI: ${ABI}" >&2; exit 1 ;;
    esac

    local ABI_BUILD_DIR="${WORK}/build-${ABI}"
    local CONFIG_BUILD_DIR="${ABI_BUILD_DIR}/MinSizeRel"
    echo "==> Building trimmed ONNX Runtime ${ORT_VERSION} for ${ABI}..."

    "${VENV}/bin/python" "${SRC}/tools/ci_build/build.py" \
        --android \
        --android_abi "${ABI}" \
        --android_api "${API}" \
        --android_sdk_path "${SDK_DIR}" \
        --android_ndk_path "${NDK}" \
        --config MinSizeRel \
        --enable_lto \
        --parallel \
        --cmake_generator=Ninja \
        --build_shared_lib \
        --skip_tests \
        --skip_submodule_sync \
        --no_telemetry \
        --disable_ml_ops \
        --disable_generation_ops \
        --disable_types float4 float8 optional sparsetensor string \
        --include_ops_by_config "${OPS_CONFIG}" \
        --enable_reduced_operator_type_support \
        --cmake_extra_defines CMAKE_POLICY_VERSION_MINIMUM=3.5 \
        --build_dir "${ABI_BUILD_DIR}" \
        --update \
        --build \
        --targets onnxruntime

    local LIB_ORT="${CONFIG_BUILD_DIR}/libonnxruntime.so"
    local LIB_JNI="${CONFIG_BUILD_DIR}/libonnxruntime4j_jni.so"
    [[ -f "${LIB_ORT}" ]] || { echo "Missing built ${LIB_ORT}" >&2; exit 1; }

    echo "==> Compiling libonnxruntime4j_jni.so for ${ABI}..."
    # shellcheck disable=SC2086
    "${TOOLCHAIN}/bin/${TRIPLE}${API}-clang" \
        -DANDROID -DNDEBUG -Os -std=gnu11 -flto=thin -fPIC \
        ${EXTRA_ABI_CFLAGS} \
        -fdata-sections -ffunction-sections -funwind-tables -fstack-protector-strong \
        -D_FORTIFY_SOURCE=2 -fexceptions \
        -DDISABLE_FLOAT4_TYPES -DDISABLE_FLOAT8_TYPES -DDISABLE_GENERATION_OPS -DDISABLE_ML_OPS \
        -DDISABLE_OPTIONAL_TYPE -DDISABLE_SPARSE_TENSORS -DDISABLE_STRING_TYPE \
        -DEIGEN_MPL2_ONLY -DEIGEN_USE_THREADS -DORT_ENABLE_GQA_VALUE_LAYOUT -DORT_ENABLE_STREAM \
        -DPLATFORM_POSIX -DREDUCED_OPS_BUILD -DUSE_DX_INTEROP=0 -Donnxruntime4j_jni_EXPORTS \
        -I"${CONFIG_BUILD_DIR}" \
        -I"${SRC}/include/onnxruntime" \
        -I"${SRC}/include/onnxruntime/core/session" \
        -I"${SRC}/include" \
        -I"${SRC}/orttraining/orttraining/training_api/include" \
        -I"${HEADERS_DIR}" \
        -shared -Wl,--build-id=sha1 -Wl,--gc-sections -Wl,--no-undefined \
        -Wl,-z,max-page-size=16384 \
        "${SRC}/java/src/main/native/"*.c \
        "${LIB_ORT}" \
        -latomic -lm \
        -o "${LIB_JNI}"

    local OUT_ABI_DIR="${OUT_ROOT}/jniLibs/${ABI}"
    mkdir -p "${OUT_ABI_DIR}"
    cp -f "${LIB_ORT}" "${OUT_ABI_DIR}/libonnxruntime.so"
    cp -f "${LIB_JNI}" "${OUT_ABI_DIR}/libonnxruntime4j_jni.so"
    "${TOOLCHAIN}/bin/llvm-strip" --strip-unneeded \
        "${OUT_ABI_DIR}/libonnxruntime.so" \
        "${OUT_ABI_DIR}/libonnxruntime4j_jni.so"
}

prepare_source_and_venv
build_java_bindings

case "${1:-arm64-v8a}" in
    arm64-v8a) build_arch arm64-v8a ;;
    armeabi-v7a) build_arch armeabi-v7a ;;
    x86_64) build_arch x86_64 ;;
    all) build_arch arm64-v8a; build_arch armeabi-v7a; build_arch x86_64 ;;
    *) echo "Usage: $0 [arm64-v8a|armeabi-v7a|x86_64|all]" >&2; exit 1 ;;
esac

echo "==> Done. Generated ONNX Runtime artifacts in ${OUT_ROOT}."
