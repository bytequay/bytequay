<p align="center">
  <img alt="ByteQuay" src="assets/wordmark.svg" width="40%">
</p>

<p align="center">
  A native macOS desktop app for daily developer review work — your PR
  dashboard, AI review drafts, CI diagnostics, and merge controls in
  one window, with the embedded GitHub UI a click away for everything
  else.
</p>

[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
[![CI](https://github.com/chenjian2664/bytequay/actions/workflows/ci.yml/badge.svg)](https://github.com/chenjian2664/bytequay/actions/workflows/ci.yml)

ByteQuay is a single-window desktop client for reviewing GitHub pull
requests faster than the GitHub web UI lets you. The morning view shows
the PRs awaiting your review and the PRs you've opened, grouped by repo,
with inline previews. The diff viewer hosts a native diff and an AI
review sidebar that drafts per-line comments before you publish a single
character. The PR detail page handles merge, draft toggle, CI refresh,
and inline log diagnostics — including a one-click "Ask AI to fix"
button that sends the failing log to your configured LLM and renders
the suggested fix inline.

The long-term goal is to fully replace github.com for daily review work:
approve, merge, request changes, create PRs, and AI-review without ever
opening a browser tab.

---

## Status

**Pre-1.0 / actively developed.** Useful day-to-day for the author and a
small group of testers; the public OSS release is the project's first
broad-distribution moment. macOS only at runtime.

---

## Download (just want to use it)

Grab the latest `.dmg` from the
[Releases page](https://github.com/chenjian2664/bytequay/releases),
double-click to mount it, and drag **ByteQuay.app** to **Applications**.
The bundle ships its own backend — no Java / Node / Maven install
required.

### First launch on macOS — "ByteQuay is damaged" workaround

The first time you open the app, macOS will likely show:

> "ByteQuay" is damaged and can't be opened. You should move it to the Trash.

Nothing is actually damaged. Apps downloaded from a browser get a
`com.apple.quarantine` flag, and macOS Gatekeeper refuses to launch
anything carrying that flag unless it's signed *and* notarized by an
Apple Developer account — which we don't have yet (see below). Clear
the flag with one command:

```sh
xattr -dr com.apple.quarantine /Applications/ByteQuay.app
```

Then double-click the app as normal — the dialog won't return.

> ⚠️ **This is a temporary workaround.** The proper fix is to sign and
> notarize the build with an Apple Developer ID so macOS trusts it
> on download — that way users just drag-and-go, no terminal step.
> An Apple Developer membership ($99/year) is required to issue the
> Developer ID certificate and submit builds to Apple's notary
> service; we'll wire that into the release workflow once the
> account is in place. Until then, the `xattr` line is the
> recommended path.

### Requirements

ByteQuay needs **`git`** on your `PATH` for the local-repo features
(repos page, branch and commit views, clone / fetch / push). It does
not bundle git — it shells out to whatever `git` you already use in
your terminal so your existing config, SSH keys, signing keys, and
credential helper all just work.

Most dev Macs already have git via Xcode Command Line Tools. If
`git --version` works in your terminal, you're set. If not:

```sh
xcode-select --install
```

The PR dashboard, AI review, diff viewer, and merge controls work
without git — only the local-repo features need it. ByteQuay
detects a missing git on launch and points you to this section.

---

## What's in the box

- **PR dashboard** — two-section "Awaiting my review / My PRs" list,
  grouped by repo, with a live preview pane on the right. Optional
  Kanban and Teams views for filtered triage.
- **Native diff viewer** — file tree, unified diff with click-to-expand
  collapsed code, inline review threads, suggestion blocks, and a
  resizable AI sidebar.
- **AI review** — pluggable provider interface (Claude / OpenAI /
  DeepSeek). Drafts per-line comments + a top-level summary, all stored
  locally until you explicitly publish.
- **Embedded github.com** — anything ByteQuay doesn't natively
  re-implement opens in an embedded `WebContentsView` window that stays
  signed in, so you don't lose context.
- **Merge controls on the PR detail page** — "Rebase and merge" button
  (gated by CI status + your push permission), Yes/No confirm dialog,
  draft / ready-for-review toggle.
- **CI diagnostics** — failing-check cards expand to show GitHub's
  actual error message. "Show full log" lazy-loads the Actions log
  inline with `[ERROR]` / `Caused by` markers highlighted, and
  "✨ Ask AI to fix" sends the log to your model for a root-cause +
  patch suggestion.
- **Reactions, threads, suggestions** — full per-line review threads
  with reactions, reply composer, resolve / unresolve. Inline
  suggestion-block accept that turns the suggestion into a commit.
- **Local-first storage** — everything (drafts, view state, watched
  repos, teams, credentials) lives in a per-user SQLite + macOS Keychain.
  Nothing leaves your machine until you click an action.

---

## Quick start

```bash
git clone https://github.com/chenjian2664/bytequay.git
cd bytequay
./dev.sh
```

That spins up the Spring Boot backend on `localhost:53123` and the
Electron + Vite renderer in dev mode. First run downloads ~250MB of
deps and takes a couple of minutes; subsequent runs start in seconds.

You'll need:

- **macOS 14+** (Apple Silicon or Intel)
- **JDK 21+** (Temurin recommended)
- **Maven 3.9+**
- **Node 20.x**

After it boots, open **Settings → GitHub** and paste a personal access
token with `repo` + `read:org` scopes. The token is encrypted with a
per-machine key and stored in
`~/Library/Application Support/ByteQuay/credentials.key` (mode 0600).

For AI features, also add an API key in **Settings → AI** for whichever
provider you want to use.

---

## Architecture in one paragraph

ByteQuay is an Electron app that spawns a Spring Boot JAR as a child
process. The renderer (React 19 + TypeScript) calls the backend over
`localhost` IPC; the backend calls GitHub's REST + GraphQL APIs and
caches PR detail / drafts / view state in a local SQLite database
managed by Flyway. The embedded github.com surface uses Electron's
`WebContentsView` (not the deprecated `<webview>` tag) and shares the
session cookie store with the rest of the app, so signing in once
sticks.

```
┌────────────────────────────────────────────┐
│ Electron main process                      │
│  ├─ React renderer (Vite-built)            │
│  └─ WebContentsView for embedded GitHub    │
└────────────────────────────────────────────┘
                  │ IPC
                  ▼
┌────────────────────────────────────────────┐
│ Spring Boot sidecar (localhost:53123)      │
│  ├─ GitHub REST + GraphQL client           │
│  ├─ AI provider registry (Claude / OpenAI) │
│  ├─ SQLite + Flyway                        │
│  └─ macOS Keychain (credentials)           │
└────────────────────────────────────────────┘
                  │ HTTPS
                  ▼
              api.github.com
```

---

## Project layout

```
backend/    Spring Boot sidecar (Java 21, Maven)
frontend/   Electron + React + TypeScript (Electron Forge + Vite)
docs/       Design docs and UI mockups (PNG, by section)
licenses/   Apache 2.0 header template shared by both modules
dev.sh      Spawns backend + frontend together
```

---

## Documentation

- **[CONTRIBUTING.md](CONTRIBUTING.md)** — full development setup,
  IDE configuration (IntelliJ + VS Code), code style, build & test
  gates, commit / PR conventions.
- **[LICENSE](LICENSE)** — Apache 2.0.
- **[NOTICE](NOTICE)** — copyright + attribution.
- Design docs and UI mockups live under `docs/` (PNGs organised by
  feature area).

---

## Comparable projects

If ByteQuay isn't the right shape for you, you might prefer:

- **[GitHub Desktop](https://desktop.github.com/)** — official, focused
  on commit / branch ops more than review.
- **[Refined GitHub](https://github.com/refined-github/refined-github)**
  — browser extension that improves the github.com UI in place.
- **[Reviewable](https://reviewable.io/)** — web-based, opinionated
  review UI sitting on top of GitHub.

ByteQuay is a different bet: a native morning-routine app where you
spend the first ten minutes of your day, with AI drafting + diff +
merge in one window.

---

## Contributing

PRs welcome. The development setup, IDE configuration, and full set of
gates (the same ones CI runs — `mvn verify`, `npm run lint`,
`npm run test`, `npm run package`, `npm run check:licenses`) are
documented in **[CONTRIBUTING.md](CONTRIBUTING.md)**.

Conventions are borrowed from
[Trino](https://github.com/trinodb/trino): Trino-derived checkstyle,
Error Prone selection, commit-message style. No Conventional Commits;
imperative subject ≤ 70 chars, body explains *why*, one logical change
per commit.

---

## License

[Apache License 2.0](LICENSE) — same terms as the projects ByteQuay
borrows conventions from. By submitting a contribution you agree to
license your work under the same terms. There is no Contributor
License Agreement; the standard "you warrant you have the right to
license this code" applies.
