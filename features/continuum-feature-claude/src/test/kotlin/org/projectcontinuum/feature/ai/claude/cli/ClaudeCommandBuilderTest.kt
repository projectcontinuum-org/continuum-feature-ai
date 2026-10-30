package org.projectcontinuum.feature.ai.claude.cli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.file.Paths

class ClaudeCommandBuilderTest {

    private val configDir = Paths.get("/tmp/ws/config")

    private fun build(request: AgentRequest, mcpFile: String? = null) =
        ClaudeCommandBuilder.build(request, "claude", configDir, mcpFile?.let { Paths.get(it) })

    private fun List<String>.valueOf(flag: String): String? = indexOf(flag).takeIf { it >= 0 }?.let { this[it + 1] }

    /** Values following a variadic flag up to the next `--flag`. */
    private fun List<String>.valuesOf(flag: String): List<String> {
        val start = indexOf(flag).takeIf { it >= 0 } ?: return emptyList()
        return drop(start + 1).takeWhile { !it.startsWith("--") }
    }

    @Test
    fun `anthropic backend uses bare mode and api key`() {
        val cmd = build(AgentRequest(prompt = "p", token = "sk-ant-x", model = "sonnet"))

        assertEquals(listOf("claude", "-p", "--bare"), cmd.args.take(3))
        assertEquals("sk-ant-x", cmd.env["ANTHROPIC_API_KEY"])
        assertFalse(cmd.env.containsKey("ANTHROPIC_AUTH_TOKEN"))
        assertFalse(cmd.env.containsKey("ANTHROPIC_BASE_URL"))
        assertFalse(cmd.env.containsKey("ANTHROPIC_MODEL"))
        assertEquals("sonnet", cmd.args.valueOf("--model"))
    }

    @Test
    fun `common isolation flags and env are always set`() {
        val cmd = build(AgentRequest(prompt = "p", maxTurns = 7))

        assertEquals("stream-json", cmd.args.valueOf("--output-format"))
        assertEquals("dontAsk", cmd.args.valueOf("--permission-mode"))
        assertEquals("", cmd.args.valueOf("--setting-sources"))
        assertEquals("7", cmd.args.valueOf("--max-turns"))
        assertTrue(cmd.args.containsAll(listOf("--verbose", "--no-session-persistence", "--strict-mcp-config", "--disable-slash-commands")))
        assertEquals(configDir.toString(), cmd.env["CLAUDE_CONFIG_DIR"])
        assertEquals("2", cmd.env["CLAUDE_CODE_MAX_RETRIES"])
        assertEquals("1", cmd.env["DISABLE_TELEMETRY"])
        assertFalse(cmd.env.containsKey("CLAUDE_CODE_SUBPROCESS_ENV_SCRUB"))
        // prompt never goes on the command line
        assertFalse(cmd.args.contains("p"))
    }

    @Test
    fun `ollama backend uses bearer token, default url and pins all model aliases`() {
        val cmd = build(AgentRequest(prompt = "p", backend = BackendPreset.OLLAMA, model = "qwen3-coder"))

        assertFalse(cmd.args.contains("--bare"))
        assertEquals("http://localhost:11434", cmd.env["ANTHROPIC_BASE_URL"])
        assertEquals("ollama", cmd.env["ANTHROPIC_AUTH_TOKEN"])
        assertEquals("", cmd.env["ANTHROPIC_API_KEY"])
        listOf(
            "ANTHROPIC_MODEL", "ANTHROPIC_DEFAULT_OPUS_MODEL", "ANTHROPIC_DEFAULT_SONNET_MODEL",
            "ANTHROPIC_DEFAULT_HAIKU_MODEL", "ANTHROPIC_SMALL_FAST_MODEL"
        ).forEach { assertEquals("qwen3-coder", cmd.env[it], it) }
    }

    @Test
    fun `configured base url overrides default and strips v1 suffix`() {
        val cmd = build(
            AgentRequest(prompt = "p", backend = BackendPreset.OPENROUTER, baseUrl = "https://proxy.local/api/v1/", token = "or-key")
        )
        assertEquals("https://proxy.local/api", cmd.env["ANTHROPIC_BASE_URL"])
        assertEquals("or-key", cmd.env["ANTHROPIC_AUTH_TOKEN"])
    }

