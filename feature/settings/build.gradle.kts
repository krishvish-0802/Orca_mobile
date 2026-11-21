plugins {
    alias(libs.plugins.orca.android.feature)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.orcamobile.feature.settings"
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
}
