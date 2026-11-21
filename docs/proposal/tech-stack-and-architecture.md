# Orca Mobile: proposed tech stack and architecture

Status: **proposal, waiting for approval.** Nothing in this document has been built yet. It builds on [`../analysis/orcaslicer-reverse-engineering.md`](../analysis/orcaslicer-reverse-engineering.md).

## 1. Recommendation at a glance

| Layer | Choice | Why |
|---|---|---|
| Slicing engine | **OrcaSlicer `libslic3r` (C++17), unmodified, built with the Android NDK** for `arm64-v8a` (and `x86_64` for the emulator) | It is the real engine, so G-code matches desktop output. It has no GUI dependencies and already builds headless (`SLIC3R_GUI=0`) |
| Upstream integration | OrcaSlicer as a **git submodule pinned to a commit**, plus a small `native/patches/` set | Syncing with upstream becomes "bump the commit, rerun the parity tests" instead of maintaining a fork |
| Native bridge | **Thin C++ facade (`orca_engine`) exposed over JNI** | One narrow, stable boundary. Native objects stay native, and Kotlin holds opaque handles |
| 3D viewport | **Native C++ renderer on OpenGL ES 3.0**, drawn into a `SurfaceView` hosted in Compose | `libvgcode` (the G-code preview) already has ES shaders. Picking, painting (`TriangleSelector`) and gizmo math work on libslic3r data directly, without copying millions of triangles across JNI |
| UI | **Kotlin + Jetpack Compose (Material 3)**, single activity | The best native Android UI toolkit. Handles gestures through `pointerInput`, adaptive layouts for phones, foldables and tablets, and accessibility |
| Architecture | **Clean Architecture + MVI** (unidirectional data flow), multi-module Gradle | Clear layers and one-way dependencies, so each module can be tested in isolation |
| Concurrency | Kotlin **coroutines and Flow**. Slicing runs in a **foreground service** | Progress, cancellation and back-pressure come for free, and slices survive the app going to the background |
| Dependency injection | **Hilt** | The standard Android DI framework, with compile-time checking |
| Persistence | **Room** (projects index, printers, history), **DataStore** (preferences), app-specific storage (projects, G-code), **Storage Access Framework** for import and export | Standard and testable |
| Networking | **OkHttp** (+ WebSocket), **kotlinx.serialization**, **HiveMQ MQTT client** (Bambu), **Apache Commons Net** (FTPS), `NsdManager` (mDNS), **usb-serial-for-android** (USB/OTG Marlin) | Mature JVM libraries, so the C++ networking stack (libcurl, wx) can be left out |
| Camera | **Media3 / ExoPlayer** (RTSP), MJPEG decoder (Klipper / OctoPrint), Bambu JPEG-over-TLS stream | Hardware decoding |
| Build | Gradle Kotlin DSL + **version catalog**, CMake + Ninja through AGP `externalNativeBuild`, **NDK r28 or later** (16 KB page alignment) | Reproducible builds; complies with Play Store native-library rules |
| SDK levels | `minSdk 29` (Android 10), `targetSdk 36` (Android 16) | GLES 3.x is universal at API 29. Google Play requires new apps and updates to target API 36 from Aug 31, 2026 |
| Quality | ktlint + detekt, clang-tidy + clang-format (upstream configs), JUnit 5, Turbine, Compose UI tests, Catch2 on device, **G-code parity tests against the desktop CLI** | Correctness checked against the reference implementation |

## 2. Why this stack, and what else I considered

**The deciding constraint** is that the engine and the 3D viewport are C++, and the viewport needs low-latency access to engine data: meshes, painted facets, raycasts, toolpaths. Any UI toolkit therefore sits *beside* a native renderer, and the question is which toolkit best handles touch, a very large settings surface and Android integration (services, storage, USB, notifications).

