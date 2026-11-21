plugins {
    alias(libs.plugins.orca.android.application)
    alias(libs.plugins.orca.android.compose)
    alias(libs.plugins.orca.hilt)
}

android {
    namespace = "com.orcamobile.app"

    defaultConfig {
        applicationId = "com.orcamobile.app"
        // CI passes its run number, so each published build installs as an update.
        versionCode = providers.gradleProperty("orca.versionCode").orNull?.toInt() ?: 1
        versionName = "0.1.0-m1"
    }

    signingConfigs {
        // A fixed debug key, committed on purpose: every CI runner would otherwise
        // sign with a fresh one, and Android refuses an update signed differently.
        // Not a secret; never use it for a store release.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
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
    implementation(project(":core:printer"))
    implementation(project(":feature:home"))
    implementation(project(":feature:prepare"))
    implementation(project(":feature:settings"))
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)
}
