# orca_viewport: the 3D plate view, drawn with OpenGL ES 3.0.
#
# Independent of libslic3r: it receives world-space triangles, a bed shape and
# toolpaths already converted for libvgcode (OrcaSlicer's G-code renderer, which
# draws them), and owns the camera. The JNI layer (native/bridge/jni) feeds it from the
# engine's Project, so meshes never travel through Kotlin.
add_library(orca_viewport STATIC
    include/orca_viewport/Viewport.hpp
    src/BedGeometry.cpp
    src/BedGeometry.hpp
    src/Camera.cpp
    src/Camera.hpp
    src/GlProgram.cpp
    src/GlProgram.hpp
    src/Shaders.hpp
    src/ToolpathRenderer.cpp
    src/ToolpathRenderer.hpp
    src/Viewport.cpp
)
target_include_directories(orca_viewport
    PUBLIC  ${CMAKE_CURRENT_SOURCE_DIR}/include
    PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/src ${ORCA_SOURCE_DIR}/src)
find_library(GLESV3_LIB GLESv3 REQUIRED)
find_library(ANDROID_LOG_LIB log)
target_link_libraries(orca_viewport PRIVATE Eigen3::Eigen libvgcode ${GLESV3_LIB} ${ANDROID_LOG_LIB})
