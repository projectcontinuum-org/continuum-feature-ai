plugins {
    kotlin("jvm") version "2.1.0"
    kotlin("plugin.spring") version "1.9.25"
    id("io.spring.dependency-management") version "1.1.6"
    `maven-publish`
    id("org.jreleaser")
}

group = "org.projectcontinuum.feature.ai.unsloth"
description = "Continuum Unsloth — a feature for generating code and documentation using large language models"
val baseVersion = property("featureVersion").toString()
val isRelease = System.getenv("IS_RELEASE_BUILD")?.toBoolean() ?: false
version = if (isRelease) baseVersion else "$baseVersion-SNAPSHOT"

// get continuum platform version from root project properties
val continuumPlatformVersion = property("continuumPlatformVersion").toString()

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    // Spring Boot dependencies
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-autoconfigure")

    // Kotlin dependencies
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    // Project continuum dependencies
    implementation("org.projectcontinuum.core:continuum-commons:$continuumPlatformVersion")

    // Jackson dependencies
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.18.2")

    // Test dependencies
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:3.4.1")
        mavenBom("io.temporal:temporal-bom:1.28.0")
    }
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}

publishing {
    val repoName = System.getenv("GITHUB_REPOSITORY") ?: property("repoName").toString()
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            group = project.group
            description = project.description
            version = project.version.toString()
            pom {
                name.set(project.name)
                description.set(project.description)
                url.set("https://github.com/$repoName")
                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
                developers {
                    developer {
                        id.set("continuum-developer")
                        name.set("Continuum Developer")
                        email.set("projectdevcontinuum@gmail.com")
                    }
                }
                scm {
                    connection.set("scm:git:git://github.com/$repoName.git")
                    developerConnection.set("scm:git:ssh://github.com/$repoName.git")
                    url.set("https://github.com/$repoName")
                }
            }
        }
    }

    repositories {
      maven {
        name = "localStaging"
        url = uri(layout.buildDirectory.dir("staging-deploy"))
      }
      if (version.toString().endsWith("-SNAPSHOT")) {
        maven {
          name = "SonatypeSnapshots"
          url = uri("https://central.sonatype.com/repository/maven-snapshots/")
          credentials {
            username = System.getenv("MAVEN_REPO_USERNAME") ?: ""
            password = System.getenv("MAVEN_REPO_PASSWORD") ?: ""
          }
        }
      }
    }
}

jreleaser {
  signing {
    active.set(org.jreleaser.model.Active.ALWAYS)
    armored.set(true)
  }
  deploy {
    maven {
      mavenCentral {
        create("sonatype") {
          active.set(org.jreleaser.model.Active.ALWAYS)
          url.set("https://central.sonatype.com/api/v1/publisher")
          stagingRepository("build/staging-deploy")
          skipPublicationCheck.set(false)
          retryDelay.set(0)
          maxRetries.set(0)
        }
      }
    }
  }
}