    @Test
    fun `custom backend without base url is rejected`() {
        assertThrows<IllegalArgumentException> {
            build(AgentRequest(prompt = "p", backend = BackendPreset.CUSTOM))
        }
    }

    @Test
    fun `tools, allow list and deny list become permission rules`() {
        val cmd = build(
            AgentRequest(
                prompt = "p",
                tools = listOf("Bash", "Read", "Write", "Edit"),
                bashAllowList = listOf("curl", "Bash(git status)", "npm run *"),
                bashDenyList = listOf("rm")
            )
        )

        assertEquals("Bash,Read,Write,Edit", cmd.args.valueOf("--tools"))
        assertEquals(
            listOf("Bash(curl)", "Bash(curl *)", "Bash(git status)", "Bash(npm run *)", "Read", "Edit(./**)"),
            cmd.args.valuesOf("--allowedTools")
        )
        assertEquals(listOf("Bash(rm)", "Bash(rm *)"), cmd.args.valuesOf("--disallowedTools"))
    }

    @Test
    fun `bash lists are ignored when bash is not enabled`() {
        val cmd = build(AgentRequest(prompt = "p", tools = listOf("Read"), bashAllowList = listOf("curl"), bashDenyList = listOf("rm")))

        assertEquals(listOf("Read"), cmd.args.valuesOf("--allowedTools"))
        assertFalse(cmd.args.contains("--disallowedTools"))
    }

    @Test
    fun `no tools disables all tools`() {
        val cmd = build(AgentRequest(prompt = "p", tools = emptyList()))
        assertEquals("", cmd.args.valueOf("--tools"))
        assertFalse(cmd.args.contains("--allowedTools"))
    }

    @Test
    fun `optional flags are added only when set`() {
        val bare = build(AgentRequest(prompt = "p"))
        listOf("--fallback-model", "--system-prompt", "--append-system-prompt", "--json-schema", "--mcp-config", "--max-budget-usd", "--effort")
            .forEach { assertFalse(bare.args.contains(it), it) }

        val full = build(
            AgentRequest(
                prompt = "p",
                fallbackModel = "haiku",
                systemPrompt = "Be terse",
                systemPromptMode = SystemPromptMode.REPLACE,
                outputSchema = """{"type":"object"}""",
                maxBudgetUsd = 0.5,
                effort = "high"
            ),
            mcpFile = "/tmp/ws/mcp.json"
        )
        assertEquals("haiku", full.args.valueOf("--fallback-model"))
        assertEquals("Be terse", full.args.valueOf("--system-prompt"))
        assertFalse(full.args.contains("--append-system-prompt"))
        assertEquals("""{"type":"object"}""", full.args.valueOf("--json-schema"))
        assertEquals("/tmp/ws/mcp.json", full.args.valueOf("--mcp-config"))
        assertEquals("0.5", full.args.valueOf("--max-budget-usd"))
        assertEquals("high", full.args.valueOf("--effort"))
    }

    @Test
    fun `append mode uses append-system-prompt`() {
        val cmd = build(AgentRequest(prompt = "p", systemPrompt = "Extra"))
        assertEquals("Extra", cmd.args.valueOf("--append-system-prompt"))
    }

    @Test
    fun `backend preset parsing`() {
        assertEquals(BackendPreset.ANTHROPIC, BackendPreset.fromProperty(null))
        assertEquals(BackendPreset.ANTHROPIC, BackendPreset.fromProperty(""))
        assertEquals(BackendPreset.LITELLM, BackendPreset.fromProperty("litellm"))
        assertNull(BackendPreset.ANTHROPIC.resolveBaseUrl("https://ignored"))
        assertThrows<IllegalArgumentException> { BackendPreset.fromProperty("GEMINI") }
        assertThrows<IllegalArgumentException> { BackendPreset.OLLAMA.resolveBaseUrl("localhost:11434") }
    }
}
