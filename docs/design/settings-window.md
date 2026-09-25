# Settings Window

One settings surface with a category sidebar replaces today's two long
scrolling pages: the global Settings page (`frontend/src/settings/SettingsShell.tsx`)
and the Workspace settings tab (`frontend/src/workspaces/WorkspaceSettings.tsx`).

The approved visual reference is the `Settings.dc.html` prototype in the
`Settings page redesign` design project. The prototype is the source of truth
for information architecture, per-row controls, and copy. This document records
what that requires of the code, what already exists, and the decisions the
prototype does not settle.

## 1. Information architecture

A single scroll container on the right, a category sidebar on the left.

| Group | Pages |
| --- | --- |
| Personal | General, Appearance |
| Agents | Models, Skills, Code graph |
| Integrations | GitHub |
| Workspace | workspace picker, then Repository, Agents, Token, Advanced |

The separate [Development Task Delivery](development-delivery.md#2-settings-and-scope)
design adds **Agents → Workflow** with default-on Auto commit, Auto draft PR,
and Auto CI fix settings plus the supported CI provider selection. It also
defines per-task overrides and accepted-turn snapshots. The page and snapshots
are implemented; automatic publication and CI progression remain pending with
the delivery coordinator. An unselected CI provider stays visibly unselected;
the service does not silently choose a provider when delivery starts.

Rules taken from the prototype:

- The workspace picker lists every watched workspace, not just the active one,
  and switching it keeps the current sub-page.
- The sidebar search filters sidebar items by label and by row title.
- `Back to app` returns to the previous app route.
- Every page is deep-linkable, replacing the `onOpenSettings` callback that
  `agent-service.md` §12.2 relies on today.
- Explanatory paragraphs are deleted. A row carries at most one short subtitle;
  anything longer becomes one muted card footnote or is removed.

### 1.1 It is a route, not an OS window

The prototype draws macOS traffic lights inside its frame. That is the same
frame convention the shipped `Workspaces` and `Workspace Home` prototypes used,
and both shipped as in-app views. `Back to app returns to the previous app
route` only means anything inside one window. Settings stays a hash route.

This matters because a second `BrowserWindow` would break four untyped
`window.dispatchEvent` contracts that die at a window boundary
(`bytequay:recent-cleared`, `bytequay:attention-handled-changed`,
`bytequay:pr-attention-updated`, `bytequay:pr-monitor-changed`), and because
every window-touching `ipcMain` handler hardcodes the module-level
`mainWindow`.

## 2. What already exists

The prototype's substance is mostly already built. The refactor is largely a
re-presentation plus one real model change.

| Prototype element | Today |
| --- | --- |
| API-key rows, add and remove | `ModelApiKeyStore`, `model_api_keys`, `POST/DELETE /api/settings/api-keys` |
| Skill list, scopes, add/edit dialog | `AgentSkillScope {GLOBAL, DEVELOPMENT, REVIEW}`, `/api/settings/agent-skills` |
| Code-graph mode Off/On | `CodeGraphMode`, `GET/PUT /api/settings/codegraph` |
| Provider readiness rows, `Check` | `AgentProviderStatus`, `refreshAgentProviderReadiness` |
| GitHub account, masked token, Copy | `CredentialController` |
| Quiet hours | `QuietHours`, `GET/PUT /api/settings/quiet-hours` |
| Remotes, default target branch | `GET /api/workspaces/{id}/settings`, commits-target endpoints |
| Workspace token, unwatch, delete | existing workspace endpoints and dialogs |

## 3. The model change

This is the only structural change the prototype demands.

Today there is exactly one global default — `model.main`, a config id — plus a
per-config `model.selection.<configId>` holding `{model, effort}`, plus one
per-workspace override `workspace.agentModel.<workspaceId>`. Nothing anywhere
carries an agent type.

The prototype moves model choice off provider rows onto two agent types.
`Set as main` and the `MAIN` badge disappear; provider rows become status and
credential management only.

### 3.1 Storage

```
model.agent.DEVELOPMENT = { configId, model, effort }
model.agent.REVIEW      = { configId, model, effort }   # absent = inherit
workspace.agentModel.<workspaceId>.DEVELOPMENT = { configId, model, effort }
workspace.agentModel.<workspaceId>.REVIEW      = { configId, model, effort }
```

Resolution: workspace override → global agent-type value → for Review only,
the Development value → the existing catalog default.

Review inheritance is an **absent row**, never a written-back copy. Every
default in this backend is applied at the read site and never persisted
(`QuietHours.DEFAULT`, `CodeGraphMode.ON`, the `claude` main fallback, the
catalog `defaultModelId`). A settings page that writes "current" values on load
would freeze implicit inheritance into stored rows.

### 3.2 Agent type

`AgentSkillService.instructions(AgentTask)` already classifies a task into
GLOBAL / DEVELOPMENT / REVIEW: an open review is REVIEW, an interactive,
`CI_AUTOFIX` or `CHERRY_PICK` task is DEVELOPMENT. That classifier is the only
existing definition of agent type and is reused rather than re-invented.

### 3.3 Call sites

Three places pick a model today. Two must change; one needs a decision.

- `DefaultAgentReviewService.selectModel` — switch to the Review value.
- `BetterWordsService.selectedModel` — rewriting is the user's own writing, so it
  resolves through the Development value, including a workspace override, and opens
  that lane's driver like any agent session. It has no separate API-key path.
- Development tasks resolve **no model on the backend at all**. The renderer
  picks it in `useAgentTaskCreation.applyCatalog` from
  `providers[].selectedModel`, and `CreateTaskRequest` rejects an empty model.
  The Development value is therefore read by the renderer to seed the composer;
  the wire contract does not change.

`InteractiveAgentTaskCreator.fingerprint` hashes model, effort, service tier and
permission policy, so a default that changes between a failed create and its
retry raises `AgentConflictException` instead of replaying. The composer keeps
sending an explicit model for this reason.

### 3.4 Migration

There is no migration machinery: `app_settings` is untyped key/value and
Hibernate owns the schema. An existing `model.main` plus its
`model.selection.<configId>` is read once and written into
`model.agent.DEVELOPMENT`; `model.agent.REVIEW` is left absent so Review starts
out inheriting. The old keys are then deleted. Per-workspace overrides migrate
to the `.DEVELOPMENT` suffix by the same read-once rule.

### 3.5 Two existing defects this touches

- `DefaultAgentReviewService.selectModel` constructs `new AgentLaneId(override.configId())`
  unguarded. `AgentLaneId` rejects `:`, so an API-key config id (`key:<alias>`)
  as main or as a workspace override already crashes review start. The
  per-agent-type resolver guards the conversion.
- `GET /api/workspaces/{id}/settings` calls `models.mainModel()` unguarded,
  which throws when the Claude catalog row is missing (a fresh or reset DB
  before `seedCatalogs()` runs). That 400 takes down the Commits tab as well as
  the settings tab. The resolver tolerates a missing catalog.

## 4. Page contents

Only deltas from what exists are listed.

**Personal → General** — keep detailed task runtime; Recent retention;
`Clear history`; app version footnote.

The prototype's **worktrees folder** row is deliberately not built. Worktrees
live under `bytequay.workspace.root/<workspaceId>/worktrees/<taskId>`;
`WorkspacePaths.repository(Workspace)` rejects any workspace whose stored
`localRepositoryPath` does not match that derived root, and a git worktree
embeds absolute paths in its `.git` file. `Change` would therefore have to
relocate every workspace, rewrite every stored path and run `git worktree
repair` — a feature, not a settings row. The row is dropped rather than shipped
as a button that bricks the checkout.

**Personal → Appearance** — theme only. See §7.

**Agents → Models** — the §3 change, plus provider status rows and API-key rows.
API-key models are exposed today as `List<String>` with no efforts; joining them
to the same `ModelOption` catalog the pickers read is a wire-shape change to
`ApiKeyDto.models`.

**API-key models appear in both agent-type pickers but cannot be chosen.** An
agent turn runs through an `AgentLaneId`, whose regex admits only `codex` and
`claude`; a `key:<alias>` config id is not a lane and never can be until an
API-key `AgentDriver` exists. The rows are rendered disabled with that reason
rather than hidden, so the picker still shows the whole configured set. The
backend rejects a key-backed config id for an agent-type value, which also
fixes today's crash when such an id is main and a review starts.

**Agents → Skills** — scope filter, one row per skill, add/edit dialog. The
dialog is a UI change only; the `agent.skills` contract is unchanged. Internal
ByteQuay guidance stays invisible.

**Agents → Code graph** — mode segmented, baseline index row.

The baseline row needs a small read for repository count and last-updated time;
`CodeGraphBaselineRefresher` exposes nothing today. Per `codegraph-service.md`
§12 this stays a read-only readout — `Rebuild` is the one control the design adds
and it needs explicit approval before it ships.

**Integrations → GitHub** — account, masked token, refresh. The account card
hardcodes the words "personal access token" even for a token imported from the
`gh` CLI, because `AccountResponse` carries no source; exposing the source is a
backend DTO change. Refresh interval is net-new: no setting exists.

**Workspace → Repository / Agents / Token / Advanced** — as today, plus the
per-agent-type override on Agents. `Remove` on an UPSTREAM remote calls a
different endpoint than `Remove` on any other remote (unlink versus delete); the
branch must survive extraction.

## 5. Components

One `SettingsWindow` shell owns the sidebar, search, routing and scroll
container. Primitives built once and reused on every page: `SettingsCard`,
`SettingsRow`, `Toggle`, `SelectPill`, `Segmented`, `StatusDot`, `Badge`,
`RepoAvatar`, `Dialog`.

`ModelChip` in `SettingsShell.tsx` is today's `SelectPill` and is imported by
`WorkspaceSettings.tsx`; it is replaced, not moved. `frontend/src/settings/model.ts`
has **eleven** non-settings importers across `agents/`, `commits/` and
`workspaces/` — it stays where it is.

The old page components are deleted once every row has a home. No parallel
legacy route.

## 6. Constraints the refactor must respect

- `.sets-page` has no `overflow` inside `.app-content { overflow: hidden }` — the
  current page is clipped, not scrollable. `.ws-tab-page` already sets
  `overflow: auto; height: 100%`; copy that rule.
- Three route regexes must stay in sync and none accepts a settings sub-path.
  `App.tsx`'s is anchored, so `#/settings/agents/models` silently resolves to
  `home`; `workspaces/model.ts` rejects anything after `settings` and drops the
  workspace entirely.
- `onOpenSettings?: () => void` is **optional** at four call sites. Dropping it
  during the route change fails silently — lint, `tsc` and vitest cannot see it —
  and §12.2 requires that navigation to exist.
- `getModelSettings` spawns real CLI subprocesses on every call and already has
  six renderer consumers outside settings. Nothing may mount it on a timer, and
  the workspace picker must not remount the machine-global providers card per
  selection.
- `GET /agent/providers/status` has a side effect: it calls
  `refreshMissingCatalogs`, which is what fills a just-fixed provider's catalog
  without a restart. It must stay a probe, not become a cached read.
- `SqliteWorkspacePurge` hardcodes both per-workspace settings prefixes as string
  literals. Any new per-workspace key must be added there or it survives
  workspace deletion.
- Frontend tests run with no DOM: `renderToStaticMarkup` only, `useEffect` never
  runs, `window.bridge` undefined. Fetch-on-mount and popover behaviour are not
  testable by rendering; assert markup and parsed CSS, and measure real layout in
  the browser pane the way `settings/fixtures/skillScopesBrowser.tsx` does.
- `SettingsController` has no HTTP-layer test. Every route change here is
  currently unguarded at that layer; the refactor adds one.
- Backend style: Guava immutables rather than `List.of`, `checkState` over
  hand-written throws, concrete `@Service` classes with no interface, the Apache
  header on every file.

## 7. Decisions

### D1 — Theme: control now, Dark palette later

`Appearance` ships the persisted System / Light / Dark control with System and
Light working. The app has no colour custom properties in any of its fourteen
stylesheets and `createWindow` hardcodes `backgroundColor: '#ffffff'`, so a real
Dark theme is a palette pass over every surface in the app and lands separately.
Dark is presented as unavailable rather than as a switch that does nothing.

### D2 — Secrets stay in SQLite; the copy is corrected

No keychain code exists in this repository. Every secret is AES-256-GCM
ciphertext in SQLite under `~/Library/Application Support/ByteQuay/credentials.key`.
The prototype's "Stored in the macOS keychain" footnote and the matching
`OnboardingScreen.tsx` string are both changed to describe local encrypted
storage. A `masked_hint` column on `model_api_keys` is written at save time so
`ModelSettingsService.apiKey()` stops decrypting every stored key on every
settings load. Moving to the keychain is a separate change.

### D3 — All four new General rows are built

Worktrees folder, task-runtime retention, Recent retention and GitHub refresh
interval each get a settings key, and `DefaultAgentCompletionService`,
`RecentRetentionJob` and the sync jobs read them instead of constants.

### D4 — Main checkout, one commit per phase

Work lands in `/Users/jack.chen/IdeaProjects/bytequay` with the gates run
immediately before each commit.

## 7a. Delivery

Two commits, each of which builds and passes on its own.

1. **Backend settings foundation.** The new store keys and their job wiring, the
   `masked_hint` column, the baseline readout, and the GitHub account source.
   Adds only; the current pages keep working untouched.
2. **The settings window.** Per-agent-type agent models replacing `model.main`
   end to end, the shell, primitives, routes and all ten pages, the old pages
   deleted and every `onOpenSettings` call site rewired. The model change and
   the page it lives on are one logical change, so they land together rather
   than behind a compatibility field.

## 8. Acceptance

1. Every setting from both current pages is reachable, and no explanatory
   paragraph survives.
2. A Development or Review model and effort persists, survives restart, and is
   what the next task or turn of that type launches with.
3. Review inherits Development until explicitly picked; an explicit Review pick
   wins.
4. A workspace override applies to that workspace and that agent type only;
   `Use global` clears it.
5. Adding an API key makes its models selectable in both pickers with no
   restart; removing it falls back to an available model rather than an empty
   selection.
6. Adding a skill from the dialog appears with its scope and reaches the next
   turn's instruction snapshot for that scope only.
7. Readiness renders from typed status, never parsed diagnostic text, and a
   blocked task start deep-links to the relevant row.
8. Code-graph mode changes affect new tasks only.
9. The workspace picker can edit any watched workspace, and switching it keeps
   the current sub-page.
