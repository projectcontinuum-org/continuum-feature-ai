plugins {
    kotlin("jvm") version "2.1.0"
    kotlin("plugin.spring") version "1.9.25"
    id("org.springframework.boot") version "3.4.0"
    id("io.spring.dependency-management") version "1.1.6"
    `maven-publish`
    id("com.google.cloud.tools.jib") version "3.4.1"
}

group = "com.continuum.feature.ai"
version = "0.0.1"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
    maven("https://packages.confluent.io/maven/")
    maven {
        name = "ContinuumGitHubPackages"
        url = uri("https://maven.pkg.github.com/projectcontinuum/continuum")
        credentials {
            username = System.getenv("GITHUB_USER") ?: ""
            password = System.getenv("GITHUB_TOKEN") ?: ""
        }
    }
}

dependencies {
    // Spring Boot dependencies
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    // Kotlin dependencies
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    // Worker framework (from GitHub Packages)
    implementation("com.continuum.core:continuum-worker-springboot-starter:0.0.1")

    // Feature node modules (local project)
    implementation(project(":features:continuum-feature-unsloth"))

    // Test dependencies
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.cloud:spring-cloud-dependencies:2024.0.0")
        mavenBom("io.temporal:temporal-bom:1.28.0")
        mavenBom("software.amazon.awssdk:bom:2.30.7")
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
            }
        }
    }
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/$repoName")
            credentials {
                username = System.getenv("GITHUB_USER") ?: ""
                password = System.getenv("GITHUB_TOKEN") ?: ""
            }
        }
    }
}

jib {
    to {
        image = "ghcr.io/${(System.getenv("GITHUB_REPOSITORY") ?: property("repoName").toString()).lowercase()}/${project.name.lowercase()}:${project.version}"
        auth {
            username = System.getenv("DOCKER_REPO_USERNAME") ?: ""
            password = System.getenv("DOCKER_REPO_PASSWORD") ?: ""
        }
    }
    from {
        image = "eclipse-temurin:21-jre"
    }
}
