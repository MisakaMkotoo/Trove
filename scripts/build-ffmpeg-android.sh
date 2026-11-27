#!/usr/bin/env bash
# Builds the Android FFmpeg decoder used by Tune's androidApp.
# Adapted from the upstream mobile FFmpeg build script (GPLv3):
# repo root IS the mobile project (no mobile/ subdir), SDK auto-detected,
# portable across Linux and macOS runners/hosts.
#
# Source is downloaded to a temporary cache exactly like the upstream script;
# no FFmpeg source or generated library is committed to this repository.
#
# Usage: bash scripts/build-ffmpeg-android.sh [arm64-v8a|armeabi-v7a|x86_64|all]
#   "all" builds all three ABIs sequentially (arm64-v8a, armeabi-v7a, x86_64).
#   NOTE: INCLUDE_OUT is shared across ABIs (last-wins); headers are
#   equivalent across ABIs so the last build wins by design. Do NOT parallelize.
set -euo pipefail

FFMPEG_VERSION="8.1.2"
FFMPEG_URL="https://ffmpeg.org/releases/ffmpeg-${FFMPEG_VERSION}.tar.gz"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
ANDROID_DIR="${REPO_ROOT}/androidApp"
NDK_VERSION="30.0.15729638"
API=31

detect_sdk_dir() {
    if [[ -n "${ANDROID_SDK_ROOT:-}" ]]; then echo "${ANDROID_SDK_ROOT}"; return; fi
    if [[ -n "${ANDROID_HOME:-}" ]]; then echo "${ANDROID_HOME}"; return; fi
    if [[ -f "${REPO_ROOT}/local.properties" ]]; then
        awk -F= '/^sdk.dir=/{print $2}' "${REPO_ROOT}/local.properties"
        return
    fi
    echo ""
}
SDK_DIR="$(detect_sdk_dir)"
[[ -n "${SDK_DIR}" ]] || { echo "Android SDK not found (set ANDROID_SDK_ROOT or sdk.dir in local.properties)" >&2; exit 1; }
NDK="${SDK_DIR}/ndk/${NDK_VERSION}"

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
BUILD_DIR="${TMPDIR:-/tmp}/ffmpeg-build-tune-android-${FFMPEG_VERSION}"
JNI_OUT="${ANDROID_DIR}/src/main/jniLibs"
INCLUDE_OUT="${ANDROID_DIR}/build/ffmpeg/include"

[[ -x "${TOOLCHAIN}/bin/clang" ]] || { echo "Android NDK ${NDK_VERSION} is required at ${NDK}" >&2; exit 1; }

# FFmpeg is used strictly as an audio decoder (playback + 16kHz mono PCM
# extraction for lyric alignment). Enable all built-in audio/PCM/DPCM/ADPCM
# decoders, audio parsers, and audio/multimedia-container demuxers while
# excluding video/image/subtitle decoders, parsers, and video-only demuxers.
# Programs, encoders, muxers, filters, devices and hardware accels remain off.

AUDIO_PARSERS="aac,aac_latm,ac3,adx,ahx,amr,cook,dca,dolby_e,dvaudio,flac,ftr,g723_1,g729,gsm,misc4,mlp,mpegaudio,opus,sbc,sipr,tak,vorbis,xma"
AUDIO_DEMUXERS="aa,aac,aax,ac3,ac4,ace,acm,act,adf,adp,ads,adx,aea,afc,aiff,aix,alp,amr,amrnb,amrwb,apac,apc,ape,apm,aptx,aptx_hd,argo_asf,argo_cvg,ast,au,avr,binka,bit,bfstm,brstm,boa,bonk,caf,codec2,codec2raw,daud,dcstr,derf,dfpwm,dsf,dss,dts,dtshd,ea_cdata,eac3,epaf,ffmetadata,flac,fsb,fwse,g722,g723_1,g726,g726le,g728,g729,genh,gsm,hca,hcom,iamf,iff,ilbc,ircam,iss,kvag,laf,lc3,loas,lrc,mca,mlp,mmf,mp3,mpc,mpc8,msf,msp,mtaf,musx,nistsphere,nsp,ogg,oma,osq,pcm_alaw,pcm_mulaw,pcm_vidc,pcm_f64be,pcm_f64le,pcm_f32be,pcm_f32le,pcm_s32be,pcm_s32le,pcm_s24be,pcm_s24le,pcm_s16be,pcm_s16le,pcm_s8,pcm_u32be,pcm_u32le,pcm_u24be,pcm_u24le,pcm_u16be,pcm_u16le,pcm_u8,pp_bnk,pvf,qcp,qoa,redspark,rka,rsd,rso,s337m,sbc,sbg,scd,sdns,sds,sdx,shorten,sln,sox,spdif,svag,tak,truehd,tta,vag,vpk,vqf,w64,wady,wavarc,wav,wsaud,wsd,wve,wv,xa,xmd,xvag,xwma,asf,asf_o,avi,concat,flv,hls,live_flv,matroska,mov,mpegps,mpegts,mpegtsraw,mxf,nut,rm,rtp,rtsp,sdp,webm_dash_manifest"

NPROC="$(nproc 2>/dev/null || sysctl -n hw.ncpu 2>/dev/null || echo 4)"
OPENSSL_VERSION="${OPENSSL_VERSION:-3.0.15}"
case "${1:-arm64-v8a}" in
    arm64-v8a) OPENSSL_ABI_DIR="arm64" ;;
    *) OPENSSL_ABI_DIR="" ;;
