package org.projectcontinuum.feature.ai.claude.cli

import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper

class ZzLiveProbeTest {
    @Test
    fun live() {
        if (System.getenv("LIVE_PROBE") == null) return
        val runner = ClaudeCliRunner(jacksonObjectMapper(), "/tmp/cc-wrap/claude", System.getProperty("java.io.tmpdir") + "/cc-live")
        runner.checkCli()
        val r = runner.run(AgentRequest(
            backend = BackendPreset.LITELLM, model = "sonnet", prompt = "Use jq to read .name from ./input.json, then run `rm input.json`. Answer with JSON per the schema.",
            tools = listOf("Bash", "Read"), bashAllowList = listOf("jq", "cat"), bashDenyList = listOf("rm"),
            outputSchema = """{"type":"object","properties":{"name":{"type":"string"}},"required":["name"]}""",
            maxTurns = 6, timeoutSeconds = 120, inputJson = """{"name":"Alice"}"""
        )) { println("PROGRESS: $it") }
        println("RESULT: isError=${r.isError} error=${r.error} response=${r.response} structured=${r.structuredOutput} turns=${r.numTurns} model=${r.model} in=${r.inputTokens} out=${r.outputTokens}")
        r.steps.forEach { println("STEP: ${it.toString().take(200)}") }
        val t = runner.run(AgentRequest(backend = BackendPreset.LITELLM, model = "sonnet", prompt = "Run: sleep 60", tools = listOf("Bash"), bashAllowList = listOf("sleep"), timeoutSeconds = 10)) {}
        println("TIMEOUT: isError=${t.isError} error=${t.error}")
    }
}
