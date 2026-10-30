package org.projectcontinuum.feature.ai.claude.cli

import tools.jackson.databind.ObjectMapper

/** Outcome of one agent run, flattened from the CLI's final `result` event. */
data class AgentResult(
  val isError: Boolean,
  val error: String,
  val response: String,
  val numTurns: Int,
  val durationMs: Long,
  val costUsd: Double,
  /** Prompt tokens including cache reads/writes */
  val inputTokens: Long,
  val outputTokens: Long,
  val model: String,
  /** Parsed `structured_output` when an output schema was given, else null */
  val structuredOutput: Any?,
  /** Text blocks, tool calls, tool results and permission denials in order */
  val steps: List<Map<String, Any>>
) {
  companion object {
    fun failure(error: String, durationMs: Long = 0, model: String = "") = AgentResult(
      isError = true, error = error, response = "", numTurns = 0, durationMs = durationMs, costUsd = 0.0,
      inputTokens = 0, outputTokens = 0, model = model, structuredOutput = null, steps = emptyList()
    )
  }
}

/**
 * Incremental parser for `claude -p --output-format stream-json --verbose` output.
 *
 * Feed every stdout line to [accept]; it returns a short human-readable progress message for tool calls.
 * After the process exits, [toResult] builds the [AgentResult] from the final `result` event.
 * Not thread-safe: use from one thread, or hand over after a happens-before edge (e.g. `Thread.join`).
 */
