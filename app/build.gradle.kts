plugins {
    alias(libs.plugins.orca.android.application)
    alias(libs.plugins.orca.android.compose)
    alias(libs.plugins.orca.hilt)
}

android {
    namespace = "com.orcamobile.app"

    defaultConfig {
        applicationId = "com.orcamobile.app"
        versionCode = 1
        versionName = "0.1.0-m0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Signed with the debug key until a release keystore is set up.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    packaging {
        // libslic3r is large; keeping .so files uncompressed lets Android map them in place.
        jniLibs.useLegacyPackaging = false
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:designsystem"))
    // Implementations are wired here, the composition root; features see only interfaces.
    implementation(project(":core:data"))
    implementation(project(":core:engine"))
    implementation(project(":feature:home"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)
}
