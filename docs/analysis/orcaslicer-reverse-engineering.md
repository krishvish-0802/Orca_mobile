# OrcaSlicer: end-to-end analysis for the Android port

**Reference tree:** OrcaSlicer `2.6.0-dev` (`version.inc`, `SLIC3R_VERSION 02.08.01.55`), upstream commit `0c62aa0` (2026-11-20). This is the same version as the local copy in `Context/Repos/Orca Slicer/OrcaSlicer`.

**Method:** OrcaSlicer is open source, so the analysis reads its source directly. That is more precise than binary reverse engineering. It covers the CMake build graph, the slicing pipeline, the configuration system, the GUI subsystems, the network layer, the bundled resources and the design notes in `docs/HLSD/`. Binary reverse engineering (the REA toolkit) is only useful for the closed-source parts OrcaSlicer loads at runtime: Bambu's `bambu_networking` plugin and its camera library. Section 7 explains why the mobile app is designed so that it never depends on those parts.

---

## 1. Size and shape of the codebase

| Area | Path | Files | Lines (C/C++) | Mobile strategy |
|---|---|---:|---:|---|
| Slicing engine | `src/libslic3r` | 608 | ~343 K | **Reuse as is.** Cross-compile with the NDK. It does not use wxWidgets. |
| G-code preview library | `src/libvgcode` | ~35 | small | **Reuse.** It already ships OpenGL ES shaders (`ShadersES.hpp`, `ENABLE_OPENGL_ES`). |
| Desktop GUI | `src/slic3r/GUI` | 792 | ~477 K | **Rewrite** as a touch-first UI. Port its algorithms; drop the wxWidgets code. |
| Network / utilities | `src/slic3r/Utils` | 124 | ~43 K | **Re-implement** the protocols in Kotlin, using the C++ as the specification. |
| Bundled third-party sources | `deps_src` | 1 656 | ~451 K | Reuse, as libslic3r already does. |
| Tests | `tests` | 514 | ~182 K | Run the engine suites (Catch2) on device for each ABI. |
| Resources | `resources/` | — | 94 MB of profiles | Pack the profiles into an indexed asset bundle. |

**The key structural fact:** libslic3r has no wxWidgets includes (0 matches for `#include <wx`). Its only dependency on GUI code is one helper header, `slic3r/Utils/ColorSpaceConvert.hpp`, used by `FlushVolCalc.cpp`. The top-level CMake already supports an engine-only build (`SLIC3R_GUI=0`), and that is how the headless CLI (`src/OrcaSlicer.cpp`, `CLI::run`) is built. **So the whole slicer, around 343 K lines, can run natively on the phone without changes to its algorithms.**

---

## 2. Slicing pipeline (libslic3r)

```
Model (objects → volumes → instances, per-object config, layer ranges, painted facets)
   │  Print::apply(model, DynamicPrintConfig)  → which steps to invalidate (incremental)
   ▼
Print::process()
   ├─ for each PrintObject:
   │    posSlice              PrintObjectSlice.cpp   mesh → layer ExPolygons, per-region
   │    posPerimeters         PerimeterGenerator     classic or Arachne variable-width walls
   │    posEstimateCurledExtrusions
   │    posPrepareInfill      surface classification (top/bottom/internal/bridge)
   │    posInfill             Fill/* patterns
   │    posIroning
   │    posContouring
   │    posSupportMaterial    Support/*: normal, tree (TreeSupport / TreeSupport3D), organic
   │    posDetectOverhangsForLift, posSimplifyPath/Wall/Infill/SupportPath
   ├─ psWipeTower / psToolOrdering   multi-material tool ordering, prime tower
   ├─ psSkirtBrim                    skirt, brim, brim ears, painted brim
   ├─ psGCodeExport                  GCode.cpp → GCodeWriter, CoolingBuffer, FanMover,
   │                                 PressureEqualizer, SeamPlacer, ArcWelder, SpiralVase,
   │                                 AdaptivePA, Thumbnails, timelapse
   └─ psConflictCheck                ConflictChecker (multi-object collisions)
   ▼
GCodeProcessor  → GCodeProcessorResult (moves, roles, times per mode, filament use)
   ▼
libvgcode::GCodeInputData → toolpath preview
```

- Steps are **incremental.** `Print::apply()` compares the new model and config with the previous ones and invalidates only the affected steps, so the app can re-slice cheaply in the background after an edit.
- **Cancellation** works through `Print::cancel()` and `throw_if_canceled()`. Progress is reported through `PrintBase::set_status` callbacks. The bridge maps both directly to Kotlin coroutine cancellation and a progress `Flow`.
- Parallelism uses **TBB**. On phones the thread count should default to the number of performance cores to limit thermal throttling.
- **Pipeline plugin hooks** (`SlicingPipelineStepPlugin`) fire after each step for the Python plugin system.

