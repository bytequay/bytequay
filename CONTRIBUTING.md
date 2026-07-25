# Contributing to ByteQuay

Thanks for your interest in ByteQuay. This document covers everything
you need to set up a development environment, run the same gates CI
runs, and ship a change that's ready to merge.

The structure here is loosely modeled after
[Trino's CONTRIBUTING + DEVELOPMENT docs](https://github.com/trinodb/trino/blob/master/.github/CONTRIBUTING.md)
— most of the conventions ByteQuay uses (checkstyle rules, Error Prone
config, commit-message style) are borrowed from Trino directly, so
their docs are a useful secondary reference.

---

## What ByteQuay is

A native macOS desktop app for daily developer review work. It's a
single-window Electron + React + TypeScript frontend talking to a
Spring Boot sidecar that runs locally on `localhost`. The backend is
spawned as a child process by Electron and exits with the app.

**In scope** — everything that helps a reviewer triage, read, and
respond to GitHub PRs faster than the GitHub web UI: PR dashboard, AI
review drafts, an embedded github.com webview for actions we don't
re-implement natively, CI status, draft toggle, merge.

**Out of scope** (for now) — non-macOS targets, server-side hosting,
non-GitHub forges, automatic publishing of comments without explicit
user action.

---

## Prerequisites

| Tool       | Version                | Notes                                                                       |
|------------|------------------------|-----------------------------------------------------------------------------|
| **JDK**    | 21+ (tested on 21, 25, 26) | Use Temurin or another official build. Newer JDKs work; pom targets 21.  |
| **Maven**  | 3.9+                   | Either system-installed or via your IDE's bundled copy.                     |
| **Node**   | 20.x                   | Matches CI; older majors will likely work but aren't tested.                |
| **npm**    | 10+                    | Bundled with Node 20.                                                       |
| **macOS**  | 14+                    | Required at runtime — the embedded GitHub view uses Electron's `WebContentsView`. Linux / Windows can run the backend and frontend dev server but a few features won't render correctly. |
| **Git**    | any recent             |                                                                             |

You don't need Docker, a Postgres install, or a GitHub App — the only
external dependency is your GitHub PAT, configured at runtime via
**Settings → GitHub** and stored in the macOS Keychain.

---

## Quick start

```bash
git clone https://github.com/bytequay/bytequay.git
cd bytequay
./dev.sh
```

That's it. `dev.sh` auto-runs `npm install` if `frontend/node_modules`
is missing, and Maven downloads its own dependencies on the first
`spring-boot:run`. The first invocation downloads ~250MB of Maven +
npm packages and takes a couple of minutes; subsequent runs start in
seconds.

Before opening a PR, run the gate commands once to make sure your
change passes the same checks CI runs — see
[Building and verifying](#building-and-verifying).

---

## Project layout

```
.
├── backend/          # Spring Boot sidecar (Java 21, Maven)
│   ├── src/main/java/com/bytequay/app/
│   ├── src/main/resources/
│   │   ├── application.properties
│   │   └── db/migration/         # Flyway V##__name.sql files
│   ├── src/main/checkstyle/      # Trino-derived checkstyle rules
│   ├── src/main/license/         # Apache 2.0 header template
│   ├── .mvn/errorprone.config    # Trino-derived Error Prone -Xep flags
│   └── .mvn/jvm.config           # --add-exports for Error Prone on JDK 16+
├── frontend/         # Electron + React + TypeScript (Electron Forge + Vite)
│   ├── src/
│   │   ├── main.ts               # Electron main process
│   │   ├── preload.ts            # IPC bridge
│   │   └── ...                   # React renderer
│   └── .eslintrc.json
├── docs/             # Design docs + UI mockups (PNG, by section)
├── .github/workflows/ci.yml
└── dev.sh            # Spawns backend + frontend together
```

Read `app-design.md` and `CLAUDE.md` (in the repo root if you have
them locally — both are gitignored from the public repo as personal
working notes, but the architectural decisions they document are
reflected in code comments).

---

## Building and verifying

The same gates CI runs are all available locally. Run them before you
push:

### Backend

```bash
cd backend
mvn verify
```

`mvn verify` runs four gates in order:

1. **License header check** (`license-maven-plugin`) — every
   `src/**/*.java` must start with the Apache 2.0 header at
   `src/main/license/header.txt`.
2. **Checkstyle** (`maven-checkstyle-plugin`, Trino's rules verbatim
   at `src/main/checkstyle/checks.xml`).
3. **Error Prone** (attached to `maven-compiler-plugin`, config at
   `.mvn/errorprone.config`).
4. **Tests** — JUnit 5 + Mockito + Spring Boot. ~110 tests today;
   includes a `TestApplicationContextSmoke` that boots the full bean
   graph + Flyway migrations + JPA.

### Frontend

```bash
cd frontend
npm run lint           # ESLint
npx tsc --noEmit       # type check
npm test               # Vitest (unit + component)
npm run package        # full Vite + electron-packager build
```

`npm run package` is the slowest step (~30s) but it catches Vite plugin
/ asset / preload-bundling regressions that nothing else sees.

### Don't skip gates

Never push with `--no-verify`, never `@SuppressWarnings` your way past
checkstyle / Error Prone unless there's a *specific, justified* reason
captured in a comment. If a rule is genuinely wrong for this codebase,
change the rule in `checks.xml` / `errorprone.config` rather than
working around it case-by-case.

---

## Code style

### Backend (Java)

- **Trino style**, enforced by checkstyle. Indent 4 spaces, opening
  brace on its own line for class / method, single-line braces for
  blocks, no wildcards in imports.
- License header on every `.java` file (matches `header.txt`).
- Error Prone: see `backend/.mvn/errorprone.config` for the enabled
  bug patterns. Most are Trino's selections.
- Prefer immutable collections (`ImmutableList`, `ImmutableMap`,
  `ImmutableSet`); the codebase uses Guava heavily.
- Records over POJOs for DTOs. `@JsonIgnoreProperties(ignoreUnknown =
  true)` on every external API DTO.
- `Optional<T>` only as a return type, not a field.

### Frontend (TypeScript / React)

- React 19, **function components + hooks only**. No class components.
- Strict TypeScript — no `any` unless genuinely necessary; prefer
  `unknown` and narrow.
- ESLint config at `frontend/.eslintrc.json`: TS-recommended,
  import-recommended, react-hooks rules-of-hooks (error) + exhaustive-deps
  (warn).
- File naming: `PascalCase.tsx` for components (`PRRow.tsx`),
  `camelCase.ts` for utilities (`githubClient.ts`).
- Inline styles or plain CSS — we haven't picked a CSS framework;
  don't introduce one without discussion.
- Keep components small; extract subcomponents when a file passes
  ~150 lines.

### Comments

- Default to writing **no** comments. Only add one when the WHY is
  non-obvious — a hidden constraint, a subtle invariant, a workaround
  for a specific bug.
- Don't explain WHAT the code does; well-named identifiers do that.
- Don't reference the current task / fix / caller (`used by X`,
  `added for the Y flow`) — that belongs in the PR description and
  rots in the code.

---

## IDE setup

### IntelliJ IDEA (recommended for backend work)

1. **Open the project** at the repo root. IntelliJ will detect both
   the Maven module (`backend/`) and the npm module (`frontend/`).
2. **Set the project SDK** to JDK 21 or newer
   (**File → Project Structure → Project SDK**).
3. **Install the Checkstyle-IDEA plugin** and point it at
   `backend/src/main/checkstyle/checks.xml`. Settings → Tools →
   Checkstyle → "+" → Configuration File → choose the file → Active.
   This gives you live in-editor warnings that match the
   `mvn verify` gate.
4. **Settings → Editor → Code Style → Java**:
   - Indent: 4 spaces, continuation indent 8.
   - Wrap on typing: yes.
   - Imports → Class count to use import with `*`: 999 (we never want
     wildcard imports).
   - Imports → Names count to use static import with `*`: 999.
   - Imports → Layout: standard java/javax block, then
     `com.bytequay.*`, then `static *`. Match what existing files have
     — the checkstyle rules verify this.
5. **Settings → Editor → Copyright**: create a profile with the
   contents of `backend/src/main/license/header.txt`, set the project
   default to it. This auto-inserts the header on new files.
6. **Settings → Editor → Inspections**: keep IntelliJ's defaults; the
   compiler-attached Error Prone is what's authoritative.
7. **Maven → JVM args** are already wired via
   `backend/.mvn/jvm.config` (the `--add-exports` flags Error Prone
   needs on JDK 16+). You shouldn't need to set anything manually.
8. (Optional but useful) **Plugin: SonarLint** for a second-opinion
   layer of static analysis.

### VS Code (recommended for frontend work)

1. Install the recommended extensions:
   - **ESLint** (`dbaeumer.vscode-eslint`)
   - **EditorConfig** (`editorconfig.editorconfig`) if/when we ship a
     `.editorconfig`.
   - **TypeScript Vue Plugin** is *not* needed; we don't use Vue.
2. Workspace `settings.json` snippet:
   ```json
   {
     "eslint.workingDirectories": ["frontend"],
     "editor.codeActionsOnSave": { "source.fixAll.eslint": true },
     "typescript.tsdk": "frontend/node_modules/typescript/lib"
   }
   ```
3. The Electron renderer's source maps point back to TS so debugging
   in the dev tools (Cmd-Opt-I when the app is running) works directly
   on the original source.

### Other editors

Anything that respects ESLint + a checkstyle CLI works. The CI gates
are the final word, so as long as `mvn verify` and `npm run lint`
pass, your IDE choice is up to you.

---

## Database and migrations

The backend uses **SQLite** with Flyway-managed migrations.

- **Runtime DB**: `~/Library/Application Support/ByteQuay/bytequay.db`.
  Created on first run; the directory is created automatically.
- **Test DB**: `${java.io.tmpdir}/bytequay-test-${user.name}.db` (set
  in `backend/src/test/resources/application.properties`).
- **Migrations**: `backend/src/main/resources/db/migration/V##__name.sql`.
  Flyway runs them in numeric order at startup.

### Adding a migration

1. Find the next free `V##` number (`ls db/migration/`).
2. Create `V<next>__short_descriptive_name.sql`.
3. Write idempotent-ish DDL (`ALTER TABLE … ADD COLUMN …` etc.). SQLite
   doesn't support most `ALTER TABLE` operations, so column drops
   require the table-rebuild pattern.
4. **Never modify a published migration.** Once a migration is on
   `main`, it's frozen. Fix forward with a new V##.
5. Run `mvn verify` — the smoke test will catch obviously-broken SQL
   and the JPA entity will catch column mismatches.

---

## AI provider configuration

ByteQuay's AI review feature supports three providers:

- **Anthropic Claude** (recommended; the most-tested path)
- **OpenAI**
- **DeepSeek**

You add an API key at runtime via **Settings → AI** in the app. Keys
are encrypted with a per-machine key and stored in
`~/Library/Application Support/ByteQuay/credentials.key` (mode 0600).
Nothing leaves your machine until you click an AI action.

When adding a new provider, implement `LlmReviewer` and let
`LlmReviewerRegistry` discover it. Each provider owns its own
credential lookup, prompt, and response parsing.

---

## Architectural constraints to respect

These are non-obvious traps the codebase has already worked around;
please don't unwind them by accident.

- **Use Electron's `WebContentsView`**, not the deprecated `<webview>`
  tag or `BrowserView`. All embedded GitHub content goes through
  `WebContentsView`.
- **Pinned `@vitejs/plugin-react` to the 4.x line** because Electron
  Forge's template uses Vite 5, and plugin-react 5+ requires Vite 8.
  Don't bump one without the other.
- **JSX via `"jsx": "react-jsx"`** in `tsconfig.json`. React 19 — no
  class components.
- **Nothing is published to GitHub automatically.** AI-drafted comments
  stay in SQLite until an explicit user action (Submit / Approve /
  Merge). Don't add code paths that auto-post.
- **The backend is a local sidecar, not a remote server.** It only
  serves `localhost`. Don't add auth to the internal API beyond basic
  sanity checks.
- **Don't introduce a new top-level dependency** (component library,
  state-management library, CSS framework, ORM swap) without raising
  it as an issue first. The current stack is small on purpose.

