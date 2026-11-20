# Orca Mobile: Android cross-compile superbuild for OrcaSlicer's third-party dependencies.
#
# Upstream OrcaSlicer ships one ExternalProject recipe per dependency under
# third_party/OrcaSlicer/deps/<Name>/<Name>.cmake. This superbuild reuses those
# recipes unchanged wherever they already cross-compile with a CMake toolchain
# file, and replaces only the autotools/perl based ones (GMP, MPFR, OpenSSL)
# with Android-aware recipes in ./recipes. Versions, URLs and hashes stay
# upstream's, so bumping the OrcaSlicer submodule bumps the dependencies too.
#
# Every dependency writes a marker into <prefix>/.orca-deps/ once installed.
# On the next configure, dependencies with a current marker are skipped, so a CI
# run that restores the prefix from cache resumes where the previous one stopped.
#
# Configure with the NDK toolchain, for example:
#   cmake -S deps -B <build> -G Ninja \
#     -DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK/build/cmake/android.toolchain.cmake \
#     -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-29
# deps/scripts/build_deps.sh wraps this.
cmake_minimum_required(VERSION 3.22)

if (POLICY CMP0135) # DOWNLOAD_EXTRACT_TIMESTAMP
    cmake_policy(SET CMP0135 NEW)
endif ()

project(OrcaMobileDeps LANGUAGES C CXX)

if (NOT ANDROID)
    message(FATAL_ERROR "This superbuild targets Android only. Pass the NDK toolchain file "
                        "(CMAKE_TOOLCHAIN_FILE=<ndk>/build/cmake/android.toolchain.cmake).")
endif ()

include(ExternalProject)
include(ProcessorCount)

set(ORCA_SOURCE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../third_party/OrcaSlicer"
    CACHE PATH "OrcaSlicer source tree (the pinned submodule)")
get_filename_component(ORCA_SOURCE_DIR "${ORCA_SOURCE_DIR}" ABSOLUTE)
set(UPSTREAM_DEPS_DIR "${ORCA_SOURCE_DIR}/deps")
if (NOT EXISTS "${UPSTREAM_DEPS_DIR}/Boost/Boost.cmake")
    message(FATAL_ERROR "OrcaSlicer sources not found at ${ORCA_SOURCE_DIR}. "
                        "Run: git submodule update --init --depth 1 third_party/OrcaSlicer")
endif ()

set(DESTDIR "${CMAKE_BINARY_DIR}/install" CACHE PATH "Install prefix for the built dependencies")
set(DEP_DOWNLOAD_DIR "${CMAKE_BINARY_DIR}/downloads" CACHE PATH "Download cache for source archives")
set(ORCA_DEPS_MARKER_DIR "${DESTDIR}/.orca-deps")
file(MAKE_DIRECTORY "${DESTDIR}" "${ORCA_DEPS_MARKER_DIR}")

# The dependency set mirrors what libslic3r links (see native/CMakeLists.txt).
option(SLIC3R_CAD "Build SolveSpace and the OCCT toolkits for the Design/CAD tab" ON)

if (NOT CMAKE_BUILD_TYPE)
    set(CMAKE_BUILD_TYPE Release)
endif ()

ProcessorCount(NPROC)
if (NPROC EQUAL 0)
    set(NPROC 1)
endif ()

find_package(Git REQUIRED)
set(PATCH_CMD ${GIT_EXECUTABLE} apply --verbose --ignore-space-change --whitespace=fix)

# Upstream recipes run `git apply --directory <rel>` when their build tree sits
# inside a git work tree. The build scripts keep the build directory outside any
# repository, so the plain form applies.
set(IN_GIT_REPO FALSE)

# Values upstream recipes read.
set(IS_CROSS_COMPILE TRUE)
set(DEPS_ARCH "${ANDROID_ABI}")
set(DEP_CMAKE_OPTS "-DCMAKE_POSITION_INDEPENDENT_CODE=ON")

# Autotools triple and NDK compiler wrappers for the non-CMake recipes.
if (ANDROID_ABI STREQUAL "arm64-v8a")
    set(TOOLCHAIN_PREFIX "aarch64-linux-android")
    set(OPENSSL_ANDROID_TARGET "android-arm64")
elseif (ANDROID_ABI STREQUAL "x86_64")
    set(TOOLCHAIN_PREFIX "x86_64-linux-android")
    set(OPENSSL_ANDROID_TARGET "android-x86_64")
else ()
    message(FATAL_ERROR "Unsupported ANDROID_ABI '${ANDROID_ABI}'. Use arm64-v8a or x86_64.")