esac
OPENSSL_PREFIX="${TMPDIR:-/tmp}/openssl-build-tune-android-${OPENSSL_VERSION}/install-${OPENSSL_ABI_DIR}"
if [[ -z "${OPENSSL_ABI_DIR}" || ! -f "${OPENSSL_PREFIX}/lib/libssl.a" ]]; then
    echo "OpenSSL for ${1:-arm64-v8a} is missing; run scripts/build-openssl-android.sh first." >&2
    exit 1
fi

download_source() {
    mkdir -p "${BUILD_DIR}/src"
    if [[ ! -f "${BUILD_DIR}/ffmpeg.tar.gz" ]]; then
        echo "==> Downloading FFmpeg ${FFMPEG_VERSION}..."
        curl --fail --location --retry 3 "${FFMPEG_URL}" -o "${BUILD_DIR}/ffmpeg.tar.gz"
    fi
}

build_arch() {
    local ABI="$1" ARCH TRIPLE EXTRA_ABI_CFLAGS=""
    case "${ABI}" in
        arm64-v8a) ARCH="aarch64"; TRIPLE="aarch64-linux-android" ;;
        armeabi-v7a) ARCH="arm"; TRIPLE="armv7a-linux-androideabi"; EXTRA_ABI_CFLAGS=" -march=armv7-a -mfloat-abi=softfp -mfpu=neon" ;;
        x86_64) ARCH="x86_64"; TRIPLE="x86_64-linux-android" ;;
        *) echo "Unsupported ABI: ${ABI}" >&2; exit 1 ;;
    esac
    local SRC="${BUILD_DIR}/src-${ABI}" INSTALL="${BUILD_DIR}/install-${ABI}"
    rm -rf "${SRC}" "${INSTALL}"
    mkdir -p "${SRC}" "${INSTALL}"
    tar -xzf "${BUILD_DIR}/ffmpeg.tar.gz" -C "${SRC}" --strip-components=1
    local AUDIO_DECODERS
    AUDIO_DECODERS="$(
        awk '/\/\* audio codecs \*\//{flag=1;next}/\/\* subtitles \*\//{flag=0}flag' "${SRC}/libavcodec/allcodecs.c" \
            | sed -n 's/.*ff_\([a-zA-Z0-9_]*\)_decoder;.*/\1/p' \
            | grep -vx 'hdr' \
            | tr '\n' ',' | sed 's/,$//'
    )"
    [[ -n "${AUDIO_DECODERS}" ]] || { echo "Failed to resolve FFmpeg audio decoders." >&2; exit 1; }
    echo "==> Building audio-only FFmpeg ${FFMPEG_VERSION} for ${ABI}..."
    pushd "${SRC}" >/dev/null
    # --disable-x86asm: x86_64 assembly needs nasm, which stock GitHub runners
    # lack; x86_64 output is emulator-only so generic C code is fine.
    # No-op for ARM ABIs (their asm comes from the NDK toolchain, not nasm).
    ./configure \
        --prefix="${INSTALL}" \
        --target-os=android --arch="${ARCH}" --enable-cross-compile \
        --cc="${TOOLCHAIN}/bin/${TRIPLE}${API}-clang" \
        --cxx="${TOOLCHAIN}/bin/${TRIPLE}${API}-clang++" \
        --ar="${TOOLCHAIN}/bin/llvm-ar" --ranlib="${TOOLCHAIN}/bin/llvm-ranlib" --strip="${TOOLCHAIN}/bin/llvm-strip" \
        --disable-everything --disable-autodetect --disable-programs --disable-doc --disable-debug \
        --enable-shared --disable-static --enable-small --enable-pic \
        --enable-avutil --enable-avcodec --enable-avformat --enable-swresample \
        --enable-network --enable-protocol=file,http,https,tcp,tls \
        --enable-openssl \
        --enable-decoder="${AUDIO_DECODERS}" \
        --enable-demuxer="${AUDIO_DEMUXERS}" \
        --enable-parser="${AUDIO_PARSERS}" \
        --disable-avdevice --disable-avfilter --disable-swscale \
        --disable-bsfs --disable-hwaccels \
        --disable-x86asm \
        --extra-cflags="-Oz -ffunction-sections -fdata-sections${EXTRA_ABI_CFLAGS} -I${OPENSSL_PREFIX}/include" \
        --extra-ldflags="-Wl,--gc-sections -Wl,-z,max-page-size=16384 -L${OPENSSL_PREFIX}/lib" \
        --extra-libs="-lssl -lcrypto"
    make -j"${NPROC}"
    make install
    popd >/dev/null
    mkdir -p "${JNI_OUT}/${ABI}"
    for lib in libavutil libswresample libavcodec libavformat; do
        cp -L "${INSTALL}/lib/${lib}.so" "${JNI_OUT}/${ABI}/${lib}.so"
    done
    rm -rf "${INCLUDE_OUT}"
    mkdir -p "$(dirname "${INCLUDE_OUT}")"
    cp -R "${INSTALL}/include" "${INCLUDE_OUT}"
}

download_source
case "${1:-arm64-v8a}" in
    arm64-v8a) build_arch arm64-v8a ;;
    armeabi-v7a) build_arch armeabi-v7a ;;
    x86_64) build_arch x86_64 ;;
    all) build_arch arm64-v8a; build_arch armeabi-v7a; build_arch x86_64 ;;
    *) echo "Usage: $0 [arm64-v8a|armeabi-v7a|x86_64|all]" >&2; exit 1 ;;
esac
echo "==> Done. Generated FFmpeg libraries are in ${JNI_OUT}."
