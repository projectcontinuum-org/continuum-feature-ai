# Continuum Feature AI — AI Agent & ML Nodes

> Full ecosystem blueprint: see the [Continuum repo AGENTS.md](https://github.com/projectcontinuum-org/continuum-platform-core/blob/main/AGENTS.md) for the complete architecture reference.

## This Repo

AI/ML nodes for Continuum workflows: an autonomous agent built on the Claude Code CLI, and LLM fine-tuning with Unsloth + LoRA.

## Modules

- `features/continuum-feature-claude/` — Claude AI Agent node (group: `org.projectcontinuum.feature.ai.claude`)
- `features/continuum-feature-unsloth/` — Unsloth trainer node (group: `org.projectcontinuum.feature.ai.unsloth`)
- `worker/` — Spring Boot worker app (group: `org.projectcontinuum.feature.ai`)
- `docker/agent-worker-base/` — worker base image (Claude Code CLI, curl/jq/yq/grep/git, python3); Jib `from` image. Its tag is `VERSION.txt` (read by `worker/build.gradle.kts` and the release workflow, which only builds a tag not yet on Docker Hub) — bump it whenever the Dockerfile changes

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
- Token: GENERIC Continuum credential with a `token` key, read via `ExecutionContext.getCredential`. The CLI process gets only an allow-list of worker env vars (`ClaudeCliRunner.agentEnvironment`: PATH, HOME, locale, proxy/CA) plus the builder's vars, and `CLAUDE_CODE_SUBPROCESS_ENV_SCRUB=1` strips credentials from the Bash tool's environment, so prompt injection cannot print secrets via `env`/`printenv`
- Prompt: FreeMarker template over the row (`${row.col}`). The config is locked down for user-authored templates: `?new` disabled (`ALLOWS_NOTHING_RESOLVER`), `?api` disabled, object wrapper `EXPOSE_PROPERTIES_ONLY` (no Java method calls); a violating template is a row error
- Config (defaults in code via `@Value`, no YAML needed): `org.projectcontinuum.feature.ai.claude-agent.claude-cli-path` (default `claude`), `org.projectcontinuum.feature.ai.claude-agent.workspace-path` (default `${java.io.tmpdir}/continuum-claude-agent`), `org.projectcontinuum.feature.ai.claude-agent.max-concurrent-runs` (default 4, a `Semaphore` in `ClaudeCliRunner`)
- Process lifecycle: on every exit path (timeout, interrupt, exception from `onProgress`/heartbeat) the CLI and all descendants seen while polling get SIGTERM, then SIGKILL after 5s. A non-zero exit after a success result is an error. Bounded memory: stdout lines > 8 MiB are skipped, at most 500 intermediate steps (only collected when requested), tool inputs/outputs truncated to 2000 chars, stderr read as a 2000-char tail
- Output columns: flattened structured-output fields never overwrite input columns (warned once, value stays in `agent_structured_output`); schema fields or a response column clashing with `agent_*` / the response column are a non-retriable config error
- Tests: the node tests use a fake `AgentRunner` (plus real Parquet round-trips); `ClaudeCliRunnerProcessTest` runs the runner against a fake `claude` shell script (no CLI needed, not on Windows); `ClaudeCliLiveTest` runs the real CLI only when `CLAUDE_LIVE_TEST` is set (see its KDoc for `CLAUDE_LIVE_TEST_*` vars)

### LLM Trainer (Unsloth)

- Input: Parquet table (`training_data` port) with instruction + response columns
- Output: JSON (`model_info` port) with model path, base model, training config
- Python execution via auto-managed venv (`unsloth/python/PythonEnvironmentManager.kt`)
- Process: `unsloth/node/UnslothTrainerCommand.kt` (pure, unit-tested) builds the argv and env. No shell: `<venv>/bin/python` (Windows `Scripts\python.exe`) runs `train.py` directly, one argv element per argument, so property values reach `argparse` verbatim. Venv activation is reproduced via `VIRTUAL_ENV` + venv bin dir prepended to `PATH`
- Env: only an allow-list of worker vars is inherited (`UnslothTrainerCommand.isAllowed`: PATH, HOME, user/shell/locale/TZ/TMP basics, proxy + CA bundle vars, `LD_LIBRARY_PATH`, `XDG_CACHE_HOME`, `PYTHONUNBUFFERED`/`PYTHONIOENCODING`/`PYTHONUTF8`, thread-count vars, Windows essentials, and prefixes `LC_`, `HF_`, `HUGGINGFACE_`, `TRANSFORMERS_`, `CUDA_`, `NVIDIA_`, `TORCH_`, `PYTORCH_`, `TRITON_`, `UNSLOTH_`), plus `HF_HUB_ENABLE_HF_TRANSFER=1` and `HF_TOKEN` from the `hfToken` property. `PYTHONPATH`/`PYTHONHOME` are excluded. The token travels only via env, never argv or logs
- Config: `com.continuum.feature.ai.unsloth-trainer.venv-path` (default: `~/.continuum/unsloth-env`)
- In the container the venv is pre-built in the base image (`/opt/continuum/unsloth-env`, packages from `docker/agent-worker-base/requirements.txt`, path set via env `CONTINUUM_FEATURE_AI_UNSLOTH_VENV_PATH`); `PythonEnvironmentManager` finds `bin/activate` and skips pip. Keep `requirements.txt` in sync with `PythonEnvironmentManager.REQUIRED_PACKAGES` and bump `VERSION.txt` when it changes
- Supported models: Phi-4, Mistral 7B, Llama 2/3, Gemma 2, Qwen 2.5, Falcon 7B, any HuggingFace causal LM

## Dependencies (from Maven Central)

`continuum-commons:0.0.20`, `continuum-worker-springboot-starter:0.0.20` (platform version in `gradle.properties` → `continuumPlatformVersion`)

## Build

```bash
cd docker && docker compose up -d
./gradlew build
./gradlew :worker:bootRun   # needs `claude` on PATH; auto-creates Python venv on first run
```

Container: `docker build -t <base> docker/agent-worker-base`, then `./gradlew :worker:jib -PworkerBaseImage=<base>`. CI builds one image per architecture on native amd64/arm64 runners and merges them into a multi-arch tag (`.github/workflows/generic-module-builder.yml`).

## Stack

Kotlin 2.2.0, Spring Boot 4, Jackson 3 (`tools.jackson.*`), JDK 21, Claude Code CLI, FreeMarker, Python 3.10+ (Unsloth, PyTorch, transformers, peft, trl)
