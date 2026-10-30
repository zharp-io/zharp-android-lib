pluginManagement {
    // io.zharp.android-gradle, built from source so the samples use the plugin as it is here.
    includeBuild("zharp-gradle-plugin")
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

rootProject.name = "zharp-android"

include(":zharp-android")
include(":samples:java-app")
include(":samples:monitoring-app")