class StreamJsonParser(
  private val objectMapper: ObjectMapper,
  private val maxStepChars: Int = 2000
) {
  private val steps = mutableListOf<Map<String, Any>>()
  private var initModel: String? = null
  private var responseModel: String? = null
  private var result: Map<*, *>? = null
  private var lastApiError: String? = null
  /**
   * Text of the latest synthetic assistant message, which the CLI emits instead of a model reply when an
   * API call fails for good (HTTP 4xx/5xx, connection refused, TLS, …). Cleared by any real model reply.
   */
  private var syntheticError: String? = null

  /** True once the final `result` event was seen. */
  val hasResult: Boolean get() = result != null

  fun accept(line: String): String? {
    val trimmed = line.trim()
    if (!trimmed.startsWith("{")) return null
    val event = try {
      objectMapper.readValue(trimmed, Map::class.java)
    } catch (_: Exception) {
      return null
    }
    return when (event["type"]) {
      "system" -> onSystem(event)
      "assistant" -> onAssistant(event)
      "user" -> onUser(event)
      "result" -> {
        result = event
        null
      }
      else -> null
    }
  }

  private fun onSystem(event: Map<*, *>): String? = when (event["subtype"]) {
    "init" -> {
      initModel = event["model"] as? String
      "Agent started"
    }
    "api_retry" -> {
      lastApiError = listOfNotNull(event["error_status"]?.toString(), event["error"]?.toString()).joinToString(" ")
      "API error, retrying (attempt ${event["attempt"]}/${event["max_retries"]})"
    }
    else -> null
  }

  private fun onAssistant(event: Map<*, *>): String? {
    val message = event["message"] as? Map<*, *> ?: return null
    val model = message["model"] as? String
    val content = message["content"] as? List<*> ?: emptyList<Any>()
    if (event["error"] != null || model == SYNTHETIC_MODEL) {
      val text = content.mapNotNull { (it as? Map<*, *>)?.get("text") as? String }.joinToString("\n").trim()
      syntheticError = text.ifBlank { event["error"]?.toString() ?: "API error" }
      steps += mapOf("type" to "api_error", "error" to (event["error"]?.toString() ?: ""), "text" to syntheticError!!.truncate())
      return "API error"
    }
    syntheticError = null
    model?.takeIf { it.isNotBlank() }?.let { responseModel = it }
    var progress: String? = null
    for (block in content) {
      val b = block as? Map<*, *> ?: continue
      when (b["type"]) {
        "text" -> (b["text"] as? String)?.takeIf { it.isNotBlank() }?.let {
          steps += mapOf("type" to "text", "text" to it.truncate())
        }
        "tool_use" -> {
          val name = b["name"]?.toString() ?: "tool"
          val input = b["input"] as? Map<*, *> ?: emptyMap<String, Any>()
          steps += mapOf(
            "type" to "tool_use",
            "id" to (b["id"]?.toString() ?: ""),
            "name" to name,
            "input" to stringKeys(input)
          )
          val detail = (input["command"] ?: input["file_path"] ?: input["pattern"] ?: input["url"] ?: input["query"])
            ?.toString()?.lineSequence()?.firstOrNull()
          progress = if (detail.isNullOrBlank()) name else "$name: ${detail.truncate(80)}"
        }
      }
    }
    return progress
  }

  private fun onUser(event: Map<*, *>): String? {
    val message = event["message"] as? Map<*, *> ?: return null
    for (block in message["content"] as? List<*> ?: emptyList<Any>()) {
      val b = block as? Map<*, *> ?: continue
      if (b["type"] != "tool_result") continue
      steps += mapOf(
        "type" to "tool_result",
        "tool_use_id" to (b["tool_use_id"]?.toString() ?: ""),
        "content" to contentText(b["content"]).truncate(),
        "is_error" to (b["is_error"] == true)
      )
    }
    return null
  }

  /**
   * Builds the run outcome. Missing `result` event (crash, kill, timeout) yields an error result
   * whose message comes from [fallbackError].
   */
  fun toResult(fallbackError: String, wallClockMs: Long): AgentResult {
    val model = responseModel ?: initModel ?: ""
    val r = result ?: return AgentResult.failure(
      error = (syntheticError ?: lastApiError?.let { "last API error: $it" })?.let { "$fallbackError ($it)" } ?: fallbackError,
      durationMs = wallClockMs,
      model = model
    ).copy(steps = steps.toList())

    val usage = r["usage"] as? Map<*, *> ?: emptyMap<String, Any>()
    val subtype = r["subtype"]?.toString() ?: ""
    // A failed API call ends the run with the CLI's error text as `result`; older CLI versions do not
    // always flag that with `is_error`, so the trailing synthetic message counts as an error too.
    val isError = r["is_error"] == true || subtype.startsWith("error") || syntheticError != null
    val text = r["result"] as? String ?: ""
    val numTurns = (r["num_turns"] as? Number)?.toInt() ?: 0

    val denials = (r["permission_denials"] as? List<*>).orEmpty().mapNotNull { d ->
      val m = d as? Map<*, *> ?: return@mapNotNull null
      mapOf(
        "type" to "permission_denied",
        "tool_name" to (m["tool_name"]?.toString() ?: ""),
        "tool_use_id" to (m["tool_use_id"]?.toString() ?: ""),
        "input" to stringKeys(m["tool_input"] as? Map<*, *> ?: emptyMap<String, Any>())
      )
    }

    return AgentResult(
      isError = isError,
      error = if (isError) errorMessage(subtype, text, numTurns, r["api_error_status"]) else "",
      // never pass CLI error text off as the agent's answer
      response = if (isError) "" else text,
      numTurns = numTurns,
      durationMs = (r["duration_ms"] as? Number)?.toLong() ?: wallClockMs,
      costUsd = (r["total_cost_usd"] as? Number)?.toDouble() ?: 0.0,
      inputTokens = listOf("input_tokens", "cache_creation_input_tokens", "cache_read_input_tokens")
        .sumOf { (usage[it] as? Number)?.toLong() ?: 0L },
      outputTokens = (usage["output_tokens"] as? Number)?.toLong() ?: 0L,
      model = model,
      structuredOutput = r["structured_output"],
      steps = steps + denials
    )
  }

  private fun errorMessage(subtype: String, text: String, numTurns: Int, apiStatus: Any?): String = when (subtype) {
    "error_max_turns" -> "Reached the maximum number of turns ($numTurns)"
    "error_max_budget_usd" -> "Reached the maximum budget"
    "error_max_structured_output_retries" -> "The agent could not produce output matching the output schema"
    "error_during_execution" -> text.ifBlank { syntheticError ?: "Error during execution" }
    else -> {
      val detail = text.ifBlank { syntheticError ?: "" }.trim()
      when {
        apiStatus == null -> detail.ifBlank { "Agent run failed" }
        detail.isBlank() -> "API error $apiStatus"
        // the CLI's own text usually reads "API Error: 500 …" already
        detail.contains(apiStatus.toString()) -> detail
        else -> "API error $apiStatus: $detail"
      }
    }
  }

  private fun contentText(content: Any?): String = when (content) {
    null -> ""
    is String -> content
    is List<*> -> content.joinToString("\n") { block ->
      val b = block as? Map<*, *>
      when (b?.get("type")) {
        "text" -> b["text"]?.toString() ?: ""
        null -> block?.toString() ?: ""
        else -> "[${b["type"]}]"
      }
    }
    else -> content.toString()
  }

  /** Cell maps must not contain nulls or non-String keys; nested values are JSON-serialized as-is. */
  private fun stringKeys(map: Map<*, *>): Map<String, Any> =
    map.entries.filter { it.key != null && it.value != null }.associate { it.key.toString() to it.value!! }

  private fun String.truncate(limit: Int = maxStepChars): String =
    if (length <= limit) this else take(limit) + "… [${length - limit} more chars]"

  private companion object {
    /** Model name the CLI puts on assistant messages it generates itself (API errors, aborts). */
    const val SYNTHETIC_MODEL = "<synthetic>"
  }
}
