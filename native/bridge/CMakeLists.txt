# orca_engine: the narrow C++ facade the app talks to. Everything above it
# (JNI, Kotlin) sees only these headers; libslic3r types never cross it.
add_library(orca_engine_core STATIC
    src/BrimEars.cpp
    src/ConfigBuilder.cpp
    src/ConfigBuilder.hpp
    src/Filaments.cpp
    src/Filaments.hpp
    src/ConfigOptions.cpp
    src/ConfigValues.hpp
    src/CutTool.cpp
    src/EmbossTool.cpp
    src/EngineInfo.cpp
    src/EngineEnvironment.cpp
    src/GCodePreview.cpp
    src/SliceJob.cpp
    src/JsonCodec.cpp
    src/LayerHeights.cpp
    src/MeasureTool.cpp
    src/MeshEdits.cpp
    src/MeshToolsCommon.hpp
    src/MeshToolsJson.cpp
    src/PaintSession.cpp
    src/PlateTools.cpp
    src/ProjectImpl.hpp
    src/ProjectObjects.cpp
    src/ProjectFilaments.cpp
    src/ProjectPlates.cpp
    src/PresetCatalog.cpp
    src/PresetCatalogImpl.hpp
    src/Project.cpp
)
target_include_directories(orca_engine_core
    PUBLIC  ${CMAKE_CURRENT_SOURCE_DIR}/include
    PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/src)
target_link_libraries(orca_engine_core PUBLIC libslic3r PRIVATE orca_vgcode_convert nlohmann_json boost_libs)
# GCodePreview.cpp includes upstream's LibVGCodeWrapper.hpp, whose includes are
# written relative to upstream's src/slic3r/GUI/LibVGCode.
target_include_directories(orca_engine_core PRIVATE "${ORCA_SOURCE_DIR}/src/libvgcode")
target_compile_definitions(orca_engine_core PRIVATE
    ORCA_UPSTREAM_COMMIT="${ORCA_UPSTREAM_COMMIT}")

if (ANDROID)
    find_library(ANDROID_LOG_LIB log)
    add_library(orca_engine SHARED
        jni/EngineJni.cpp
        jni/EngineState.cpp
        jni/EngineState.hpp
        jni/JniOnLoad.cpp
        jni/JniRegistry.hpp
        jni/JniUtils.hpp
        jni/MeshJni.cpp
        jni/ToolsJni.cpp
        jni/ObjectJni.cpp
        jni/PaintJni.cpp
        jni/PlateJni.cpp
        jni/PreviewJni.cpp
        jni/ProjectJni.cpp
        jni/ViewportJni.cpp)
    target_link_libraries(orca_engine PRIVATE orca_engine_core orca_viewport nlohmann_json ${ANDROID_LOG_LIB})
    # Only the JNI entry points are exported from the .so.
    set_target_properties(orca_engine PROPERTIES
        CXX_VISIBILITY_PRESET hidden
        VISIBILITY_INLINES_HIDDEN ON)
endif ()

if (ORCA_BUILD_CLI)
    add_executable(orca_slice_cli cli/orca_slice_cli.cpp)
    target_link_libraries(orca_slice_cli PRIVATE orca_engine_core)
endif ()
