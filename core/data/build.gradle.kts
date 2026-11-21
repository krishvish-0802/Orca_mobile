plugins {
    alias(libs.plugins.orca.android.library)
    alias(libs.plugins.orca.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.orcamobile.core.data"
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    implementation(project(":core:common"))
    implementation(project(":core:domain"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.datastore.preferences)
    testImplementation(libs.kotlinx.coroutines.test)
}
