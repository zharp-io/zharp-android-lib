// Its own build, included by the SDK's settings (pluginManagement { includeBuild(…) }) so the
// samples apply it by id, as apps do once it's on the Gradle Plugin Portal.
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "zharp-gradle-plugin"
