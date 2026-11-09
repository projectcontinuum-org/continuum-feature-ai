package org.projectcontinuum.feature.ai.claude

import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.Configuration

/**
 * Spring Boot auto-configuration for the Claude AI Agent feature module.
 *
 * ## Auto-configured Components
 * - [ClaudeAIAgentNodeModel][org.projectcontinuum.feature.ai.claude.node.ClaudeAIAgentNodeModel] - agent node
 * - [ClaudeCliRunner][org.projectcontinuum.feature.ai.claude.cli.ClaudeCliRunner] - runs the `claude` CLI
 *
 * @author Continuum Team
 * @since 0.0.6
 */
@Configuration
@ComponentScan(basePackages = ["org.projectcontinuum.feature.ai.claude"])
class AutoConfigure
