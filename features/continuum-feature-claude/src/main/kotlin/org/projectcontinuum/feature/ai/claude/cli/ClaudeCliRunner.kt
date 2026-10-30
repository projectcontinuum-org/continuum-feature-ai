package org.projectcontinuum.feature.ai.claude.cli

import jakarta.annotation.PostConstruct
import org.projectcontinuum.core.commons.exception.NodeRuntimeException
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Runs one agent request to completion. Abstracted so the node can be tested without the CLI. */
fun interface AgentRunner {
  /**
   * @param onProgress Receives short progress messages (current tool call); invoked on the calling thread
   */
  fun run(request: AgentRequest, onProgress: (String) -> Unit): AgentResult
}

/**
 * [AgentRunner] backed by the Claude Code CLI (`claude -p`).
 *
 * Each run gets a private workspace `<workspace-path>/<uuid>/` containing:
 * - `work/` — the agent's working directory (`./input.json` lives here; file edits are confined to it)
 * - `config/` — `CLAUDE_CONFIG_DIR`, so no host settings, memory or sessions leak in or out
 * - `prompt.txt`, `mcp.json`, `stderr.log` — CLI plumbing, outside the agent's working directory
 *
 * The workspace is deleted after the run. Timeouts send SIGTERM, then SIGKILL after a grace period.
 *
 * Configuration (all optional):
 * - `org.projectcontinuum.feature.ai.claude-agent.claude-cli-path` — CLI executable (default `claude` on PATH)
 * - `org.projectcontinuum.feature.ai.claude-agent.workspace-path` — workspace root (default `<java.io.tmpdir>/continuum-claude-agent`)
 */
