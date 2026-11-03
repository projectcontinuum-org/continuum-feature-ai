package org.projectcontinuum.feature.ai.claude.cli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class AgentEnvironmentTest {

    private val workerEnv = mapOf(
        "PATH" to "/usr/bin",
        "HOME" to "/home/continuum",
        "LC_ALL" to "C.UTF-8",
        "HTTPS_PROXY" to "http://proxy:3128",
        "ANTHROPIC_API_KEY" to "worker-key",
        "ANTHROPIC_AUTH_TOKEN" to "worker-token",
        "CLAUDE_CODE_OAUTH_TOKEN" to "oauth",
        "AWS_SECRET_ACCESS_KEY" to "aws",
        "SPRING_DATASOURCE_PASSWORD" to "db",
        "CONTINUUM_CREDENTIALS_SERVER_BASE_URL" to "http://creds"
    )

    @Test
    fun `only allow-listed worker variables are inherited`() {
        val env = ClaudeCliRunner.agentEnvironment(workerEnv, emptyMap())

        assertEquals(
            setOf("PATH", "HOME", "LC_ALL", "HTTPS_PROXY"),
            env.keys
        )
    }

    @Test
    fun `node credential comes only from the builder`() {
        val env = ClaudeCliRunner.agentEnvironment(workerEnv, mapOf("ANTHROPIC_API_KEY" to "node-key"))

        assertEquals("node-key", env["ANTHROPIC_API_KEY"])
        assertFalse(env.containsKey("ANTHROPIC_AUTH_TOKEN"))
        assertFalse(env.values.contains("worker-key"))
    }
}
