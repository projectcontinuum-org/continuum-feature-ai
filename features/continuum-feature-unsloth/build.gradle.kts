plugins {
    id("org.projectcontinuum.feature") version "0.0.9"
}

group = "org.projectcontinuum.feature.ai.unsloth"
description = "Continuum Unsloth — a feature for generating code and documentation using large language models"
version = property("featureVersion").toString()

// get continuum platform version from root project properties
val continuumPlatformVersion = property("continuumPlatformVersion").toString()

continuum {
    continuumVersion.set(continuumPlatformVersion)
}

dependencies {
    // Jackson Kotlin module
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.18.2")
}