# Builds OrcaSlicer's libslic3r without the desktop top-level CMakeLists.txt.
#
# Upstream's top-level project requires curl, OpenGL, GLFW and Python even with
# SLIC3R_GUI=OFF, none of which the engine uses. This file reproduces the part
# of that project libslic3r depends on (compile definitions, the boost_libs
# interface target, package lookups) and then adds the engine sources and the
# subset of deps_src the engine links. Keep it in step with upstream's
# CMakeLists.txt when the submodule is bumped.

include("${ORCA_SOURCE_DIR}/version.inc")
include(GNUInstallDirs)

set(SLIC3R_STATIC 1)
set(SLIC3R_GUI 0)
set(SLIC3R_PCH OFF)
set(SLIC3R_PROFILE OFF)
set(SLIC3R_RESOURCES_DIR "${ORCA_SOURCE_DIR}/resources")
set(IS_CROSS_COMPILE TRUE)
set(SLIC3R_ENC_CHECK OFF)

if (SLIC3R_CAD)
    add_compile_definitions(SLIC3R_CAD)
endif ()
add_compile_definitions(
    "BBL_RELEASE_TO_PUBLIC=$<CONFIG:Release>"
    "$<IF:$<CONFIG:Debug>,DEBUG,NDEBUG>")
add_compile_options(-fsigned-char)
# Third-party and upstream sources: warnings are upstream's business here.
add_compile_options(-w)

# Upstream helpers that libslic3r's CMake calls.
function(encoding_check TARGET)
endfunction()
function(slic3r_remap_configs targets from_Cfg to_Cfg)
endfunction()
include(PrecompiledHeader)

set(LIBDIR "${ORCA_SOURCE_DIR}/src")
set(LIBDIR_BIN "${CMAKE_BINARY_DIR}/src")
include_directories(SYSTEM "${LIBDIR}")
include_directories(SYSTEM "${LIBDIR_BIN}/dev-utils/platform")

# Boost: the same components and interface targets as upstream.
set(Boost_USE_STATIC_LIBS ON)
set(Boost_NO_SYSTEM_PATHS TRUE)
if (POLICY CMP0167)
    cmake_policy(SET CMP0167 NEW)
endif ()
find_package(Boost 1.83.0 REQUIRED COMPONENTS
    system filesystem thread log log_setup locale regex chrono atomic date_time iostreams program_options nowide)
add_library(boost_libs INTERFACE)
add_library(boost_headeronly INTERFACE)
target_include_directories(boost_headeronly SYSTEM INTERFACE ${Boost_INCLUDE_DIRS})
target_link_libraries(boost_libs INTERFACE boost_headeronly ${Boost_LIBRARIES})

find_package(Eigen3 5.0.1 REQUIRED)
set(TBB_STATIC 1)
find_package(TBB REQUIRED)
find_package(OpenSSL REQUIRED)
find_package(ZLIB REQUIRED)
find_package(EXPAT 2.10.0 REQUIRED)
find_package(PNG REQUIRED)
find_package(cereal REQUIRED)
if (NOT TARGET cereal::cereal)
    set_target_properties(cereal PROPERTIES IMPORTED_GLOBAL TRUE)
    add_library(cereal::cereal ALIAS cereal)
else ()
    set_target_properties(cereal::cereal PROPERTIES IMPORTED_GLOBAL TRUE)
endif ()
find_package(NLopt 1.4 REQUIRED)
# libnest2d links Boost::boost; upstream gets it from its own Boost lookup.
if (NOT TARGET Boost::boost)
    add_library(Boost::boost INTERFACE IMPORTED)
    target_include_directories(Boost::boost INTERFACE ${Boost_INCLUDE_DIRS})
endif ()

# OpenCV on Android installs its package config under sdk/native/jni.
if (ANDROID AND EXISTS "${ORCA_DEPS_PREFIX}/sdk/native/jni/OpenCVConfig.cmake")
    set(OpenCV_DIR "${ORCA_DEPS_PREFIX}/sdk/native/jni")
endif ()

# The deps_src libraries the engine links. Upstream also builds GUI-only ones
# (imgui, imguizmo, hidapi, md4c, mdns, hints) that Android does not need.
set(_orca_deps_src
    agg ankerl earcut fast_float nanosvg nlohmann stb_dxt
    admesh clipper2 glu-libtess libigl libnest2d mcut miniz minilzo qhull qoi semver)
foreach (_dir IN LISTS _orca_deps_src)
    add_subdirectory("${ORCA_SOURCE_DIR}/deps_src/${_dir}" "${CMAKE_BINARY_DIR}/deps_src/${_dir}")
endforeach ()

# Upstream's src/CMakeLists.txt looks this up right before adding libslic3r.
find_package(libnoise REQUIRED)
add_subdirectory("${ORCA_SOURCE_DIR}/src/libslic3r" "${CMAKE_BINARY_DIR}/src/libslic3r")

# Implementations libslic3r depends on that the desktop build compiles into its
# GUI. See native/upstream_glue/ for why each one is needed.
target_sources(libslic3r PRIVATE
    "${CMAKE_CURRENT_LIST_DIR}/../upstream_glue/NanoSvgImplementation.cpp")
target_link_libraries(libslic3r PRIVATE nanosvg)

# libvgcode: the G-code preview renderer, in its OpenGL ES 3.0 flavour (it loads
# GL entry points at run time with its bundled glad loader).
set(SLIC3R_OPENGL_ES ON)
add_subdirectory("${ORCA_SOURCE_DIR}/src/libvgcode" "${CMAKE_BINARY_DIR}/src/libvgcode")
# glad finds GLES entry points through eglGetProcAddress. Upstream ships no
# glad/egl.h, so use the platform's <EGL/egl.h> (the NDK has it) and libEGL.
target_compile_definitions(libvgcode PRIVATE GLAD_GLES2_USE_SYSTEM_EGL=1)
find_library(ORCA_EGL_LIB EGL)
if (ORCA_EGL_LIB)
    target_link_libraries(libvgcode PUBLIC ${ORCA_EGL_LIB} ${CMAKE_DL_LIBS})
endif ()

# Upstream's GCodeProcessorResult -> libvgcode conversion, which the desktop app
# keeps with its GUI. The glue include directory stands in for the one GUI
# header it includes; ${ORCA_SOURCE_DIR}/src/libvgcode resolves its
# "../../src/libvgcode/..." includes.
add_library(orca_vgcode_convert STATIC
    "${ORCA_SOURCE_DIR}/src/slic3r/GUI/LibVGCode/LibVGCodeWrapper.cpp")
target_include_directories(orca_vgcode_convert BEFORE PRIVATE
    "${CMAKE_CURRENT_LIST_DIR}/../upstream_glue/include")
target_include_directories(orca_vgcode_convert PRIVATE "${ORCA_SOURCE_DIR}/src/libvgcode")
target_link_libraries(orca_vgcode_convert PUBLIC libslic3r libvgcode)

# Commit of the pinned engine, reported in the app's About screen.
execute_process(
    COMMAND git -C "${ORCA_SOURCE_DIR}" rev-parse --short=12 HEAD
    OUTPUT_VARIABLE ORCA_UPSTREAM_COMMIT
    OUTPUT_STRIP_TRAILING_WHITESPACE
    ERROR_QUIET)
if (NOT ORCA_UPSTREAM_COMMIT)
    set(ORCA_UPSTREAM_COMMIT "unknown")
endif ()