---

## Testing

### Backend

- JUnit 5 + Mockito + Spring Boot Test.
- Three flavours:
  - **Plain unit tests** — pure logic, no Spring (most files).
  - **Slice tests** — `@WebMvcTest` for controllers; `@DataJpaTest` if
    we need JPA only.
  - **Smoke** — `TestApplicationContextSmoke` boots the full context
    end-to-end. Don't add more `@SpringBootTest` classes than needed;
    they're slow.
- New domain logic should ship with tests. New endpoints should ship
  with at least a happy-path slice test.

### Frontend

- Vitest + Testing Library.
- The render-smoke spec for `PullRequestPreview` is a hard CI gate —
  it catches "X is not defined" type errors that slip past `tsc` when
  JSX references a removed identifier. Keep that file working.
- Test new pure utilities (anything in `src/pr/`, `src/utils/`, etc.)
  with focused vitest cases.
- React component tests: optional, but appreciated for tricky
  rendering (anything with conditional branches).

---

## Commit messages and pull requests

ByteQuay follows **Trino's commit-message guidelines**:
[Trino: writing commit messages](https://github.com/trinodb/trino/blob/master/.github/DEVELOPMENT.md#commit-messages-and-pull-requests).
The short version:

- **Subject line**: capitalized imperative voice, ≤ 50 characters, **no trailing
  period**, no `feat:` / `fix:` / Conventional-Commits prefix.
  - Good: `Paginate check runs and add refresh button`
  - Bad: `feat(ci): added pagination support.`
- **Body**: omit it when the subject is self-explanatory. Otherwise keep
  it tight, wrap at ~72 columns, and explain *what and why* rather than
  how. Reference issues with `Fixes #N` if applicable.
- **One logical change per commit.** "Refactor + add feature" should
  be two commits. Don't be afraid to split.
- **Attribution**: never add `Co-Authored-By` or similar trailers for AI
  agents, assistants, or bots.
- **Squash review fixups** before merge so the history reads cleanly.

### Pull requests

- Run all gates locally before pushing (see
  [Building and verifying](#building-and-verifying)). CI runs the
  same set; if it fails for you locally, it'll fail there too.
- Open the PR against `main`.
- The CI workflow at `.github/workflows/ci.yml` runs the backend +
  frontend gates on every push and PR.
- Reviewer flow: drag the PR onto a reviewer's plate, or use
  ByteQuay's own AI review surface (`/ai/review` in the running app)
  for a first pass.

---

## Cutting a release

The full release flow — versioning, the GitHub Actions workflow that
builds the DMG, the Gatekeeper / quarantine model that decides what
end users see on first launch, and the planned signing + auto-update
follow-ups — lives in [RELEASING.md](RELEASING.md). Tag a `v*` commit
and push the tag; the workflow at `.github/workflows/release.yml` does
the rest.

---

## Reporting issues

Open a GitHub issue describing:

- What you tried to do.
- What you expected.
- What happened.
- macOS version + JDK version + Node version + ByteQuay commit SHA.

For UI bugs, a screenshot or short screen recording goes a long way.

For backend bugs, the log file is at
`~/Library/Application Support/ByteQuay/logs/backend.log` —
attach the relevant tail (last 200 lines is usually enough).

---

## License

ByteQuay is released under the
[Apache License 2.0](LICENSE). By submitting a contribution you agree
that your work is licensed under the same terms.

There is no Contributor License Agreement (CLA). The standard "you
warrant you have the right to license this code" applies.