### Engine feature inventory (all reusable through the bridge)

| Domain | Contents |
|---|---|
| Configuration | `PrintConfig.cpp` defines **906 options** (188 float, 161 bool, 124 float-vector, 82 string, 70 int, 57 enum, and others). Each `ConfigOptionDef` carries its label, tooltip, category, mode (simple/advanced/develop), min/max, enum labels and units. **That metadata is enough to generate the settings UI from data.** |
| Presets | `Preset`, `PresetBundle`, `PresetCacheFormat`: system, user and project presets for printer, filament and process. Inheritance, compatibility conditions, and import/export of bundles. |
| Formats | Import: STL, OBJ, AMF, 3MF (with Bambu/Orca extensions), STEP/STP (OCCT), SVG, DRC (Draco), and through Assimp more formats (FBX, GLB/glTF, …), plus ZIP archives. Export: G-code, G-code-3MF, 3MF project, STL/OBJ. |
| Walls | Classic and Arachne (`Arachne/*` skeletal trapezoidation); wall order, wall direction; the "cyclic" perimeter generator; "only one wall on top". |
| Infill (31 patterns) | Monotonic, monotonic line, rectilinear, aligned rectilinear, zig-zag, cross-zag, locked-zag, line, grid, triangles, stars, cubic, adaptive cubic, quarter cubic, support cubic, lightning, honeycomb, 3D honeycomb, lateral honeycomb, lateral lattice, cross-hatch, TPMS-D, TPMS-FK, gyroid, concentric, spiral inset, Hilbert curve, Archimedean chords, octagram spiral, and others. Also multiline infill, separated infills, infill corner smoothing. |
| Supports | Normal and tree supports (including organic `TreeSupport3D`), support spot generation, painted enforcers and blockers, interface patterns. |
| Seams | `SeamPlacer`, precise seam, seam alignment, scarf joints, painted seams. |
| G-code | 12 firmware flavors plus a no-extrusion mode (Marlin legacy, Marlin 2, Klipper, RepRapFirmware, Repetier, Smoothie, Mach3, Machinekit, Sailfish, Teacup, MakerWare, Sprinter). Cooling buffer, fan mover, pressure equalizer, adaptive pressure advance, arc fitting, spiral vase, small-area flow compensation, resonance avoidance, wipe tower (two implementations), timelapse, thumbnails, exclusion volumes, placeholder macro language (`PlaceholderParser`). |
| Multi-material | Tool ordering, filament grouping for multi-nozzle machines, flush-volume calculation and prediction, filament mixer, color decomposition, MMU segmentation of painted colors. |
| Mesh tools | Cut (`CutUtils`, `CutSurface`), mesh boolean (CGAL and MCUT), repair, simplify (quadric edge collapse), emboss text and SVG, measure, lay on face, orient, arrange (`libnest2d`), fill bed, texture displacement and baking, texture-to-color. |
| Calibration | 12 modes in `calib.hpp`: PA line, PA pattern, PA tower, auto PA line, flow rate, temperature tower, volumetric speed tower, VFA tower, retraction tower, input-shaping frequency, input-shaping damping, cornering. |
| Specialized machines | Belt printers (`Belt*`, `BeltKinematics`), IMEX/IDEX modes, per-nozzle settings, SLA (`SLAPrint`, supports, hollowing, pad). |
| CAD (`SLIC3R_CAD`) | A parametric "Design" tab: `CAD/SketchSolver` (SolveSpace), `GeometryEngine` (OCCT fillet/offset/boolean), thread standards, sketch import. |

---

## 3. GUI subsystems and how each maps to mobile

The desktop GUI is about 477 K lines of wxWidgets, OpenGL 3.2/ImGui and WebView code. None of it can run on Android directly, but most of what it *does* is call libslic3r. The table separates **orchestration logic to port** from **widget code to replace**.

