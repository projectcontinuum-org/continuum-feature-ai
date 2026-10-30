package org.projectcontinuum.feature.ai.claude.cli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper

/** Fixtures are trimmed copies of real Claude Code 2.1.204 stream-json output. */
class StreamJsonParserTest {

    private val objectMapper = jacksonObjectMapper()

    private val init =
        """{"type":"system","subtype":"init","cwd":"/w","session_id":"s1","tools":["Bash"],"mcp_servers":[],"model":"sonnet-latest","permissionMode":"dontAsk"}"""
    private val assistantText =
        """{"type":"assistant","message":{"model":"claude-sonnet-5.5","content":[{"type":"text","text":"I'll run it."}],"usage":{"input_tokens":4}},"parent_tool_use_id":null,"session_id":"s1"}"""
    private val assistantTool =
        """{"type":"assistant","message":{"model":"claude-sonnet-5.5","content":[{"type":"tool_use","id":"toolu_1","name":"Bash","input":{"command":"curl -s https://example.com\n| head","description":"Fetch"}}]},"session_id":"s1"}"""
    private val toolResult =
        """{"type":"user","message":{"role":"user","content":[{"tool_use_id":"toolu_1","type":"tool_result","content":"<html>Example Domain</html>","is_error":false}]},"session_id":"s1"}"""
    private val success =
        """{"type":"result","subtype":"success","is_error":false,"api_error_status":null,"duration_ms":6770,"num_turns":3,"result":"Example Domain","stop_reason":"end_turn","session_id":"s1","total_cost_usd":0.0999,"usage":{"input_tokens":6,"cache_creation_input_tokens":100,"cache_read_input_tokens":50,"output_tokens":42},"modelUsage":{},"permission_denials":[]}"""

    private fun parse(vararg lines: String): StreamJsonParser =
        StreamJsonParser(objectMapper).apply { lines.forEach { accept(it) } }

    @Test
    fun `successful run is parsed`() {
        val result = parse(init, assistantText, assistantTool, toolResult, success).toResult("unused", 1)

        assertFalse(result.isError)
        assertEquals("", result.error)
        assertEquals("Example Domain", result.response)
        assertEquals(3, result.numTurns)
        assertEquals(6770L, result.durationMs)
        assertEquals(0.0999, result.costUsd, 1e-9)
        assertEquals(156L, result.inputTokens)
        assertEquals(42L, result.outputTokens)
        assertEquals("claude-sonnet-5.5", result.model)
        assertNull(result.structuredOutput)
        assertEquals(listOf("text", "tool_use", "tool_result"), result.steps.map { it["type"] })
        assertEquals("Bash", result.steps[1]["name"])
        assertEquals("<html>Example Domain</html>", result.steps[2]["content"])
    }

    @Test
    fun `tool use yields a progress message with the first command line`() {
        val parser = StreamJsonParser(objectMapper)
        assertEquals("Agent started", parser.accept(init))
        assertNull(parser.accept(assistantText))
        assertEquals("Bash: curl -s https://example.com", parser.accept(assistantTool))
        assertNull(parser.accept("not json"))
        assertNull(parser.accept("{broken"))
    }

    @Test
    fun `structured output is returned`() {
        val structured = success.replace(
            "\"permission_denials\":[]",
            "\"permission_denials\":[],\"structured_output\":{\"title\":\"Example Domain\",\"links\":1}"
        )
        val result = parse(init, structured).toResult("unused", 1)
        assertEquals(mapOf("title" to "Example Domain", "links" to 1), result.structuredOutput)
    }

    @Test
    fun `max turns is an error`() {
        val maxTurns =
            """{"type":"result","subtype":"error_max_turns","is_error":true,"duration_ms":100,"num_turns":2,"session_id":"s1","total_cost_usd":0.01,"usage":{"input_tokens":1,"output_tokens":1}}"""
        val result = parse(init, maxTurns).toResult("unused", 1)
        assertTrue(result.isError)
        assertEquals("Reached the maximum number of turns (2)", result.error)
    }

    @Test
    fun `api error with success subtype is an error`() {
        val apiError =
            """{"type":"result","subtype":"success","is_error":true,"api_error_status":401,"duration_ms":10,"num_turns":1,"result":"Invalid API key","total_cost_usd":0,"usage":{}}"""
        val result = parse(init, apiError).toResult("unused", 1)
        assertTrue(result.isError)
        assertEquals("API error 401: Invalid API key", result.error)
        assertEquals("", result.response)
    }

