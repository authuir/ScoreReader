cmake_minimum_required(VERSION 3.22)
project(scorereader-verovio LANGUAGES CXX C)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# vendor/verovio-version-6.1.0 lives at the workspace root.
# This file is at app/src/main/cpp/CMakeLists.txt, so go up four levels.
set(VEROVIO_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../vendor/verovio-version-6.1.0")

if(NOT EXISTS "${VEROVIO_ROOT}/include/vrv/toolkit.h")
    message(FATAL_ERROR "Verovio source not found at ${VEROVIO_ROOT}. "
                        "Run the fetch step or check the path.")
endif()

# Match the upstream switches used in the cmake/ tree, minus anything we
# don't need on Android (DARMS / runtime clock / etc). MusicXML + MEI + ABC
# + Humdrum support stay on.
add_compile_definitions(
    NO_DARMS_SUPPORT
    NO_PAE_SUPPORT
    USE_PAE_OLD_PARSER
    NO_RUNTIME
)
# RESOURCE_DIR is only used as a fallback when the app forgets to call
# SetResourcePath(). Point at a location that's guaranteed not to exist so
# any accidental use surfaces quickly.
add_compile_definitions(RESOURCE_DIR="/data/local/tmp/verovio-not-set")

# Verovio's src/vrv.cpp does `#include "git_commit.h"` on non-Windows builds.
# Upstream creates that file via tools/get_git_commit.sh which requires bash
# and a git checkout — neither is available here. Synthesize a stub that just
# defines GIT_COMMIT to an empty marker.
set(GIT_COMMIT_DIR "${CMAKE_BINARY_DIR}/generated")
file(MAKE_DIRECTORY "${GIT_COMMIT_DIR}")
file(WRITE "${GIT_COMMIT_DIR}/git_commit.h" "#define GIT_COMMIT \"[android]\"\n")

include_directories(
    ${GIT_COMMIT_DIR}
    ${VEROVIO_ROOT}/include
    ${VEROVIO_ROOT}/include/crc
    ${VEROVIO_ROOT}/include/midi
    ${VEROVIO_ROOT}/include/hum
    ${VEROVIO_ROOT}/include/json
    ${VEROVIO_ROOT}/include/pugi
    ${VEROVIO_ROOT}/include/tuning-library
    ${VEROVIO_ROOT}/include/zip
    ${VEROVIO_ROOT}/include/vrv
    ${VEROVIO_ROOT}/libmei/dist
    ${VEROVIO_ROOT}/libmei/addons
    ${VEROVIO_ROOT}/tools
)

file(GLOB verovio_SRC      "${VEROVIO_ROOT}/src/*.cpp")
file(GLOB libmei_dist_SRC  "${VEROVIO_ROOT}/libmei/dist/*.cpp")
file(GLOB libmei_addons_SRC "${VEROVIO_ROOT}/libmei/addons/*.cpp")
file(GLOB hum_SRC          "${VEROVIO_ROOT}/src/hum/*.cpp")
file(GLOB midi_SRC         "${VEROVIO_ROOT}/src/midi/*.cpp")
file(GLOB crc_SRC          "${VEROVIO_ROOT}/src/crc/*.cpp")

add_library(scorereader-verovio SHARED
    ${verovio_SRC}
    ${libmei_dist_SRC}
    ${libmei_addons_SRC}
    ${hum_SRC}
    ${midi_SRC}
    ${crc_SRC}
    ${VEROVIO_ROOT}/src/json/jsonxx.cc
    ${VEROVIO_ROOT}/src/pugi/pugixml.cpp
    ${VEROVIO_ROOT}/tools/c_wrapper.cpp
    verovio-jni.cpp
)

find_library(log-lib log)
target_link_libraries(scorereader-verovio PRIVATE ${log-lib})

# Verovio's own code emits a lot of warnings under clang; silence the noisiest
# so the NDK build doesn't drown in output.
target_compile_options(scorereader-verovio PRIVATE
    -Wno-deprecated-declarations
    -Wno-unused-but-set-variable
    -Wno-unused-function
    -Wno-unused-variable
    -Wno-unused-parameter
    -Wno-overloaded-virtual
    -Wno-deprecated-builtins
    -Wno-dollar-in-identifier-extension
    -Wno-missing-field-initializers
    -Wno-missing-braces
    -Wno-sign-compare
    -Wno-shorten-64-to-32
)
