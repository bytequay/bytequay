# Contributing to ByteQuay

ByteQuay is a macOS desktop app for daily GitHub pull-request and local agent
work. An Electron + React + TypeScript frontend talks to a local Spring Boot
sidecar on `127.0.0.1`. It first tries port `9527`, falls back to an OS-assigned
port if that port is occupied, and reports the selected port to Electron.

The current product boundary includes Home, watched repositories, GitHub
credentials, pull-request lists/details/actions, and the approved
worktree-scoped Codex and Claude agent-task surface. The previous task-agent
and staged development-flow systems remain deleted. Do not restore their APIs,
schemas, compatibility layers, or design assumptions; expand the current agent
service within the documented architecture and explicitly approved product scope.

## Prerequisites

| Tool | Version | Notes |
| --- | --- | --- |
| JDK | 25.0.4.1+ | Java 25 LTS; the Maven compiler targets Java 25 and CI selects the latest patch. |
| Maven | 3.9+ | Used for the local backend. |
| Node | 24.21.0 | Latest Node LTS; pinned in `.nvmrc` and used by CI. |
| npm | 11.19+ | Node 24.21.0 ships with npm 11.19.0. |
| macOS | 14+ | Required for the packaged desktop app. |

No Docker or PostgreSQL installation is required. Runtime data is stored in a
local SQLite database.

Codex agent tasks require Codex CLI `0.159.3` or newer. To install the minimum
supported release explicitly:

```bash
npm install -g @openai/codex@0.159.3
codex --version
```

The release minimum is configured by `bytequay.agent.codex-minimum-version`
in `backend/app/src/main/resources/application.properties`, currently
`0.159.3`. Update that property when a later ByteQuay release requires a newer
CLI. At startup, ByteQuay automatically upgrades an older installed CLI with
`npm install --global @openai/codex@<configured minimum>`; it leaves equal or
newer versions installed. It then checks the configured Codex executable
again, so an npm installation that does not update that executable cannot
bypass the minimum. Missing, failed, or unreadable version checks do not
trigger installation. Restart ByteQuay after changing the installation
manually. The separate `0.151.0` App Server compatibility fixture is historical
protocol evidence, not the production version requirement.

The `0.159.3` minimum includes the GPT-6.1 Sol catalog added in Codex `0.159.1`.
ByteQuay discovers available models through the CLI's `model/list` response;
model names are not hard-coded in the picker. After the startup upgrade, model
discovery refreshes automatically. Account availability still determines which
models the CLI exposes.

## Quick start

```bash
git clone https://github.com/bytequay/bytequay.git
cd bytequay
nvm install
./dev.sh
```

The dev backend runs a temporary copy of its built JAR so later Maven builds
cannot overwrite classes used by the running JVM. Restart `./dev.sh` to load
backend changes; the temporary copy is removed when that dev session exits.
The backend writes its ready port into that session's temporary directory;
`dev.sh` verifies `/hello`, prints the URL, and passes `BYTEQUAY_BACKEND_PORT`
to the frontend. When starting the backend and frontend separately, set that
variable for `npm start` if the backend selected a port other than `9527`.

## Claude accounts

Settings → Models shows CLI status and the default Claude account, with Sign
in when needed. Select a default Claude model there for repositories that use
global settings.

For a different account, in a repository's **Model overrides**, use **Claude accounts → Add Claude
account**, enter a display name such as Work or Personal and the account's
email, then choose **Add and sign in**. Claude pre-fills that email in its
browser login. ByteQuay checks that the authenticated email matches before
marking the account ready; the account row shows the email reported by Claude.
Named-account setup and removal are available in repository settings, not the
global provider list or global model choices.

Each account uses its own ByteQuay profile and macOS Keychain entry, selected
automatically during both sign-in and execution. No password or OAuth token is
stored by ByteQuay. No manual profile path or shell configuration is needed.
Profiles stay in ByteQuay's application data directory, outside repositories
and their worktrees, so Git cannot accidentally commit or clean them.

Readiness checks run with network access disabled: Claude's short status command
can otherwise start token rotation and exit before saving its replacement.
Usage probes keep their input open while the CLI's refresh lock or stored token
expiry indicates a pending refresh, including when usage is returned from cache.
ByteQuay inspects the CLI-owned credential entry for this shutdown check but
does not persist tokens, refresh them itself, or write the credential store.

Override Development and Review as needed and choose a model under the intended
Claude account. Account names are shown with the selection. New tasks use that choice; existing tasks keep the
account they started with, including later turns and resumed sessions. Accounts
can run concurrently across repositories. The default Claude profile remains
available for existing tasks and settings.

The login profile does not change the task's working directory or the user's
`HOME`. Tasks still run in their own worktrees and use the usual build caches,
including `~/.m2`; ByteQuay supplies `JAVA_HOME` for Java and Maven in Claude's
macOS sandbox.

Claude development, review, and CI repair sessions can use a running Docker
Desktop or OrbStack daemon for container-backed tests. The sandbox allows the
standard Docker sockets at `/var/run/docker.sock`, `~/.docker/run/docker.sock`,
and `~/.orbstack/run/docker.sock`. Read-only, text-only, and cherry-pick sessions
do not receive this allowance. Restart ByteQuay after changing its sandbox
configuration so newly opened or resumed provider sessions load the change.
Docker access includes control of the daemon's containers and host bind mounts;
it is not confined to the task worktree.

## Project layout

