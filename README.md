<p align="center">
  <img alt="ByteQuay" src="assets/wordmark.svg" width="40%">
</p>

<p align="center">
  A native macOS desktop app built to make code <strong>review</strong>
  the center of your day. A fast PR dashboard, AI-assisted review, CI
  diagnostics, and merge controls in one window — plus AI agents that do
  the writing and committing in isolated git worktrees, so your job
  shifts from author to reviewer. The embedded GitHub UI is a click away
  for everything else.
</p>

[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
[![CI](https://github.com/chenjian2664/bytequay/actions/workflows/ci.yml/badge.svg)](https://github.com/chenjian2664/bytequay/actions/workflows/ci.yml)

ByteQuay is, first and foremost, a tool for **reviewing** code — built
to review GitHub pull requests faster and with more context than the web
UI lets you, and grown into a workspace for the whole loop around a
change. The morning view shows the PRs awaiting your review and the PRs
you've opened, grouped by repo, with inline previews. The diff viewer
hosts a native diff and an AI review sidebar that drafts per-line
comments before you publish a single character. The PR detail page handles merge, draft toggle, CI refresh,
and inline log diagnostics — including a one-click "Ask AI to fix"
button that sends the failing log to your configured LLM and renders the
suggested fix inline.

To keep review at the center, ByteQuay runs **AI dev agents** that do
the writing for you. You cut a task from a planning thread; an agent
picks it up in its own git worktree and drives it through a tracked
lifecycle — implement, validate, internal review, push, CI, mark-ready,
remote review, merge — surfacing the phase live in the UI. The agent
never touches the remote on its own: pushes, PRs, and review requests go
through approval-gated tools, so nothing reaches GitHub without your
explicit click.

That's the guiding philosophy: **you shouldn't be the one writing and
committing code — you should be the one reviewing it.** Let the agents
author and commit; you spend your attention where it matters most —
reading, questioning, and approving the change. Everyone becomes the
code reviewer.

The long-term goal is to fully replace github.com for that daily loop:
review, approve, merge, and create PRs — delegating the writing to
agents — without ever opening a browser tab.

---

## Status

**v0.2.0 — pre-1.0, actively developed.** Used day-to-day by the author
and a small group of testers. The PR-review surface is stable; the AI
dev-agent and task-lifecycle features are newer and evolving fast —
expect rough edges there. macOS only at runtime.

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
- **Merge controls on the PR detail page** — merge button (gated by CI
  status + your push permission), draft / ready-for-review toggle, and
  merge-queue support: enqueue, dequeue, and a clear "removed from the
  queue — checks failed" state when the queue ejects a PR.
- **CI diagnostics** — failing-check cards expand to show GitHub's
  actual error message. "Show full log" lazy-loads the Actions log
  inline with `[ERROR]` / `Caused by` markers highlighted, and
  "✨ Ask AI to fix" sends the log to your model for a root-cause +
  patch suggestion.
- **Reactions, threads, suggestions** — full per-line review threads
  with reactions, reply composer, resolve / unresolve. Inline
  suggestion-block accept that turns the suggestion into a commit.
- **AI dev agents** — the writing side of the loop, so your side stays
  review. Cut a task from a planning thread and an agent develops it in
  an isolated git worktree, driven through a tracked lifecycle (implement
  → validate → internal review → push → CI → ready → remote review →
  merge). The phase is shown live; a server-side reconciler watches the
  linked PR and advances it as CI finishes and the PR merges. Publishing
  runs through approval-gated tools — direct `git push` / `gh` are
  blocked so nothing reaches GitHub without your click.
- **Task queue & threads** — a planning "trunk" thread fans out into
  serial work-unit tasks; queue them up, reorder, and let the scheduler
  run them within a small resource cap.
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
managed by Flyway. Dev-agent tasks run through a scheduler that spawns
the agent CLI per task in its own git worktree, mediates its tool calls
through an approval gate, and tracks each task's lifecycle phase. The
embedded github.com surface uses Electron's
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
│  ├─ Agent scheduler + task lifecycle       │
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
