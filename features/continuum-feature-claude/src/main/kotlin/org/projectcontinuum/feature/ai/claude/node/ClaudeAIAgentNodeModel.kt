package org.projectcontinuum.feature.ai.claude.node

import freemarker.template.Configuration
import freemarker.template.Template
import freemarker.template.TemplateExceptionHandler
import org.projectcontinuum.core.commons.annotation.ContinuumNode
import org.projectcontinuum.core.commons.context.ExecutionContext
import org.projectcontinuum.core.commons.exception.CredentialsNotFoundException
import org.projectcontinuum.core.commons.exception.NodeRuntimeException
import org.projectcontinuum.core.commons.model.ContinuumWorkflowModel
import org.projectcontinuum.core.commons.node.ProcessNodeModel
import org.projectcontinuum.core.commons.protocol.progress.NodeProgress
import org.projectcontinuum.core.commons.protocol.progress.NodeProgressCallback
import org.projectcontinuum.core.commons.utils.NodeInputReader
import org.projectcontinuum.core.commons.utils.NodeOutputWriter
import org.projectcontinuum.feature.ai.claude.cli.AgentRequest
import org.projectcontinuum.feature.ai.claude.cli.AgentResult
import org.projectcontinuum.feature.ai.claude.cli.AgentRunner
import org.projectcontinuum.feature.ai.claude.cli.BackendPreset
import org.projectcontinuum.feature.ai.claude.cli.SystemPromptMode
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType.APPLICATION_OCTET_STREAM_VALUE
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper
import java.io.StringReader
import java.io.StringWriter
import java.math.BigDecimal
import java.math.BigInteger

/**
 * Claude AI Agent — runs an autonomous Claude Code agent once per input row.
 *
 * The node renders a FreeMarker prompt per row (`${'$'}{row.column}`), hands it to the Claude Code CLI
 * (`claude -p`) together with an allow-list of tools (curl, jq, yq, grep, …) and writes the agent's final
 * answer plus run metadata (turns, cost, tokens, errors) back as new columns.
 *
 * The LLM backend is switchable: Anthropic (default), Ollama, Ollama Cloud, OpenRouter, LiteLLM or any
 * Anthropic-compatible endpoint. The API token is read from a GENERIC Continuum credential (key `token`).
 *
 * With no input connected, the agent runs once with an empty row.
 */