| Option | Verdict |
|---|---|
| **Kotlin + Compose + native GLES viewport** | **Recommended.** No extra runtime. Direct JNI. First-class Android APIs (foreground services, SAF, USB host, NSD). Compose's `pointerInput` gives full multi-touch control. Domain and data modules are plain Kotlin, so a later iOS app could share them through Kotlin Multiplatform. |
| Flutter + `dart:ffi` | Good UI productivity and a credible iOS path. But the GL viewport has to go through a `Texture` widget or a platform view, each Android integration (USB serial, foreground service, NSD) needs a plugin or channel code, and it adds a second language and runtime. Not worth it for an Android-first app. |
| React Native | JS-bridge overhead, a weak story for native 3D surfaces, and the heaviest dependency surface. Rejected. |
| Compose Multiplatform for iOS now | Possible later. The module boundaries below keep that option open, but you asked for Android only. |
| Filament / SceneView instead of a custom GLES renderer | Excellent for PBR model viewing. But libvgcode can't run inside it, painting needs per-triangle state that would have to be duplicated, and every edit would copy mesh data across JNI. Rejected for the main viewport. It could still be used for a "pretty" model thumbnail mode later. |
| Unity / Godot | A huge runtime, poor fit for form-heavy UI, and awkward with AGPL. Rejected. |
| Vulkan | Not needed now. GLES 3.0 reuses libvgcode as is. The renderer sits behind an interface, so a Vulkan backend can be added later. |

## 3. Architecture

```
┌──────────────────────────────── Android app (Kotlin) ────────────────────────────────┐
│ feature:*   Compose screens + ViewModels (MVI: Intent → reducer → StateFlow<UiState>)│
│ core:designsystem   theme, components, gesture primitives                           │
│ core:domain   use cases + repository/service interfaces    ← pure Kotlin, no Android│
│ core:data / core:printer / core:engine / core:viewport   implementations           │
└──────────────┬───────────────────────────────────────┬──────────────────────────────┘
               │ JNI (opaque handles, DirectByteBuffers, callbacks)                       
┌──────────────▼──────────────┐   ┌────────────────────▼────────────────────┐
│ native/bridge  (C++17)      │   │ native/viewport  (C++17, GLES 3.0, EGL) │
│ orca_engine facade:         │   │ camera, scene, picking, gizmo rendering │
│  Project, Plate, Presets,   │◄──┤ paint brushes, libvgcode preview        │
│  SliceSession, Calibration, │   └─────────────────────────────────────────┘
│  Schema/OptionState export  │
└──────────────┬──────────────┘
┌──────────────▼───────────────────────────────────────────────────────────┐
│ third_party/OrcaSlicer (submodule): libslic3r, libvgcode, deps_src       │
│ deps/ (Android superbuild): Boost, TBB, CGAL+GMP+MPFR, OCCT, OpenCV, …   │
└──────────────────────────────────────────────────────────────────────────┘
```

### How the SOLID principles apply

- **Single responsibility:** one use case per action (`SliceActivePlate`, `ImportModel`, `SendToPrinter`, …). One adapter per printer protocol. The C++ facade classes each own one engine concept.
- **Open/closed:** the settings UI is generated from schema plus layout (see the analysis, section 4), so new upstream options need no new screens. Printer protocols register through a `PrinterConnectionFactory`; adding a new ecosystem means adding a class, not editing a `when` branch.
- **Liskov substitution:** every `PrinterConnection` honors the same contract (discover → connect → upload → start → observe). A conformance test suite runs against each adapter.
- **Interface segregation:** printer capabilities are small interfaces (`FileUpload`, `JobControl`, `TemperatureControl`, `CameraStream`, `FilamentSystem`, …). The UI asks an adapter for a capability instead of assuming one large interface.
- **Dependency inversion:** features depend only on `core:domain` interfaces. JNI, Room and OkHttp live behind them and are injected by Hilt, so ViewModels can be tested with fakes and no device.

### Native bridge (sketch)

