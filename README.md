# Orca Mobile

A full 3D-printing slicer for **Android**, built on the slicing engine from [OrcaSlicer](https://github.com/SoftFever/OrcaSlicer).

The goal is to slice on the phone itself: load a model, pick printer, filament and process profiles, slice it, preview the toolpaths, and send the G-code to the printer. No desktop or cloud service is needed.

**Try it:** on an Android 10+ phone, download and install [orca-mobile-arm64-v8a.apk](https://github.com/krishvish-0802/Orca_mobile/releases/download/latest-build/orca-mobile-arm64-v8a.apk), the latest build of `main` that passed CI. Later builds install over it.

> **Status: M1 (first slice on the phone) in progress.** Working today: OrcaSlicer's system printer, process and filament profiles; opening STL, 3MF, OBJ and STEP files; a 3D plate with touch gestures (orbit, pan, pinch zoom, tap to select, drag to move, twist to turn); slicing on the phone; a G-code preview drawn by OrcaSlicer's own renderer, with a layer range and coloring by line type, speed, height and more; and sending the G-code to Klipper (Moonraker) or OctoPrint. See [the plan](docs/proposal/tech-stack-and-architecture.md) for milestones M0–M6.

## How it fits together

```
Android app (Kotlin, Jetpack Compose)        app/, feature/*, core/*
        │  JNI: a few functions, JSON results
native/bridge (C++17): orca_engine facade    native/bridge, native/viewport
        │
OrcaSlicer libslic3r + libvgcode, unmodified third_party/OrcaSlicer (git submodule, pinned)
        │
Boost, TBB, CGAL, OCCT, OpenCV, …            deps/ (built with the Android NDK)
```

- **The engine is OrcaSlicer's own code**, built for `arm64-v8a`. Nothing is re-implemented, so G-code matches the desktop app.
- **The bridge is the only C++ the app calls.** It hides libslic3r types behind `orca_engine/*.hpp` and returns JSON across JNI.
- **The UI is Kotlin + Compose**, structured as Clean Architecture with MVI view models. See [CONTRIBUTING.md](CONTRIBUTING.md) for the module rules.

## Repository layout

```
Orca_mobile/
├── app/                  composition root: Activity, Application, Hilt wiring
├── core/
│   ├── common/           coroutine dispatchers and other plain-Kotlin helpers
│   ├── model/            domain types (EngineInfo, SliceRequest, SliceResult, …)
│   ├── domain/           use cases + interfaces (SlicingEngine, repositories)
│   ├── data/             repository implementations (files, imports, settings)
│   ├── engine/           slicing, presets, projects and previews over the native engine (JNI)
│   ├── printer/          print hosts: Moonraker, OctoPrint
│   ├── viewport/         the 3D plate composable and its touch gestures
│   └── designsystem/     theme and shared Compose components
├── feature/
│   ├── home/             start screen: engine status, printer choice, open a model
│   ├── prepare/          plate, slicing, G-code preview, sending to the printer
│   └── settings/         print settings over the chosen presets
├── native/
│   ├── CMakeLists.txt    builds libslic3r without the desktop top-level project
│   ├── cmake/            upstream compatibility layer
│   ├── upstream_glue/    small pieces upstream keeps in its desktop GUI
│   ├── viewport/         OpenGL ES 3.0 plate view: bed, models, toolpaths, picking
│   └── bridge/           orca_engine facade, JNI glue, orca_slice_cli harness
├── deps/                 Android superbuild reusing upstream dependency recipes
├── third_party/OrcaSlicer   pinned upstream sources (submodule)
├── build-logic/          Gradle convention plugins
├── tools/                generators (settings layout extraction, …)
├── ci/                   CI helper scripts
└── docs/                 analysis, proposal, architecture decision records
```

## Building

### Kotlin layers only (fast)

Needs JDK 17 and the Android SDK (Android Studio installs both).

```bash
git clone https://github.com/krishvish-0802/Orca_mobile.git
cd Orca_mobile
./gradlew assembleDebug test
```

This build reports the engine as unavailable, because it does not include the native library.

### With the native engine

Also needs Android NDK r28 or newer, CMake 3.22+, Ninja, and autoconf/automake/libtool (for GMP/MPFR). The dependency build takes a few hours the first time.

```bash
git submodule update --init --depth 1 third_party/OrcaSlicer
export ANDROID_NDK_HOME=/path/to/ndk/28.x
deps/scripts/build_deps.sh --abi arm64-v8a --prefix "$HOME/orca-deps/arm64-v8a"
./gradlew :app:assembleDebug -Porca.native=true -Porca.depsPrefix="$HOME/orca-deps/arm64-v8a"
```

The engine is always compiled optimized, even in the debug app. If Gradle's bundled CMake is older than the one that built the dependencies, point it at yours with `cmake.dir=/path/to/cmake` in `local.properties`. A library built separately can be packaged instead with `-Porca.prebuiltEngineDir=<dir>` (holding `<abi>/liborca_engine.so` and `libc++_shared.so`), which is what CI does.

The `Native engine` GitHub workflow builds the dependencies (cached between runs), the engine, an APK for each ABI, and slices test models on an emulator. Each build of `main` that passes the emulator test replaces the APKs on the [latest-build](https://github.com/krishvish-0802/Orca_mobile/releases/tag/latest-build) pre-release. Builds are signed with the committed `app/debug.keystore` so they install over each other; it is a debug key, not for store releases.

## License

OrcaSlicer is licensed under the **GNU Affero General Public License v3.0**. This project builds on its code, so it is a derivative work and is distributed under the same license: see [LICENSE](LICENSE). Anyone who receives the app must be able to get its full source.

## Acknowledgements

- [OrcaSlicer](https://github.com/SoftFever/OrcaSlicer) by SoftFever and contributors, which builds on Bambu Studio, PrusaSlicer and Slic3r.
- The look of the app (grouped rounded lists, pill buttons, bold section titles) takes its inspiration from [Mochy Music](https://github.com/realidkroo/Mochy-Music) by realidkroo. No code is copied from it.

*Orca Mobile is an independent project and is not affiliated with or endorsed by the OrcaSlicer team.*
