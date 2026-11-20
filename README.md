# Orca Mobile

A full 3D-printing slicer for **Android**, built on the slicing engine from [OrcaSlicer](https://github.com/SoftFever/OrcaSlicer).

The goal is to slice on the phone itself: load a model, pick printer, filament and process profiles, slice it, preview the toolpaths, and send the G-code to the printer. No desktop or cloud service is needed.

> **Status: M0 (foundation) in progress.** The repository has the module skeleton, the Android dependency build, the native bridge and CI. See [the plan](docs/proposal/tech-stack-and-architecture.md) for milestones M0–M6.

## How it fits together

```
Android app (Kotlin, Jetpack Compose)        app/, feature/*, core/*
        │  JNI: a few functions, JSON results
native/bridge (C++17): orca_engine facade    native/
        │
OrcaSlicer libslic3r, unmodified             third_party/OrcaSlicer (git submodule, pinned)
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
│   ├── data/             repository implementations (files, bundled samples)
│   ├── engine/           SlicingEngine backed by the native engine (JNI)
│   └── designsystem/     theme and shared Compose components
├── feature/
│   └── home/             start screen; currently the on-device engine check
├── native/
│   ├── CMakeLists.txt    builds libslic3r without the desktop top-level project
│   ├── cmake/            upstream compatibility layer
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

The `Native engine` GitHub workflow runs the same steps, caches the dependency prefix between runs, and uploads an APK with the engine. To try it on a phone, download the `orca-mobile-debug-arm64-v8a` artifact from the workflow run and install it. Then tap **Slice test cube** on the start screen.

## License

OrcaSlicer is licensed under the **GNU Affero General Public License v3.0**. This project builds on its code, so it is a derivative work and is distributed under the same license: see [LICENSE](LICENSE). Anyone who receives the app must be able to get its full source.

## Acknowledgements

- [OrcaSlicer](https://github.com/SoftFever/OrcaSlicer) by SoftFever and contributors, which builds on Bambu Studio, PrusaSlicer and Slic3r.

*Orca Mobile is an independent project and is not affiliated with or endorsed by the OrcaSlicer team.*
