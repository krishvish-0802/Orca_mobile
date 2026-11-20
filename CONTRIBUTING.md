# Contributing to Orca Mobile

This guide covers how the code is organized and the rules that keep it that way. The reasoning behind the stack is in [docs/proposal/tech-stack-and-architecture.md](docs/proposal/tech-stack-and-architecture.md), and individual decisions are recorded in [docs/adr/](docs/adr/).

## Architecture rules

### Module dependencies

```
app ──► feature:* ──► core:domain ◄── core:data
                         ▲   ▲   ◄── core:engine
                         │   └── core:model
feature:* ──► core:designsystem
everything may use core:common and core:model
```

- **`core:model` and `core:domain` are plain Kotlin** (no Android imports). Domain code is testable on the JVM and could later be shared with other platforms.
- **Features depend on interfaces, never on implementations.** A feature module may use `core:domain`, `core:model`, `core:common` and `core:designsystem`. It must not depend on `core:data`, `core:engine` or another feature. The `orca.android.feature` convention plugin sets this up.
- **`app` is the composition root.** It is the only module that depends on implementation modules, and Hilt binds them there.
- **Features don't depend on each other.** Shared UI goes in `core:designsystem` and shared logic in `core:domain`.

### SOLID in practice

- **Single responsibility:** one use case per user action (`SliceSampleModelUseCase`). One class per concern on the native side too: `ConfigBuilder` builds configs, `SliceJob` runs slices, `JsonCodec` serializes.
- **Open/closed:** add new behaviour by adding a class behind an existing interface, for example a new `PrinterConnection` adapter, rather than by growing a `when`.
- **Liskov substitution:** every implementation of an interface must pass that interface's test suite. Fakes in tests honour the same contracts as the real classes.
- **Interface segregation:** keep interfaces small and capability-shaped (`SampleModelRepository`, `WorkspaceRepository`), not one "repository of everything".
- **Dependency inversion:** high-level code receives interfaces through constructors (`@Inject constructor`). Never call `Dispatchers.IO` directly; inject `CoroutineDispatchers`.

### UI: MVI

Each screen has a `<Name>Contract.kt` with its `UiState` and `Intent` types, a `<Name>ViewModel` that exposes `StateFlow<UiState>` and handles `onIntent`, and a stateless `<Name>Screen` composable plus a `<Name>Route` that connects it to the view model. Screens render state and send intents. They hold no logic.

### Native code

- **The bridge is the boundary.** Only `native/bridge/include/orca_engine/*.hpp` is visible to the JNI layer. libslic3r types never appear in those headers.
- **JNI stays thin.** Natives are registered in `JNI_OnLoad`. Results that can fail cross as JSON (`JsonCodec.cpp` on the C++ side, `EngineJson.kt` on the Kotlin side), and both sides are updated together.
- **Never let a C++ exception or a Java exception cross the boundary.** `run_slice` catches engine exceptions, and the JNI progress sink clears Java exceptions.
- **Don't modify upstream sources.** When the engine needs a change for Android, add a documented patch under `native/patches/` and an ADR explaining why.

## Code style

- **Kotlin:** the official Kotlin style, 120 columns, trailing commas (`.editorconfig` configures this).
- **C++:** C++17, upstream's `.clang-format`. `PascalCase` types, `snake_case` functions and variables, `m_` prefix for members, `#pragma once`, include what you use.
- **Comments say why**, not what. Public APIs get KDoc or Doxygen-style comments.
- **User-visible strings go in `res/values/strings.xml`.**

## Tests

- Domain use cases and view models get JVM unit tests with fakes (see `SliceSampleModelUseCaseTest`, `HomeViewModelTest`).
- JNI payload decoding is tested against the exact JSON the C++ side produces (`EngineJsonTest`).
- Engine output is checked against desktop OrcaSlicer with G-code parity tests, which are planned for later in M0.

Run `./gradlew test lint` before you push.

## Upstream updates

OrcaSlicer is a pinned git submodule. To update it:

1. Bump `third_party/OrcaSlicer` to the new commit.
2. Compare upstream's top-level `CMakeLists.txt` against `native/cmake/OrcaUpstream.cmake`, and its `deps/` recipes against `deps/CMakeLists.txt`.
3. Run the `Native engine` workflow and the parity tests.
4. Regenerate anything that comes from upstream sources (for example `tools/extract_tab_layout.py`).

## Commits and pull requests

- Work on a branch, then open a pull request against `Build-baseline-code`.
- Write commit subjects in the imperative and under 72 characters ("Add Moonraker upload adapter"). Use the body to explain why.
- Keep each PR to one concern. CI (`Android CI`, and `Native engine` when native code changes) must pass.