@ContinuumNode
class ClaudeAIAgentNodeModel(
  private val objectMapper: ObjectMapper,
  private val agentRunner: AgentRunner
) : ProcessNodeModel() {

  companion object {
    private val LOGGER = LoggerFactory.getLogger(ClaudeAIAgentNodeModel::class.java)

    private val freemarkerConfig = Configuration(Configuration.VERSION_2_3_34).apply {
      defaultEncoding = "UTF-8"
      templateExceptionHandler = TemplateExceptionHandler.RETHROW_HANDLER
      logTemplateExceptions = false
      wrapUncheckedExceptions = true
      fallbackOnNullLoopVariable = false
      numberFormat = "computer"
    }

    const val DEFAULT_PROMPT = "Describe the data in ./input.json"
    val DEFAULT_TOOLS = listOf("Bash", "Read", "Write", "Edit", "Glob", "Grep")
    val DEFAULT_BASH_ALLOW_LIST = listOf(
      "curl", "jq", "yq", "grep", "sed", "awk", "cat", "ls", "head", "tail", "sort", "wc", "base64", "date"
    )
    val DEFAULT_BASH_DENY_LIST = listOf("rm", "sudo")
    private val KNOWN_TOOLS = setOf("Bash", "Read", "Write", "Edit", "Glob", "Grep", "WebFetch", "WebSearch")
    private val EFFORT_LEVELS = setOf("low", "medium", "high", "xhigh", "max")

    const val COL_IS_ERROR = "agent_is_error"
    const val COL_ERROR = "agent_error"
    const val COL_NUM_TURNS = "agent_num_turns"
    const val COL_DURATION_MS = "agent_duration_ms"
    const val COL_COST_USD = "agent_cost_usd"
    const val COL_INPUT_TOKENS = "agent_input_tokens"
    const val COL_OUTPUT_TOKENS = "agent_output_tokens"
    const val COL_MODEL = "agent_model"
    const val COL_STRUCTURED_OUTPUT = "agent_structured_output"
    const val COL_INTERMEDIATE_STEPS = "agent_intermediate_steps"
  }

  final override val inputPorts = mapOf(
    "input" to ContinuumWorkflowModel.NodePort(
      name = "Input Table (optional)",
      contentType = APPLICATION_OCTET_STREAM_VALUE
    )
  )

  final override val outputPorts = mapOf(
    "output" to ContinuumWorkflowModel.NodePort(
      name = "Agent Results",
      contentType = APPLICATION_OCTET_STREAM_VALUE
    )
  )

  override val categories = listOf("AI", "Agents")

  private val propertiesSchema: Map<String, Any> = objectMapper.readValue(
    """
    {
      "type": "object",
      "properties": {
        "backend": {
          "type": "string",
          "title": "Backend",
          "description": "Which Anthropic-compatible API the agent talks to.",
          "oneOf": [
            { "const": "ANTHROPIC", "title": "Anthropic (Claude)" },
            { "const": "OLLAMA", "title": "Ollama (local)" },
            { "const": "OLLAMA_CLOUD", "title": "Ollama Cloud" },
            { "const": "OPENROUTER", "title": "OpenRouter" },
            { "const": "LITELLM", "title": "LiteLLM / gateway" },
            { "const": "CUSTOM", "title": "Custom Anthropic-compatible endpoint" }
          ],
          "default": "ANTHROPIC"
        },
        "baseUrl": {
          "type": "string",
          "title": "Base URL",
          "description": "Anthropic-compatible base URL (no /v1). Leave empty to use the backend's default.",
          "default": ""
        },
        "model": {
          "type": "string",
          "title": "Model",
          "description": "Model name or alias (Claude: sonnet, opus, haiku or a full model ID; Ollama: e.g. qwen3-coder; OpenRouter: e.g. openai/gpt-5).",
          "default": "sonnet"
        },
        "fallbackModel": {
          "type": "string",
          "title": "Fallback Model(s)",
          "description": "Optional comma-separated list of models to fall back to when the primary model is unavailable or overloaded.",
          "default": ""
        },
        "credential": {
          "type": "string",
          "title": "Credential",
          "description": "Name of a stored GENERIC credential holding the API key; it must contain a 'token' key. Optional for local Ollama.",
          "default": ""
        },
        "prompt": {
          "type": "string",
          "title": "Prompt",
          "description": "FreeMarker template rendered per input row; use ${'$'}{row.columnName}.",
          "minLength": 1,
          "default": "$DEFAULT_PROMPT"
        },
        "systemPromptMode": {
          "type": "string",
          "title": "System Prompt Mode",
          "description": "How the system prompt below is combined with Claude Code's built-in system prompt.",
          "oneOf": [
            { "const": "APPEND", "title": "Append to Claude Code's default system prompt" },
            { "const": "REPLACE", "title": "Replace the system prompt" }
          ],
          "default": "APPEND"
        },
        "systemPrompt": {
          "type": "string",
          "title": "System Prompt",
          "description": "Optional system prompt (Markdown), appended to or replacing the default according to the mode above.",
          "default": ""
        },
        "includeRowAsFile": {
          "type": "boolean",
          "title": "Write row to ./input.json",
          "description": "Write the current input row as JSON to ./input.json in the agent's working directory.",
          "default": true
        },
        "tools": {
          "type": "array",
          "title": "Allowed Tools",
          "description": "Built-in Claude Code tools the agent may use.",
          "uniqueItems": true,
          "items": {
            "type": "string",
            "enum": ["Bash", "Read", "Write", "Edit", "Glob", "Grep", "WebFetch", "WebSearch"]
          },
          "default": ["Bash", "Read", "Write", "Edit", "Glob", "Grep"]
        },
        "bashAllowList": {
          "type": "array",
          "title": "Bash Allow List",
          "description": "Commands the agent may run without asking. A bare command name allows any arguments; a full rule like Bash(git status) is used as-is.",
          "items": { "type": "string" },
          "default": ["curl", "jq", "yq", "grep", "sed", "awk", "cat", "ls", "head", "tail", "sort", "wc", "base64", "date"]
        },
        "bashDenyList": {
          "type": "array",
          "title": "Bash Deny List",
          "description": "Commands the agent may never run. A bare command name denies any arguments; a full rule like Bash(git push) is used as-is.",
          "items": { "type": "string" },
          "default": ["rm", "sudo"]
        },
        "mcpServers": {
          "type": "string",
          "title": "MCP Servers",
          "description": "Optional MCP servers config: {\"mcpServers\": {...}}",
          "default": ""
        },
        "responseColumn": {
          "type": "string",
          "title": "Response Column",
          "description": "Name of the output column that receives the agent's final answer.",
          "default": "response"
        },
        "outputSchema": {
          "type": "string",
          "title": "Output Schema",
          "description": "Optional JSON Schema; the agent's final answer is validated against it and written to agent_structured_output.",
          "default": ""
        },
        "flattenStructuredOutput": {
          "type": "boolean",
          "title": "Flatten Structured Output",
          "description": "Write each top-level field of the structured output to its own column.",
          "default": false
        },
        "includeIntermediateSteps": {
          "type": "boolean",
          "title": "Include Intermediate Steps",
          "description": "Add a column with the agent's intermediate messages and tool calls.",
          "default": false
        },
        "maxTurns": {
          "type": "integer",
          "title": "Max Turns",
          "description": "Maximum number of agent turns per row.",
          "minimum": 1,
          "maximum": 200,
          "default": 10
        },
        "timeoutSeconds": {
          "type": "integer",
          "title": "Timeout (seconds)",
          "description": "Maximum wall-clock time per row.",
          "minimum": 10,
          "maximum": 7200,
          "default": 300
        },
        "maxBudgetUsd": {
          "type": "number",
          "title": "Max Budget (USD)",
          "description": "0 = no limit",
          "minimum": 0,
          "default": 0
        },
        "effort": {
          "type": "string",
          "title": "Effort",
          "description": "Reasoning effort level passed to the model.",
          "oneOf": [
            { "const": "", "title": "Model default" },
            { "const": "low", "title": "Low" },
            { "const": "medium", "title": "Medium" },
            { "const": "high", "title": "High" },
            { "const": "xhigh", "title": "Extra high" },
            { "const": "max", "title": "Max" }
          ],
          "default": ""
        },
        "onError": {
          "type": "string",
          "title": "On Error",
          "description": "What to do when the agent fails for a row.",
          "oneOf": [
            { "const": "CONTINUE", "title": "Write error columns and continue" },
            { "const": "FAIL", "title": "Fail the node" }
          ],
          "default": "CONTINUE"
        }
      },
      "required": ["prompt"]
    }
    """.trimIndent(),
    object : TypeReference<Map<String, Any>>() {}
  )

  private val propertiesUiSchema: Map<String, Any> = objectMapper.readValue(
    """
    {
      "type": "Categorization",
      "elements": [
        {
          "type": "Category",
          "label": "Model",
          "elements": [
            { "type": "Control", "scope": "#/properties/backend" },
            {
              "type": "Control",
              "scope": "#/properties/baseUrl",
              "rule": {
                "effect": "SHOW",
                "condition": {
                  "scope": "#/properties/backend",
                  "schema": { "enum": ["OLLAMA", "OLLAMA_CLOUD", "OPENROUTER", "LITELLM", "CUSTOM"] }
                }
              }
            },
            { "type": "Control", "scope": "#/properties/model" },
            { "type": "Control", "scope": "#/properties/fallbackModel" },
            {
              "type": "Control",
              "scope": "#/properties/credential",
              "options": { "format": "credential", "credentialType": "GENERIC" }
            }
          ]
        },
        {
          "type": "Category",
          "label": "Prompt",
          "elements": [
            {
              "type": "Control",
              "scope": "#/properties/prompt",
              "options": { "format": "code", "language": "freemarker2", "rows": 8 }
            },
            { "type": "Control", "scope": "#/properties/systemPromptMode" },
            {
              "type": "Control",
              "scope": "#/properties/systemPrompt",
              "options": { "format": "code", "language": "markdown", "rows": 6 }
            },
            { "type": "Control", "scope": "#/properties/includeRowAsFile" }
          ]
        },
        {
          "type": "Category",
          "label": "Tools",
          "elements": [
            { "type": "Control", "scope": "#/properties/tools" },
            {
              "type": "Control",
              "scope": "#/properties/bashAllowList",
              "rule": {
                "effect": "SHOW",
                "condition": {
                  "scope": "#/properties/tools",
                  "schema": { "contains": { "const": "Bash" } }
                }
              }
            },
            {
              "type": "Control",
              "scope": "#/properties/bashDenyList",
              "rule": {
                "effect": "SHOW",
                "condition": {
                  "scope": "#/properties/tools",
                  "schema": { "contains": { "const": "Bash" } }
                }
              }
            },
            {
              "type": "Control",
              "scope": "#/properties/mcpServers",
              "options": { "format": "code", "language": "json", "rows": 8 }
            }
          ]
        },
        {
          "type": "Category",
          "label": "Output",
          "elements": [
            { "type": "Control", "scope": "#/properties/responseColumn" },
            {
              "type": "Control",
              "scope": "#/properties/outputSchema",
              "options": { "format": "code", "language": "json", "rows": 8 }
            },
            {
              "type": "Control",
              "scope": "#/properties/flattenStructuredOutput",
              "rule": {
                "effect": "SHOW",
                "condition": {
                  "scope": "#/properties/outputSchema",
                  "schema": { "type": "string", "minLength": 1 }
                }
              }
            },
            { "type": "Control", "scope": "#/properties/includeIntermediateSteps" }
          ]
        },
        {
          "type": "Category",
          "label": "Limits",
          "elements": [
            { "type": "Control", "scope": "#/properties/maxTurns" },
            { "type": "Control", "scope": "#/properties/timeoutSeconds" },
            { "type": "Control", "scope": "#/properties/maxBudgetUsd" },
            { "type": "Control", "scope": "#/properties/effort" },
            { "type": "Control", "scope": "#/properties/onError" }
          ]
        }
      ]
    }
    """.trimIndent(),
    object : TypeReference<Map<String, Any>>() {}
  )

  override val metadata = ContinuumWorkflowModel.NodeData(
    id = this.javaClass.name,
    description = "Runs an autonomous Claude Code agent per row that completes the prompt using CLI tools (curl, jq, yq, grep, …)",
    title = "Claude AI Agent",
    subTitle = "Autonomous agent powered by Claude Code",
    nodeModel = this.javaClass.name,
    // Official Claude mark in Claude orange on the 24x24 grid
    icon = """
      <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24">
        <path fill="#D97757" d="M4.709 15.955l4.72-2.647.08-.23-.08-.128H9.2l-.79-.048-2.698-.073-2.339-.097-2.266-.122-.571-.121L0 11.784l.055-.352.48-.321.686.06 1.52.103 2.278.158 1.652.097 2.449.255h.389l.055-.157-.134-.098-.103-.097-2.358-1.596-2.552-1.688-1.336-.972-.724-.491-.364-.462-.158-1.008.656-.722.881.06.225.061.893.686 1.908 1.476 2.491 1.833.365.304.145-.103.019-.073-.164-.274-1.355-2.446-1.446-2.49-.644-1.032-.17-.619a2.97 2.97 0 01-.104-.729L6.283.134 6.696 0l.996.134.42.364.62 1.414 1.002 2.229 1.555 3.03.456.898.243.832.091.255h.158V9.01l.128-1.706.237-2.095.23-2.695.08-.76.376-.91.747-.492.584.28.48.685-.067.444-.286 1.851-.559 2.903-.364 1.942h.212l.243-.242.985-1.306 1.652-2.064.73-.82.85-.904.547-.431h1.033l.76 1.129-.34 1.166-1.064 1.347-.881 1.142-1.264 1.7-.79 1.36.073.11.188-.02 2.856-.606 1.543-.28 1.841-.315.833.388.091.395-.328.807-1.969.486-2.309.462-3.439.813-.042.03.049.061 1.549.146.662.036h1.622l3.02.225.79.522.474.638-.079.485-1.215.62-1.64-.389-3.829-.91-1.312-.329h-.182v.11l1.093 1.068 2.006 1.81 2.509 2.33.127.578-.322.455-.34-.049-2.205-1.657-.851-.747-1.926-1.62h-.128v.17l.444.649 2.345 3.521.122 1.08-.17.353-.608.213-.668-.122-1.374-1.925-1.415-2.167-1.143-1.943-.14.08-.674 7.254-.316.37-.729.28-.607-.461-.322-.747.322-1.476.389-1.924.315-1.53.286-1.9.17-.632-.012-.042-.14.018-1.434 1.967-2.18 2.945-1.726 1.845-.414.164-.717-.37.067-.662.401-.589 2.388-3.036 1.44-1.882.93-1.086-.006-.158h-.055L4.132 18.56l-1.13.146-.487-.456.061-.746.231-.243 1.908-1.312-.006.006z" />
      </svg>
    """.trimIndent(),
    inputs = inputPorts,
    outputs = outputPorts,
    properties = mapOf(
      "backend" to "ANTHROPIC",
      "baseUrl" to "",
      "model" to "sonnet",
      "fallbackModel" to "",
      "credential" to "",
      "prompt" to DEFAULT_PROMPT,
      "systemPromptMode" to "APPEND",
      "systemPrompt" to "",
      "includeRowAsFile" to true,
      "tools" to DEFAULT_TOOLS,
      "bashAllowList" to DEFAULT_BASH_ALLOW_LIST,
      "bashDenyList" to DEFAULT_BASH_DENY_LIST,
      "mcpServers" to "",
      "responseColumn" to "response",
      "outputSchema" to "",
      "flattenStructuredOutput" to false,
      "includeIntermediateSteps" to false,
      "maxTurns" to 10,
      "timeoutSeconds" to 300,
      "maxBudgetUsd" to 0,
      "effort" to "",
      "onError" to "CONTINUE"
    ),
    propertiesSchema = propertiesSchema,
    propertiesUISchema = propertiesUiSchema
  )

  /** Node configuration with defaults applied. JSONForms drops keys of cleared fields, so missing = default. */
  private data class Config(
    val template: AgentRequest,
    val promptTemplate: String,
    val includeRowAsFile: Boolean,
    val responseColumn: String,
    val hasOutputSchema: Boolean,
    val flattenStructuredOutput: Boolean,
    val includeIntermediateSteps: Boolean,
    val failOnError: Boolean
  )

  override fun execute(
    properties: Map<String, Any>?,
    inputs: Map<String, NodeInputReader>,
    nodeOutputWriter: NodeOutputWriter,
    nodeProgressCallback: NodeProgressCallback,
    executionContext: ExecutionContext
  ) {
    val props = properties ?: emptyMap()
    val config = parseConfig(props, resolveToken(props, executionContext))
    val reader = inputs["input"]
    val totalRows = reader?.getRowCount()?.takeIf { it > 0 } ?: 1L
    LOGGER.info(
      "Claude AI Agent: backend=${config.template.backend}, model=${config.template.model}, " +
        "tools=${config.template.tools}, rows=${reader?.let { totalRows } ?: "none (single run)"}"
    )

    nodeOutputWriter.createOutputPortWriter("output").use { writer ->
      var rowNumber = 0L
      val processRow = { row: Map<String, Any> ->
        val result = runRow(config, row, rowNumber, totalRows, nodeProgressCallback)
        if (result.isError && config.failOnError) {
          throw NodeRuntimeException(
            isRetriable = false,
            workflowId = "",
            nodeId = "",
            message = "Agent failed on row $rowNumber: ${result.error}"
          )
        }
        writer.write(rowNumber, buildOutputRow(config, row, result))
        rowNumber++
      }

      if (reader == null) {
        processRow(emptyMap())
      } else {
        reader.use {
          var row = it.read()
          while (row != null) {
            processRow(row)
            row = it.read()
          }
        }
      }
      nodeProgressCallback.report(100)
      LOGGER.info("Claude AI Agent processed $rowNumber row(s)")
    }
  }

  private fun runRow(
    config: Config,
    row: Map<String, Any>,
    rowNumber: Long,
    totalRows: Long,
    progress: NodeProgressCallback
  ): AgentResult {
    val percentage = (rowNumber * 100 / totalRows).toInt().coerceAtMost(99)
    val label = "Row ${rowNumber + 1}/$totalRows"
    progress.report(NodeProgress(progressPercentage = percentage, message = "$label: starting agent"))

    val prompt = try {
      renderTemplate(config.promptTemplate, row)
    } catch (e: Exception) {
      return AgentResult.failure("Prompt template rendering failed: ${e.message}")
    }
    if (prompt.isBlank()) {
      return AgentResult.failure("Rendered prompt is empty")
    }

    val request = config.template.copy(
      prompt = prompt,
      inputJson = if (config.includeRowAsFile) objectMapper.writeValueAsString(row) else null
    )
    val result = try {
      agentRunner.run(request) { message ->
        progress.report(NodeProgress(progressPercentage = percentage, message = "$label: $message"))
      }
    } catch (e: NodeRuntimeException) {
      throw e
    } catch (e: InterruptedException) {
      throw e
    } catch (e: Exception) {
      LOGGER.error("Agent run failed for row $rowNumber", e)
      AgentResult.failure("Agent run failed: ${e.message}")
    }
    if (result.isError) {
      LOGGER.warn("Agent returned an error for row $rowNumber: ${result.error}")
    }
    return result
  }

  private fun buildOutputRow(config: Config, row: Map<String, Any>, result: AgentResult): Map<String, Any> =
    row.toMutableMap().apply {
      this[config.responseColumn] = result.response
      this[COL_IS_ERROR] = result.isError
      this[COL_ERROR] = result.error
      this[COL_NUM_TURNS] = result.numTurns
      this[COL_DURATION_MS] = result.durationMs
      this[COL_COST_USD] = result.costUsd
      this[COL_INPUT_TOKENS] = result.inputTokens
      this[COL_OUTPUT_TOKENS] = result.outputTokens
      this[COL_MODEL] = result.model
      if (config.hasOutputSchema) {
        val structured = toCellValue(result.structuredOutput)
        this[COL_STRUCTURED_OUTPUT] = structured ?: emptyMap<String, Any>()
        if (config.flattenStructuredOutput && structured is Map<*, *>) {
          structured.forEach { (key, value) ->
            val cell = toCellValue(value)
            if (key != null && cell != null) this[key.toString()] = cell
          }
        }
      }
      if (config.includeIntermediateSteps) {
        this[COL_INTERMEDIATE_STEPS] = result.steps
      }
    }

  /** Converts parsed JSON into a supported cell type (String/Int/Long/Double/Boolean/List/Map); null stays null. */
  private fun toCellValue(value: Any?): Any? = when (value) {
    null -> null
    is String, is Int, is Long, is Double, is Float, is Boolean -> value
    is Short, is Byte -> (value as Number).toInt()
    is BigInteger -> if (value.bitLength() < 64) value.toLong() else value.toString()
    is BigDecimal -> value.toDouble()
    is Number -> value.toDouble()
    is Map<*, *> -> value.entries.filter { it.key != null && it.value != null }
      .associate { it.key.toString() to toCellValue(it.value)!! }
    is List<*> -> value.mapNotNull { toCellValue(it) }
    else -> value.toString()
  }

  private fun resolveToken(props: Map<String, Any>, executionContext: ExecutionContext): String? {
    val backend = parseBackend(props)
    val credentialName = props.string("credential")
    if (credentialName == null) {
      if (backend.requiresCredential) {
        throw configError("A credential with an API token is required for backend $backend")
      }
      return null
    }
    val credential = try {
      executionContext.getCredential<Map<String, Any>>(credentialName)
    } catch (_: CredentialsNotFoundException) {
      throw configError("Credential '$credentialName' not found")
    }
    return credential["token"]?.toString()?.takeIf { it.isNotBlank() }
      ?: throw configError("Credential '$credentialName' has no 'token' key (keys: ${credential.keys.joinToString()})")
  }

  private fun parseConfig(props: Map<String, Any>, token: String?): Config {
    val backend = parseBackend(props)
    val baseUrl = props.string("baseUrl")
    try {
      backend.resolveBaseUrl(baseUrl)
    } catch (e: IllegalArgumentException) {
      throw configError(e.message ?: "Invalid base URL")
    }

    val tools = props.stringList("tools") ?: DEFAULT_TOOLS
    val unknownTools = tools - KNOWN_TOOLS
    if (unknownTools.isNotEmpty()) {
      throw configError("Unknown tools: ${unknownTools.joinToString()}")
    }
    val outputSchema = props.string("outputSchema")?.also { validateJsonObject(it, "Output Schema") }
    val effort = props.string("effort")?.lowercase()?.also {
      if (it !in EFFORT_LEVELS) throw configError("Unknown effort level: $it")
    }
    val systemPromptMode = when (props.string("systemPromptMode")?.uppercase()) {
      null, "APPEND" -> SystemPromptMode.APPEND
      "REPLACE" -> SystemPromptMode.REPLACE
      else -> throw configError("Unknown system prompt mode: ${props["systemPromptMode"]}")
    }

    return Config(
      template = AgentRequest(
        prompt = "",
        backend = backend,
        baseUrl = baseUrl,
        token = token,
        model = props.string("model") ?: "sonnet",
        fallbackModel = props.string("fallbackModel"),
        systemPrompt = props.string("systemPrompt"),
        systemPromptMode = systemPromptMode,
        tools = tools,
        bashAllowList = props.stringList("bashAllowList") ?: DEFAULT_BASH_ALLOW_LIST,
        bashDenyList = props.stringList("bashDenyList") ?: DEFAULT_BASH_DENY_LIST,
        mcpConfig = props.string("mcpServers")?.let { normalizeMcpConfig(it) },
        outputSchema = outputSchema,
        maxTurns = props.int("maxTurns", 10).coerceIn(1, 200),
        maxBudgetUsd = props.double("maxBudgetUsd", 0.0).coerceAtLeast(0.0),
        effort = effort,
        timeoutSeconds = props.int("timeoutSeconds", 300).coerceIn(10, 7200).toLong()
      ),
      promptTemplate = props.string("prompt") ?: DEFAULT_PROMPT,
      includeRowAsFile = props.boolean("includeRowAsFile", true),
      responseColumn = props.string("responseColumn") ?: "response",
      hasOutputSchema = outputSchema != null,
      flattenStructuredOutput = props.boolean("flattenStructuredOutput", false),
      includeIntermediateSteps = props.boolean("includeIntermediateSteps", false),
      failOnError = props.string("onError")?.uppercase() == "FAIL"
    )
  }

  private fun parseBackend(props: Map<String, Any>): BackendPreset = try {
    BackendPreset.fromProperty(props.string("backend"))
  } catch (e: IllegalArgumentException) {
    throw configError(e.message ?: "Unknown backend")
  }

  /** Accepts either `{"mcpServers": {...}}` or the bare server map and returns the wrapped form. */
  private fun normalizeMcpConfig(json: String): String {
    val parsed = validateJsonObject(json, "MCP Servers")
    val wrapped = if (parsed.containsKey("mcpServers")) parsed else mapOf("mcpServers" to parsed)
    return objectMapper.writeValueAsString(wrapped)
  }

  private fun validateJsonObject(json: String, field: String): Map<String, Any> = try {
    objectMapper.readValue(json, object : TypeReference<Map<String, Any>>() {})
  } catch (e: Exception) {
    throw configError("$field must be a JSON object: ${e.message}")
  }

  private fun renderTemplate(templateString: String, row: Map<String, Any>): String {
    val template = Template("prompt", StringReader(templateString), freemarkerConfig)
    val writer = StringWriter()
    template.process(mapOf("row" to row), writer)
    return writer.toString()
  }

  private fun configError(message: String) =
    NodeRuntimeException(isRetriable = false, workflowId = "", nodeId = "", message = message)

  private fun Map<String, Any>.string(key: String): String? = this[key]?.toString()?.trim()?.takeIf { it.isNotEmpty() }

  private fun Map<String, Any>.stringList(key: String): List<String>? =
    (this[key] as? List<*>)?.mapNotNull { it?.toString()?.trim()?.takeIf { s -> s.isNotEmpty() } }

  private fun Map<String, Any>.int(key: String, default: Int): Int = when (val v = this[key]) {
    is Number -> v.toInt()
    is String -> v.trim().toIntOrNull() ?: default
    else -> default
  }

  private fun Map<String, Any>.double(key: String, default: Double): Double = when (val v = this[key]) {
    is Number -> v.toDouble()
    is String -> v.trim().toDoubleOrNull() ?: default
    else -> default
  }

  private fun Map<String, Any>.boolean(key: String, default: Boolean): Boolean = when (val v = this[key]) {
    is Boolean -> v
    is String -> v.trim().toBooleanStrictOrNull() ?: default
    else -> default
  }
}
