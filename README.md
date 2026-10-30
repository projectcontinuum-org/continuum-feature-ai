<div align="center">
  <h1>Continuum Feature AI</h1>
  <strong>AI agents and LLM fine-tuning nodes for your <a href="https://github.com/projectcontinuum-org/continuum-platform-core">Project Continuum</a> workflows</strong>
</div>

<div align="center">
  <img src="https://img.shields.io/badge/Kotlin-2.2.0-blue?logo=kotlin&logoColor=white" alt="Kotlin">
  <img src="https://img.shields.io/badge/Claude_Code-Agent-orange" alt="Claude Code">
  <img src="https://img.shields.io/badge/Python-Unsloth-yellow?logo=python&logoColor=white" alt="Python">
  <img src="https://img.shields.io/badge/LoRA-Fine--Tuning-purple" alt="LoRA">
  <img src="https://img.shields.io/badge/JDK-21-red" alt="JDK 21">
</div>

---

## 🌐 Part of Project Continuum

This is the **AI/ML feature repository** for [Project Continuum](https://github.com/projectcontinuum-org/continuum-platform-core) — a distributed, crash-proof workflow execution platform. It provides nodes for running autonomous AI agents and for training and fine-tuning large language models directly inside your visual workflows.

---

## 🔥 What Is This

A standalone Gradle project containing AI/ML workflow nodes:

- **Claude AI Agent** — an autonomous agent powered by the [Claude Code](https://code.claude.com) CLI. Give it a prompt per row and it gets the job done with shell tools (`curl`, `jq`, `yq`, `grep`, …), MCP servers and optional structured JSON output. Runs against Claude by default, or Ollama, OpenRouter, LiteLLM and other Anthropic-compatible backends.
- **LLM Trainer (Unsloth)** — fine-tune large language models using LoRA (Low-Rank Adaptation) with Unsloth acceleration, right inside your workflow graph.

Ships as a Spring Boot worker whose container image bundles the Claude Code CLI, the agent's shell tools and Python for ML execution.

---

## 🧪 Included Nodes

### Claude AI Agent

Runs one Claude Code agent per input row (or once when no input is connected). The prompt is a FreeMarker template over the row (`${row.columnName}`), and the agent completes it with an allow-listed set of tools.

| | |
|---|---|
| **Input** | Optional table — each row becomes one agent run |
| **Output** | Input columns + response, usage/cost metadata, optional structured output and intermediate steps |
| **Category** | AI, Agents |

**Backends** (Claude Code speaks the Anthropic Messages API; other models are reached through Anthropic-compatible endpoints):

| Preset | Default base URL | Credential |
|--------|------------------|------------|
| **Anthropic** (default) | Anthropic API | Required |
| **Ollama** (local) | `http://localhost:11434` | Not needed |
| **Ollama Cloud** | `https://ollama.com` | Required |
| **OpenRouter** | `https://openrouter.ai/api` | Required |
| **LiteLLM / gateway** (GPT, Gemini, Bedrock, …) | `http://localhost:4000` | Optional |
| **Custom** | — (must be set) | Optional |

The API token is stored as a Continuum **GENERIC** credential with a `token` key and is never written to the output.

**Configurable Parameters:**

| Tab | Parameters |
|-----|-----------|
| **Model** | Backend preset, base URL, model, fallback model, credential |
| **Prompt** | User prompt template, system prompt (append / replace), write the row as `input.json` |
| **Tools** | Built-in tools (Bash, Read, Write, Edit, Glob, Grep, WebFetch, WebSearch), Bash allow-list and deny-list, MCP servers JSON |
| **Output** | JSON Schema for structured output, flatten into columns, intermediate steps, response column name |
| **Limits** | Max turns, timeout per row, max budget (USD), effort, on error (write error columns / fail the node) |

**Key Features:**
- Agent loop, tool calling and provider protocol handled by Claude Code (`claude -p`, `--permission-mode dontAsk`)
- Each run is isolated: a temporary workspace and config dir, with no host settings, memory, `CLAUDE.md` or session persistence
- Live progress showing the tool the agent is currently using
- Output columns: `response`, `agent_is_error`, `agent_error`, `agent_num_turns`, `agent_duration_ms`, `agent_cost_usd`, `agent_input_tokens`, `agent_output_tokens`, `agent_model`, plus `agent_structured_output` and `agent_intermediate_steps` when enabled

> ⚠️ The Bash allow-list controls what the agent is permitted to run; it is not a sandbox. In `dontAsk` mode Claude Code also auto-allows read-only tools and some harmless commands. Run the worker in an isolated container and give it only the credentials and network access it needs.

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

## 🤖 Claude Code CLI

The Claude AI Agent node runs the `claude` CLI as a subprocess. The worker container image (built on [`docker/agent-worker-base`](docker/agent-worker-base/Dockerfile)) already includes it together with `curl`, `jq`, `yq`, `grep`, `git` and Python. For a local `bootRun`, install it yourself:

```bash
curl -fsSL https://claude.ai/install.sh | bash
claude --version
```

| Setting | Default |
|---------|---------|
| `org.projectcontinuum.feature.ai.claude-agent.claude-cli-path` | `claude` (resolved from `PATH`) |
| `org.projectcontinuum.feature.ai.claude-agent.workspace-path` | `${java.io.tmpdir}/continuum-claude-agent` |

---

## 🐍 Python Environment

The Unsloth node executes training via a Python virtual environment that is **automatically created at startup** if missing.

| Setting | Default |
|---------|---------|
| `com.continuum.feature.ai.unsloth-trainer.venv-path` | `~/.continuum/unsloth-env` |
| `org.projectcontinuum.feature.ai.unsloth-trainer.cache-storage-path` | `./.continuum-cache/workflow-data` |

**Required Python packages** (auto-installed): pyarrow, pandas, datasets, torch, transformers, peft, trl, accelerate, hf_transfer, sentencepiece, protobuf, bitsandbytes, unsloth (Linux + CUDA only).

---

## 📦 Dependencies

Shared libraries from [Continuum](https://github.com/projectcontinuum-org/continuum-platform-core) from Maven Central:

| Dependency | Purpose |
|-----------|---------|
| `continuum-commons:0.0.20` | Base node model, data types, Parquet/S3 utilities |
| `continuum-worker-springboot-starter:0.0.20` | Worker framework — registers nodes with Temporal |

---

## 🚀 Quick Start

### Prerequisites

- **JDK 21** — [Eclipse Temurin](https://adoptium.net/) recommended
- **Claude Code CLI** — For the Claude AI Agent node (see above)
- **Python 3.10+** — For the Unsloth training environment
- **Docker & Docker Compose** — For local infrastructure
- **(Optional) CUDA GPU** — For Unsloth acceleration

### Run

```bash
# Start infrastructure (Temporal, Kafka, MinIO, API Server, Message Bridge)
cd docker && docker compose up -d

# Build
./gradlew build

# Start the AI worker (auto-creates Python venv on first run)
./gradlew :worker:bootRun
```

### Container image

```bash
# Base image with Claude Code, shell tools and Python
docker build -t projectcontinuum/continuum-feature-ai-worker-base:dev docker/agent-worker-base

# Worker image on top of it (Jib, pushed to the registry)
./gradlew :worker:jib -PworkerBaseImage=projectcontinuum/continuum-feature-ai-worker-base:dev

# ...or into the local Docker daemon
./gradlew :worker:jibDockerBuild -PworkerBaseImage=projectcontinuum/continuum-feature-ai-worker-base:dev
```

---

## 📁 Project Structure

```
continuum-feature-ai/
├── features/
│   ├── continuum-feature-claude/             # Claude AI Agent node
│   │   └── src/main/kotlin/.../claude/
│   │       ├── AutoConfigure.kt
│   │       ├── node/
│   │       │   └── ClaudeAIAgentNodeModel.kt
│   │       └── cli/                          # CLI command builder, runner, stream-json parser
│   └── continuum-feature-unsloth/            # Unsloth LLM trainer node
│       ├── build.gradle.kts                  # Depends on continuum-commons
│       └── src/main/kotlin/.../
│           ├── AutoConfigure.kt              # Spring auto-configuration
│           ├── node/
│           │   └── UnslothTrainerNodeModel.kt
│           └── python/
│               └── PythonEnvironmentManager.kt
├── worker/                                   # Spring Boot worker application
│   ├── build.gradle.kts                      # Depends on starter + feature modules, Jib image
│   └── src/main/
│       ├── kotlin/.../App.kt
│       └── resources/application.yaml
├── docker/
│   ├── agent-worker-base/Dockerfile          # Worker base image (Claude Code + tools + Python)
│   └── docker-compose.yml                    # Local development infrastructure
├── settings.gradle.kts
├── gradle.properties
└── README.md
```

---

## 🗺️ Roadmap

- [x] Claude AI Agent — Claude Code agent with shell tools, MCP and structured output
- [x] Pluggable agent backends — Anthropic, Ollama, OpenRouter, LiteLLM, custom
- [ ] Agent memory — share a conversation across rows with the same session key
- [ ] Agent conversation input/output tables, file and image inputs/outputs
- [ ] Parallel agent runs per execution
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
| [Continuum](https://github.com/projectcontinuum-org/continuum-platform-core) | Core backend — API server, worker framework, shared libraries |
| [continuum-workbench](https://github.com/projectcontinuum-org/continuum-workbench) | Browser IDE — Eclipse Theia + React Flow workflow editor |
| [continuum-feature-base](https://github.com/projectcontinuum-org/continuum-feature-base) | Base analytics nodes — data transforms, REST, scripting, anomaly detection |
| **continuum-feature-ai** (this repo) | AI/ML nodes — Claude AI Agent, LLM fine-tuning with Unsloth + LoRA |
| [continuum-feature-template](https://github.com/projectcontinuum-org/continuum-feature-template) | Template — scaffold your own custom worker with nodes |

## 📄 License

[Apache 2.0](LICENSE)
