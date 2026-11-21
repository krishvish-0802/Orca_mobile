plugins {
    alias(libs.plugins.orca.jvm.library)
    alias(libs.plugins.ksp)
}

dependencies {
    api(project(":core:model"))
    implementation(project(":core:common"))
    implementation(libs.kotlinx.coroutines.core)
    // Use cases are constructor-injected; Dagger factories are generated here,
    // the module that owns the classes.
    implementation(libs.javax.inject)
    implementation(libs.dagger)
    ksp(libs.dagger.compiler)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
}