| Desktop subsystem (file, size) | What it does | Mobile approach |
|---|---|---|
| `Plater.cpp` (24.8 K lines) | Central orchestrator: load, arrange, slice, export, send, undo/redo | Split across use cases in `core:domain`, coordinated by feature ViewModels |
| `PartPlate.cpp` (9.2 K) | **Multi-plate model.** Lives in the GUI layer, mixed with GL code | Move the non-GL plate logic into the C++ bridge so the UI layer stays thin |
| `GLCanvas3D.cpp` (14 K), `3DScene`, `GLModel`, `Camera` (778, 6 wx references) | Viewport, rendering, picking, hover | New native GLES 3.0 viewport (`native/viewport`). Port `Camera` and `SceneRaycaster`, which are almost free of wx |
| `Selection.cpp` (3.5 K) | Selection and transformations | Port the logic into the viewport and bridge |
| `Gizmos/*` (19 gizmos) | Move, rotate, scale, flatten (lay on face), cut, mesh boolean, measure, assembly, alignment, emboss (text), SVG, simplify, FDM supports paint, seam paint, MMU/color paint, fuzzy-skin paint, painted modifier, brim ears, texture displacement | Touch-first rewrite of the interaction; the algorithms already live in libslic3r. Painting reuses `TriangleSelector` |
| `GCodeViewer.cpp` (6.2 K) + `libvgcode` | Toolpath preview, legends, layer and move sliders | libvgcode runs unchanged on GLES. Legends and sliders become Compose UI |
| `Tab.cpp` (10.2 K) | Settings pages | **Generated from data:** a build-time extractor turns `Tab::build()` into a layout JSON (section 4) |
| `ConfigManipulation.cpp` (1.5 K, 232 toggle calls) | Enables, disables and validates options based on other values | Port the rules into the bridge as pure C++, then expose "option state" to the UI |
| `GUI_ObjectList.cpp` (7.5 K) | Object, part, modifier and negative-volume tree; per-object settings; layer ranges | Compose tree with bottom-sheet editors |
| `BackgroundSlicingProcess.cpp` (1 K) | Background apply → process → export thread | `SliceSession` in the bridge plus a foreground service on Android |
| `ConfigWizard`, `web/guide` | First-run printer and filament selection | Native Compose onboarding. The web pages could be reused in a WebView with a `window.wx` shim, but native UI is preferred |
| `CalibrationWizard*`, `calib_dlg` | Calibration flows | Compose wizard over the engine's `calib` generators |
| `DeviceManager` (6.2 K), `DeviceCore/*`, `Monitor*`, `SelectMachine` (7.3 K), `MultiMachine*` | Printer state, AMS/filament mapping, control, camera, HMS errors, multi-machine queue | `core:printer` with one adapter per ecosystem behind a common interface |
| `HttpServer`, `WebView*`, `ModelMall`, `OAuthDialog`, `UserManager` | Cloud login, model mall, web dialogs | Optional, later phase (Orca cloud) |
| Python plugins (`Plugins*`, `pybind11`, `deps/python3`) | User plugins, including pipeline hooks and printer agents | Later phase. CPython supports Android officially since 3.13, so embedding it is possible |

The web UI (`resources/web`, about 6 MB) talks to C++ through `window.wx.postMessage(JSON)` commands such as `get_recent_projects`, `update_custom_filaments` and `run_action`. Because that is a narrow interface, any page worth reusing can be hosted in an Android WebView with a small JavaScript bridge.

---

## 4. Settings UI: generated, not hand-written

The desktop settings tabs are regular C++:

```cpp
auto page = add_options_page(L("Quality"), ...);
auto optgroup = page->new_optgroup(L("Layer height"), ...);
optgroup->append_single_option_line("layer_height", "quality_settings_layer_height");
```

A prototype extractor (`tools/extract_tab_layout.py`, written during this analysis) already recovers:

| Tab | Pages | Groups | Options |
|---|---:|---:|---:|
| Process (`TabPrint`) | 7 (Quality, Strength, Speed, Support, Multimaterial, Others, Dependencies) | 46 | 383 |
| Filament (`TabFilament`) | 6 | 22 | 67 (partial; some lines use other append forms) |
| SLA material / SLA print | 3 / 7 | 6 / 11 | 8 / 40 |
| Printer (`TabPrinter`) | built in helper functions; the extractor needs extending | | |

**The design:** the native side exports a JSON schema from `PrintConfigDef` (type, label, tooltip, units, ranges, enum values, mode). A build-time tool generates the layout JSON from the pinned upstream `Tab.cpp`. Compose renders both. When upstream adds an option, the app picks it up on the next sync without UI code changes. Developers extend the app with new option types rather than by editing every page, which keeps it open for extension and closed for modification.

---

## 5. Resources

- `resources/profiles`: **73 vendor bundles and 14 813 JSON files (94 MB).** They are packed at build time into a compressed, indexed asset (the format follows `PresetCacheFormat`), and each vendor is expanded only when it is selected.
- `resources/calib`: calibration models and G-code for the 12 calibration modes.
- `resources/handy_models`: sample models (Benchy, Voron cube, tolerance test, …) in DRC/3MF format.
- `resources/shaders`: desktop GLSL 1.10/1.40, used as a reference for writing the ES 3.0 shaders.
- `resources/printers`, `hms`, `info`, `flush`, `filament_mixing`, `i18n` (translations), `fonts`, `textures`.

