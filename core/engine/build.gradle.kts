plugins {
    alias(libs.plugins.orca.android.library)
    alias(libs.plugins.orca.hilt)
    alias(libs.plugins.kotlin.serialization)
}

// The native engine (third_party/OrcaSlicer libslic3r + native/bridge) is built
// only with -Porca.native=true, because it needs the prebuilt Android
// dependency prefix from deps/scripts/build_deps.sh (-Porca.depsPrefix=...).
// Without it the module still builds and reports the engine as unavailable.
val nativeEnabled = providers.gradleProperty("orca.native").map(String::toBoolean).getOrElse(false)
val depsPrefix = providers.gradleProperty("orca.depsPrefix")
    .orElse(providers.environmentVariable("ORCA_DEPS_PREFIX"))
val maxCompileJobs = providers.gradleProperty("orca.maxCompileJobs").orNull
val useCcache = providers.gradleProperty("orca.ccache").map(String::toBoolean).getOrElse(false)
val abis = providers.gradleProperty("orca.abis").getOrElse("arm64-v8a").split(',').map(String::trim)

android {
    namespace = "com.orcamobile.core.engine"

    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
        buildConfigField("boolean", "NATIVE_ENGINE_BUNDLED", nativeEnabled.toString())
        if (nativeEnabled) {
            ndk { abiFilters += abis }
            externalNativeBuild {
                cmake {
                    val prefix = depsPrefix.orNull
                        ?: error("orca.native=true needs -Porca.depsPrefix=<deps install prefix> or ORCA_DEPS_PREFIX")
                    arguments += listOf(
                        "-DORCA_DEPS_PREFIX=$prefix",
                        "-DANDROID_STL=c++_shared",
                        "-DORCA_BUILD_CLI=OFF",
                    )
                    if (maxCompileJobs != null) arguments += "-DORCA_MAX_COMPILE_JOBS=$maxCompileJobs"
                    if (useCcache) {
                        arguments += listOf("-DCMAKE_C_COMPILER_LAUNCHER=ccache", "-DCMAKE_CXX_COMPILER_LAUNCHER=ccache")
                    }
                    targets += "orca_engine"
                }
            }
        }
    }

    buildFeatures { buildConfig = true }

    if (nativeEnabled) {
        // NDK r28+ links 16 KB-aligned libraries, which Google Play requires.
        ndkVersion = providers.gradleProperty("orca.ndkVersion").get()
        externalNativeBuild {
            cmake { path = file("../../native/CMakeLists.txt") }
        }
    }
}

// OrcaSlicer resources the engine reads at runtime, packed into one asset.
// Extend `includes` as later milestones need more of resources/.
val packEngineResources = tasks.register<PackEngineResourcesTask>("packEngineResources") {
    resourcesRoot.set(rootProject.layout.projectDirectory.dir("third_party/OrcaSlicer/resources"))
    includes.set(listOf("profiles/**/*.json"))
    outputDir.set(layout.buildDirectory.dir("generated/engineResources"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(packEngineResources, PackEngineResourcesTask::outputDir)
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:domain"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.kotlinx.coroutines.test)
}
