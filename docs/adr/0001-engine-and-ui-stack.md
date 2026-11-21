# ADR 0001: Native OrcaSlicer engine with a Kotlin/Compose UI

- Status: accepted (2026-11-21)

## Context

The app must run every slicing step on the phone and produce the same G-code as desktop OrcaSlicer. OrcaSlicer's engine, libslic3r, is about 343,000 lines of C++ with no GUI dependency. Its desktop GUI (wxWidgets) cannot run on Android.

## Decision

- Build libslic3r unmodified with the Android NDK, pinned as a git submodule.
- Put a small C++ facade (`native/bridge`) between the engine and the app. It exposes JNI with JSON results.
- Write the UI in Kotlin with Jetpack Compose, structured as Clean Architecture with MVI view models and Hilt for dependency injection.
- Render the 3D viewport natively with OpenGL ES 3.0, reusing upstream's `libvgcode` for the G-code preview. This is planned for M1.

## Consequences

- Engine behaviour tracks upstream when the pin is bumped. The desktop wxWidgets GUI is rewritten for touch rather than ported.
- Rejected alternatives: Flutter, React Native, Filament-only rendering and game engines. The reasons are in `docs/proposal/tech-stack-and-architecture.md`, section 2.
