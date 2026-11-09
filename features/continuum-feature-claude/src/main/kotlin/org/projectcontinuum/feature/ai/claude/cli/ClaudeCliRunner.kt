package org.projectcontinuum.feature.ai.claude.cli

import jakarta.annotation.PostConstruct
import org.projectcontinuum.core.commons.exception.NodeRuntimeException
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.io.IOException
import java.io.Reader
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Runs one agent request to completion. Abstracted so the node can be tested without the CLI. */
fun interface AgentRunner {
  /**
   * @param onProgress Receives short progress messages (current tool call); invoked on the calling thread.
   *   Exceptions it throws (e.g. a cancelled Temporal heartbeat) stop the run and propagate unchanged.
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
 * The workspace is deleted after the run. However the run ends (success, timeout, interrupt, an exception
 * from `onProgress`), the CLI and every tool subprocess it started get SIGTERM, then SIGKILL after a grace period.
 * At most `max-concurrent-runs` CLI processes run at once per worker; further runs wait for a free slot.
 *
 * Configuration (all optional):
 * - `org.projectcontinuum.feature.ai.claude-agent.claude-cli-path` — CLI executable (default `claude` on PATH)
 * - `org.projectcontinuum.feature.ai.claude-agent.workspace-path` — workspace root (default `<java.io.tmpdir>/continuum-claude-agent`)
 * - `org.projectcontinuum.feature.ai.claude-agent.max-concurrent-runs` — CLI processes per worker (default 4)
 */
@Component
class ClaudeCliRunner(
  private val objectMapper: ObjectMapper,
  @param:Value($$"${org.projectcontinuum.feature.ai.claude-agent.claude-cli-path:claude}")
  private val cliPath: String,
  @param:Value($$"${org.projectcontinuum.feature.ai.claude-agent.workspace-path:${java.io.tmpdir}/continuum-claude-agent}")
  private val workspacePath: String,
  @param:Value($$"${org.projectcontinuum.feature.ai.claude-agent.max-concurrent-runs:4}")
  private val maxConcurrentRuns: Int
) : AgentRunner {

  companion object {
    private val LOGGER = LoggerFactory.getLogger(ClaudeCliRunner::class.java)

    private const val POLL_INTERVAL_MS = 500L
    private const val SLOT_WAIT_REPORT_INTERVAL_MS = 30_000L
    private const val TERMINATE_GRACE_SECONDS = 5L
    private const val STDERR_TAIL_CHARS = 2000

    /** Longest stdout line kept (one stream-json event); longer lines are skipped so one event cannot exhaust the heap. */
    internal const val MAX_LINE_CHARS = 8 * 1024 * 1024

    /** Steps kept per run when intermediate steps are requested. */
    private const val MAX_STEPS = 500

    /**
     * Only these inherited variables reach the CLI (and so its Bash tool): everything else in the worker's
     * environment (database URLs, cloud keys, Continuum service tokens, …) is dropped, because the agent can
     * be talked into printing it. Credentials the agent needs come from the node's Continuum credential.
     */
    private val ENV_ALLOW_EXACT = setOf(
      "PATH", "HOME", "USER", "LOGNAME", "SHELL", "LANG", "LANGUAGE", "TZ", "TERM", "TMPDIR", "TEMP", "TMP",
      "HTTP_PROXY", "HTTPS_PROXY", "NO_PROXY", "ALL_PROXY", "http_proxy", "https_proxy", "no_proxy", "all_proxy",
      "SSL_CERT_FILE", "SSL_CERT_DIR", "NODE_EXTRA_CA_CERTS", "NODE_USE_SYSTEM_CA", "REQUESTS_CA_BUNDLE", "CURL_CA_BUNDLE"
    )
    private const val ENV_ALLOW_PREFIX = "LC_"

    /** The environment handed to the CLI: [inherited] filtered to the allow-list, plus the builder's [extra] vars. */
    internal fun agentEnvironment(inherited: Map<String, String>, extra: Map<String, String>): Map<String, String> =
      inherited.filterKeys { it in ENV_ALLOW_EXACT || it.startsWith(ENV_ALLOW_PREFIX) } + extra
  }

  private val slots = Semaphore(maxConcurrentRuns.coerceAtLeast(1), true)

  @PostConstruct
  fun checkCli() {
    try {
      val process = ProcessBuilder(cliPath, "--version").redirectErrorStream(true).start()
      if (process.waitFor(30, TimeUnit.SECONDS)) {
        val version = process.inputStream.bufferedReader().use { it.readText() }.trim()
        LOGGER.info("Claude AI Agent: using Claude Code CLI '$cliPath' ($version), max $maxConcurrentRuns concurrent run(s)")
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
    acquireSlot(onProgress)
    try {
      val workspace = Paths.get(workspacePath).toAbsolutePath().resolve(UUID.randomUUID().toString())
      try {
        return runInWorkspace(request, workspace, onProgress)
      } finally {
        deleteRecursively(workspace)
      }
    } finally {
      slots.release()
    }
  }

  /** Waits interruptibly for a run slot, reporting the wait now and then so heartbeats (and cancellation) keep flowing. */
  private fun acquireSlot(onProgress: (String) -> Unit) {
    if (slots.tryAcquire()) return
    var nextReportAt = 0L
    while (!slots.tryAcquire(POLL_INTERVAL_MS, TimeUnit.MILLISECONDS)) {
      val now = System.currentTimeMillis()
      if (now >= nextReportAt) {
        onProgress("waiting for a free agent slot ($maxConcurrentRuns in use on this worker)")
        nextReportAt = now + SLOT_WAIT_REPORT_INTERVAL_MS
      }
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
      val sanitized = agentEnvironment(System.getenv(), command.env)
      clear()
      putAll(sanitized)
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

    // Every descendant seen while polling, so tool subprocesses that outlive the CLI are still killed at the end
    val descendants = ConcurrentHashMap<Long, ProcessHandle>()
    val shutdownHook = Thread { killTree(process, descendants) }
    Runtime.getRuntime().addShutdownHook(shutdownHook)

    // The parser is shared by the stdout pump and this thread; every access holds its monitor
    val parser = StreamJsonParser(objectMapper, maxSteps = if (request.collectSteps) MAX_STEPS else 0)
    val latestProgress = AtomicReference<String?>()
    val pumpFailure = AtomicReference<Throwable?>()
    val skippedLines = AtomicInteger()
    val stdoutPump = Thread({
      try {
        process.inputStream.bufferedReader().use { reader ->
          reader.forEachLineBounded(MAX_LINE_CHARS, onOversized = { length ->
            skippedLines.incrementAndGet()
            LOGGER.warn("Skipped a $length-char line of agent output (limit $MAX_LINE_CHARS)")
          }) { line ->
            synchronized(parser) { parser.accept(line) }?.let { latestProgress.set(it) }
          }
        }
      } catch (e: Throwable) {
        pumpFailure.set(e)
      }
    }, "claude-agent-stdout").apply { isDaemon = true; start() }

    var timedOut = false
    try {
      val deadline = startedAt + TimeUnit.SECONDS.toMillis(request.timeoutSeconds)
      while (!process.waitFor(POLL_INTERVAL_MS, TimeUnit.MILLISECONDS)) {
        trackDescendants(process, descendants)
        latestProgress.getAndSet(null)?.let(onProgress)
        if (System.currentTimeMillis() >= deadline) {
          timedOut = true
          LOGGER.warn("Agent run exceeded ${request.timeoutSeconds}s — terminating")
          break
        }
      }
    } finally {
      // Any exit path — timeout, interrupt, exception from onProgress, normal exit with leftover tool processes
      killTree(process, descendants)
      try {
        Runtime.getRuntime().removeShutdownHook(shutdownHook)
      } catch (_: IllegalStateException) {
        // JVM is already shutting down — the hook runs anyway
      }
    }

    // stdout reaches EOF once every process holding it is gone; one that escaped the tree can keep it open
    stdoutPump.join(TimeUnit.SECONDS.toMillis(TERMINATE_GRACE_SECONDS))
    val pumpStuck = stdoutPump.isAlive
    latestProgress.getAndSet(null)?.let(onProgress)

    val elapsedMs = System.currentTimeMillis() - startedAt
    val exitCode = if (process.isAlive) -1 else process.exitValue()
    val streamProblems = listOfNotNull(
      skippedLines.get().takeIf { it > 0 }?.let { "$it output line(s) over $MAX_LINE_CHARS chars skipped" },
      pumpFailure.get()?.let { "reading the output failed: ${it.message ?: it.javaClass.simpleName}" },
      "the output stream did not close".takeIf { pumpStuck }
    )
    if (pumpStuck) LOGGER.warn("Agent output stream still open ${TERMINATE_GRACE_SECONDS}s after the CLI exited")
    pumpFailure.get()?.let { LOGGER.warn("Reading agent output failed", it) }

    val fallbackError = when {
      timedOut -> "Timed out after ${request.timeoutSeconds}s"
      else -> "Claude Code exited with code $exitCode without a result: ${stderrTail(stderrFile).ifBlank { "no error output" }}"
    } + streamProblems.joinToString("") { "; $it" }
    val result = synchronized(parser) { parser.toResult(fallbackError, elapsedMs) }
    return when {
      timedOut -> result.copy(isError = true, error = fallbackError)
      // the answer is kept, but a crash after the final event must not pass as a clean run
      !result.isError && exitCode != 0 -> result.copy(
        isError = true,
        error = "Claude Code exited with code $exitCode after reporting success: ${stderrTail(stderrFile).ifBlank { "no error output" }}"
      )
      else -> result
    }
  }

  private fun trackDescendants(process: Process, descendants: MutableMap<Long, ProcessHandle>) {
    descendants.values.removeIf { !it.isAlive }
    process.descendants().forEach { descendants[it.pid()] = it }
  }

  /**
   * SIGTERM the CLI and all its (current and previously seen) descendants, then SIGKILL whatever is left after
   * the grace period. Safe to call with the interrupt flag set and on an already exited process.
   */
  private fun killTree(process: Process, descendants: MutableMap<Long, ProcessHandle>) {
    if (process.isAlive) trackDescendants(process, descendants)
    val tree = (listOf(process.toHandle()) + descendants.values).filter { it.isAlive }
    if (tree.isEmpty()) return
    tree.forEach { it.destroy() }
    if (!awaitExit(tree)) tree.filter { it.isAlive }.forEach { it.destroyForcibly() }
    awaitExit(tree)
  }

  /**
   * Waits up to the grace period for [handles] to exit. A pending interrupt does not skip the wait (the tree
   * still gets its grace period) and is restored afterwards; a new interrupt during the wait ends it early.
   */
  private fun awaitExit(handles: List<ProcessHandle>): Boolean {
    var interrupted = Thread.interrupted()
    val deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(TERMINATE_GRACE_SECONDS)
    try {
      while (handles.any { it.isAlive } && System.currentTimeMillis() < deadline) Thread.sleep(50)
    } catch (_: InterruptedException) {
      interrupted = true
    } finally {
      if (interrupted) Thread.currentThread().interrupt()
    }
    return handles.none { it.isAlive }
  }

  /** The last [STDERR_TAIL_CHARS] of stderr, reading only the end of the file. */
  private fun stderrTail(file: Path): String = try {
    FileChannel.open(file).use { channel ->
      // UTF-8 needs at most 4 bytes per char
      val bytes = minOf(channel.size(), STDERR_TAIL_CHARS * 4L).toInt()
      val buffer = ByteBuffer.allocate(bytes)
      channel.position(channel.size() - bytes)
      while (buffer.hasRemaining() && channel.read(buffer) >= 0) Unit
      String(buffer.array(), 0, buffer.position(), Charsets.UTF_8).trim().takeLast(STDERR_TAIL_CHARS)
    }
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

/**
 * Reads lines like [java.io.BufferedReader.readLine] (split on `\n`, a trailing `\r` is kept), but never buffers
 * more than [maxChars] of a line: a longer line is dropped and its length passed to [onOversized].
 */
internal fun Reader.forEachLineBounded(maxChars: Int, onOversized: (Long) -> Unit, onLine: (String) -> Unit) {
  val buffer = CharArray(8192)
  val line = StringBuilder()
  var lineLength = 0L
  var inLine = false

  fun append(from: Int, to: Int) {
    if (to <= from) return
    inLine = true
    lineLength += to - from
    if (lineLength <= maxChars) line.appendRange(buffer, from, to) else line.setLength(0)
  }

  fun endLine() {
    if (lineLength > maxChars) onOversized(lineLength) else onLine(line.toString())
    line.setLength(0)
    lineLength = 0
    inLine = false
  }

  while (true) {
    val read = read(buffer)
    if (read < 0) break
    var start = 0
    for (i in 0 until read) {
      if (buffer[i] == '\n') {
        append(start, i)
        endLine()
        start = i + 1
      }
    }
    append(start, read)
  }
  if (inLine) endLine()
}
