# Continuum Feature Template

A template repository for building custom [Continuum](https://github.com/roushan65/Continuum) workflow nodes. Create your own feature modules with processing nodes that plug into the Continuum distributed workflow execution platform.

---

## Overview

**Continuum** is a distributed workflow execution platform built on [Temporal](https://temporal.io/), [Apache Kafka](https://kafka.apache.org/), and [Spring Boot](https://spring.io/projects/spring-boot). It lets you design and run data processing workflows as directed acyclic graphs (DAGs), where each node in the graph performs a specific operation on tabular data (stored as [Apache Parquet](https://parquet.apache.org/) files in S3/MinIO).

This template provides a standalone repository for creating **custom Continuum nodes** without needing the full Continuum monorepo. It contains:

- **Feature module** (`features/continuum-feature-template/`) — A library that implements one or more workflow nodes. Each node extends `ProcessNodeModel` from `continuum-commons`, defines input/output ports, configuration properties (JSON Schema), and an `execute()` method that processes data.

- **Worker module** (`worker/`) — A Spring Boot application that bundles your feature module(s) with the `continuum-worker-springboot-starter` framework. The worker registers your nodes with Temporal and handles the full execution lifecycle: downloading inputs from S3, running your node logic, uploading outputs, and reporting progress via Kafka.

```
┌─────────────────────────────────────────────────────────┐
│  Your Repository                                        │
│                                                         │
│  features/continuum-feature-template/                   │
│  └── ColumnJoinerNodeModel  ←  Your custom nodes        │
│                                                         │
│  worker/                                                │
│  └── Spring Boot App  ←  Runs your nodes                │
│       ├── continuum-worker-springboot-starter (framework)│
│       └── your feature module(s)                        │
└────────────────────┬────────────────────────────────────┘
                     │
        ┌────────────┼────────────┐
        ▼            ▼            ▼
   ┌─────────┐ ┌─────────┐ ┌──────────┐
   │ Temporal │ │  Kafka  │ │ MinIO/S3 │
   │ (orch.) │ │ (events)│ │ (storage)│
   └─────────┘ └─────────┘ └──────────┘
```

**One repo = one worker, many feature modules.** You can add multiple feature modules under `features/` and the single worker will load all of them.

---

## Prerequisites

- **JDK 21** — [Eclipse Temurin](https://adoptium.net/) recommended
- **Docker & Docker Compose** — For running local infrastructure (Temporal, Kafka, MinIO)
- **GitHub Account** — With a Personal Access Token (PAT) that has `read:packages` scope, needed to download `continuum-commons` and `continuum-worker-springboot-starter` from GitHub Packages
- **IDE** — IntelliJ IDEA recommended for Kotlin/Gradle projects

---

## Getting Started

### 1. Create Your Repository

Click **"Use this template"** on GitHub to create a new repository from this template, or fork it.

### 2. Clone and Rename

```bash
git clone https://github.com/YOUR_USERNAME/continuum-feature-template.git
cd continuum-feature-template
```

Rename the project from `template` to your feature name (e.g., `myfeature`). Update these files:

| File | What to change |
|------|---------------|
| `settings.gradle.kts` | Module name: `:features:continuum-feature-template` → `:features:continuum-feature-myfeature` |
| `features/` directory | Rename `continuum-feature-template/` → `continuum-feature-myfeature/` |
| `features/.../build.gradle.kts` | `group = "com.continuum.feature.myfeature"` |
| `worker/build.gradle.kts` | Update `project(":features:continuum-feature-myfeature")` and group |
| `AutoConfigure.kt` | Package: `com.continuum.feature.myfeature`, basePackages |
| `AutoConfiguration.imports` | `com.continuum.feature.myfeature.AutoConfigure` |
| `App.kt` | Package: `com.continuum.app.worker.myfeature` |
| `application.yaml` | `spring.application.name` and logging packages |
| Source directories | Rename `template` → `myfeature` in all directory paths |

### 3. Set Environment Variables

GitHub Packages requires authentication even for reading public packages:

```bash
export GITHUB_USER=your-github-username
export GITHUB_TOKEN=ghp_your-personal-access-token
```

> **Tip:** Add these to your `~/.zshrc` or `~/.bashrc` so they persist across sessions.

### 4. Update `gradle.properties`

Set `repoName` to your GitHub repository (used for publishing):

```properties
sourceRepoName=roushan65/Continuum
repoName=YOUR_USERNAME/continuum-feature-myfeature
```

### 5. Start Local Infrastructure

```bash
cd docker
docker compose up -d
```

This starts Temporal, Kafka (3-node cluster), MinIO, Mosquitto, the Continuum API server, and the Continuum message bridge. Wait ~30 seconds for all services to initialize.

### 6. Build the Project

```bash
./gradlew build
```

### 7. Run the Worker

```bash
./gradlew :worker:bootRun
```

Your worker is now running and your custom nodes are registered with Temporal, ready to execute workflows.

---

## Creating Your Own Node

The template includes an example node (`ColumnJoinerNodeModel`) that demonstrates all the key patterns. Here's how to create your own:

### 1. Create a New Class

Create a new Kotlin file under `features/continuum-feature-template/src/main/kotlin/com/continuum/feature/template/node/`:

```kotlin
@Component
class MyNodeModel : ProcessNodeModel() {
    // ...
}
```

### 2. Define Input and Output Ports

```kotlin
final override val inputPorts = mapOf(
    "input" to ContinuumWorkflowModel.NodePort(
        name = "input table",
        contentType = APPLICATION_OCTET_STREAM_VALUE
    )
)

final override val outputPorts = mapOf(
    "output" to ContinuumWorkflowModel.NodePort(
        name = "output table",
        contentType = APPLICATION_OCTET_STREAM_VALUE
    )
)
```

### 3. Define Configuration Properties (JSON Schema)

```kotlin
val propertiesSchema: Map<String, Any> = objectMapper.readValue("""
    {
      "type": "object",
      "properties": {
        "myParam": {
          "type": "string",
          "title": "My Parameter",
          "description": "Description of what this does"
        }
      },
      "required": ["myParam"]
    }
""".trimIndent(), object : TypeReference<Map<String, Any>>() {})
```

### 4. Define Metadata

```kotlin
override val metadata = ContinuumWorkflowModel.NodeData(
    id = this.javaClass.name,
    title = "My Node",
    description = "What this node does",
    subTitle = "Short subtitle",
    nodeModel = this.javaClass.name,
    icon = """<svg>...</svg>""",
    inputs = inputPorts,
    outputs = outputPorts,
    properties = mapOf("myParam" to "defaultValue"),
    propertiesSchema = propertiesSchema,
    propertiesUISchema = propertiesUiSchema
)
```

### 5. Implement the Execute Method

```kotlin
override fun execute(
    properties: Map<String, Any>?,
    inputs: Map<String, NodeInputReader>,
    nodeOutputWriter: NodeOutputWriter,
    nodeProgressCallback: NodeProgressCallback
) {
    val myParam = properties?.get("myParam") as String?
        ?: throw NodeRuntimeException(workflowId = "", nodeId = "", message = "myParam is required")

    val inputReader = inputs["input"]!!
    val totalRows = inputReader.getRowCount()

    nodeOutputWriter.createOutputPortWriter("output").use { writer ->
        inputReader.use { reader ->
            var row = reader.read()
            var rowNumber = 0L

            while (row != null) {
                // Your processing logic here
                val outputRow = row.toMutableMap<String, Any>()
                writer.write(rowNumber, outputRow)

                rowNumber++
                if (totalRows > 0) {
                    nodeProgressCallback.report((rowNumber * 100 / totalRows).toInt())
                }
                row = reader.read()
            }
        }
    }
    nodeProgressCallback.report(100)
}
```

### 6. Add Documentation

Create `MyNodeModel.doc.md` in the resources directory matching your package path:
```
src/main/resources/com/continuum/feature/template/node/MyNodeModel.doc.md
```

This file is auto-loaded by `ProcessNodeModel` and displayed in the workflow editor.

---

## Adding More Feature Modules

To add another set of nodes, create a new module under `features/`:

1. Create the directory: `features/continuum-feature-another/`
2. Add a `build.gradle.kts` (copy from the existing feature module)
3. Add to `settings.gradle.kts`:
   ```kotlin
   include(":features:continuum-feature-another")
   ```
4. Add as a dependency in `worker/build.gradle.kts`:
   ```kotlin
   implementation(project(":features:continuum-feature-another"))
   ```

The worker will automatically discover and register all nodes from all feature modules via Spring Boot auto-configuration.

---

## Publishing

### Publish to GitHub Packages

```bash
./gradlew publish
```

### Build and Push Container Image

```bash
./gradlew :worker:jib
```

This builds a container image using [Jib](https://github.com/GoogleContainerTools/jib) (no Docker daemon needed) and pushes to GitHub Container Registry (GHCR).

---

## Project Structure

```
continuum-feature-template/
├── .github/workflows/build.yml          # CI/CD: build, test, publish, containerize
├── docker/                              # Local development infrastructure
│   ├── docker-compose.yml               # Temporal, Kafka, MinIO, API server, message bridge
│   ├── .env                             # Docker image version pins
│   ├── temporal/dynamicconfig/          # Temporal dynamic config
│   └── mosquitto/config/               # MQTT broker config
├── features/
│   └── continuum-feature-template/      # Your node implementations
│       ├── build.gradle.kts             # Depends on continuum-commons
│       └── src/
│           ├── main/kotlin/.../
│           │   ├── AutoConfigure.kt     # Spring Boot auto-configuration
│           │   └── node/
│           │       └── ColumnJoinerNodeModel.kt  # Example node
│           ├── main/resources/
│           │   ├── META-INF/spring/...  # Auto-config registration
│           │   └── .../ColumnJoinerNodeModel.doc.md  # Node documentation
│           └── test/kotlin/.../
│               └── ColumnJoinerNodeModelTest.kt
├── worker/                              # Spring Boot worker application
│   ├── build.gradle.kts                 # Depends on starter + feature modules
│   └── src/main/
│       ├── kotlin/.../App.kt            # Application entry point
│       └── resources/application.yaml   # Kafka, Temporal, S3 config
├── settings.gradle.kts                  # Multi-module project settings
├── gradle.properties                    # Repository names for packages
└── README.md
```

---

## Infrastructure Services

When you run `docker compose up -d` in the `docker/` directory, these services start:

| Service | Port(s) | Purpose |
|---------|---------|---------|
| **Temporal** | 7233 | Workflow orchestration engine |
| **Temporal UI** | 38081 | Web UI for monitoring workflows |
| **PostgreSQL** | 35432 | Temporal's persistence backend |
| **Kafka** (x3) | 39092, 39093, 39094 | Event streaming (3-node KRaft cluster) |
| **Schema Registry** | 38080 | Kafka schema management |
| **Kafka UI** | 38082 | Web UI for Kafka topics |
| **MinIO** | 39000 (API), 39001 (Console) | S3-compatible object storage |
| **Mosquitto** | 31883 (TCP), 31884 (WS) | MQTT broker |
| **API Server** | 8080 | Continuum REST API |
| **Message Bridge** | 8081 | Kafka-to-MQTT event bridge |
