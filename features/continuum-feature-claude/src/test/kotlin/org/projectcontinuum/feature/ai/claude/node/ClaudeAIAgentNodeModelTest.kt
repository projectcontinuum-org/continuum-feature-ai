package org.projectcontinuum.feature.ai.claude.node

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.projectcontinuum.core.commons.context.ExecutionContext
import org.projectcontinuum.core.commons.exception.NodeRuntimeException
import org.projectcontinuum.core.commons.protocol.progress.NodeProgress
import org.projectcontinuum.core.commons.protocol.progress.NodeProgressCallback
import org.projectcontinuum.core.commons.utils.NodeInputReader
import org.projectcontinuum.core.commons.utils.NodeOutputWriter
import org.projectcontinuum.feature.ai.claude.cli.AgentRequest
import org.projectcontinuum.feature.ai.claude.cli.AgentResult
import org.projectcontinuum.feature.ai.claude.cli.AgentRunner
import org.projectcontinuum.feature.ai.claude.cli.BackendPreset
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.nio.file.Path
import java.util.concurrent.CancellationException

class ClaudeAIAgentNodeModelTest {

    /** Records requests and answers with [respond]. */
    private class FakeRunner(var respond: (AgentRequest) -> AgentResult = { ok("answer to: ${it.prompt}") }) : AgentRunner {
        val requests = mutableListOf<AgentRequest>()
        override fun run(request: AgentRequest, onProgress: (String) -> Unit): AgentResult {
            requests += request
            onProgress("Bash: curl")
            return respond(request)
        }
    }

    companion object {
        fun ok(response: String, structured: Any? = null) = AgentResult(
            isError = false, error = "", response = response, numTurns = 2, durationMs = 1500, costUsd = 0.01,
            inputTokens = 100, outputTokens = 20, model = "claude-sonnet-5.5", structuredOutput = structured,
            steps = listOf(mapOf("type" to "text", "text" to "thinking"))
        )
    }

    private val objectMapper = jacksonObjectMapper()
    private lateinit var runner: FakeRunner
    private lateinit var node: ClaudeAIAgentNodeModel
    private lateinit var mockOutputWriter: NodeOutputWriter
    private lateinit var mockPortWriter: NodeOutputWriter.OutputPortWriter
    private lateinit var mockProgressCallback: NodeProgressCallback
    private val context = ExecutionContext(ownerId = "t", credentialFetcher = { name ->
        if (name == "anthropic") mapOf("token" to "sk-ant-secret") else null
    })

    @BeforeEach
    fun setup() {
        runner = FakeRunner()
        node = ClaudeAIAgentNodeModel(objectMapper, runner)
        mockOutputWriter = mock()
        mockPortWriter = mock()
        mockProgressCallback = mock()
        whenever(mockOutputWriter.createOutputPortWriter("output")).thenReturn(mockPortWriter)
    }

    private fun execute(properties: Map<String, Any>?, rows: List<Map<String, Any>>? = null) {
        val inputs = rows?.let { mapOf("input" to createMockReader(it)) } ?: emptyMap()
        node.execute(properties, inputs, mockOutputWriter, mockProgressCallback, context)
    }

    private fun writtenRows(count: Int): List<Map<String, Any>> {
        val captor = argumentCaptor<Map<String, Any>>()
        verify(mockPortWriter, times(count)).write(any(), captor.capture())
        return captor.allValues
    }

    private val baseProps = mapOf<String, Any>("credential" to "anthropic")

    @Test
    fun `metadata is correctly configured`() {
        assertEquals(ClaudeAIAgentNodeModel::class.java.name, node.metadata.id)
        assertEquals("Claude AI Agent", node.metadata.title)
        assertEquals(listOf("AI", "Agents"), node.categories)
        assertTrue(node.inputPorts.containsKey("input"))
        assertTrue(node.outputPorts.containsKey("output"))
        assertTrue(node.metadata.propertiesUISchema.isNotEmpty())
        // every schema property has an explicit default in NodeData.properties
        @Suppress("UNCHECKED_CAST")
        val schemaProps = (node.metadata.propertiesSchema["properties"] as Map<String, Any>).keys
        assertEquals(schemaProps, node.metadata.properties.keys)
    }

    @Test
    fun `prompt description shows the literal FreeMarker syntax`() {
        @Suppress("UNCHECKED_CAST")
        val prompt = (node.metadata.propertiesSchema["properties"] as Map<String, Map<String, Any>>)["prompt"]!!
        assertTrue((prompt["description"] as String).contains("\${row.columnName}"))
    }

