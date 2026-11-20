# ADR 0002: Pin a known-good Android toolchain

- Status: accepted (2026-11-21)

## Context

In November 2026 the current Android toolchain is AGP 9.x, Kotlin 2.4 and a Compose BOM that needs compileSdk 37. AGP 9 changed its DSL (built-in Kotlin, a new `compileSdk` syntax). The project's build can only be verified in CI, so every unknown in the build scripts costs a CI round trip.

## Decision

Pin AGP 8.13.0, Gradle 8.14.3, Kotlin 2.2.20, KSP 2.2.20-2.0.4, Hilt 2.57.1 and Compose BOM 2025.08.00, with compileSdk/targetSdk 36 and minSdk 29. All versions live in `gradle/libs.versions.toml`.

targetSdk 36 meets Google Play's requirement for new apps and updates from 31 August 2026.

## Consequences

- The build uses mature, documented APIs while the native work stabilizes.
- **Follow-up:** move to AGP 9 and the current Compose BOM once M1 builds are green. Use Android Studio's AGP Upgrade Assistant, then raise compileSdk to 37.
