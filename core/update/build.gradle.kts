plugins {
    alias(libs.plugins.orca.android.library)
    alias(libs.plugins.orca.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.orcamobile.core.update"
    buildFeatures.buildConfig = true
    defaultConfig {
        // The release this build updates from: main's builds use latest-build, a
        // milestone branch's its own (see .github/workflows/native.yml).
        val tag = providers.gradleProperty("orca.updateTag").orNull ?: "latest-build"
        buildConfigField("String", "UPDATE_TAG", "\"$tag\"")
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:domain"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
}