```cpp
// native/bridge/include/orca_engine/Project.hpp — C++ facade, wrapped by JNI glue
namespace orca::engine {
class Project {                       // owns Slic3r::Model + plates + per-object config
 public:
  ImportResult import(const std::string& path, ImportOptions);
  ObjectId     add_primitive(Primitive);
  void         transform(Selection, const Transform3d&);
  void         arrange(PlateId, ArrangeParams);  void orient(Selection);
  void         cut(ObjectId, const CutPlane&, CutOptions);
  // painting, modifiers, layer ranges, mesh boolean, emboss, … one method family per tool
  UndoToken    snapshot(std::string_view label);
};
class SliceSession {                  // wraps Print / BackgroundSlicingProcess semantics
 public:
  void apply(const Project&, const ResolvedConfig&);   // incremental invalidation
  SliceResult run(ProgressSink&, CancelToken&);         // posSlice … psGCodeExport
  GCodePreviewData preview() const;                     // → libvgcode
};
class ConfigSchema { std::string export_json() const; }; // from PrintConfigDef metadata
class OptionStateEvaluator { OptionStates evaluate(const DynamicPrintConfig&) const; }; // ported ConfigManipulation
}
```

The JNI layer is generated code plus thin glue. Kotlin sees `NativeHandle`-backed classes that close through `AutoCloseable`, with a `Cleaner` as a safety net.

## 4. Gestures

The viewport handles raw multi-touch itself, so these gestures never conflict with Compose scrolling. Every gesture also has an on-screen equivalent (numeric transform panel, buttons) for accessibility and precision.

### Prepare (3D plate)

| Gesture | No object selected | Object selected |
|---|---|---|
| **1-finger drag on empty space** | Orbit camera | Orbit camera |
| **1-finger drag starting on the selected object** | — | **Move the object on the bed (XY)** by ray-plane intersection. Snaps to grid and to other objects, with a haptic tick |
| **2-finger drag** | Pan camera | Pan camera |
| **Pinch** | Zoom toward the point between the fingers | Same. Pinch *on a scale handle* scales uniformly |
| **2-finger twist** | — | **Rotate the object around Z** (the bed normal), with 15° detents and haptics |
| **Tap** | Select the object under the finger | Tap another object to change the selection, or empty space to deselect |
| **Long-press, then tap** | — | Add to or remove from a multi-selection |
| **Double-tap** | Fit the plate in view | Zoom to the object |
| **Long-press on an object** | Context sheet: duplicate, delete, split, add part/modifier/negative volume, filament, per-object settings | Same |
| **Drag a gizmo handle** (≥ 48 dp hit area) | — | Move along an axis or Z, rotate around an axis, or scale an axis |
| **Lay-on-face mode: tap a face** | — | Rotate so that face rests on the bed |
| **2-finger tap / 3-finger tap** | Undo / Redo | Undo / Redo |
| **Swipe between plates** (plate strip) | Change the active plate | Long-press and drag an object onto another plate |

### Paint tools (supports, seam, color/MMU, fuzzy skin, painted modifiers)

- **1 finger paints, 2 fingers navigate,** so painting never fights the camera.
- **Stylus (S Pen and others):** pressure sets brush size. The side button erases. Hover shows a brush cursor. Palm rejection ignores touches while a stylus is in range.
- Brush and smart-fill (`TriangleSelector`) options sit in a bottom toolbar. A two-finger tap undoes the last stroke.

### Preview (G-code)

- Orbit, pan and zoom work as in Prepare.
- A vertical layer slider (range thumbs) sits on the edge. Dragging along it scrubs layers. A horizontal move slider covers moves inside a layer, with a play button.
- Tap a toolpath to see its feature type, speed, flow and layer time.

On ChromeOS, DeX, or with a mouse and keyboard, input falls back to desktop OrcaSlicer conventions (left-drag orbit, right/middle-drag pan, wheel zoom, its keyboard shortcuts).

## 5. Directory layout

