# Use the official image as a parent image
FROM mcr.microsoft.com/vscode/devcontainers/java:21-bookworm

# Install Node.js, Yarn, pkg-config, and libsecret-1-dev
RUN curl -fsSL https://deb.nodesource.com/setup_22.x | bash - && \
    apt-get install -y nodejs pkg-config libsecret-1-dev python3-venv


# Set the default shell to bash
SHELL ["/bin/bash", "-c"]

# Set the working directory
WORKDIR /workspace