    @Test
    fun `runs once per row with rendered prompt and metadata columns`() {
        val props = baseProps + ("prompt" to "Summarize \${row.name} aged \${row.age}")
        execute(props, listOf(mapOf("name" to "Alice", "age" to 30), mapOf("name" to "Bob", "age" to 1000)))

        assertEquals(listOf("Summarize Alice aged 30", "Summarize Bob aged 1000"), runner.requests.map { it.prompt })
        assertEquals("sk-ant-secret", runner.requests[0].token)
        assertEquals("""{"name":"Alice","age":30}""", runner.requests[0].inputJson)

        val rows = writtenRows(2)
        val first = rows[0]
        assertEquals("Alice", first["name"])
        assertEquals("answer to: Summarize Alice aged 30", first["response"])
        assertEquals(false, first[ClaudeAIAgentNodeModel.COL_IS_ERROR])
        assertEquals("", first[ClaudeAIAgentNodeModel.COL_ERROR])
        assertEquals(2, first[ClaudeAIAgentNodeModel.COL_NUM_TURNS])
        assertEquals(100L, first[ClaudeAIAgentNodeModel.COL_INPUT_TOKENS])
        assertEquals("claude-sonnet-5.5", first[ClaudeAIAgentNodeModel.COL_MODEL])
        assertFalse(first.containsKey(ClaudeAIAgentNodeModel.COL_STRUCTURED_OUTPUT))
        assertFalse(first.containsKey(ClaudeAIAgentNodeModel.COL_INTERMEDIATE_STEPS))
        // secrets never end up in the output
        assertFalse(rows.any { row -> row.values.any { it.toString().contains("sk-ant-secret") } })
    }

    @Test
    fun `defaults apply when properties are missing`() {
        execute(baseProps, listOf(mapOf("x" to 1)))

        val request = runner.requests.single()
        assertEquals(ClaudeAIAgentNodeModel.DEFAULT_PROMPT, request.prompt)
        assertEquals(BackendPreset.ANTHROPIC, request.backend)
        assertEquals("sonnet", request.model)
        assertEquals(ClaudeAIAgentNodeModel.DEFAULT_TOOLS, request.tools)
        assertEquals(ClaudeAIAgentNodeModel.DEFAULT_BASH_ALLOW_LIST, request.bashAllowList)
        assertEquals(10, request.maxTurns)
        assertEquals(300L, request.timeoutSeconds)
        assertNull(request.outputSchema)
    }

    @Test
    fun `no input runs once with an empty row`() {
        execute(baseProps + ("prompt" to "What day is it?"))

        assertEquals(1, runner.requests.size)
        assertEquals("{}", runner.requests[0].inputJson)
        val row = writtenRows(1).single()
        assertEquals("answer to: What day is it?", row["response"])
    }

    @Test
    fun `structured output is written and flattened`() {
        runner.respond = { ok("done", mapOf("title" to "Example", "count" to 3, "tags" to listOf("a"))) }
        val props = baseProps + mapOf(
            "outputSchema" to """{"type":"object","properties":{"title":{"type":"string"}}}""",
            "flattenStructuredOutput" to true,
            "includeIntermediateSteps" to true,
            "responseColumn" to "answer"
        )
        execute(props, listOf(mapOf("id" to 1)))

        val row = writtenRows(1).single()
        assertEquals("done", row["answer"])
        assertEquals(mapOf("title" to "Example", "count" to 3, "tags" to listOf("a")), row[ClaudeAIAgentNodeModel.COL_STRUCTURED_OUTPUT])
        assertEquals("Example", row["title"])
        assertEquals(3, row["count"])
        assertEquals(listOf(mapOf("type" to "text", "text" to "thinking")), row[ClaudeAIAgentNodeModel.COL_INTERMEDIATE_STEPS])
        assertTrue(runner.requests.single().collectSteps)
    }

    @Test
    fun `steps are not collected unless requested`() {
        execute(baseProps, listOf(mapOf("id" to 1)))
        assertFalse(runner.requests.single().collectSteps)
    }

