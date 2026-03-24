plugins {
    id("org.projectcontinuum.worker") version "0.0.9"
}

group = "org.projectcontinuum.feature.ai"
description = "Continuum Feature AI Worker — Spring Boot worker application for AI feature nodes"
version = property("featureVersion").toString()

// get continuum platform version from root project properties
val continuumPlatformVersion = property("continuumPlatformVersion").toString()

continuum {
    continuumVersion.set(continuumPlatformVersion)
}

dependencies {
    // Feature node modules (local project)
    implementation(project(":features:continuum-feature-unsloth"))
}