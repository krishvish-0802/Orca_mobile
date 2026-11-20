# Orca Mobile

A full 3D-printing slicer for **Android**, built on the slicing engine from [OrcaSlicer](https://github.com/SoftFever/OrcaSlicer).

The goal is to slice on the phone itself: load a model, pick printer, filament, and process profiles, slice it, preview the toolpaths, and export or send the G-code. No desktop or cloud service is needed.

> **Status: pre-alpha / planning.** This repository has no code yet. The build steps and structure below describe the planned design and will change.

---

## Why

Today you need a desktop to slice for most printers. Orca Mobile aims to:

- Run OrcaSlicer's `libslic3r` engine on-device through the Android NDK
- Reuse OrcaSlicer's printer, filament, and process profiles, so results match the desktop app
- Give you a touch-first UI for the common workflow, not a port of the full desktop UI

## Planned architecture

```
┌──────────────────────────────────────────┐
│  Android UI layer   (stack: TBD)         │  model view, profiles, settings,
│                                          │  G-code preview, export/send
├──────────────────────────────────────────┤
│  Native bridge      (JNI / FFI)          │  thin C API over the engine
├──────────────────────────────────────────┤
│  Slicing core       (C++17, NDK)         │  libslic3r from OrcaSlicer,
│                                          │  built for arm64-v8a
└──────────────────────────────────────────┘
```

| Layer | Responsibility | Notes |
|---|---|---|
| **Slicing core** | Mesh loading (STL/3MF/OBJ), slicing, G-code generation | Built from OrcaSlicer's `src/libslic3r`. The desktop GUI (`src/slic3r`, wxWidgets) is **not** used. |
| **Native bridge** | A small, stable C API: load model → apply config → slice → get G-code and preview data | Keeps the UI stack swappable |
| **UI** | Android app | Framework not chosen yet: Kotlin/Compose, Flutter, or React Native |

### Main technical challenges

- **Cross-compiling dependencies for Android.** `libslic3r` depends on Boost, TBB, CGAL (with GMP/MPFR), Eigen, OpenVDB, Clipper, Qhull, NLopt, cereal, expat, zlib, PNG/JPEG, and others. Some, such as OCCT for STEP import and OpenVDB, are heavy, so they may be dropped or made optional at first.
- **Memory and CPU limits.** Large or multi-plate projects need to fit in mobile RAM, and long slices must survive the app going to the background (e.g. run in a foreground service).
- **Toolpath preview.** Desktop preview uses OpenGL through `libvgcode`. Mobile needs OpenGL ES or Vulkan.
- **Profiles.** OrcaSlicer's `resources/profiles` must be bundled or downloaded, then kept in sync with upstream.

## Roadmap

- [ ] Choose the UI stack
- [ ] Cross-compile `libslic3r` and its minimum dependency set for `arm64-v8a`
- [ ] Write a native CLI/test harness that slices an STL to G-code on a device or emulator
- [ ] Define the C bridge API
- [ ] Build an MVP app: import STL → pick bundled profile → slice → save G-code
- [ ] Add a 3D model viewer and a layer-by-layer G-code preview
- [ ] Support 3MF projects and multiple plates
- [ ] Send to printer (OctoPrint / Moonraker / vendor protocols)
- [ ] Add profile management and import from OrcaSlicer desktop

## Prerequisites (expected)

These will be pinned once the build exists:

- Android Studio and Android SDK
- Android NDK (r26+), CMake 3.13+, and Ninja
- A Git checkout of [OrcaSlicer](https://github.com/SoftFever/OrcaSlicer) (reference: `2.6.0-dev`)
- A host machine with enough disk space and RAM to build Boost, CGAL, and TBB from source

## Getting started

There's nothing to build yet. To follow along or contribute:

```bash
git clone https://github.com/krishvish-0802/Orca_mobile.git
cd Orca_mobile
```

Build instructions will go here when the native core compiles.

## Project layout (planned)

```
Orca_mobile/
├── core/        # CMake project wrapping OrcaSlicer's libslic3r for Android
├── deps/        # Android cross-compile scripts for third-party libraries
├── bridge/      # C API + JNI/FFI bindings
├── app/         # Android application
├── profiles/    # Bundled printer / filament / process profiles
└── docs/        # Design notes
```

## Contributing

The project is at an early stage, and design discussion is welcome. Open an issue before starting large changes. Most early work will be in the native build: getting dependencies to cross-compile is the first milestone.

## License

OrcaSlicer is licensed under the **GNU Affero General Public License v3.0 (AGPL-3.0)**. This project builds on its code, so it is a derivative work and must also be distributed under **AGPL-3.0**. That means full corresponding source must be available to anyone who receives the app. A `LICENSE` file will be added with the first code commit.

## Acknowledgements

- [OrcaSlicer](https://github.com/SoftFever/OrcaSlicer) by SoftFever and contributors
- It builds on Bambu Studio, PrusaSlicer, and Slic3r. Thanks to those projects and their communities.

*Orca Mobile is an independent project and is not affiliated with or endorsed by the OrcaSlicer team.*
