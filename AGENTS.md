# Continuum Feature AI — AI Agent & ML Nodes

> Full ecosystem blueprint: see the [Continuum repo AGENTS.md](https://github.com/projectcontinuum-org/continuum-platform-core/blob/main/AGENTS.md) for the complete architecture reference.

## This Repo

AI/ML nodes for Continuum workflows: an autonomous agent built on the Claude Code CLI, and LLM fine-tuning with Unsloth + LoRA.

## Modules

- `features/continuum-feature-claude/` — Claude AI Agent node (group: `org.projectcontinuum.feature.ai.claude`)
- `features/continuum-feature-unsloth/` — Unsloth trainer node (group: `org.projectcontinuum.feature.ai.unsloth`)
- `worker/` — Spring Boot worker app (group: `org.projectcontinuum.feature.ai`)
- `docker/agent-worker-base/` — worker base image (Claude Code CLI, curl/jq/yq/grep/git, python3); Jib `from` image

## Nodes

| Node | Module / class | Title | Category |
|------|----------------|-------|----------|
| Claude AI Agent | `continuum-feature-claude` — `org.projectcontinuum.feature.ai.claude.node.ClaudeAIAgentNodeModel` | Claude AI Agent | AI, Agents |
| LLM Trainer | `continuum-feature-unsloth` — `org.projectcontinuum.feature.ai.node.UnslothTrainerNodeModel` | LLM Trainer (Unsloth) | Machine Learning, LLM Training |

### Claude AI Agent

- Ports: optional `input` table (one agent run per row; runs once when not connected) → `output` (input columns + `response` + `agent_*` metadata columns)
- Runs `claude -p` as a subprocess (`cli/ClaudeCliRunner.kt`) in a per-run temp workspace with an isolated `CLAUDE_CONFIG_DIR`; parses `--output-format stream-json` (`cli/StreamJsonParser.kt`)
- Arguments and env are built in `cli/ClaudeCommandBuilder.kt` (pure, unit-tested): `--permission-mode dontAsk`, `--tools`, `--allowedTools` / `--disallowedTools` rules from the Bash allow-/deny-lists, `--json-schema`, `--mcp-config --strict-mcp-config`, `--max-turns`, `--max-budget-usd`, `--effort`
- Backends (`cli/BackendPreset.kt`): ANTHROPIC (default, `--bare` + `ANTHROPIC_API_KEY`), OLLAMA, OLLAMA_CLOUD, OPENROUTER, LITELLM, CUSTOM (`ANTHROPIC_BASE_URL` + `ANTHROPIC_AUTH_TOKEN`, model pinned to all model aliases). `--bare` ignores `ANTHROPIC_AUTH_TOKEN`, so bearer backends run non-bare with `--setting-sources ""` and `CLAUDE_CODE_DISABLE_*` isolation.
- Token: GENERIC Continuum credential with a `token` key, read via `ExecutionContext.getCredential`; inherited `ANTHROPIC_*` / `CLAUDE_CODE_OAUTH_TOKEN` env vars are removed from the subprocess
- Prompt: FreeMarker template over the row (`${row.col}`)
- Config (defaults in code via `@Value`, no YAML needed): `org.projectcontinuum.feature.ai.claude-agent.claude-cli-path` (default `claude`), `org.projectcontinuum.feature.ai.claude-agent.workspace-path` (default `${java.io.tmpdir}/continuum-claude-agent`)
- Tests swap in a fake `AgentRunner`, so they never need the CLI

### LLM Trainer (Unsloth)

- Input: Parquet table (`training_data` port) with instruction + response columns
- Output: JSON (`model_info` port) with model path, base model, training config
- Python execution via auto-managed venv (`unsloth/python/PythonEnvironmentManager.kt`)
- Config: `com.continuum.feature.ai.unsloth-trainer.venv-path` (default: `~/.continuum/unsloth-env`)
- Supported models: Phi-4, Mistral 7B, Llama 2/3, Gemma 2, Qwen 2.5, Falcon 7B, any HuggingFace causal LM

## Dependencies (from Maven Central)

`continuum-commons:0.0.20`, `continuum-worker-springboot-starter:0.0.20` (platform version in `gradle.properties` → `continuumPlatformVersion`)

## Build

```bash
cd docker && docker compose up -d
./gradlew build
./gradlew :worker:bootRun   # needs `claude` on PATH; auto-creates Python venv on first run
```

Container: `docker build -t <base> docker/agent-worker-base`, then `./gradlew :worker:jib -PworkerBaseImage=<base>`.

## Stack

Kotlin 2.2.0, Spring Boot 4, Jackson 3 (`tools.jackson.*`), JDK 21, Claude Code CLI, FreeMarker, Python 3.10+ (Unsloth, PyTorch, transformers, peft, trl)