```text
.
├── backend/          # Maven reactor for the Spring Boot sidecar
│   ├── core/         # Shared domain values and storage ports
│   ├── agent-spi/    # Provider contracts and normalized events
│   ├── agent-core/   # Agent task, turn, approval, queue, and retention logic
│   ├── agent-cli/    # Codex App Server and Claude CLI adapters
│   ├── persistence-sqlite/ # JPA, SQLite, and credential storage
│   └── app/          # GitHub/PR services, agent HTTP, and executable packaging
├── frontend/         # Electron + React + TypeScript
│   └── src/          # Main process, IPC bridge, Home, PR, and Settings UI
├── docs/             # Product design and system architecture
├── website/          # Static product website, documentation, and design overview
└── dev.sh
```

## Building and verifying

Keep the [user documentation](website/documentation.md) focused on the
product's purpose, goals, and short guides that match current UI controls.
Keep `docs/` limited to [design.md](docs/design.md) and
[architecture.md](docs/architecture.md): durable product intent, ownership,
data flow, trust boundaries, and tradeoffs. Describe individual capabilities
as features in the website guide rather than maintaining public per-feature
specifications. Implementation contracts and verification belong with source
and tests. Keep user guides with the website and developer runbooks beside
their scripts. Use GitHub Releases for published release notes.

Developer runbooks: [agent shell](scripts/agent-e2e.md) and
[CI Autofix shell](scripts/ci-autofix.md).

Backend:

```bash
cd backend
mvn verify
```

This checks license headers, Checkstyle, the retained unit tests,
and a full Spring context boot. Hibernate creates or updates the current SQLite
schema from the retained JPA entities. Since the app has no released user-data
migration contract yet, there is no migration history to maintain.

Frontend:

```bash
cd frontend
npm run lint
npx tsc --noEmit
npm test
npm run package
```

Electron 44 requires a code-signed macOS app to display native PR notifications.
Unsigned development and packaged builds still show PR updates inside ByteQuay.

## Code style

Backend Java follows the checked-in Trino-derived Checkstyle rules. Prefer
records for DTOs and immutable values at boundaries.

Keep public collection signatures on JDK interfaces, but use Guava immutable
implementations for values and defensive copies. Do not use JDK
`List.of/copyOf`, `Set.of/copyOf`, or `Map.of/copyOf` in new production code,
tests, or HTTP request bodies; use the corresponding `ImmutableList`,
`ImmutableSet`, or `ImmutableMap` factory. Pass collection arguments directly
to `ImmutableList.copyOf`, `ImmutableSet.copyOf`, and `ImmutableMap.copyOf`;
do not wrap them in `requireNonNull`, because the immutable-copy operation
already rejects null collections and elements.

Keep independently useful records as top-level types. Nest a record in a
service, client, or controller only when it is private implementation data or
belongs exclusively to that enclosing API contract. Reuse the module's shared
validation helper for repeated non-blank and bounded-text checks instead of
duplicating local methods.

State guards use Guava `Preconditions.checkState` rather than a hand-written
`if (...) { throw new IllegalStateException(...); }`, the same way argument
checks already use `checkArgument`. Keep the plain `throw` where `checkState`
would hurt readability: wrapping a caught exception, throwing after a side
effect such as destroying a process, or a fall-through throw at the end of a
multi-branch block.

Refer to types by their simple name and an import, not by a fully qualified
name inline (`Optional.empty()`, not `java.util.Optional.empty()`). Qualify
inline only when two types in scope share a simple name.

Lines may run to 180 characters. Do not wrap a statement that fits: a
precondition check, an assertion, a mock stub, or a log call belongs on one
line. Wrap only where the line break carries meaning — a multi-line boolean
expression broken at `||`/`&&`, a text block, or a lambda with a statement
body.

Frontend code uses React 19 function components and hooks, strict TypeScript,
and plain CSS. Do not add a component library, state manager, or CSS framework
without discussing it first.

Comments should explain a non-obvious reason or constraint, not narrate the
code.

## Database

- Runtime database: `~/Library/Application Support/ByteQuay/bytequay-1.0.db`
- Test databases: unique SQLite files under the system temporary directory
- Single backend: startup locks `backend.lock` in the runtime directory and
  exits when another backend holds it, so quit a running ByteQuay or `./dev.sh`
  before starting a second one
- Schema management: `spring.jpa.hibernate.ddl-auto=update` in production and
  `create-drop` in tests

Schema changes currently move forward by changing the JPA model. Reset local
development data with `./dev.sh --reset-data` when an incompatible redesign
lands.

## Architectural constraints

- Use Electron `WebContentsView`, never `<webview>` or `BrowserView`.
- Keep `@vitejs/plugin-react` on 4.x while this project uses Vite 5.
- Use the `react-jsx` TypeScript transform and React function components.
- Never write to GitHub without an explicit user action.
- The backend is a localhost sidecar, not a remotely exposed service.
- Keep dependencies and abstractions proportionate to the current product.

## Testing

- Backend: JUnit 5, Mockito, and focused Spring tests.
- Frontend: Vitest for current pure behavior; component tests may be added when
  they protect meaningful interaction logic.
- Add tests for new behavior in the same surface. Do not preserve tests for
  deleted compatibility or speculative architecture.

## Commit messages

Use a capitalized imperative subject with no trailing period. Keep one logical
change per commit and omit a body when the subject is self-explanatory. Do not
add AI co-author trailers.
