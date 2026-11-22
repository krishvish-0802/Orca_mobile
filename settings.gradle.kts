pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "OrcaMobile"

// Dependency direction: app -> feature:* -> core:domain <- core:{data,engine,printer}
//                       everything may use core:model and core:common.
include(":app")
include(":core:common")
include(":core:model")
include(":core:domain")
include(":core:data")
include(":core:engine")
include(":core:printer")
include(":core:update")
include(":core:designsystem")
include(":core:viewport")
include(":feature:home")
include(":feature:prepare")
include(":feature:settings")
