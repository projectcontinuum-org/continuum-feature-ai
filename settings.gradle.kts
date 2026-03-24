pluginManagement {
    repositories {
        mavenLocal()
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "continuum-feature-ai"

include(":features:continuum-feature-unsloth")
include(":worker")