    @Test
    fun `flattened fields never overwrite input or agent columns`() {
        val props = baseProps + mapOf(
            // the schema does not declare the agent_* fields, the agent returned them anyway
            "outputSchema" to """{"type":"object","properties":{"drug":{"type":"string"},"summary":{"type":"string"}}}""",
            "flattenStructuredOutput" to true,
            "onError" to "CONTINUE"
        )
        runner.respond = { r ->
            if (r.prompt.contains("fail")) AgentResult.failure("boom").copy(structuredOutput = mapOf("agent_error" to "", "agent_is_error" to false))
            else ok("done", mapOf("drug" to "agent's drug", "agent_error" to "spoofed", "summary" to "s"))
        }
        execute(props + ("prompt" to "\${row.p}"), listOf(mapOf("drug" to "ibuprofen", "p" to "ok"), mapOf("drug" to "x", "p" to "fail")))

        val (good, bad) = writtenRows(2)
        assertEquals("ibuprofen", good["drug"])
        assertEquals("s", good["summary"])
        assertEquals("", good[ClaudeAIAgentNodeModel.COL_ERROR])
        assertEquals("agent's drug", (good[ClaudeAIAgentNodeModel.COL_STRUCTURED_OUTPUT] as Map<*, *>)["drug"])
        assertEquals(true, bad[ClaudeAIAgentNodeModel.COL_IS_ERROR])
        assertEquals("boom", bad[ClaudeAIAgentNodeModel.COL_ERROR])
    }

    @Test
    fun `output schema fields or response column clashing with agent columns fail fast`() {
        listOf(
            baseProps + mapOf(
                "outputSchema" to """{"type":"object","properties":{"agent_error":{"type":"string"}}}""",
                "flattenStructuredOutput" to true
            ),
            baseProps + mapOf(
                "outputSchema" to """{"type":"object","properties":{"response":{"type":"string"}}}""",
                "flattenStructuredOutput" to true
            ),
            baseProps + ("responseColumn" to "agent_is_error")
        ).forEach { props ->
            val e = assertThrows<NodeRuntimeException>(props.toString()) { execute(props, listOf(mapOf("p" to 1))) }
            assertFalse(e.isRetriable)
        }
        assertTrue(runner.requests.isEmpty())
    }

    @Test
    fun `clashing schema fields are fine without flattening`() {
        runner.respond = { ok("done", mapOf("response" to "inner")) }
        execute(
            baseProps + ("outputSchema" to """{"type":"object","properties":{"response":{"type":"string"}}}"""),
            listOf(mapOf("p" to 1))
        )
        val row = writtenRows(1).single()
        assertEquals("done", row["response"])
        assertEquals(mapOf("response" to "inner"), row[ClaudeAIAgentNodeModel.COL_STRUCTURED_OUTPUT])
    }

    @Test
    fun `an exception from progress reporting stops the node instead of becoming a row error`() {
        val cancelled = CancellationException("activity cancelled")
        whenever(mockProgressCallback.report(any<NodeProgress>())).thenAnswer { invocation ->
            if ((invocation.getArgument<NodeProgress>(0).message ?: "").contains("Bash")) throw cancelled
        }
        val thrown = assertThrows<CancellationException> { execute(baseProps, listOf(mapOf("p" to 1), mapOf("p" to 2))) }

        assertEquals(cancelled, thrown)
        assertEquals(1, runner.requests.size)
        verify(mockPortWriter, never()).write(any(), any())
    }

    @Test
    fun `agent error writes error columns and continues by default`() {
        runner.respond = { if (it.prompt.contains("bad")) AgentResult.failure("Timed out after 300s") else ok("fine") }
        execute(baseProps + ("prompt" to "\${row.p}"), listOf(mapOf("p" to "bad"), mapOf("p" to "good")))

        val rows = writtenRows(2)
        assertEquals(true, rows[0][ClaudeAIAgentNodeModel.COL_IS_ERROR])
        assertEquals("Timed out after 300s", rows[0][ClaudeAIAgentNodeModel.COL_ERROR])
        assertEquals("", rows[0]["response"])
        assertEquals("fine", rows[1]["response"])
    }

    @Test
    fun `agent error fails the node when onError is FAIL`() {
        runner.respond = { AgentResult.failure("boom") }
        val e = assertThrows<NodeRuntimeException> {
            execute(baseProps + ("onError" to "FAIL"), listOf(mapOf("p" to 1)))
        }
        assertFalse(e.isRetriable)
        assertTrue(e.message.contains("boom"))
        verify(mockPortWriter, never()).write(any(), any())
    }

