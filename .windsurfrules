# Continuum Feature AI — ML Nodes

> Full ecosystem blueprint: see the [Continuum repo CLAUDE.md](https://github.com/projectcontinuum/Continuum/blob/main/CLAUDE.md) for the complete architecture reference.

## This Repo

AI/ML nodes for Continuum workflows. Currently: LLM fine-tuning with Unsloth + LoRA.

## Modules

- `features/continuum-feature-unsloth/` — Unsloth trainer node (group: `com.continuum.feature.unsloth`)
- `worker/` — Spring Boot worker app (group: `com.continuum.feature.ai`)

## Nodes

All in `features/continuum-feature-unsloth/src/main/kotlin/com/continuum/feature/ai/`:

| Node | Title | Category |
|------|-------|----------|
| `node/UnslothTrainerNodeModel` | LLM Trainer (Unsloth) | Machine Learning, LLM Training |

**Key details:**
- Input: Parquet table (`training_data` port) with instruction + response columns
- Output: JSON (`model_info` port) with model path, base model, training config
- Python execution via auto-managed venv (`python/PythonEnvironmentManager.kt`)
- Config: `com.continuum.feature.ai.unsloth-trainer.venv-path` (default: `~/.continuum/unsloth-env`)
- Supported models: Phi-4, Mistral 7B, Llama 2/3, Gemma 2, Qwen 2.5, Falcon 7B, any HuggingFace causal LM

## Dependencies (from GitHub Packages)

`continuum-commons:0.0.1`, `continuum-worker-springboot-starter:0.0.1`

## Build

```bash
cd docker && docker compose up -d
./gradlew build
./gradlew :worker:bootRun   # Auto-creates Python venv on first run
```

## Stack

Kotlin 2.1.0, Spring Boot 3.4.1, JDK 21, Python 3.10+ (Unsloth, PyTorch, transformers, peft, trl)
