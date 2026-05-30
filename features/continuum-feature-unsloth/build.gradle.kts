plugins {
    id("org.projectcontinuum.feature") version "0.0.12"
}

group = "org.projectcontinuum.feature.ai.unsloth"
description = "Continuum Unsloth — a feature for generating code and documentation using large language models"
version = property("featureVersion").toString()

// get continuum platform version from root project properties
val continuumPlatformVersion = property("continuumPlatformVersion").toString()

continuum {
    continuumVersion.set(continuumPlatformVersion)
}

// Jackson 3 (tools.jackson.module:jackson-module-kotlin) is supplied transitively
// by the org.projectcontinuum.feature plugin — no explicit Jackson dependency needed.
