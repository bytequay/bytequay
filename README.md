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

ByteQuay puts your GitHub pull requests, their details, diffs, checks,
activity, comments, approvals, and merge controls in one desktop app. Data and
credentials stay on your Mac; GitHub writes happen only after a direct user
action.

ByteQuay is pre-1.0, under active redesign, and currently supports macOS only.

## Current scope

- A Home page with watched repositories, contribution history, and recent activity.
- Pull-request lists for authored PRs and review requests.
- PR descriptions, changed files, patches, checks, and activity.
- Direct comment, approve, and merge actions.
- GitHub OAuth, GitHub CLI token import, or personal access token onboarding.
- An embedded GitHub view using Electron `WebContentsView`.

Agent development flows and their local-worktree/task runtime are intentionally
not part of the current codebase. They will be redesigned from a clean starting
point.

See the [ByteQuay design index](docs/design/design.md) for the whole-system
overview, module architecture, and proposed agent-service design.

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

Requirements: macOS 14+, JDK 25 LTS, Maven 3.9+, Node 24.18.1 LTS,
and npm 11.16+. Run `nvm install` from the repository root to select the
checked-in Node version.

```sh
git clone https://github.com/bytequay/bytequay.git
cd bytequay
./dev.sh
```

`dev.sh` starts the Spring Boot sidecar on `localhost:53123` and launches the
Electron frontend. The backend uses SQLite with Hibernate managing the current
schema directly; there is no migration framework.

To start and monitor a CI Autofix run from a pull request worktree, see the
[CI Autofix shell](docs/ci-autofix-shell.md).

See [CONTRIBUTING.md](CONTRIBUTING.md) for build and test commands.

## License

ByteQuay is released under the [Apache License 2.0](LICENSE).
