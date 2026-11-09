package org.projectcontinuum.feature.ai.claude.cli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.nio.file.Path

/**
 * End-to-end runs against a real Claude Code CLI and backend. Skipped unless `CLAUDE_LIVE_TEST` is set:
 * - `CLAUDE_LIVE_TEST_CLI` — CLI path (default `claude`)
 * - `CLAUDE_LIVE_TEST_BACKEND` — [BackendPreset] name (default `LITELLM`), `CLAUDE_LIVE_TEST_BASE_URL`, `CLAUDE_LIVE_TEST_TOKEN`
 * - `CLAUDE_LIVE_TEST_MODEL` — model (default `sonnet`)
 */
@EnabledIfEnvironmentVariable(named = "CLAUDE_LIVE_TEST", matches = ".+")
class ClaudeCliLiveTest {

    @TempDir
    lateinit var workspace: Path

    private fun env(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }

    private val runner by lazy {
        ClaudeCliRunner(jacksonObjectMapper(), env("CLAUDE_LIVE_TEST_CLI") ?: "claude", workspace.toString(), 2)
    }

    private fun request(prompt: String) = AgentRequest(
        prompt = prompt,
        backend = BackendPreset.fromProperty(env("CLAUDE_LIVE_TEST_BACKEND") ?: "LITELLM"),
        baseUrl = env("CLAUDE_LIVE_TEST_BASE_URL"),
        token = env("CLAUDE_LIVE_TEST_TOKEN"),
        model = env("CLAUDE_LIVE_TEST_MODEL") ?: "sonnet"
    )

    @Test
    fun `allowed tool runs, denied tool is refused, structured output matches the schema`() {
        val progress = mutableListOf<String>()
        val result = runner.run(
            request("Use jq to read .name from ./input.json, then run `rm input.json`. Answer with JSON per the schema.").copy(
                tools = listOf("Bash", "Read"), bashAllowList = listOf("jq", "cat"), bashDenyList = listOf("rm"),
                outputSchema = """{"type":"object","properties":{"name":{"type":"string"}},"required":["name"]}""",
                maxTurns = 6, timeoutSeconds = 120, inputJson = """{"name":"Alice"}"""
            )
        ) { progress += it }

        assertFalse(result.isError, result.error)
        assertEquals(mapOf("name" to "Alice"), result.structuredOutput)
        assertTrue(result.numTurns > 0)
        assertTrue(result.outputTokens > 0)
        assertTrue(result.model.isNotBlank())
        assertTrue(progress.any { it.startsWith("Bash: jq") }, progress.toString())
        assertTrue(
            result.steps.any { it["type"] == "permission_denied" && it["tool_name"] == "Bash" },
            "rm was not denied: ${result.steps}"
        )
    }

    @Test
    fun `timeout ends a long tool call`() {
        val startedAt = System.currentTimeMillis()
        val result = runner.run(
            request("Run: sleep 60").copy(tools = listOf("Bash"), bashAllowList = listOf("sleep"), timeoutSeconds = 10)
        ) {}

        assertTrue(result.isError)
        assertTrue(result.error.startsWith("Timed out after 10s"), result.error)
        assertTrue(System.currentTimeMillis() - startedAt < 30_000)
    }
}
