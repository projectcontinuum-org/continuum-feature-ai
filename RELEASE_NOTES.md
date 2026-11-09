# continuum-feature-ai 0.0.6 (proposed)

Changes on branch `CONTNM-143` (`75d4d3e`) compared with `main` (`249b6dd`): 9 commits, no divergence from main.

This release adds the **Claude AI Agent** node, which runs an autonomous Claude Code agent once per input row. It also adds the container image and chart changes the node needs, and moves the module to platform `0.0.21` (Spring Boot 4, Jackson 3).

## Highlights

- **New node: Claude AI Agent.** It runs `claude -p` per input row (or once with no input) with a FreeMarker prompt template, an allow-listed set of tools, MCP servers and optional structured JSON output. Category: AI, Agents.
- **Pluggable backends.** Anthropic (default), Ollama (local and cloud), OpenRouter, LiteLLM or any gateway, and a Custom Anthropic-compatible endpoint.
- **Worker image with the Claude Code CLI.** A new base image bundles `claude`, `curl`, `jq`, `yq`, `grep`, `git` and Python.
- **Five example workflows** for the new node.
- **Platform upgrade:** `continuumPlatformVersion` goes from `0.0.14` to `0.0.21`, and `featureVersion` from `0.0.5` to `0.0.6`.

## Breaking / attention

- **Platform `0.0.21` is required.** The feature now builds against Spring Boot 4 and Jackson 3 (`tools.jackson.*`), so it cannot be used with workers on older platform versions.
- **Chart and deployment:** the worker now needs the credentials server address to read the agent's API token (see Helm chart below).

## What's Changed

All changes are under CONTNM-143:

- Add Claude AI Agent node (`052e470`)
- Backend failures are now treated as errors (`59d93c5`)
- Use the original Claude icon (`9a46dc8`)
- Add a system prompt for the sandbox (`eebd084`)
- Extra guards for prompt injection (`39c6caa`)
- Add example workflows for the Claude AI Agent (`c558e8c`)
- Improve AI agent node error handling (`2d21264`)
- Use platform version 0.0.21 (`766f233`)

## Details

### New module: `features/continuum-feature-claude`

**Node:** `ClaudeAIAgentNodeModel`

- **Ports:** an optional input table (one agent run per row) and an output table (input columns, the response column and `agent_*` metadata).
- **Prompt:**
  - FreeMarker template over the row, e.g. `${row.url}`.
  - System prompt in append or replace mode.
  - Optionally writes the row to `./input.json` in the agent's working directory.
- **Tools:**
  - Built-in tools: Bash, Read, Write, Edit, Glob, Grep (default), plus opt-in WebFetch and WebSearch.
  - Bash allow list (default `curl, jq, yq, grep, sed, awk, cat, ls, head, tail, sort, wc, base64, date`).
  - Bash deny list (default `rm, sudo`).
  - MCP servers as JSON.
  - Write and Edit are limited to the working directory.
- **Output:**
  - Configurable response column.
  - Optional JSON Schema for structured output, stored in `agent_structured_output`.
  - Optional flattening of structured fields into columns. Fields that clash with input or `agent_*` columns are never overwritten, and clashing schema fields are rejected as a non-retriable configuration error.
  - Optional `agent_intermediate_steps` (the first 500 steps, with long inputs and outputs truncated).
- **Metadata columns:** `agent_is_error`, `agent_error`, `agent_num_turns`, `agent_duration_ms`, `agent_cost_usd`, `agent_input_tokens`, `agent_output_tokens`, `agent_model`.
- **Limits:** max turns (default 10), timeout per row (default 300 s), max budget in USD, effort, and on-error mode (write error columns and continue, or fail the node).
- **Backends:** Anthropic, Ollama, Ollama Cloud, OpenRouter, LiteLLM / gateway, Custom. The API token comes from a Continuum **GENERIC** credential with a `token` key. It is passed to the CLI through environment variables only and is never logged or written to the output.
- **Worker settings** (defaults in code, no YAML needed):
  - `org.projectcontinuum.feature.ai.claude-agent.claude-cli-path` (default `claude`)
  - `org.projectcontinuum.feature.ai.claude-agent.workspace-path` (default `${java.io.tmpdir}/continuum-claude-agent`)
  - `org.projectcontinuum.feature.ai.claude-agent.max-concurrent-runs` (default `4`)

**Isolation and security**

- Runs with `--permission-mode dontAsk`. Anything not pre-approved is refused instead of prompting.
- Each run uses a fresh private working directory, which is deleted afterwards, and an isolated config directory. No user settings, memory, sessions or `CLAUDE.md` are loaded.
- The CLI process receives only an allow-list of worker environment variables (PATH, HOME, locale, proxy and CA settings). `CLAUDE_CODE_SUBPROCESS_ENV_SCRUB=1` strips credentials from the environment of Bash commands, so a prompt-injected `env` or `echo $ANTHROPIC_API_KEY` prints nothing.
- A sandbox system prompt tells the agent that paths outside the working directory are denied. The deny list is enforced.
- The allow list is **not a sandbox**: Claude Code still auto-approves read-only tools and some harmless commands. Run workers with least privilege and treat input data in prompts as untrusted.