    @Test
    fun `template errors become row errors`() {
        execute(baseProps + ("prompt" to "Hi \${row.missing}"), listOf(mapOf("p" to 1)))

        assertTrue(runner.requests.isEmpty())
        val row = writtenRows(1).single()
        assertEquals(true, row[ClaudeAIAgentNodeModel.COL_IS_ERROR])
        assertTrue((row[ClaudeAIAgentNodeModel.COL_ERROR] as String).startsWith("Prompt template rendering failed"))
    }

    @Test
    fun `template cannot instantiate Java classes with new`() {
        val exploit = "<#assign ex=\"freemarker.template.utility.Execute\"?new()>\${ex(\"id\")}"
        execute(baseProps + ("prompt" to exploit), listOf(mapOf("p" to 1)))

        assertTrue(runner.requests.isEmpty())
        val row = writtenRows(1).single()
        val error = row[ClaudeAIAgentNodeModel.COL_ERROR] as String
        assertEquals(true, row[ClaudeAIAgentNodeModel.COL_IS_ERROR])
        assertTrue(error.startsWith("Prompt template rendering failed"), error)
        assertTrue(error.contains("not allowed"), error)
        assertFalse(error.contains("uid="), error)
    }

    /** A non-scalar row value (wrapped as a bean, not a String/Number/Map/List). */
    class Bean(val name: String)

    @Test
    fun `template cannot use api or call Java methods on row values`() {
        execute(baseProps + ("prompt" to "\${row?api.getClass().name}"), listOf(mapOf("p" to 1)))
        execute(baseProps + ("prompt" to "\${row.b.getClass().name}"), listOf(mapOf("b" to Bean("x"))))

        assertTrue(runner.requests.isEmpty())
        val errors = writtenRows(2).map { it[ClaudeAIAgentNodeModel.COL_ERROR] as String }
        assertTrue(errors[0].contains("api_builtin_enabled"), errors[0])
        assertTrue(errors[1].startsWith("Prompt template rendering failed"), errors[1])
    }

    @Test
    fun `bean properties of row values are still readable`() {
        execute(baseProps + ("prompt" to "hi \${row.b.name}"), listOf(mapOf("b" to Bean("x"))))

        assertEquals(listOf("hi x"), runner.requests.map { it.prompt })
    }

    @Test
    fun `restricted template engine still supports normal FreeMarker features`() {
        val prompt = "<#if row.n gt 1>many<#else>one</#if> \${row.name?upper_case} " +
            "<#list row.tags as t>\${t}<#sep>,</#list> \${row.missing!\"-\"} \${row?keys?size} \${row.n}"
        execute(baseProps + ("prompt" to prompt), listOf(mapOf("name" to "ann", "n" to 1000, "tags" to listOf("a", "b"))))

        assertEquals(listOf("many ANN a,b - 3 1000"), runner.requests.map { it.prompt })
    }

    @Test
    fun `runner exceptions become row errors`() {
        runner.respond = { throw IllegalStateException("disk full") }
        execute(baseProps, listOf(mapOf("p" to 1)))

        val row = writtenRows(1).single()
        assertEquals("Agent run failed: disk full", row[ClaudeAIAgentNodeModel.COL_ERROR])
    }

    @Test
    fun `missing credential for anthropic fails without retry`() {
        val e = assertThrows<NodeRuntimeException> { execute(emptyMap(), listOf(mapOf("p" to 1))) }
        assertFalse(e.isRetriable)
        assertTrue(runner.requests.isEmpty())
    }

    @Test
    fun `unknown credential fails without retry`() {
        val e = assertThrows<NodeRuntimeException> { execute(mapOf("credential" to "nope"), listOf(mapOf("p" to 1))) }
        assertFalse(e.isRetriable)
        assertEquals("Credential 'nope' not found", e.message)
    }

    @Test
    fun `local ollama runs without a credential`() {
        execute(mapOf("backend" to "OLLAMA", "model" to "qwen3-coder"), listOf(mapOf("p" to 1)))

        val request = runner.requests.single()
        assertEquals(BackendPreset.OLLAMA, request.backend)
        assertNull(request.token)
        assertEquals("qwen3-coder", request.model)
    }

    @Test
    fun `invalid configuration fails fast`() {
        listOf(
            mapOf("backend" to "CUSTOM"),
            mapOf("backend" to "GEMINI"),
            baseProps + ("tools" to listOf("Bash", "Teleport")),
            baseProps + ("outputSchema" to "not json"),
            baseProps + ("mcpServers" to "[1,2]"),
            baseProps + ("effort" to "extreme")
        ).forEach { props ->
            assertThrows<NodeRuntimeException>(props.toString()) { execute(props, listOf(mapOf("p" to 1))) }
        }
        assertTrue(runner.requests.isEmpty())
    }

