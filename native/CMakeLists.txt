# Orca Mobile native layer: OrcaSlicer's libslic3r engine plus the orca_engine
# bridge, built for Android with the NDK.
#
# Inputs:
#   ORCA_SOURCE_DIR   OrcaSlicer sources (defaults to the pinned submodule)
#   ORCA_DEPS_PREFIX  Install prefix produced by deps/scripts/build_deps.sh
#
# Outputs:
#   liborca_engine.so  JNI library loaded by :core:engine
#   orca_slice_cli     Command-line harness that slices one model on a device
cmake_minimum_required(VERSION 3.22)
project(OrcaMobileNative LANGUAGES C CXX)

set(ORCA_SOURCE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../third_party/OrcaSlicer"
    CACHE PATH "OrcaSlicer source tree (the pinned submodule)")
get_filename_component(ORCA_SOURCE_DIR "${ORCA_SOURCE_DIR}" ABSOLUTE)
set(ORCA_DEPS_PREFIX "" CACHE PATH "Prefix of the Android dependency build")
option(ORCA_BUILD_CLI "Build the orca_slice_cli harness" ON)
option(SLIC3R_CAD "Build the parametric Design/CAD engine parts (needs OCCT CAD toolkits)" ON)
set(ORCA_MAX_COMPILE_JOBS "" CACHE STRING
    "Cap on parallel compile jobs (Ninja). Some engine sources (CGAL) need 2-3 GB each to compile.")

if (NOT EXISTS "${ORCA_SOURCE_DIR}/src/libslic3r/CMakeLists.txt")
    message(FATAL_ERROR "OrcaSlicer sources not found at ${ORCA_SOURCE_DIR}. "
                        "Run: git submodule update --init --depth 1 third_party/OrcaSlicer")
endif ()
if (NOT ORCA_DEPS_PREFIX OR NOT EXISTS "${ORCA_DEPS_PREFIX}/include")
    message(FATAL_ERROR "ORCA_DEPS_PREFIX must point at the output of deps/scripts/build_deps.sh "
                        "(got '${ORCA_DEPS_PREFIX}').")
endif ()

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
if (NOT CMAKE_BUILD_TYPE)
    set(CMAKE_BUILD_TYPE Release)
endif ()

# Dependencies live in one prefix. The NDK toolchain restricts package lookup to
# CMAKE_FIND_ROOT_PATH, so the prefix goes there as well as CMAKE_PREFIX_PATH.
# libslic3r reads ${CMAKE_PREFIX_PATH}/lib/cmake/occt, so this must be exactly one path.
set(CMAKE_PREFIX_PATH "${ORCA_DEPS_PREFIX}")
list(APPEND CMAKE_FIND_ROOT_PATH "${ORCA_DEPS_PREFIX}")
list(APPEND CMAKE_MODULE_PATH "${ORCA_SOURCE_DIR}/cmake/modules")

# Google Play requires 16 KB aligned native libraries for apps targeting Android 15+.
if (ANDROID)
    string(APPEND CMAKE_SHARED_LINKER_FLAGS " -Wl,-z,max-page-size=16384")
    string(APPEND CMAKE_EXE_LINKER_FLAGS " -Wl,-z,max-page-size=16384")
endif ()

if (ORCA_MAX_COMPILE_JOBS)
    set_property(GLOBAL APPEND PROPERTY JOB_POOLS orca_compile=${ORCA_MAX_COMPILE_JOBS})
    set(CMAKE_JOB_POOL_COMPILE orca_compile)
endif ()

include(cmake/OrcaUpstream.cmake)

add_subdirectory(bridge)