**Reliability and error handling**

- Backend failures (HTTP 4xx/5xx, connection refused, TLS), timeouts, max-turns or budget limits, and CLI crashes are reported as errors. A non-zero exit after an answer counts as an error but keeps the response.
- Stopping the agent: on timeout, cancellation, or any failure, the CLI and every process it started get SIGTERM, then SIGKILL after 5 seconds.
- Bounded memory: stdout lines over 8 MiB are skipped, stderr is read as a 2000-character tail, and tool inputs and outputs are truncated to 2000 characters.
- Runs per worker are capped by `max-concurrent-runs`. Other rows wait and show "waiting for a free agent slot".
- Live progress shows the tool the agent is currently using.

**Tests:** about 1,200 lines in six test classes covering the command builder, the stream-JSON parser, the CLI runner (against a fake `claude` script), agent environment isolation, and the node (with a fake runner and Parquet round-trips). `ClaudeCliLiveTest` runs the real CLI only when `CLAUDE_LIVE_TEST` is set.

### Example workflows (`example-workflows/claude/`)

All use public APIs and need only the Claude token (credential named `anthropic`, key `token`):

1. `01-AdverseEventTriage.cwf`: FDA adverse-event triage (openFDA).
2. `02-LiteratureScreening.cwf`: PubMed title and abstract screening with supporting quotes.
3. `03-ClinicalTrialMonitor.cwf`: new phase 2/3 trials per indication (ClinicalTrials.gov).
4. `04-DependencyCveScan.cwf`: lockfile scan via OSV.
5. `05-CompoundNameResolver.cwf`: compound names to PubChem structures, then RDKit descriptors. This one also needs a worker with the RDKit nodes (`continuum-feature-cheminformatics`).

### Worker and image

- **New base image `docker/agent-worker-base`** (`eclipse-temurin:21-jre` plus Claude Code CLI, curl, git, jq, yq, grep, sed, gawk, python3 with venv and pip). It runs as non-root user `continuum` (uid 1000) with auto-update and telemetry disabled.
- **Worker contents:** the worker depends on both feature modules, so one image registers the Claude AI Agent and the LLM Trainer (Unsloth) nodes.
- **Worker build:** Jib now builds on the base image `projectcontinuum/continuum-feature-ai-worker-base:<version>` (override with `-PworkerBaseImage=...`) and pushes `projectcontinuum/continuum-feature-ai:<version>`.
- **Worker config:** a new `worker/src/main/resources/application.yaml` with only the Temporal block (`TEMPORAL_NAMESPACE`, `TEMPORAL_SERVER_ADDRESS`, worker auto-discovery).
- **Release workflow** (`.github/workflows/on-release.yml`):
  - New `worker-base-image` job builds and pushes the multi-arch (amd64 and arm64) base image, tagged with `featureVersion`.
  - The release job waits for it.
  - `features:continuum-feature-claude` is published to Maven.

### Helm chart (`charts/continuum-feature-ai`)

- Chart version `0.1.2` to `0.1.3`, appVersion `0.0.5` to `0.0.6`.
- The deployment sets `CONTINUUM_CREDENTIALS_SERVER_BASE_URL`, built from the new `continuum.credentialsServer.host` and `.port` values (defaults `continuum-platform-credentials-server` and `8080`). This assumes the same release-name convention as the existing `apiServer` value, so override it if your credentials server has a different name.

### continuum-feature-unsloth

- Moved to Jackson 3 imports (`tools.jackson.*`) and dropped the explicit `jackson-module-kotlin` dependency.
- Component scan now covers `org.projectcontinuum.feature.ai.node` and `org.projectcontinuum.feature.ai.unsloth`. The node keeps its package so its node ID is unchanged.
- No functional changes to the trainer.

### Documentation

- README and `AGENTS.md` rewritten for the Claude AI Agent: node reference, backends, CLI setup, container image build, updated module tree, roadmap, and corrected package names. They still list `continuum-commons` and `continuum-worker-springboot-starter` at `0.0.20`, although `gradle.properties` is `0.0.21`.
- New node documentation `ClaudeAIAgentNodeModel.doc.md`, covering configuration, output columns, security notes and tips.

## Upgrade notes

- **Platform:** deploy against continuum-platform-core `0.0.21`. Feature workers on Spring Boot 4 need the matching platform libraries.
- **Using the Claude AI Agent:**
  - Create a GENERIC credential with a `token` key.
  - Run the worker from the new image, or install the `claude` CLI on `PATH` for local runs.
  - Upgrade the Helm chart to `0.1.3` so the worker can reach the credentials server.
- **Unsloth users:** the LLM Trainer node is still part of the worker and its node ID is unchanged. The worker image is now built on the new base image, which runs as non-root user `continuum` (uid 1000) and includes Python 3 with venv.
- **Cost control:** set a Max Budget and Max Turns on the node. Rows run sequentially, so a large table takes (rows x average run time).

**Full Changelog**: compare `main...CONTNM-143` on `projectcontinuum-org/continuum-feature-ai`
