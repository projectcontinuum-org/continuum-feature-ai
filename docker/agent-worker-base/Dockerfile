# Base image for the continuum-feature-ai worker.
#
# Jib layers the worker application on top of this image (see worker/build.gradle.kts).
# It adds what the AI nodes need at runtime on top of the default eclipse-temurin JRE:
#   - Claude AI Agent: the Claude Code CLI plus the shell tools the agent is allowed to use
#   - Unsloth LLM Trainer: python3 + venv (packages are installed into the venv at runtime)
#
# Build locally:
#   docker build -t projectcontinuum/continuum-feature-ai-worker-base:dev docker/agent-worker-base
FROM eclipse-temurin:21-jre

ARG TARGETARCH
# Claude Code release channel ("stable", "latest") or an exact version, e.g. "2.1.204"
ARG CLAUDE_CODE_VERSION=stable
ARG YQ_VERSION=v4.45.4

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        ca-certificates curl git jq grep sed gawk less unzip \
        python3 python3-venv python3-pip \
    && rm -rf /var/lib/apt/lists/*

RUN ARCH="${TARGETARCH:-$(dpkg --print-architecture)}" \
    && curl -fsSL -o /usr/local/bin/yq "https://github.com/mikefarah/yq/releases/download/${YQ_VERSION}/yq_linux_${ARCH}" \
    && chmod 0755 /usr/local/bin/yq \
    && yq --version

# The native installer puts a versioned binary under ~/.local; copy the resolved binary
# to /usr/local/bin so it is on PATH for any user and drop the installer state.
RUN curl -fsSL https://claude.ai/install.sh | bash -s "${CLAUDE_CODE_VERSION}" \
    && cp -L /root/.local/bin/claude /usr/local/bin/claude \
    && chmod 0755 /usr/local/bin/claude \
    && rm -rf /root/.local /root/.claude /root/.claude.json \
    && claude --version

# Non-root runtime user matching the chart's securityContext (runAsUser/fsGroup 1000).
# Ubuntu-based temurin images ship an "ubuntu" user with uid 1000; replace it.
RUN (userdel -r ubuntu 2>/dev/null || true) \
    && useradd --create-home --uid 1000 --shell /bin/bash continuum

ENV DISABLE_AUTOUPDATER=1 \
    DISABLE_TELEMETRY=1

USER 1000
WORKDIR /home/continuum