```
Orca_mobile/
├── app/                       # composition root: Activity, navigation graph, Hilt setup
├── core/
│   ├── common/                # Result/error types, dispatchers, logging (pure Kotlin)
│   ├── model/                 # domain entities: Project, Plate, Preset, Printer, Job …
│   ├── domain/                # use cases + repository/service interfaces (pure Kotlin)
│   ├── data/                  # Room, DataStore, file storage, preset bundle repository
│   ├── engine/                # Kotlin API over JNI + packaging of the native libs
│   ├── viewport/              # Compose host for the native surface + gesture → command mapping
│   ├── printer/               # PrinterConnection API, discovery, one package per protocol
│   ├── designsystem/          # Material 3 theme, components, settings field renderers
│   └── testing/               # fakes, test rules, golden-file helpers
├── feature/
│   ├── home/  prepare/  preview/  objects/  settings/  profiles/
│   ├── device/  calibration/  onboarding/  (later: design/  plugins/)
├── native/
│   ├── bridge/                # orca_engine facade + JNI glue + CLI test harness
│   ├── viewport/              # GLES renderer, camera, picking, gizmos, libvgcode host
│   ├── cmake/                 # toolchain helpers, find-modules
│   └── patches/               # minimal upstream patches (each documented)
├── deps/                      # Android cross-compile superbuild + prebuilt cache scripts
├── third_party/OrcaSlicer/    # git submodule, pinned
├── profiles/                  # build step: resources/profiles → indexed asset bundle
├── tools/                     # generators: settings schema/layout extraction, sync scripts
├── docs/                      # analysis/, proposal/, architecture/, adr/ (decision records)
└── .github/workflows/         # deps build (cached), native tests, app build, parity tests
```

## 6. Delivery plan

The engine is reused, so the work is the native build, the bridge, and rewriting a ~477 K-line desktop GUI as a touch UI. The plan delivers a usable app early and then works toward full parity.

| Milestone | Scope | Done when |
|---|---|---|
| **M0: Foundation** | Android deps superbuild, libslic3r for arm64, on-device CLI harness, CI, parity test rig, Gradle skeleton with all modules | An STL slices on a phone and the G-code matches desktop output for the reference set |
| **M1: Core loop (MVP)** | Import STL/3MF/OBJ/STEP, bundled system profiles + user presets, basic plate view with gestures, slice in a foreground service, libvgcode preview, export, send to Moonraker / OctoPrint / Bambu Developer Mode | Import → slice → preview → print works end to end on a real printer |
| **M2: Prepare parity** | Multi-plate, arrange/orient/fill bed, all transform gizmos, object list (parts, modifiers, negative volumes), per-object and layer-range settings, variable layer height, cut, mesh boolean, simplify, measure, assembly/alignment, emboss text, SVG, brim ears, all paint tools | Every desktop gizmo has a touch version |
| **M3: Settings parity** | Generated settings UI for all ~900 options, option-state rules, search, compare/diff, unsaved-changes flow, bundle import/export, setup wizard | Any desktop profile edits and round-trips identically |
| **M4: Devices** | All 17 print hosts and printer agents, monitoring, control, camera, filament/AMS mapping, multi-machine queue, notifications, USB-serial | Every protocol passes the conformance suite |
| **M5: Calibration** | All 12 calibration modes and the calibration wizard | Same output as the desktop calibration generators |
| **M6: Advanced** | Design/CAD tab (OCCT + SolveSpace), texture displacement/bake, texture-to-color, belt and IMEX machines, Python plugins (CPython on Android), Orca cloud | Feature-complete against the pinned upstream version |

## 7. Risks and mitigations

| Risk | Mitigation |
|---|---|
| Cross-compiling heavy dependencies (CGAL with GMP/MPFR, OCCT, OpenCV) | Build them once in CI and cache them as versioned prebuilt archives. Stage OCCT behind STEP and CAD features |
| Memory pressure on large projects | Measure peak RSS per test model in CI. Cap TBB threads. Stream G-code to disk. Warn before slicing very large models |
| Thermal throttling and battery drain | Use performance cores only. Offer a "low-power slicing" option. Let incremental re-slicing avoid unnecessary work |
| Behavior drift from upstream | Pin the submodule. Run G-code parity tests against the desktop CLI on every bump. Generate the settings UI from upstream metadata |
| Bambu ecosystem lock-down | Support Developer Mode only. Make no use of proprietary plugins or extracted keys. Document the limitation in the app |
| AGPL obligations | Publish the source. Show license and source links in the app's About screen |