    // Captured from Claude Code 2.1.204 against a backend answering HTTP 500
    private val retry500 =
        """{"type":"system","subtype":"api_retry","attempt":1,"max_retries":1,"retry_delay_ms":557.6,"error_status":500,"error":"server_error","session_id":"s1"}"""
    private val synthetic500 =
        """{"type":"assistant","message":{"id":"m1","model":"<synthetic>","role":"assistant","stop_reason":"stop_sequence","type":"message","usage":{"input_tokens":0,"output_tokens":0},"content":[{"type":"text","text":"API Error: 500 {\"error\":\"boom\"}. This is a server-side issue, usually temporary."}]},"parent_tool_use_id":null,"session_id":"s1","error":"server_error"}"""
    private val result500 =
        """{"type":"result","subtype":"success","is_error":true,"api_error_status":500,"duration_ms":900,"num_turns":1,"result":"API Error: 500 {\"error\":\"boom\"}. This is a server-side issue, usually temporary.","total_cost_usd":0,"usage":{"input_tokens":0,"output_tokens":0},"permission_denials":[]}"""

    @Test
    fun `backend 500 is an error and never the response`() {
        val parser = StreamJsonParser(objectMapper)
        parser.accept(init)
        parser.accept(retry500)
        assertEquals("API error", parser.accept(synthetic500))
        parser.accept(result500)

        val result = parser.toResult("unused", 1)
        assertTrue(result.isError)
        assertEquals("", result.response)
        assertEquals("API Error: 500 {\"error\":\"boom\"}. This is a server-side issue, usually temporary.", result.error)
        // the synthetic message is not reported as the model
        assertEquals("sonnet-latest", result.model)
        assertEquals(listOf("api_error"), result.steps.map { it["type"] })
    }

    @Test
    fun `synthetic error message marks the run failed even without is_error`() {
        val unflagged = result500
            .replace("\"is_error\":true", "\"is_error\":false")
            .replace("\"api_error_status\":500", "\"api_error_status\":null")
        val result = parse(init, synthetic500, unflagged).toResult("unused", 1)
        assertTrue(result.isError)
        assertEquals("", result.response)
        assertTrue(result.error.startsWith("API Error: 500"), result.error)
    }

    @Test
    fun `connection error without result event keeps the cli message`() {
        val refused =
            """{"type":"assistant","message":{"model":"<synthetic>","content":[{"type":"text","text":"Unable to connect to API (ECONNREFUSED)"}]},"session_id":"s1","error":"unknown"}"""
        val result = parse(init, refused).toResult("Claude Code exited with code 1 without a result: no error output", 1)
        assertTrue(result.isError)
        assertEquals(
            "Claude Code exited with code 1 without a result: no error output (Unable to connect to API (ECONNREFUSED))",
            result.error
        )
    }

    @Test
    fun `a real reply after a synthetic error clears it`() {
        val result = parse(init, synthetic500, assistantText, success).toResult("unused", 1)
        assertFalse(result.isError)
        assertEquals("Example Domain", result.response)
    }

    @Test
    fun `permission denials are appended to steps`() {
        val denied = success.replace(
            "\"permission_denials\":[]",
            "\"permission_denials\":[{\"tool_name\":\"Bash\",\"tool_use_id\":\"toolu_2\",\"tool_input\":{\"command\":\"rm x.txt\"}}]"
        )
        val result = parse(init, denied).toResult("unused", 1)
        val denial = result.steps.single()
        assertEquals("permission_denied", denial["type"])
        assertEquals(mapOf("command" to "rm x.txt"), denial["input"])
    }

    @Test
    fun `missing result event uses fallback error and last api error`() {
        val retry =
            """{"type":"system","subtype":"api_retry","attempt":1,"max_retries":2,"retry_delay_ms":500,"error_status":529,"error":"overloaded"}"""
        val parser = StreamJsonParser(objectMapper)
        parser.accept(init)
        assertEquals("API error, retrying (attempt 1/2)", parser.accept(retry))
        assertFalse(parser.hasResult)

        val result = parser.toResult("Claude Code exited with code 1", 1234)
        assertTrue(result.isError)
        assertEquals("Claude Code exited with code 1 (last API error: 529 overloaded)", result.error)
        assertEquals(1234L, result.durationMs)
        assertEquals("sonnet-latest", result.model)
    }

    @Test
    fun `long tool output is truncated`() {
        val long = "x".repeat(50)
        val parser = StreamJsonParser(objectMapper, maxStepChars = 10)
        parser.accept(toolResult.replace("<html>Example Domain</html>", long))
        val content = parser.toResult("e", 0).steps.single()["content"] as String
        assertTrue(content.startsWith("xxxxxxxxxx… [40 more chars]"))
    }
}
