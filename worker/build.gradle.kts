plugins {
    id("org.projectcontinuum.worker")
}

group = "org.projectcontinuum.feature.ai"
description = "Continuum Feature AI Worker — Spring Boot worker application for AI feature nodes"
version = property("featureVersion").toString()

// get continuum platform version from root project properties
val continuumPlatformVersion = property("continuumPlatformVersion").toString()

continuum {
    continuumVersion.set(continuumPlatformVersion)
    // Base image with the Claude Code CLI, shell tools and python3 (built from docker/agent-worker-base).
    // Override with -PworkerBaseImage=... for local builds.
    jibFromImage.set(
        findProperty("workerBaseImage")?.toString()
            ?: "projectcontinuum/continuum-feature-ai-worker-base:${project.version}"
    )
    // Container image pushed by `./gradlew :worker:jib` (see .github/workflows/on-release.yml)
    jibToImage.set("projectcontinuum/continuum-feature-ai:${project.version}")
}

dependencies {
    // Feature node modules (local project)
//    implementation(project(":features:continuum-feature-unsloth"))
    implementation(project(":features:continuum-feature-claude"))
}