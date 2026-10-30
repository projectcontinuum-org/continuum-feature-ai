package org.projectcontinuum.feature.ai.claude.cli

import java.nio.file.Path

enum class SystemPromptMode { APPEND, REPLACE }

/**
 * Everything needed for one agent run (one input row).
 *
 * @property prompt Rendered user prompt, sent to the CLI on stdin
 * @property token API token from the Continuum credential, or null when none is configured
 * @property tools Built-in Claude Code tools made available to the agent
 * @property bashAllowList Bash commands (bare names or full `Bash(...)` rules) pre-approved under `dontAsk`
 * @property bashDenyList Bash commands (bare names or full `Bash(...)` rules) that are always refused
 * @property mcpConfig Normalized `{"mcpServers": {...}}` JSON, or null
 * @property outputSchema JSON Schema for structured output, or null
 * @property maxBudgetUsd Spend cap per run; 0 disables it
 * @property inputJson Row serialized as JSON, written to `./input.json`; null skips the file
 */
data class AgentRequest(
  val prompt: String,
  val backend: BackendPreset = BackendPreset.ANTHROPIC,
  val baseUrl: String? = null,
  val token: String? = null,
  val model: String? = null,
  val fallbackModel: String? = null,
  val systemPrompt: String? = null,
  val systemPromptMode: SystemPromptMode = SystemPromptMode.APPEND,
  val tools: List<String> = emptyList(),
  val bashAllowList: List<String> = emptyList(),
  val bashDenyList: List<String> = emptyList(),
  val mcpConfig: String? = null,
  val outputSchema: String? = null,
  val maxTurns: Int = 10,
  val maxBudgetUsd: Double = 0.0,
  val effort: String? = null,
  val timeoutSeconds: Long = 300,
  val inputJson: String? = null
)

/** A fully resolved CLI invocation: argv and environment additions. The prompt is fed on stdin by the runner. */
data class ClaudeCommand(
  val args: List<String>,
  val env: Map<String, String>
)

/**
 * Builds the `claude -p` invocation for an [AgentRequest]. Pure — no I/O — so it is unit-tested directly.
 *
 * Isolation: every run gets its own `CLAUDE_CONFIG_DIR`, loads no user/project settings, MCP servers,
 * slash commands, CLAUDE.md files or memory, and never persists the session. Anthropic (API key auth)
 * additionally runs in `--bare` mode; `--bare` ignores `ANTHROPIC_AUTH_TOKEN`, so Bearer-token backends
 * (Ollama, OpenRouter, LiteLLM, …) run without it.
 */
object ClaudeCommandBuilder {

  /** Tools whose permission rules use the `Edit(...)` file-path syntax. Confined to the run's working directory. */
  private val FILE_EDIT_TOOLS = setOf("Write", "Edit")

  /** Bash command names / rules: letters, digits and `._+-` for bare names. */
  private val BARE_COMMAND = Regex("^[A-Za-z0-9._+-]+$")

  fun build(
    request: AgentRequest,
    cliPath: String,
    configDir: Path,
    mcpConfigFile: Path?
  ): ClaudeCommand {
    val args = mutableListOf(cliPath, "-p")
    if (request.backend.authMode == AuthMode.API_KEY) {
      args += "--bare"
    }
    args += listOf(
      "--output-format", "stream-json",
      "--verbose",
      "--no-session-persistence",
      "--permission-mode", "dontAsk",
      "--setting-sources", "",
      "--strict-mcp-config",
      "--disable-slash-commands",
      "--max-turns", request.maxTurns.toString(),
      "--tools", request.tools.joinToString(",")
    )

    val allowRules = allowRules(request)
    if (allowRules.isNotEmpty()) {
      args += "--allowedTools"
      args += allowRules
    }
    val denyRules = if ("Bash" in request.tools) request.bashDenyList.flatMap { bashRules(it) }.distinct() else emptyList()
    if (denyRules.isNotEmpty()) {
      args += "--disallowedTools"
      args += denyRules
    }

    request.model.nonBlank()?.let { args += listOf("--model", it) }
    request.fallbackModel.nonBlank()?.let { args += listOf("--fallback-model", it) }
    request.systemPrompt.nonBlank()?.let {
      val flag = if (request.systemPromptMode == SystemPromptMode.REPLACE) "--system-prompt" else "--append-system-prompt"
      args += listOf(flag, it)
    }
    request.outputSchema.nonBlank()?.let { args += listOf("--json-schema", it) }
    mcpConfigFile?.let { args += listOf("--mcp-config", it.toString()) }
    if (request.maxBudgetUsd > 0) {
      args += listOf("--max-budget-usd", request.maxBudgetUsd.toString())
    }
    request.effort.nonBlank()?.let { args += listOf("--effort", it) }

    return ClaudeCommand(args = args, env = env(request, configDir))
  }

  private fun allowRules(request: AgentRequest): List<String> =
    request.tools.flatMap { tool ->
      when (tool) {
        "Bash" -> request.bashAllowList.flatMap { bashRules(it) }
        in FILE_EDIT_TOOLS -> listOf("Edit(./**)")
        else -> listOf(tool)
      }
    }.distinct()

  /**
   * Turns an allow/deny list entry into permission rules. A bare command name covers the command with and
   * without arguments (`Bash(curl)`, `Bash(curl *)`); anything else (e.g. `Bash(git status)`, `git push *`)
   * is used verbatim, wrapped in `Bash(...)` when needed.
   */
  internal fun bashRules(entry: String): List<String> {
    val trimmed = entry.trim()
    return when {
      trimmed.isEmpty() -> emptyList()
      trimmed.startsWith("Bash(") && trimmed.endsWith(")") -> listOf(trimmed)
      BARE_COMMAND.matches(trimmed) -> listOf("Bash($trimmed)", "Bash($trimmed *)")
      else -> listOf("Bash($trimmed)")
    }
  }

  private fun env(request: AgentRequest, configDir: Path): Map<String, String> {
    val env = linkedMapOf(
      "CLAUDE_CONFIG_DIR" to configDir.toString(),
      // Retries are bounded by the per-row timeout; the CLI default (10 with backoff) would eat it up
      "CLAUDE_CODE_MAX_RETRIES" to "2",
      "CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC" to "1",
      "CLAUDE_CODE_DISABLE_CLAUDE_MDS" to "1",
      "CLAUDE_CODE_DISABLE_AUTO_MEMORY" to "1",
      "DISABLE_TELEMETRY" to "1",
      "DISABLE_AUTOUPDATER" to "1"
    )
    val backend = request.backend
    val token = request.token.nonBlank()
    when (backend.authMode) {
      AuthMode.API_KEY -> {
        token?.let { env["ANTHROPIC_API_KEY"] = it }
      }
      AuthMode.BEARER -> {
        env["ANTHROPIC_BASE_URL"] = backend.resolveBaseUrl(request.baseUrl)!!
        env["ANTHROPIC_AUTH_TOKEN"] = token ?: backend.placeholderToken
        env["ANTHROPIC_API_KEY"] = ""
        // Background calls (titles, summaries) would otherwise request Claude model names the backend doesn't have
        request.model.nonBlank()?.let { model ->
          env["ANTHROPIC_MODEL"] = model
          env["ANTHROPIC_DEFAULT_OPUS_MODEL"] = model
          env["ANTHROPIC_DEFAULT_SONNET_MODEL"] = model
          env["ANTHROPIC_DEFAULT_HAIKU_MODEL"] = model
          env["ANTHROPIC_SMALL_FAST_MODEL"] = model
        }
      }
    }
    return env
  }

  private fun String?.nonBlank(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
}
