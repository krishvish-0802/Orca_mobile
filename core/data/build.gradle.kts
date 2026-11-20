plugins {
    alias(libs.plugins.orca.android.library)
    alias(libs.plugins.orca.hilt)
}

android {
    namespace = "com.orcamobile.core.data"
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:domain"))
    implementation(libs.kotlinx.coroutines.android)
}
