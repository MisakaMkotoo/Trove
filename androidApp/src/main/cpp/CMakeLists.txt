cmake_minimum_required(VERSION 3.22.1)
project(tune_player LANGUAGES C CXX)

set(FFMPEG_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}")
set(FFMPEG_INCLUDE "${CMAKE_CURRENT_SOURCE_DIR}/../../../build/ffmpeg/include")

if(NOT EXISTS "${FFMPEG_INCLUDE}/libavcodec/avcodec.h")
    message(FATAL_ERROR "FFmpeg artifacts are missing. Run: bash scripts/build-ffmpeg-android.sh ${ANDROID_ABI}")
endif()

foreach(library avutil swresample avcodec avformat)
    add_library(${library} SHARED IMPORTED)
    set_target_properties(${library} PROPERTIES IMPORTED_LOCATION "${FFMPEG_ROOT}/lib${library}.so")
endforeach()

add_library(tune_player SHARED ffmpeg_player.cpp auto_sync_pcm.cpp)
target_include_directories(tune_player PRIVATE "${FFMPEG_INCLUDE}")
target_compile_options(tune_player PRIVATE -Oz -ffunction-sections -fdata-sections)
target_link_options(tune_player PRIVATE -Wl,--gc-sections -Wl,-z,max-page-size=16384)
target_link_libraries(tune_player avformat avcodec swresample avutil aaudio android log)

# ── Japanese G2P (vendored OpenJTalk text frontend, Modified BSD) ────────────
# Only the text frontend is built: text2mecab → MeCab + NAIST dictionary → NJD
# sets → JPCommon labels. No HTS engine, no voice. Adapted from MeloX-Android.
set(OPEN_JTALK_SRC "${CMAKE_CURRENT_SOURCE_DIR}/third_party/open_jtalk")
set(OPEN_JTALK_GEN "${CMAKE_CURRENT_BINARY_DIR}/open_jtalk_gen")
file(MAKE_DIRECTORY "${OPEN_JTALK_GEN}")

file(WRITE "${OPEN_JTALK_GEN}/config.h"
"/* generated for the Tune G2P build */
#define HAVE_CTYPE_H 1
#define HAVE_DIRENT_H 1
#define HAVE_FCNTL_H 1
#define HAVE_INTTYPES_H 1
#define HAVE_MEMORY_H 1
#define HAVE_SETJMP_H 1
#define HAVE_STDINT_H 1
#define HAVE_STDLIB_H 1
#define HAVE_STRING_H 1
#define HAVE_STRINGS_H 1
#define HAVE_SYS_MMAN_H 1
#define HAVE_SYS_STAT_H 1
#define HAVE_SYS_TIMES_H 1
#define HAVE_SYS_TYPES_H 1
#define HAVE_UNISTD_H 1
#define HAVE_GETENV 1
#define HAVE_GETPAGESIZE 1
#define HAVE_MMAP 1
#define HAVE_OPENDIR 1
#define HAVE_SETJMP 1
#define HAVE_SQRT 1
#define HAVE_STRSTR 1
#define SIZEOF_CHAR 1
#define SIZEOF_INT 4
#define SIZEOF_LONG 8
#define SIZEOF_LONG_LONG 8
#define SIZEOF_SHORT 2
#define SIZEOF_SIZE_T 8
#define MECAB_CHARSET utf-8
")

set(OPEN_JTALK_DIRS
        jpcommon mecab/src mecab2njd njd njd2jpcommon
        njd_set_accent_phrase njd_set_accent_type njd_set_digit
        njd_set_long_vowel njd_set_pronunciation njd_set_unvoiced_vowel text2mecab)
set(OPEN_JTALK_SOURCES "")
foreach(dir ${OPEN_JTALK_DIRS})
    aux_source_directory("${OPEN_JTALK_SRC}/${dir}" dir_sources)
    list(APPEND OPEN_JTALK_SOURCES ${dir_sources})
endforeach()

add_library(tune_ja_g2p SHARED ja_g2p.cpp ja_g2p_jni.cpp ${OPEN_JTALK_SOURCES})
set_target_properties(tune_ja_g2p PROPERTIES
        OUTPUT_NAME "tune_ja_g2p"
        CXX_STANDARD 14
        CXX_STANDARD_REQUIRED ON)
foreach(dir ${OPEN_JTALK_DIRS})
    target_include_directories(tune_ja_g2p PRIVATE "${OPEN_JTALK_SRC}/${dir}")
endforeach()
target_include_directories(tune_ja_g2p PRIVATE "${OPEN_JTALK_GEN}" "${CMAKE_CURRENT_SOURCE_DIR}")
target_compile_definitions(tune_ja_g2p PRIVATE
        HAVE_CONFIG_H DIC_VERSION=102 MECAB_DEFAULT_RC="dummy" MECAB_WITHOUT_SHARE_DIC
        PACKAGE="open_jtalk" VERSION="1.11" CHARSET_UTF_8 MECAB_CHARSET=utf-8
        MECAB_UTF8_USE_ONLY)
target_compile_options(tune_ja_g2p PRIVATE -Oz -ffunction-sections -fdata-sections)
target_link_options(tune_ja_g2p PRIVATE -Wl,--gc-sections -Wl,-z,max-page-size=16384)
target_link_libraries(tune_ja_g2p log)