---

## 6. Third-party dependencies and Android portability

| Dependency | Used for | Android status |
|---|---|---|
| Boost | Everywhere (filesystem, log, thread, …) | Builds with the NDK (cross-compile with b2 or CMake) |
| oneTBB | Parallelism | Supported on Android upstream |
| CGAL (with GMP and MPFR) | Mesh booleans, repair, Voronoi utilities | CGAL is header-only; GMP and MPFR cross-compile with autotools |
| Eigen, cereal, nlohmann, fast_float, libigl | Math, serialization | Header-only |
| Clipper2, qhull, admesh, libnest2d, mcut, earcut, glu-libtess, miniz, qoi, semver, libnoise | Geometry and utilities | Bundled source; portable C/C++ |
| OpenCASCADE (OCCT) | STEP import, CAD tab | Has upstream Android build support. Heavy, so the plan stages it |
| OpenCV (core) | Image ops (textures, color) | Official Android SDK |
| Assimp, Draco | Extra mesh formats | Both build for Android |
| OpenSSL (crypto), zlib, libpng, libjpeg, expat | | Standard NDK builds |
| SolveSpace (SLVS) | CAD sketch solver | Portable C++ |
| OpenVDB, Blosc, OpenEXR | SLA hollowing only (`SLIC3R_OPENVDB` is OFF by default) | Skipped |
| wxWidgets, GLFW, WebView2, FFMPEG, libdatachannel, CURL, hidapi, wxInspector | Desktop GUI, video, networking | **Not needed.** Android SDK equivalents replace them |
| python3 / pybind11 | Plugins | Later phase (CPython on Android) |

The upstream superbuild (`deps/CMakeLists.txt`, with per-OS `deps-*.cmake` files) can take an `deps-android.cmake` alongside the existing Linux/macOS/MinGW/Windows variants, built with the NDK toolchain file for `arm64-v8a` (and `x86_64` for the emulator).

**Prior art confirms this is feasible:** OrcaXR cross-compiled OrcaSlicer 2.3.2's libslic3r for arm64-v8a with a Jetpack Compose XR front end, and Slice Beam is an Android slicer built on PrusaSlicer's engine. Neither covers OrcaSlicer's full feature set or phones as the main target.

---

## 7. Printer connectivity

OrcaSlicer has two layers:

1. **Print hosts** (`PrintHost` factory, upload-and-start): PrusaLink, PrusaConnect, OctoPrint, Duet, UltiMaker, FlashAir, AstroBox, Repetier, MKS, ESP3D, CrealityPrint, Obico, Flashforge, SimplyPrint, ElegooLink, 3DPrinterOS, Moonraker (**17 types**). They are built on `slic3r::Http` (libcurl), and most also use wx types.
2. **Printer agents** (`IPrinterAgent`, see `docs/HLSD/printer-agent.md`): two-way device control and monitoring through BBL, Moonraker, Orca, Qidi, Snapmaker and CrealityPrint agents. The payload dialect is currently Bambu-shaped JSON.

**Mobile plan:** one Kotlin `PrinterConnection` interface with one adapter per protocol (Strategy plus Factory), built on OkHttp, WebSockets, MQTT, FTPS and Android `NsdManager` for mDNS discovery. The C++ implementations serve as the protocol specification. USB-serial (OTG) to Marlin boards fits the same interface.

**Bambu Lab:** the desktop app `dlopen`s Bambu's proprietary `bambu_networking` plugin, which has no Android build. Since the 2025 firmware, third-party control needs either Bambu Connect or the printer's **Developer Mode** (LAN-only), which opens the MQTT channel, live stream and FTP. The app will support Developer Mode directly: MQTT over TLS with the LAN access code, FTPS upload, and the camera stream. It will **not** use certificates or keys extracted from Bambu's software. That rules out cloud printing to Bambu machines, which matches how other third-party tools work.

---

## 8. Constraints that shape the mobile design

- **Compatibility:** 3MF projects and profiles must round-trip with desktop OrcaSlicer, a rule upstream itself enforces. The test suite therefore compares G-code generated on the phone with G-code from the desktop CLI for the same inputs.
- **Memory and thermal limits:** large multi-plate projects, Arachne and tree supports are memory-heavy. Limit TBB concurrency, stream G-code to disk instead of holding it in memory, and run slices in a foreground service so they survive the app going to the background.
- **16 KB memory pages:** apps on Google Play that target Android 15 or later must ship 16 KB-aligned native libraries. That means NDK r28 or later (or the matching linker flags) for every dependency.
- **License:** OrcaSlicer is AGPL-3.0, so this app is AGPL-3.0 and must publish its full corresponding source.
