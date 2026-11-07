// Its own build, included by the SDK's settings (pluginManagement { includeBuild(…) }) so the
// samples apply it by id, as apps do from Maven Central.
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "zharp-gradle-plugin"
