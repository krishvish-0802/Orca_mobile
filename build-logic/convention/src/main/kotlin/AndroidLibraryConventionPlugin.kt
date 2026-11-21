import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.library")
        pluginManager.apply("org.jetbrains.kotlin.android")
        extensions.configure<LibraryExtension> {
            configureKotlinAndroid(this)
            testOptions.targetSdk = libs.versionInt("targetSdk")
            lint.targetSdk = libs.versionInt("targetSdk")
        }
        dependencies {
            add("testImplementation", libs.library("junit"))
            add("testImplementation", libs.library("kotlin-test"))
        }
    }
}
