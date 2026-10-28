# Contributing to continuum-feature-ai

Thanks for your interest in contributing to Project Continuum. This repository provides AI and ML nodes, including LLM fine-tuning.

By contributing, you agree that your contributions are licensed under the [Apache License 2.0](LICENSE), the same license as this project.

## Ways to contribute

- **Report a bug or request a feature** by [opening an issue](https://github.com/projectcontinuum-org/continuum-feature-ai/issues). Include what you expected, what happened, and steps to reproduce. For bugs, include versions and relevant logs.
- **Improve the documentation**, including typos and unclear explanations. The docs live in [continuum-docs](https://github.com/projectcontinuum-org/continuum-docs).
- **Submit a pull request** for a bug fix or feature. For anything larger than a small fix, open an issue first so we can agree on the approach before you invest time.

## Development setup

You will need **JDK 21** ([Eclipse Temurin](https://adoptium.net/) is recommended). Docker is needed to run the local stack.

```bash
git clone https://github.com/<your-username>/continuum-feature-ai.git
cd continuum-feature-ai

# Build and run all tests
./gradlew build

# Run the worker locally (registers the nodes with Temporal)
./gradlew :worker:bootRun
```

The worker needs the Continuum platform running. Use [continuum-local-setup](https://github.com/projectcontinuum-org/continuum-local-setup) or the compose file in `docker/`.

## Adding or changing a node

Each node has a unit test and a short documentation file. The step-by-step [guide to creating new nodes](https://docs.projectcontinuum.org/contributing) covers the node class, the data and UI schemas, tests and documentation. [continuum-feature-template](https://github.com/projectcontinuum-org/continuum-feature-template) is a minimal working example.

## Pull request process

1. Fork the repository and create a branch from `main`.
2. Keep each pull request focused on one change.
3. Make sure the build and tests above pass locally, and add or update tests for behavior you change.
4. Write a clear commit message and pull request description: a short imperative summary line (for example "Fix retry config validation"), followed by the reason for the change.
5. Open the pull request against `main` and respond to review feedback. A maintainer will merge it once it is approved and CI passes.

## Other Continuum repositories

Project Continuum is split across several repositories. If your change belongs elsewhere, see the [organization overview](https://github.com/projectcontinuum-org) and the [documentation](https://docs.projectcontinuum.org).
