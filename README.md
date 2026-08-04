<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="assets/wordmark-dark.svg">
    <img alt="ByteQuay" src="assets/wordmark.svg" width="420">
  </picture>
</p>

<p align="center">
  <strong>Review is all you need.</strong><br>
  A local-first macOS workspace for GitHub pull requests and AI coding tasks.
</p>

<p align="center">
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-Apache%202.0-blue.svg" alt="Apache 2.0 license"></a>
  <a href="https://github.com/bytequay/bytequay/actions/workflows/ci.yml"><img src="https://github.com/bytequay/bytequay/actions/workflows/ci.yml/badge.svg" alt="CI status"></a>
</p>

ByteQuay brings pull-request triage, native diffs, AI-assisted review,
CI diagnostics, merge controls, and AI development agents into one app.
Agents work in isolated git worktrees, while pushes, pull requests,
review requests, and merges remain behind explicit approval.

## Vision

We believe every developer will eventually run capable, free, open-source
coding agents locally. Open models can already write useful code; the harness—
context, tools, review, and guardrails—is what makes them effective. ByteQuay's
end goal is to replace cloud agents from OpenAI and Anthropic with open-source
agents running entirely on your machine.

**v0.3.2 is pre-1.0 and actively developed. ByteQuay currently supports macOS only.**

## Highlights

- Review pull requests with native diffs, inline threads, suggestions,
  merge controls, and an embedded GitHub view.
- Draft AI summaries and comments locally before publishing anything.
- Diagnose failing CI and ask AI for root-cause and patch suggestions.
- Review agent work privately as a Local PR before approving a push.
- Plan and run coding tasks in isolated worktrees with a tracked,
  approval-gated lifecycle.
- Keep drafts, view state, repositories, and credentials local.

## Install

Download the latest `.dmg` from
[GitHub Releases](https://github.com/bytequay/bytequay/releases),
open it, and drag **ByteQuay.app** to **Applications**.

Current builds require:

- macOS 14+
- Java 21+
- Git for local repository and agent workflows

Node and Maven are not required when using a release build.

Current releases are not notarized. If macOS reports that ByteQuay is
damaged after downloading it from the official Releases page, run:

```sh
xattr -dr com.apple.quarantine /Applications/ByteQuay.app
```

Connect GitHub during onboarding. Add AI provider keys under
**Settings → Credentials** and choose engines under **Settings → AI**.

## Develop

Requirements: macOS 14+, JDK 21+, Maven 3.9+, Node 20/npm 10+, and Git.

```sh
git clone https://github.com/bytequay/bytequay.git
cd bytequay
./dev.sh
```

`dev.sh` installs missing frontend packages, starts the Spring Boot
sidecar on `localhost:53123`, and launches Electron with Vite.

The frontend is Electron, React 19, and TypeScript. The local backend is
Spring Boot with SQLite and Flyway.

See [CONTRIBUTING.md](CONTRIBUTING.md) for project structure, build
commands, tests, IDE setup, and architectural constraints.

## Documentation

- [Getting started](docs/website/docs/usage/getting-started.md)
- [User guide](docs/website/docs)
- [Release notes](docs/website/release-notes)
- [Release process](RELEASING.md)

## License

ByteQuay is released under the [Apache License 2.0](LICENSE).
