pluginManagement {
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

include(":zharp-logs")
include(":zharp-android")
include(":samples:kotlin-app")
include(":samples:java-app")
include(":samples:monitoring-app")
