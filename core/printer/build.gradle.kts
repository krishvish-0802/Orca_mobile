plugins {
    alias(libs.plugins.orca.android.library)
    alias(libs.plugins.orca.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.orcamobile.core.printer"
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:domain"))
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
}
