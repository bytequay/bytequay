<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="assets/wordmark-dark.svg">
    <img alt="ByteQuay" src="assets/wordmark.svg" width="420">
  </picture>
</p>

<p align="center">
  <strong>Review is all you need.</strong><br>
  A local-first macOS app for GitHub pull-request work.
</p>

<p align="center">
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-Apache%202.0-blue.svg" alt="Apache 2.0 license"></a>
  <a href="https://github.com/bytequay/bytequay/actions/workflows/ci.yml"><img src="https://github.com/bytequay/bytequay/actions/workflows/ci.yml/badge.svg" alt="CI status"></a>
</p>

ByteQuay brings GitHub pull requests and local agent work into one desktop
app. Review diffs, checks, and conversation, then work with Codex or Claude in
separate managed worktrees. Application state stays on your Mac; GitHub and
model-provider requests use their external services. GitHub writes require a
direct user action or explicitly accepted automation.

ByteQuay is pre-1.0, under active redesign, and currently supports macOS only.

## Current scope

- A Home page with watched repositories, contribution history, and recent activity.
- Pull-request lists for authored PRs and review requests.
- PR descriptions, changed files, patches, checks, and activity.
- Direct comment, approve, and merge actions.
- GitHub CLI token import or personal access token onboarding.
- An embedded GitHub view using Electron `WebContentsView`.
- Worktree-scoped Codex and Claude tasks with transcripts, approvals, steering,
  queued requests, attachments, and child-agent inspection.
- WorkBench review, private discussions, and repository context alongside
  development work.

Start with the [user guide](docs/documentation/README.md) for ByteQuay's goals,
setup, and instructions for its main features. See the
[high-level design](docs/overview/design.md) for architecture, workflows, and
trust boundaries. The [website source](website/README.md) contains the landing
page, documentation, and design overview.

## Install

Download the latest `.dmg` from
[GitHub Releases](https://github.com/bytequay/bytequay/releases), open it, and
drag **ByteQuay.app** to **Applications**.

Current builds require macOS 14+ and JDK 25 LTS.

Current releases are not notarized. If macOS reports that ByteQuay is damaged
after downloading it from the official Releases page, run:

```sh
xattr -dr com.apple.quarantine /Applications/ByteQuay.app
```

## Develop

Requirements: macOS 14+, JDK 25 LTS (25.0.4.1+), Maven 3.9+, Node 24.21.0 LTS,
and npm 11.19+. Run `nvm install` from the repository root to select the
checked-in Node version.

```sh
git clone https://github.com/bytequay/bytequay.git
cd bytequay
./dev.sh
```

`dev.sh` starts the Spring Boot sidecar on `127.0.0.1`, preferring port `9527`
and falling back to an OS-assigned port when needed. It passes the selected
port to the Electron frontend. The backend uses SQLite with Hibernate managing
the current schema directly; there is no migration framework.

See [CONTRIBUTING.md](CONTRIBUTING.md) for build and test commands.

## License

ByteQuay is released under the [Apache License 2.0](LICENSE).