endif ()
set(ANDROID_API_LEVEL "${ANDROID_PLATFORM_LEVEL}")
set(ANDROID_CC  "${ANDROID_TOOLCHAIN_ROOT}/bin/${TOOLCHAIN_PREFIX}${ANDROID_API_LEVEL}-clang")
set(ANDROID_CXX "${ANDROID_TOOLCHAIN_ROOT}/bin/${TOOLCHAIN_PREFIX}${ANDROID_API_LEVEL}-clang++")
set(ANDROID_AR  "${ANDROID_TOOLCHAIN_ROOT}/bin/llvm-ar")
set(ANDROID_RANLIB "${ANDROID_TOOLCHAIN_ROOT}/bin/llvm-ranlib")
set(ANDROID_STRIP "${ANDROID_TOOLCHAIN_ROOT}/bin/llvm-strip")
foreach (_tool ANDROID_CC ANDROID_CXX ANDROID_AR ANDROID_RANLIB)
    if (NOT EXISTS "${${_tool}}")
        message(FATAL_ERROR "NDK tool not found: ${${_tool}}")
    endif ()
endforeach ()

# Google Play requires 16 KB aligned native libraries for apps targeting Android 15+.
set(ORCA_ANDROID_LINKER_FLAGS "-Wl,-z,max-page-size=16384")

message(STATUS "Orca deps: ABI=${ANDROID_ABI} API=${ANDROID_API_LEVEL} prefix=${DESTDIR}")
message(STATUS "Orca deps: upstream recipes from ${UPSTREAM_DEPS_DIR}")

# Same contract as upstream's orcaslicer_add_cmake_project(), reduced to the
# single-config Ninja case and forwarding the Android toolchain.
function(orcaslicer_add_cmake_project projectname)
    cmake_parse_arguments(P_ARGS "FORWARD_CONFIG" "INSTALL_DIR;BUILD_COMMAND;INSTALL_COMMAND;SOURCE_DIR" "CMAKE_ARGS" ${ARGN})
    set(_source_dir_arg "")
    if (P_ARGS_SOURCE_DIR)
        set(_source_dir_arg SOURCE_DIR ${P_ARGS_SOURCE_DIR})
    endif ()
    ExternalProject_Add(
        dep_${projectname}
        EXCLUDE_FROM_ALL    ON
        INSTALL_DIR         ${DESTDIR}
        DOWNLOAD_DIR        ${DEP_DOWNLOAD_DIR}/${projectname}
        ${_source_dir_arg}
        CMAKE_GENERATOR     Ninja
        CMAKE_ARGS
            -DCMAKE_POLICY_VERSION_MINIMUM=3.10
            -DCMAKE_BUILD_TYPE:STRING=${CMAKE_BUILD_TYPE}
            -DCMAKE_INSTALL_PREFIX:STRING=${DESTDIR}
            -DCMAKE_MODULE_PATH:STRING=${ORCA_SOURCE_DIR}/cmake/modules
            -DCMAKE_PREFIX_PATH:STRING=${DESTDIR}
            -DCMAKE_FIND_ROOT_PATH:STRING=${DESTDIR}
            -DCMAKE_TOOLCHAIN_FILE:STRING=${CMAKE_TOOLCHAIN_FILE}
            -DANDROID_ABI:STRING=${ANDROID_ABI}
            -DANDROID_PLATFORM:STRING=${ANDROID_PLATFORM}
            -DANDROID_STL:STRING=c++_shared
            -DCMAKE_C_COMPILER_LAUNCHER:STRING=${CMAKE_C_COMPILER_LAUNCHER}
            -DCMAKE_CXX_COMPILER_LAUNCHER:STRING=${CMAKE_CXX_COMPILER_LAUNCHER}
            -DCMAKE_SHARED_LINKER_FLAGS:STRING=${ORCA_ANDROID_LINKER_FLAGS}
            -DCMAKE_EXE_LINKER_FLAGS:STRING=${ORCA_ANDROID_LINKER_FLAGS}
            -DBUILD_SHARED_LIBS:BOOL=OFF
            ${DEP_CMAKE_OPTS}
            ${P_ARGS_CMAKE_ARGS}
            ${ORCA_DEP_EXTRA_ARGS_${projectname}}
        ${P_ARGS_UNPARSED_ARGUMENTS}
        BUILD_COMMAND   ${CMAKE_COMMAND} --build . --config ${CMAKE_BUILD_TYPE} -- -j${NPROC}
        INSTALL_COMMAND ${CMAKE_COMMAND} --build . --target install --config ${CMAKE_BUILD_TYPE}
    )
endfunction()

