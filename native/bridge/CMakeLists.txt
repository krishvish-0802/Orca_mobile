# orca_engine: the narrow C++ facade the app talks to. Everything above it
# (JNI, Kotlin) sees only these headers; libslic3r types never cross it.
add_library(orca_engine_core STATIC
    src/ConfigBuilder.cpp
    src/ConfigBuilder.hpp
    src/EngineInfo.cpp
    src/EngineEnvironment.cpp
    src/SliceJob.cpp
    src/JsonCodec.cpp
)
target_include_directories(orca_engine_core
    PUBLIC  ${CMAKE_CURRENT_SOURCE_DIR}/include
    PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/src)
target_link_libraries(orca_engine_core PUBLIC libslic3r PRIVATE nlohmann_json boost_libs)
target_compile_definitions(orca_engine_core PRIVATE
    ORCA_UPSTREAM_COMMIT="${ORCA_UPSTREAM_COMMIT}")

if (ANDROID)
    find_library(ANDROID_LOG_LIB log)
    add_library(orca_engine SHARED jni/OrcaEngineJni.cpp jni/JniUtils.hpp)
    target_link_libraries(orca_engine PRIVATE orca_engine_core ${ANDROID_LOG_LIB})
    # Only the JNI entry points are exported from the .so.
    set_target_properties(orca_engine PROPERTIES
        CXX_VISIBILITY_PRESET hidden
        VISIBILITY_INLINES_HIDDEN ON)
endif ()

if (ORCA_BUILD_CLI)
    add_executable(orca_slice_cli cli/orca_slice_cli.cpp)
    target_link_libraries(orca_slice_cli PRIVATE orca_engine_core)
endif ()