@Component
class ClaudeCliRunner(
  private val objectMapper: ObjectMapper,
  @param:Value($$"${org.projectcontinuum.feature.ai.claude-agent.claude-cli-path:claude}")
  private val cliPath: String,
  @param:Value($$"${org.projectcontinuum.feature.ai.claude-agent.workspace-path:${java.io.tmpdir}/continuum-claude-agent}")
  private val workspacePath: String
) : AgentRunner {

  companion object {
    private val LOGGER = LoggerFactory.getLogger(ClaudeCliRunner::class.java)

    private const val POLL_INTERVAL_MS = 500L
    private const val TERMINATE_GRACE_SECONDS = 5L
    private const val STDERR_TAIL_CHARS = 2000

    /** Inherited worker env vars that must never reach the agent: tokens come only from Continuum credentials. */
    private val SCRUBBED_ENV = listOf("ANTHROPIC_API_KEY", "ANTHROPIC_AUTH_TOKEN", "CLAUDE_CODE_OAUTH_TOKEN")
  }

  @PostConstruct
  fun checkCli() {
    try {
      val process = ProcessBuilder(cliPath, "--version").redirectErrorStream(true).start()
      if (process.waitFor(30, TimeUnit.SECONDS)) {
        val version = process.inputStream.bufferedReader().use { it.readText() }.trim()
        LOGGER.info("Claude AI Agent: using Claude Code CLI '$cliPath' ($version)")
      } else {
        process.destroyForcibly()
        LOGGER.warn("Claude AI Agent: '$cliPath --version' did not finish within 30s")
      }
    } catch (e: IOException) {
      LOGGER.warn(
        "Claude AI Agent: Claude Code CLI not found at '$cliPath' — the node will fail until it is installed " +
          "(https://code.claude.com/docs/en/setup) or 'org.projectcontinuum.feature.ai.claude-agent.claude-cli-path' is set. ${e.message}"
      )
    }
  }

  override fun run(request: AgentRequest, onProgress: (String) -> Unit): AgentResult {
    val workspace = Paths.get(workspacePath).toAbsolutePath().resolve(UUID.randomUUID().toString())
    try {
      return runInWorkspace(request, workspace, onProgress)
    } finally {
      deleteRecursively(workspace)
    }
  }

  private fun runInWorkspace(request: AgentRequest, workspace: Path, onProgress: (String) -> Unit): AgentResult {
    val workDir = Files.createDirectories(workspace.resolve("work"))
    val configDir = Files.createDirectories(workspace.resolve("config"))
    val promptFile = Files.writeString(workspace.resolve("prompt.txt"), request.prompt)
    val stderrFile = workspace.resolve("stderr.log")
    request.inputJson?.let { Files.writeString(workDir.resolve("input.json"), it) }
    val mcpFile = request.mcpConfig?.let { Files.writeString(workspace.resolve("mcp.json"), it) }

    val command = ClaudeCommandBuilder.build(request, cliPath, configDir, mcpFile)
    LOGGER.debug("Running agent: {}", command.args)

    val processBuilder = ProcessBuilder(command.args)
      .directory(workDir.toFile())
      .redirectInput(promptFile.toFile())
      .redirectError(stderrFile.toFile())
    processBuilder.environment().apply {
      SCRUBBED_ENV.forEach { remove(it) }
      putAll(command.env)
    }

    val startedAt = System.currentTimeMillis()
    val process = try {
      processBuilder.start()
    } catch (e: IOException) {
      throw NodeRuntimeException(
        isRetriable = false,
        workflowId = "",
        nodeId = "",
        message = "Claude Code CLI could not be started ('$cliPath'): ${e.message}. Install Claude Code on the worker " +
          "or set org.projectcontinuum.feature.ai.claude-agent.claude-cli-path."
      )
    }

    val shutdownHook = Thread { process.descendants().forEach { it.destroyForcibly() }; process.destroyForcibly() }
    Runtime.getRuntime().addShutdownHook(shutdownHook)

    val parser = StreamJsonParser(objectMapper)
    val latestProgress = AtomicReference<String?>()
    val stdoutPump = Thread({
      process.inputStream.bufferedReader().useLines { lines ->
        lines.forEach { line -> parser.accept(line)?.let { latestProgress.set(it) } }
      }
    }, "claude-agent-stdout").apply { isDaemon = true; start() }

    var timedOut = false
    try {
      val deadline = startedAt + TimeUnit.SECONDS.toMillis(request.timeoutSeconds)
      while (!process.waitFor(POLL_INTERVAL_MS, TimeUnit.MILLISECONDS)) {
        latestProgress.getAndSet(null)?.let(onProgress)
        if (System.currentTimeMillis() >= deadline) {
          timedOut = true
          LOGGER.warn("Agent run exceeded ${request.timeoutSeconds}s — terminating")
          terminate(process)
          break
        }
      }
      stdoutPump.join(TimeUnit.SECONDS.toMillis(TERMINATE_GRACE_SECONDS))
    } catch (e: InterruptedException) {
      terminate(process)
      Thread.currentThread().interrupt()
      throw e
    } finally {
      try {
        Runtime.getRuntime().removeShutdownHook(shutdownHook)
      } catch (_: IllegalStateException) {
        // JVM is already shutting down — the hook runs anyway
      }
    }
    latestProgress.getAndSet(null)?.let(onProgress)

    val elapsedMs = System.currentTimeMillis() - startedAt
    val exitCode = if (process.isAlive) -1 else process.exitValue()
    val fallbackError = when {
      timedOut -> "Timed out after ${request.timeoutSeconds}s"
      else -> "Claude Code exited with code $exitCode without a result: ${stderrTail(stderrFile).ifBlank { "no error output" }}"
    }
    val result = parser.toResult(fallbackError, elapsedMs)
    return if (timedOut) result.copy(isError = true, error = fallbackError) else result
  }

  /** SIGTERM the CLI and its tool subprocesses, then SIGKILL whatever is left after the grace period. */
  private fun terminate(process: Process) {
    val children = process.descendants().toList()
    process.destroy()
    children.forEach { it.destroy() }
    if (!process.waitFor(TERMINATE_GRACE_SECONDS, TimeUnit.SECONDS)) {
      process.destroyForcibly()
    }
    children.filter { it.isAlive }.forEach { it.destroyForcibly() }
  }

  private fun stderrTail(file: Path): String = try {
    Files.readString(file).trim().takeLast(STDERR_TAIL_CHARS)
  } catch (_: IOException) {
    ""
  }

  private fun deleteRecursively(path: Path) {
    try {
      path.toFile().deleteRecursively()
    } catch (e: Exception) {
      LOGGER.warn("Failed to delete agent workspace $path: ${e.message}")
    }
  }
}
