# Contributing to ByteQuay

ByteQuay is a macOS desktop app for daily GitHub pull-request work. An Electron
+ React + TypeScript frontend talks to a local Spring Boot sidecar on
`localhost:53123`.

The current product boundary is deliberately small: Home, watched repositories,
GitHub credentials, pull-request lists, and pull-request details/actions. Do not
reintroduce task agents, staged development flows, local-PR runtimes, worktree
orchestration, or compatibility layers without a new design decision.

## Prerequisites

| Tool | Version | Notes |
| --- | --- | --- |
| JDK | 25 | Latest Java LTS; the Maven compiler targets Java 25. |
| Maven | 3.9+ | Used for the local backend. |
| Node | 24.18.1 | Latest Node LTS; pinned in `.nvmrc` and used by CI. |
| npm | 11.16+ | Node 24.18.1 ships with npm 11.16.0. |
| macOS | 14+ | Required for the packaged desktop app. |

No Docker or PostgreSQL installation is required. Runtime data is stored in a
local SQLite database.

## Quick start

```bash
git clone https://github.com/bytequay/bytequay.git
cd bytequay
nvm install
./dev.sh
```

## Project layout

```text
.
├── backend/          # Maven reactor for the Spring Boot sidecar
│   ├── core/         # Shared domain values and storage ports
│   ├── persistence-sqlite/ # JPA, SQLite, and credential storage
│   └── app/          # GitHub/PR services, HTTP, and executable packaging
├── frontend/         # Electron + React + TypeScript
│   └── src/          # Main process, IPC bridge, Home, PR, and Settings UI
├── docs/             # Design notes and screenshots
└── dev.sh
```

## Building and verifying

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

## Code style

Backend Java follows the checked-in Trino-derived Checkstyle rules. Prefer
records for DTOs and immutable values at boundaries.

Keep public collection signatures on JDK interfaces, but use Guava immutable
implementations for values and defensive copies. Do not use JDK
`List.of/copyOf`, `Set.of/copyOf`, or `Map.of/copyOf` in new production code,
tests, or HTTP request bodies; use the corresponding `ImmutableList`,
`ImmutableSet`, or `ImmutableMap` factory.

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

- Runtime database: `~/Library/Application Support/ByteQuay/bytequay.db`
- Test databases: unique SQLite files under the system temporary directory
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
