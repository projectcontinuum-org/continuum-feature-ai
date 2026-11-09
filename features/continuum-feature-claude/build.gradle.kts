plugins {
    id("org.projectcontinuum.feature")
}

group = "org.projectcontinuum.feature.ai.claude"
description = "Continuum Claude AI Agent — an autonomous agent node powered by the Claude Code CLI"
version = property("featureVersion").toString()

// get continuum platform version from root project properties
val continuumPlatformVersion = property("continuumPlatformVersion").toString()

continuum {
    continuumVersion.set(continuumPlatformVersion)
}

dependencies {
    // Prompt templating (${row.column}), same engine as the REST node in continuum-feature-base
    implementation("org.freemarker:freemarker:2.3.34")
}
