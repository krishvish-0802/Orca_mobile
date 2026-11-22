plugins {
    alias(libs.plugins.orca.android.library)
    alias(libs.plugins.orca.android.compose)
    alias(libs.plugins.orca.hilt)
}

android {
    namespace = "com.orcamobile.core.viewport"
}

// The 3D plate view. Its natives live in :core:engine's liborca_engine.so
// (native/viewport), which also owns the projects it draws.
dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:engine"))
    implementation(libs.kotlinx.coroutines.android)
}
