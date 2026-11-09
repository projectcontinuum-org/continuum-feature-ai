package org.projectcontinuum.feature.ai.claude.cli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.io.StringReader
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText

/**
 * Runs [ClaudeCliRunner] against real processes: a shell script stands in for the `claude` CLI and prints
 * canned stream-json, sleeps, crashes or spawns tool subprocesses.
 */
@DisabledOnOs(OS.WINDOWS)
class ClaudeCliRunnerProcessTest {

    @TempDir
    lateinit var dir: Path

    private val objectMapper = jacksonObjectMapper()

    private val init = """{"type":"system","subtype":"init","model":"sonnet-latest"}"""
    private val toolUse =
        """{"type":"assistant","message":{"model":"claude-sonnet-5.5","content":[{"type":"tool_use","id":"t1","name":"Bash","input":{"command":"jq .name input.json"}}]}}"""
    private val success =
        """{"type":"result","subtype":"success","is_error":false,"num_turns":2,"result":"Alice","total_cost_usd":0.01,"usage":{"input_tokens":5,"output_tokens":3}}"""

    private val workspace get() = dir.resolve("ws")

    /** A fake CLI running [body] in `sh`; it sees the prompt on stdin like the real one. */
    private fun runner(body: String, maxConcurrentRuns: Int = 4): ClaudeCliRunner {
        val script = dir.resolve("claude")
        Files.writeString(script, "#!/bin/sh\n$body\n")
        script.toFile().setExecutable(true)
        return ClaudeCliRunner(objectMapper, script.toString(), workspace.toString(), maxConcurrentRuns)
    }

    private fun request(timeoutSeconds: Long = 30) =
        AgentRequest(prompt = "hi", timeoutSeconds = timeoutSeconds, inputJson = """{"name":"Alice"}""")

    private fun echo(vararg lines: String) = lines.joinToString("\n") { "printf '%s\\n' '$it'" }

    /** Starts a long-running tool subprocess and records its PID, like the agent's Bash tool would. */
    private fun spawnTool(pidFile: Path) = "sleep 60 &\necho \$! > '$pidFile'"

    private fun toolHandle(pidFile: Path): ProcessHandle? = ProcessHandle.of(pidFile.readText().trim().toLong()).orElse(null)

