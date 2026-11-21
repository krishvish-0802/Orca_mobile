# ADR 0003: Reuse upstream dependency recipes for Android

- Status: accepted (2026-11-21)

## Context

libslic3r needs Boost, TBB, CGAL with GMP/MPFR, Eigen, OCCT, OpenCV, Assimp, Draco, OpenSSL, NLopt, Qhull, cereal, expat, zlib, libpng, libjpeg-turbo, libnoise and SolveSpace. Upstream builds them through an ExternalProject superbuild (`deps/`), with one recipe per library, and the desktop top-level CMake requires curl, OpenGL, GLFW and Python even without the GUI.

## Decision

- `deps/CMakeLists.txt` includes upstream's recipes unchanged and forwards the NDK toolchain to them. It replaces only the autotools/perl recipes (GMP, MPFR, OpenSSL) with Android-aware versions in `deps/recipes/`, which keep upstream's URLs and hashes.
- Each installed dependency writes a marker whose name hashes its recipe. CI caches the prefix and saves it even when a run fails, so the next run rebuilds only what is missing or changed.
- `native/cmake/OrcaUpstream.cmake` reproduces the part of upstream's top-level project that libslic3r needs, instead of using that project.

## Consequences

- Bumping the submodule bumps the dependency versions too.
- `native/cmake/OrcaUpstream.cmake` must be reviewed against upstream's `CMakeLists.txt` on every bump (see CONTRIBUTING.md).
