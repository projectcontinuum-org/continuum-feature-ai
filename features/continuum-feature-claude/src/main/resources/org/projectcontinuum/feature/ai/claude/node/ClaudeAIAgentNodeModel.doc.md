# Claude AI Agent

Runs an autonomous **Claude Code** agent once per input row. The agent reads your prompt, plans, runs command-line tools (`curl`, `jq`, `yq`, `grep`, …), inspects the results and keeps going until the task is done. The final answer and run metadata are added as new columns.

Comparable to the *AI Agent* node in n8n or the *Agent Prompter* in KNIME, with Claude Code as the agent runtime.

## What It Does

For each input row the node:

1. Renders the **Prompt** as a FreeMarker template, e.g. `Fetch ${row.url} with curl and extract the page title`.
2. Optionally writes the row to `./input.json` in a fresh, private working directory.
3. Starts `claude -p` with the configured model, tools and limits. Each run is isolated: no user settings, memory, sessions or CLAUDE.md files are loaded or kept.
4. Writes the agent's final answer and metadata to the output row. The working directory is deleted afterwards.

If no input table is connected, the agent runs **once** with an empty row.

## Backends

Claude Code speaks the Anthropic Messages API. Any backend exposing an Anthropic-compatible endpoint works:

| Backend | Default base URL | Credential | Notes |
|---------|------------------|------------|-------|
| **Anthropic (Claude)** | Anthropic API | required | Models: `sonnet`, `opus`, `haiku` or a full model ID |
| **Ollama (local)** | `http://localhost:11434` | optional | Use a model with tool support, e.g. `qwen3-coder`, `gpt-oss:20b` |
| **Ollama Cloud** | `https://ollama.com` | required | Ollama API key |
| **OpenRouter** | `https://openrouter.ai/api` | required | Any OpenRouter model, e.g. `openai/gpt-5`, `google/gemini-2.5-pro` |
| **LiteLLM / gateway** | `http://localhost:4000` | optional | Reach OpenAI, Azure OpenAI, Bedrock, Gemini, vLLM, … through a LiteLLM proxy |
| **Custom** | — (must be set) | optional | Any other Anthropic-compatible endpoint |

The base URL is given **without** `/v1`. Non-Claude models are not officially supported by Claude Code. Results depend on how well the model handles tool calling.

### Credential

Create a **GENERIC** credential with a key named `token` holding the API key, and select it in **Credential**. Tokens are passed to the CLI through environment variables only and are never logged or written to the output.

## Configuration

### Model

| Property | Default | Description |
|----------|---------|-------------|
| **Backend** | `Anthropic (Claude)` | LLM provider (see above) |
| **Base URL** | *(backend default)* | Override the endpoint. Hidden for Anthropic |
| **Model** | `sonnet` | Model name or alias |
| **Fallback Model(s)** | — | Comma-separated models tried when the primary is overloaded or unavailable |
| **Credential** | — | GENERIC credential with a `token` key |

### Prompt

| Property | Default | Description |
|----------|---------|-------------|
| **Prompt** | `Describe the data in ./input.json` | FreeMarker template; `${row.columnName}` inserts a cell value |
| **System Prompt Mode** | `Append` | Append to Claude Code's built-in system prompt, or replace it |
| **System Prompt** | — | Extra instructions, e.g. persona, output style, domain rules |
| **Write row to ./input.json** | on | Lets the agent inspect the whole row, e.g. `jq .price input.json` |

### Tools

| Property | Default | Description |
|----------|---------|-------------|
| **Allowed Tools** | Bash, Read, Write, Edit, Glob, Grep | Built-in tools; WebFetch and WebSearch are opt-in |
| **Bash Allow List** | curl, jq, yq, grep, sed, awk, cat, ls, head, tail, sort, wc, base64, date | Pre-approved commands. A bare name allows any arguments; `Bash(git status)` style rules are used as-is |
| **Bash Deny List** | rm, sudo | Commands that are always refused |
| **MCP Servers** | — | JSON `{"mcpServers": {...}}` (or just the server map) to give the agent MCP tools |

Write and Edit are limited to the agent's working directory.

### Output

| Property | Default | Description |
|----------|---------|-------------|
| **Response Column** | `response` | Column receiving the agent's final answer |
| **Output Schema** | — | JSON Schema; the final answer is validated against it and stored in `agent_structured_output` |
| **Flatten Structured Output** | off | Also write each top-level field of the structured output to its own column |
| **Include Intermediate Steps** | off | Add `agent_intermediate_steps` with text, tool calls, tool results and permission denials |

### Limits

| Property | Default | Description |
|----------|---------|-------------|
| **Max Turns** | 10 | Maximum agent turns per row (1–200) |
| **Timeout (seconds)** | 300 | Wall-clock limit per row; the agent is killed afterwards |
| **Max Budget (USD)** | 0 | Spend cap per row, 0 = unlimited |
| **Effort** | model default | Reasoning effort: low, medium, high, xhigh, max |
| **On Error** | `Write error columns and continue` | Or **Fail the node** on the first failed row |

## Ports

| Port | Direction | Description |
|------|-----------|-------------|
| Input Table (optional) | Input | One agent run per row. Leave unconnected for a single run |
| Agent Results | Output | Input columns + agent columns |

## Output Columns

| Column | Type | Description |
|--------|------|-------------|
| *Response Column* | string | Agent's final answer |
| `agent_is_error` | boolean | Whether the run failed |
| `agent_error` | string | Error message (empty on success) |
| `agent_num_turns` | int | Turns used |
| `agent_duration_ms` | long | Run duration |
| `agent_cost_usd` | double | Cost estimated by Claude Code |
| `agent_input_tokens` | long | Prompt tokens incl. cache reads/writes |
| `agent_output_tokens` | long | Generated tokens |
| `agent_model` | string | Model that answered |
| `agent_structured_output` | json | Only with an Output Schema |
| `agent_intermediate_steps` | json | Only with Include Intermediate Steps |

## Example

**Input:**

| url |
|-----|
| https://example.com |

**Prompt:** `Fetch ${row.url} with curl and return the page title.`
**Output Schema:** `{"type":"object","properties":{"title":{"type":"string"}},"required":["title"]}`, with **Flatten** on.

**Output:**

| url | response | title | agent_num_turns | agent_is_error |
|-----|----------|-------|-----------------|----------------|
| https://example.com | The title is "Example Domain". | Example Domain | 3 | false |

## Security Notes

- The agent runs with `--permission-mode dontAsk`: anything not pre-approved is refused instead of prompting.
- The **deny list is enforced**. The allow list is **not a sandbox**: Claude Code auto-approves read-only tools and some harmless commands (e.g. `ls`, `date`) even when they are not listed. Commands with side effects outside the working directory are refused unless allowed.
- The agent runs as the worker's OS user and can reach whatever the worker can reach on the network. Run workers with least privilege and treat input data placed in prompts as untrusted.

## Tips

- Put the data the agent needs in `./input.json` instead of the prompt. It keeps prompts short and lets the agent use `jq`.
- Use **Output Schema** + **Flatten** to turn free-form agent answers into typed columns.
- Keep **Max Turns** and **Timeout** low while developing prompts. Raise them for complex tasks.
- Rows run sequentially, so large tables take `rows × average run time`.

## Requirements

The worker needs the `claude` CLI on its `PATH`. The `continuum-feature-ai` image ships it together with curl, jq, yq and grep. For local runs, install Claude Code (https://code.claude.com/docs/en/setup) or set `org.projectcontinuum.feature.ai.claude-agent.claude-cli-path`.
