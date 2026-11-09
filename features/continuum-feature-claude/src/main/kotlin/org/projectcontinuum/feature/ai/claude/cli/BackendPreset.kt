package org.projectcontinuum.feature.ai.claude.cli

/**
 * How the API token is sent to the backend.
 *
 * - [API_KEY]: `ANTHROPIC_API_KEY` → `X-Api-Key` header (Anthropic API)
 * - [BEARER]: `ANTHROPIC_AUTH_TOKEN` → `Authorization: Bearer` header (Ollama, OpenRouter, LiteLLM, …)
 */
enum class AuthMode { API_KEY, BEARER }

/**
 * LLM backends reachable by Claude Code.
 *
 * Claude Code only speaks the Anthropic Messages API (`/v1/messages`), so every backend must expose an
 * Anthropic-compatible endpoint: Ollama does natively, OpenRouter and LiteLLM translate to OpenAI, Gemini,
 * etc. Base URLs are given without the `/v1` suffix.
 *
 * @property defaultBaseUrl Base URL used when the node's Base URL field is empty (null = Anthropic default / must be set)
 * @property requiresCredential Whether a token credential is mandatory
 * @property placeholderToken Token sent when no credential is configured (some backends reject an empty token)
 */
enum class BackendPreset(
  val defaultBaseUrl: String?,
  val authMode: AuthMode,
  val requiresCredential: Boolean,
  val placeholderToken: String = "unused"
) {
  ANTHROPIC(null, AuthMode.API_KEY, requiresCredential = true),
  OLLAMA("http://localhost:11434", AuthMode.BEARER, requiresCredential = false, placeholderToken = "ollama"),
  OLLAMA_CLOUD("https://ollama.com", AuthMode.BEARER, requiresCredential = true),
  OPENROUTER("https://openrouter.ai/api", AuthMode.BEARER, requiresCredential = true),
  LITELLM("http://localhost:4000", AuthMode.BEARER, requiresCredential = false),
  CUSTOM(null, AuthMode.BEARER, requiresCredential = false);

  /**
   * Resolves the base URL for this backend: the configured value (trailing `/` and `/v1` stripped)
   * or [defaultBaseUrl]. Always null for [ANTHROPIC], which uses Claude Code's built-in endpoint.
   *
   * @throws IllegalArgumentException if [CUSTOM] has no base URL
   */
  fun resolveBaseUrl(configured: String?): String? {
    if (this == ANTHROPIC) return null
    val url = configured?.trim()?.trimEnd('/')?.removeSuffix("/v1")?.takeIf { it.isNotEmpty() } ?: defaultBaseUrl
    require(url != null) { "Base URL is required for backend $name" }
    require(url.startsWith("http://") || url.startsWith("https://")) { "Base URL must use HTTP or HTTPS: $url" }
    return url
  }

  companion object {
    fun fromProperty(value: String?): BackendPreset =
      if (value.isNullOrBlank()) ANTHROPIC
      else entries.firstOrNull { it.name.equals(value.trim(), ignoreCase = true) }
        ?: throw IllegalArgumentException("Unknown backend: $value")
  }
}
