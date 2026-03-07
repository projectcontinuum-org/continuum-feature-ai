<div align="center">
  <h1>Continuum Feature AI</h1>
  <strong>AI/ML nodes for fine-tuning LLMs inside your <a href="https://github.com/projectcontinuum/Continuum">Project Continuum</a> workflows</strong>
</div>

<div align="center">
  <img src="https://img.shields.io/badge/Kotlin-2.1.0-blue?logo=kotlin&logoColor=white" alt="Kotlin">
  <img src="https://img.shields.io/badge/Python-Unsloth-yellow?logo=python&logoColor=white" alt="Python">
  <img src="https://img.shields.io/badge/LoRA-Fine--Tuning-purple" alt="LoRA">
  <img src="https://img.shields.io/badge/JDK-21-red" alt="JDK 21">
</div>

---

## 🌐 Part of Project Continuum

This is the **AI/ML feature repository** for [Project Continuum](https://github.com/projectcontinuum/Continuum) — a distributed, crash-proof workflow execution platform. It provides nodes for training and fine-tuning large language models directly inside your visual workflows.

---

## 🔥 What Is This

A standalone Gradle project containing AI/ML workflow nodes. Currently features the **LLM Trainer (Unsloth)** node — fine-tune large language models using LoRA (Low-Rank Adaptation) with Unsloth acceleration, right inside your workflow graph.

Ships as a Spring Boot worker with an auto-managed Python virtual environment for ML execution.

---

## 🧪 Included Nodes

### LLM Trainer (Unsloth)

Fine-tune Large Language Models using LoRA with Unsloth acceleration.

| | |
|---|---|
| **Input** | Parquet table with instruction + response columns |
| **Output** | Model info — path to LoRA adapter weights, base model, training config |
| **Category** | Machine Learning, LLM Training |

**Supported Base Models:**

| Provider | Models |
|----------|--------|
| **Unsloth** (fastest) | Phi-4, Phi-4-mini-instruct, Mistral 7B, Llama 3/3.1/3.2, Gemma 2, Qwen 2.5 |
| **Microsoft** | Phi-2, Phi-3-mini-4k-instruct |
| **Meta** | Llama-2-7b, Llama-2-7b-chat |
| **Google** | Gemma 2B, Gemma 7B |
| **Qwen** | Qwen2-7B, Qwen2-7B-Instruct |
| **TII** | Falcon 7B, Falcon 7B-instruct |
| **Custom** | Any HuggingFace causal language model |

**Configurable Parameters:**

| Group | Parameters |
|-------|-----------|
| **Model** | Base model (HuggingFace ID), HuggingFace token for gated models |
| **Data** | Input column, output column, system prompt |
| **Training** | Epochs, batch size, learning rate, max sequence length, warmup steps, weight decay, gradient accumulation |
| **LoRA** | Rank (r), alpha, dropout |
| **Advanced** | 4-bit quantization, random seed, save steps, logging steps, Parquet batch size |

**Key Features:**
- Unsloth acceleration on Linux + CUDA (2x faster, 60% less memory)
- Falls back to standard HuggingFace transformers on other platforms
- 4-bit quantization for reduced memory usage
- Real-time training progress streaming via Kafka
- Auto-managed Python virtual environment

---

## 🐍 Python Environment

The Unsloth node executes training via a Python virtual environment that is **automatically created at startup** if missing.

| Setting | Default |
|---------|---------|
| `com.continuum.feature.ai.unsloth-trainer.venv-path` | `~/.continuum/unsloth-env` |
| `com.continuum.feature.ai.unsloth-trainer.cache-storage-path` | `./.continuum-cache/workflow-data` |

**Required Python packages** (auto-installed): pyarrow, pandas, datasets, torch, transformers, peft, trl, accelerate, hf_transfer, sentencepiece, protobuf, bitsandbytes, unsloth (Linux + CUDA only).

---

## 📦 Dependencies

Shared libraries from [Continuum](https://github.com/projectcontinuum/Continuum) via GitHub Packages:

| Dependency | Purpose |
|-----------|---------|
| `continuum-commons:0.0.1` | Base node model, data types, Parquet/S3 utilities |
| `continuum-worker-springboot-starter:0.0.1` | Worker framework — registers nodes with Temporal |

---

## 🚀 Quick Start

### Prerequisites

- **JDK 21** — [Eclipse Temurin](https://adoptium.net/) recommended
- **Python 3.10+** — For the Unsloth training environment
- **Docker & Docker Compose** — For local infrastructure
- **GitHub PAT** with `read:packages` scope
- **(Optional) CUDA GPU** — For Unsloth acceleration

Set environment variables:

```bash
export GITHUB_USERNAME=your-github-username
export GITHUB_TOKEN=ghp_your-personal-access-token
```

### Run

```bash
# Start infrastructure (Temporal, Kafka, MinIO, API Server, Message Bridge)
cd docker && docker compose up -d

# Build
./gradlew build

# Start the AI worker (auto-creates Python venv on first run)
./gradlew :worker:bootRun
```

---

## 📁 Project Structure

```
continuum-feature-ai/
├── features/
│   └── continuum-feature-unsloth/            # Unsloth LLM trainer node
│       ├── build.gradle.kts                  # Depends on continuum-commons
│       └── src/main/kotlin/.../
│           ├── AutoConfigure.kt              # Spring auto-configuration
│           ├── node/
│           │   └── UnslothTrainerNodeModel.kt
│           └── python/
│               └── PythonEnvironmentManager.kt
├── worker/                                   # Spring Boot worker application
│   ├── build.gradle.kts                      # Depends on starter + unsloth feature
│   └── src/main/
│       ├── kotlin/.../App.kt
│       └── resources/application.yaml
├── docker/                                   # Local development infrastructure
│   └── docker-compose.yml
├── settings.gradle.kts
├── gradle.properties
└── README.md
```

---

## 🗺️ Roadmap

- [x] LLM fine-tuning with Unsloth + LoRA
- [x] Auto-managed Python virtual environment
- [x] 4-bit quantization support
- [ ] Inference node — run inference against fine-tuned or base models
- [ ] Model evaluation node — automated benchmarking
- [ ] Multi-GPU training support
- [ ] More model architectures (vision, embedding)

---

## 🔗 Related Repositories

| Repository | Description |
|-----------|-------------|
| [Continuum](https://github.com/projectcontinuum/Continuum) | Core backend — API server, worker framework, shared libraries |
| [continuum-workbench](https://github.com/projectcontinuum/continuum-workbench) | Browser IDE — Eclipse Theia + React Flow workflow editor |
| [continuum-feature-base](https://github.com/projectcontinuum/continuum-feature-base) | Base analytics nodes — data transforms, REST, scripting, anomaly detection |
| **continuum-feature-ai** (this repo) | AI/ML nodes — LLM fine-tuning with Unsloth + LoRA |
| [continuum-feature-template](https://github.com/projectcontinuum/continuum-feature-template) | Template — scaffold your own custom worker with nodes |