    @Test
    fun `mcp servers config is normalized`() {
        execute(baseProps + ("mcpServers" to """{"fs":{"command":"npx","args":["x"]}}"""), listOf(mapOf("p" to 1)))
        assertEquals("""{"mcpServers":{"fs":{"command":"npx","args":["x"]}}}""", runner.requests.single().mcpConfig)
    }

    @Test
    fun `reports tool progress and completion`() {
        execute(baseProps, listOf(mapOf("p" to 1)))

        val captor = argumentCaptor<NodeProgress>()
        verify(mockProgressCallback, times(2)).report(captor.capture())
        assertEquals(listOf("Row 1/1: starting agent", "Row 1/1: Bash: curl"), captor.allValues.map { it.message })
        verify(mockProgressCallback).report(100)
    }

    @Test
    fun `writes real parquet output that reads back`(@TempDir dir: Path) {
        runner.respond = { ok("hi ${it.prompt}", mapOf("score" to 0.5, "tags" to listOf("a", "b"))) }
        val outputWriter = NodeOutputWriter(dir)
        val props = baseProps + mapOf(
            "prompt" to "\${row.name}",
            "outputSchema" to """{"type":"object","properties":{"score":{"type":"number"}}}""",
            "flattenStructuredOutput" to true,
            "includeIntermediateSteps" to true
        )
        node.execute(props, mapOf("input" to parquetInput(dir, listOf(mapOf("name" to "Alice"), mapOf("name" to "Bob")))), outputWriter, mockProgressCallback, context)

        val rows = readParquet(dir.resolve("output.output.parquet"))
        assertEquals(listOf("hi Alice", "hi Bob"), rows.map { it["response"] })
        assertEquals("Alice", rows[0]["name"])
        assertEquals(false, rows[0][ClaudeAIAgentNodeModel.COL_IS_ERROR])
        assertEquals(100L, rows[0][ClaudeAIAgentNodeModel.COL_INPUT_TOKENS])
        assertEquals(0.5, rows[0]["score"])
        assertEquals(mapOf("score" to 0.5, "tags" to listOf("a", "b")), rows[0][ClaudeAIAgentNodeModel.COL_STRUCTURED_OUTPUT])
        assertEquals(listOf(mapOf("type" to "text", "text" to "thinking")), rows[0][ClaudeAIAgentNodeModel.COL_INTERMEDIATE_STEPS])
        val spec = outputWriter.getTableSpec("output").associate { it["name"] to it["contentType"] }
        assertEquals("application/vnd.continuum.x-boolean", spec[ClaudeAIAgentNodeModel.COL_IS_ERROR])
        assertEquals("application/vnd.continuum.x-long", spec[ClaudeAIAgentNodeModel.COL_DURATION_MS])
    }

    @Test
    fun `empty input table runs no agent and writes an empty table`(@TempDir dir: Path) {
        val outputWriter = NodeOutputWriter(dir)
        node.execute(baseProps, mapOf("input" to parquetInput(dir, emptyList())), outputWriter, mockProgressCallback, context)

        assertTrue(runner.requests.isEmpty())
        assertTrue(readParquet(dir.resolve("output.output.parquet")).isEmpty())
        verify(mockProgressCallback).report(100)
    }

    /** Writes [rows] with the real Parquet writer and opens them like the worker does. */
    private fun parquetInput(dir: Path, rows: List<Map<String, Any>>): NodeInputReader {
        val inputDir = dir.resolve("in").toFile().apply { mkdirs() }.toPath()
        NodeOutputWriter(inputDir).createOutputPortWriter("input").use { writer ->
            rows.forEachIndexed { i, row -> writer.write(i.toLong(), row) }
        }
        return NodeInputReader(inputDir.resolve("output.input.parquet"))
    }

    private fun readParquet(file: Path): List<Map<String, Any>> = NodeInputReader(file).use { reader ->
        generateSequence { reader.read() }.toList()
    }

    private fun createMockReader(rows: List<Map<String, Any>>): NodeInputReader {
        val iterator = rows.iterator()
        val mockReader = mock<NodeInputReader>()
        whenever(mockReader.read()).thenAnswer {
            if (iterator.hasNext()) iterator.next() else null
        }
        whenever(mockReader.getRowCount()).thenReturn(rows.size.toLong())
        return mockReader
    }
}