# Adds one dependency. A dependency whose marker exists becomes an empty target,
# so DEPENDS clauses in other recipes keep working. The marker name carries a
# hash of the recipe and of any Android-specific arguments, so editing either
# rebuilds that dependency on the next run while the others stay cached.
set(_orca_dep_targets "")
function(orca_add_dep name recipe)
    file(READ "${recipe}" _recipe_text)
    string(MD5 _recipe_hash "${_recipe_text}|${ORCA_DEP_EXTRA_ARGS_${name}}|${ANDROID_ABI}|${ANDROID_PLATFORM}")
    string(SUBSTRING "${_recipe_hash}" 0 12 _recipe_hash)
    set(_marker "${ORCA_DEPS_MARKER_DIR}/${name}-${_recipe_hash}.done")
    if (EXISTS "${_marker}")
        message(STATUS "Orca deps: ${name} already installed, skipping")
        add_custom_target(dep_${name})
    else ()
        include("${recipe}")
        ExternalProject_Add_Step(dep_${name} orca_mark_installed
            DEPENDEES install
            COMMAND ${CMAKE_COMMAND} -E touch "${_marker}"
            COMMENT "Orca deps: ${name} installed")
    endif ()
    set(_orca_dep_targets ${_orca_dep_targets} dep_${name} PARENT_SCOPE)
endfunction()

# Android-only CMake arguments appended to an upstream recipe's own list.
# Later arguments win, so these override upstream values where they collide.
set(ORCA_DEP_EXTRA_ARGS_OpenCV
    -DBUILD_ANDROID_PROJECTS=OFF
    -DBUILD_ANDROID_EXAMPLES=OFF
    -DBUILD_ANDROID_SERVICE=OFF
    -DBUILD_FAT_JAVA_LIB=OFF
    -DWITH_ANDROID_MEDIANDK=OFF
    -DWITH_CAROTENE=OFF
    -DBUILD_KOTLIN_EXTENSIONS=OFF
    -DOPENCV_INSTALL_BINARIES_PREFIX=)
# oneTBB 2021.5 copies libc++_shared.so from the pre-r23 NDK layout when
# ANDROID_STL is c++_shared, and that path no longer exists. TBB is linked
# statically, and both runtimes share the same headers, so the static runtime is
# safe here; the final .so still links c++_shared.
set(ORCA_DEP_EXTRA_ARGS_TBB
    -DANDROID_STL=c++_static)

# Variables some recipes reference for their DEPENDS lists.
set(ZLIB_PKG dep_ZLIB)
set(PNG_PKG dep_PNG)
set(JPEG_PKG dep_JPEG)
set(EXPAT_PKG dep_EXPAT)

# Order matters only for readability; DEPENDS clauses carry the real ordering.
orca_add_dep(ZLIB     "${UPSTREAM_DEPS_DIR}/ZLIB/ZLIB.cmake")
orca_add_dep(PNG      "${UPSTREAM_DEPS_DIR}/PNG/PNG.cmake")
orca_add_dep(JPEG     "${UPSTREAM_DEPS_DIR}/JPEG/JPEG.cmake")
orca_add_dep(EXPAT    "${UPSTREAM_DEPS_DIR}/EXPAT/EXPAT.cmake")
orca_add_dep(Boost    "${UPSTREAM_DEPS_DIR}/Boost/Boost.cmake")
orca_add_dep(TBB      "${UPSTREAM_DEPS_DIR}/TBB/TBB.cmake")
orca_add_dep(Cereal   "${UPSTREAM_DEPS_DIR}/Cereal/Cereal.cmake")
orca_add_dep(Qhull    "${UPSTREAM_DEPS_DIR}/Qhull/Qhull.cmake")
orca_add_dep(GMP      "${CMAKE_CURRENT_SOURCE_DIR}/recipes/GMP.cmake")
orca_add_dep(MPFR     "${CMAKE_CURRENT_SOURCE_DIR}/recipes/MPFR.cmake")
orca_add_dep(Eigen    "${UPSTREAM_DEPS_DIR}/Eigen/Eigen.cmake")
orca_add_dep(CGAL     "${UPSTREAM_DEPS_DIR}/CGAL/CGAL.cmake")
orca_add_dep(NLopt    "${UPSTREAM_DEPS_DIR}/NLopt/NLopt.cmake")
orca_add_dep(libnoise "${UPSTREAM_DEPS_DIR}/libnoise/libnoise.cmake")
orca_add_dep(Draco    "${UPSTREAM_DEPS_DIR}/Draco/Draco.cmake")
orca_add_dep(OpenSSL  "${CMAKE_CURRENT_SOURCE_DIR}/recipes/OpenSSL.cmake")
orca_add_dep(Assimp   "${UPSTREAM_DEPS_DIR}/Assimp/Assimp.cmake")
orca_add_dep(OpenCV   "${UPSTREAM_DEPS_DIR}/OpenCV/OpenCV.cmake")
orca_add_dep(OCCT     "${UPSTREAM_DEPS_DIR}/OCCT/OCCT.cmake")
if (SLIC3R_CAD)
    orca_add_dep(SLVS "${UPSTREAM_DEPS_DIR}/SLVS/SLVS.cmake")
endif ()

add_custom_target(deps ALL DEPENDS ${_orca_dep_targets})
