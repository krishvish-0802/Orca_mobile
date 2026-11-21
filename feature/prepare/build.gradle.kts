plugins {
    alias(libs.plugins.orca.android.feature)
}

android {
    namespace = "com.orcamobile.feature.prepare"
}

dependencies {
    // The 3D plate component; it brings the native engine with it.
    implementation(project(":core:viewport"))
}