    private fun assertDead(pidFile: Path) {
        val handle = toolHandle(pidFile) ?: return
        val deadline = System.currentTimeMillis() + 5_000
        while (handle.isAlive && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertFalse(handle.isAlive, "tool subprocess ${handle.pid()} is still running")
    }

    private fun assertWorkspaceCleaned() {
        assertTrue(!workspace.exists() || workspace.listDirectoryEntries().isEmpty(), "workspace not deleted")
    }

    @Test
    fun `successful run reads stdin, sees input json and is parsed`() {
        val seen = dir.resolve("seen.txt")
        val result = runner(
            """
            cat > '$seen'
            cat input.json >> '$seen'
            ${echo(init, toolUse, success)}
            """.trimIndent()
        ).run(request()) {}

        assertFalse(result.isError, result.error)
        assertEquals("Alice", result.response)
        assertEquals(2, result.numTurns)
        assertEquals("""hi{"name":"Alice"}""", seen.readText())
        assertEquals(listOf("tool_use"), result.steps.map { it["type"] })
        assertWorkspaceCleaned()
    }

    @Test
    fun `progress messages reach the caller`() {
        val messages = mutableListOf<String>()
        runner("${echo(init, toolUse)}\nsleep 1\n${echo(success)}").run(request()) { messages += it }
        assertTrue("Bash: jq .name input.json" in messages, messages.toString())
    }

    @Test
    fun `timeout kills the CLI and its tool subprocesses`() {
        val pidFile = dir.resolve("tool.pid")
        val startedAt = System.currentTimeMillis()
        val result = runner("${spawnTool(pidFile)}\n${echo(init)}\nsleep 60").run(request(timeoutSeconds = 1)) {}

        assertTrue(result.isError)
        assertTrue(result.error.startsWith("Timed out after 1s"), result.error)
        assertTrue(System.currentTimeMillis() - startedAt < 15_000)
        assertDead(pidFile)
        assertWorkspaceCleaned()
    }

    @Test
    fun `an exception from onProgress kills the process tree and propagates`() {
        val pidFile = dir.resolve("tool.pid")
        val cancelled = IllegalStateException("activity cancelled")
        val thrown = assertThrows<IllegalStateException> {
            runner("${spawnTool(pidFile)}\n${echo(init)}\nsleep 60").run(request()) { throw cancelled }
        }

        assertEquals(cancelled, thrown)
        assertDead(pidFile)
        assertWorkspaceCleaned()
    }

    @Test
    fun `interrupting the caller kills the process tree`() {
        val pidFile = dir.resolve("tool.pid")
        val agentRunner = runner("${spawnTool(pidFile)}\nsleep 60")
        var thrown: Throwable? = null
        val caller = thread {
            try {
                agentRunner.run(request()) {}
            } catch (e: Throwable) {
                thrown = e
            }
        }
        // wait for the tool to start, then interrupt
        val deadline = System.currentTimeMillis() + 10_000
        while (!(pidFile.exists() && pidFile.readText().isNotBlank()) && System.currentTimeMillis() < deadline) Thread.sleep(50)
        caller.interrupt()
        caller.join(15_000)

        assertFalse(caller.isAlive)
        assertTrue(thrown is InterruptedException, "got $thrown")
        assertDead(pidFile)
    }

    @Test
    fun `a background process holding stdout open does not hang the run`() {
        val pidFile = dir.resolve("tool.pid")
        val startedAt = System.currentTimeMillis()
        // the CLI exits at once, its orphaned child inherited stdout and keeps it open. An orphan of an already
        // exited CLI is no longer its descendant, so the runner cannot kill it; it must just not wait forever.
        try {
            val result = runner("${echo(init, success)}\n${spawnTool(pidFile)}\nexit 0").run(request()) {}

            assertFalse(result.isError, result.error)
            assertEquals("Alice", result.response)
            assertTrue(System.currentTimeMillis() - startedAt < 15_000)
        } finally {
            toolHandle(pidFile)?.destroyForcibly()
        }
    }

    @Test
    fun `crash without a result reports exit code and stderr tail`() {
        val result = runner("${echo(init)}\necho 'boom: missing API key' >&2\nexit 3").run(request()) {}

        assertTrue(result.isError)
        assertEquals("Claude Code exited with code 3 without a result: boom: missing API key", result.error)
    }

    @Test
    fun `stderr tail is bounded`() {
        val result = runner("head -c 200000 /dev/zero | tr '\\0' x >&2\necho END >&2\nexit 1").run(request()) {}

        assertTrue(result.error.endsWith("xEND"), result.error.takeLast(40))
        assertTrue(result.error.length < 2200, "error is ${result.error.length} chars")
    }

    @Test
    fun `non-zero exit after a success result is an error`() {
        val result = runner("${echo(init, success)}\necho 'segfault' >&2\nexit 139").run(request()) {}

        assertTrue(result.isError)
        assertEquals("Alice", result.response)
        assertEquals("Claude Code exited with code 139 after reporting success: segfault", result.error)
    }

    @Test
    fun `malformed and oversized lines are skipped`() {
        val big = dir.resolve("big.txt")
        // one line longer than the limit, then valid events
        Files.writeString(big, "{\"type\":\"assistant\",\"x\":\"" + "y".repeat(ClaudeCliRunner.MAX_LINE_CHARS) + "\"}\n")
        val result = runner(
            """
            echo 'not json'
            echo '{broken'
            cat '$big'
            ${echo(init, success)}
            """.trimIndent()
        ).run(request()) {}

        assertTrue(result.response == "Alice", result.error)
    }

    @Test
    fun `missing CLI is a non-retriable configuration error`() {
        val agentRunner = ClaudeCliRunner(objectMapper, dir.resolve("nope").toString(), workspace.toString(), 1)
        val e = assertThrows<org.projectcontinuum.core.commons.exception.NodeRuntimeException> { agentRunner.run(request()) {} }
        assertFalse(e.isRetriable)
        assertWorkspaceCleaned()
    }

    @Test
    fun `concurrent runs are limited`() {
        val running = dir.resolve("running")
        val peak = dir.resolve("peak")
        Files.createDirectories(running)
        // each run registers itself, records how many runs are active, then finishes
        val agentRunner = runner(
            """
            touch '$running'/$$
            ls '$running' | wc -l >> '$peak'
            sleep 1
            rm '$running'/$$
            ${echo(success)}
            """.trimIndent(),
            maxConcurrentRuns = 2
        )
        val waiting = AtomicInteger()
        val start = CountDownLatch(1)
        val threads = (1..4).map {
            thread {
                start.await()
                agentRunner.run(request()) { if (it.startsWith("waiting for a free agent slot")) waiting.incrementAndGet() }
            }
        }
        start.countDown()
        threads.forEach { it.join(TimeUnit.SECONDS.toMillis(30)) }

        val counts = peak.readText().lines().filter { it.isNotBlank() }.map { it.trim().toInt() }
        assertEquals(4, counts.size)
        assertTrue(counts.max() <= 2, "peak concurrency ${counts.max()}")
        assertTrue(waiting.get() > 0, "no run reported waiting for a slot")
    }

    @Test
    fun `bounded line reader splits lines and drops oversized ones`() {
        val lines = mutableListOf<String>()
        val oversized = mutableListOf<Long>()
        StringReader("a\n" + "x".repeat(20) + "\n\nlast").forEachLineBounded(10, { oversized += it }) { lines += it }

        assertEquals(listOf("a", "", "last"), lines)
        assertEquals(listOf(20L), oversized)
    }
}
