# Watched Repository / Workspace UI Design

Status: proposed mockups for the four changed surfaces. Canonical behavior:
[watched-repo.md](watched-repo.md). Mockups live in
[watched-repo-ui/](watched-repo-ui/) as interactive HTML state boards (open in a
browser) plus PNG exports.

| Board | Files |
| --- | --- |
| Watch Repository flow | `watched-repo-ui/Watch Repository.dc.html` · `watch-repository.png` |
| Workspaces page | `watched-repo-ui/Workspaces.dc.html` · `workspaces.png` |
| Workspace Home | `watched-repo-ui/Workspace Home.dc.html` · `workspace-home.png` |
| Workspace Pull Requests | `watched-repo-ui/Workspace Pull Requests.dc.html` · `workspace-pull-requests.png` |

## UI principles

1. **Save is instant; everything else is background.** Watch always succeeds or fails
   synchronously and visibly. Clone and metadata progress are card/badge state, never a
   blocking dialog.
2. **One record, two views.** Home "Repos you watch" cards and Workspaces cards share one
   anatomy and one status-chip vocabulary; only density differs.
3. **Two independent status tracks, always in the same order.** Code first, then
   "labels & people" (the user-facing name for cached repository metadata). Neither ever
   hides or downgrades the other.
4. **Direct vs fork is unmistakable.** Every fork surface carries the amber
   `⑂ fork of owner/name` chip and a routing card naming origin, upstream, development
   base, and PR target. Direct surfaces carry a quiet gray `direct` chip and a one-sentence
   routing summary.
5. **Failure never blocks.** Failed and pending workspaces stay clickable; errors are
   plain-language, sanitized, and carry their retry button inline.
6. **No internal vocabulary.** `PENDING`/`metadataState`/`monitoredRepositoryId` never
   appear. UI words: *queued, cloning…, fetching latest, code ready, labels & people,
   needs attention, followed for updates*.
7. **No filesystem in the watch flow.** No folder picker anywhere; no local path on Home
   or Workspaces cards — the mono context line shows branch + upstream instead. The
   managed checkout path is still visible **read-only in Workspace Settings**, with
   *Copy path* and *Reveal in Finder* actions; it is never an input.

## Navigation and end-to-end flow

```
Home ── Watch repository ──► Modal: search → results → confirm (direct|fork) → Watch
  │                                              │ already watched ──► Open workspace →
  │ card appears immediately (queued/cloning)    ▼
  ▼                                    workspace saved; clone + metadata run async
Workspaces ── click any card (any state) ──► /workspaces/:id  Workspace Home
                                                  │
                     Commits (enabled when code ready) ──► existing commit browser
                     Pull requests (own PR sync state)   ──► existing PullsScreen, scoped
```

- The modal never blocks on clone. Watch → modal closes → the Home card is already in the
  grid in `queued` state.
- "Already watched" resolves inside the modal: selecting a watched repository (also
  labeled in results) offers **Open workspace →** instead of a second clone.
- Workspace shell navigation is unchanged; only tab enablement/badges change (below).

## Component reuse map

| Existing component | Reused as | Change |
| --- | --- | --- |
| `AddRepoModal.tsx` | Watch modal steps 1–3 | Enriched result rows (fork/visibility/already-watched chips); new confirm step |
| `WatchedReposGrid.tsx` (Home) | Home cards | New status-chip row; retry button; same card as Workspaces |
| `WorkspacesPage.tsx` | Workspaces grid | Data from `GET /api/workspaces`; local-path line and folder picker removed |
| `WorkspaceShell.tsx` sidebar | Unchanged | Disabled tabs get 45 % opacity + reason text on Home |
| `WorkspaceShell.tsx` Home | Evolved | Keeps title, stat cards, activity, Browse commits; adds Repository + Status cards |
| `PullsScreen.tsx` | Workspace PR tab | Optional repository scope prop; scope chip in the tab toolbar; repo-specific loading/empty/stale/error. Tabs and card anatomy unchanged. The **filter box is a new enhancement** (the current PullsScreen has none) — tracked as its own checklist item, droppable for the minimal slice |
| `PullOverview` / `PullChanges` | Unchanged | None |
| Commits / cherry-pick views | Unchanged | Data source becomes workspace ID (no mockup needed) |
| Status chips / pills (existing palette) | All boards | New shared vocabulary (see Workspaces board legend) |

Confirmed: **no replacement mockups are needed** for Commits, Tasks, PR detail, or the
workspace shell/navigation — their interaction is unchanged beyond data source and the
tab-enablement rules documented here.

## State matrix

Chip vocabulary (rendered in the Workspaces board legend):
`queued` gray · `cloning…`/`fetching latest` blue+spinner · `✓ code ready` /
`✓ labels & people` green · `contributors N days old` or `labels & topics N days old`
amber · `✕ clone failed` / `✕ sync failed` red · card-level `needs attention` red
replaces the topology chip when anything failed. The stale chip names the expired
projection because contributors and labels/topics have different refresh intervals.

### 1. Watch Repository (board frames 1–8)

| State | Frame | Behavior |
| --- | --- | --- |
| Initial | 1 | Focused search input, explainer, no folder picking |
| Searching | 2 | Spinner row, input stays editable |
| Results | 3 | Rows: owner/name, visibility, default branch, `⑂ fork of…`, `already watched` |
| Confirm — direct | 4 | Gray chip; routing card: one sentence + start/push/PR-target rows; **Watch repository** |
| Confirm — fork | 5 | Amber chip; routing card: origin / followed-for-updates / branch start / PR target + summary sentence; **Watch fork** |
| No push access | 6 | Amber banner; routing shows `✕ read-only for you`; **Watch anyway** stays enabled |
| Already watched | 7 | Blue banner; **Open workspace →** primary, no second clone possible |
| Saved / preparing | 8 (left card) | Home card appears instantly; code spinner, metadata may already be green |
| Clone failure + retry | 8 (right card) | Red border + `needs attention`; inline **Retry clone**; card still opens |

### 2. Workspaces (one full-page board, 8 cards + anatomy + legend)

| Card | State shown |
| --- | --- |
| trinodb/charts | direct · both green |
| jackchen/trino | fork · both green · running-task activity |
| jackchen/airlift | pending — both `queued`, "You can open it now" |
| jackchen/presto | cloning… while metadata already green (independence) |
| trinodb/trino-gateway | syncing (`fetching latest`) · last success shown |
| jackchen/iceberg | contributors stale (9 days) while labels/topics and code remain current |
| jackchen/trino-python-client | clone failed · red border · **Retry clone** |
| jackchen/arrow | metadata failed while code green · **Retry sync** |

Each card is an `<article>`; its primary link (the workspace name, stretched over the
card) routes to `/workspaces/:id`. Retry is a sibling button beside the footer text —
not nested inside the click target.

### 3. Workspace Home (frames A–D)

| Frame | Topology | States shown |
| --- | --- | --- |
| A | Fork | code ready + metadata stale/refreshing; full routing card; Sync now |
| B | Direct | both ready; one-sentence routing card |
| C | Fork | cloning; blue banner; only Commits dimmed with its reason — Tasks and Memory stay enabled (history/notes need no code); PRs already live |
| D | Direct | clone failed; red banner with **Retry clone**; metadata untouched |

Always present (preserved from the coded page): workspace title, open-PR count, active
and finished task counts, recent cherry-pick activity, Browse commits shortcut.
Added: topology chip, Repository routing card, Status card (two independent rows +
freshness footnote), last-successful-sync times, inline retries, disabled-tab reasons.

### 4. Workspace Pull Requests (frames A–E)

List UI matches the existing global Pulls page: **All / My PRs / Review / Done** tabs,
filter box, and list cards with PR icon, title + #number, label chips (long sets truncate
to `+N`), repo slug with org avatar, author avatar + name, relative time, and the CI
(`No CI`) and comment-count columns. In **All**, right-edge chips mark `mine` (authored)
and `review` (review requested) rows. Clicking a card opens the existing
PullOverview/PullChanges detail + timeline in the right pane — unchanged; the mockup
shows it as an explicit placeholder. Note: the **filter box is new** — the current
PullsScreen has tabs and refresh/detail controls only; it ships as a separate small
enhancement (or is dropped for the minimal implementation).

| Frame | State |
| --- | --- |
| A | Fork workspace, split view: tabs + filter + scoped card list (upstream trinodb/trino PRs) on the left; selected-PR detail placeholder (header + "PR details & timeline render here — existing PullOverview/PullChanges, unchanged") on the right |
| B | First-sync loading: tabs + skeleton rows + "Fetching pull requests from … for the first time" |
| C | Empty: "No open pull requests in …" + cherry-pick hint |
| D | Stale: amber banner "Showing pull requests from 2 hours ago" + **Refresh now**, last good card list below |
| E | Error with no local data: centered error + **Try again** |

## Interaction behavior

- **Watch modal**: `Esc`/`✕` closes at any step without saving. Selecting a result loads
  the preview (spinner replaces content area); Watch is disabled until the preview loads.
  Watch posts the preview fingerprint; a `WatchPreviewChangedException` re-opens the
  confirm step with a notice. After success the modal closes and the Home grid inserts
  the new card at its saved position.
- **Already watched**: primary action navigates to the existing workspace; nothing is
  created.
- **Cards**: each card is an `<article>` with a stretched primary workspace link; Retry is
  a sibling button outside that link. Status chips are not interactive.
- **Retry**: retries only the failed track (clone vs metadata); button shows a spinner
  and the chip transitions to its blue in-progress form.
- **Sync now** (Workspace Home): fetches code and refreshes the workspace's PR data;
  reference metadata (contributors/labels/topics) refreshes only if its weekly/monthly
  interval has expired — fresh caches are not re-fetched. Idempotent while a sync runs
  (button disabled with spinner).
- **Disabled tabs**: only Commits requires ready code; it is shown at 45 % opacity, not
  removed, clicking is a no-op, and Workspace Home shows the reason where the stat number
  would be ("opens when the clone finishes"). Tasks, Memory, Issues, and Pull requests
  stay enabled in every state.
- **PR refresh**: `⟳` re-syncs only the monitored repository; stale banner's Refresh now
  is the same action.
- **PR tabs**: All / My PRs / Review / Done classify the scoped list (same semantics as
  the global page); the filter box filters within the active tab. Card click selects the
  PR and renders the existing detail/timeline in the right pane; the amber scope chip
  stays visible in the toolbar so the monitored repository is never ambiguous.

## Exact important UI copy

- Modal title: **Watch a repository**
- Search placeholder: *Search your GitHub repositories…*
- Empty search: *Find a repository to watch* / *Search by name or owner/name. ByteQuay
  clones it for you and keeps it in sync — no folder picking.*
- Direct routing: *Everything uses **owner/name** — code, issues, pull requests, and pushes.*
- Fork routing rows: *Selected fork · origin* / *Followed for updates* / *New branches
  start from* / *Pull requests open against* ("Selected fork", not "Your fork" — the
  selected fork can belong to someone else, as in the no-push state)
- Fork summary: *ByteQuay clones the selected fork for local work and follows
  **upstream/name** for new commits, issues, and pull requests. Finished work is pushed
  to the selected fork when writable and proposed back to upstream/name.*
- No-push banner: *You can't push to **owner/name**. You can still browse commits,
  cherry-pick, and work locally. Push and Open pull request stay off until you get write
  access — ByteQuay never pushes anywhere else on its own.*
- Already watched: *You're already watching this repository. A repository can be watched
  once — its workspace **name** is ready with everything you've done there.* / button
  **Open workspace →**
- Buttons: **Watch repository** (direct) · **Watch fork** · **Watch anyway** (read-only)
- Clone failure: *Clone failed — couldn't reach github.com. Nothing was lost; the
  workspace is saved and will pick up where it left off.* / **Retry clone**
- Metadata failure (card): *GitHub sync failed — showing what was fetched last week.
  Commits still works.* / **Retry sync**
- Cloning banner (Workspace Home): *Getting this workspace ready. Cloning the selected
  fork and fetching upstream/name — usually a few minutes for a large repository. You
  can leave; this keeps going.*
- Disabled Commits reason: *opens when the clone finishes* (failed: *needs a successful clone*)
- PR tab labels: **All · My PRs · Review · Done**; row chips: `mine`, `review`; scope
  chip: *from upstream/name* (amber for a fork, gray for direct).
- PR empty: *No open pull requests in owner/name* / *Open pull requests will appear here
  after the next repository sync.*
- PR stale: *Showing pull requests from N ago — the last refresh didn't finish.* / **Refresh now**
- PR error: *Couldn't fetch pull requests from owner/name. GitHub didn't respond. Your
  access may have changed, or the network is down.* / **Try again**
- Metadata freshness footnote: *Contributors refresh weekly · labels & topics monthly ·
  older data keeps working meanwhile*

## Accessibility and keyboard behavior

- Modal: focus trapped; search input autofocused; `↑/↓` move result selection, `Enter`
  opens the preview, `Esc` steps back (confirm → results → close). Watch button is the
  default action on the confirm step (`Enter`).
- Cards are `<article>` elements, not buttons: the workspace name is the primary link,
  stretched to make the whole card surface clickable; Retry is a **sibling** `<button>`
  (its own tab stop), never nested inside another interactive element.
- Status is never color-only: every chip pairs color with a word and (for failures/
  success) a `✓`/`✕` glyph; spinners have `aria-live="polite"` labels ("Cloning
  repository…").
- Chip contrast follows the existing palette (Primer pairs, all ≥ 4.5:1 for text).
- Disabled tabs keep `aria-disabled="true"` + a tooltip/`title` with the same reason text
  shown on Workspace Home.
- Banners use `role="status"` (info/stale) or `role="alert"` (failure).

## Responsive behavior (normal desktop window sizes)

- Minimum supported width follows the existing app (~1024 px logical).
- Modal: fixed 620 px, centered; content scrolls beyond 70 vh.
- Card grids: existing `repeat(auto-fill, minmax(320px, 1fr))` — 1 column near minimum,
  4 columns on wide displays.
- Workspace Home: Repository/Status cards sit in a 2-column grid that stacks to one
  column below ~1120 px window width; stat cards wrap.
- PR list rows truncate title with ellipsis; check counts and chips never wrap.

## Traceability

| Mockup / state | Requirement in watched-repo.md |
| --- | --- |
| Watch board 1–3 (search/results) | "User flow → 1. Watch from Home" steps 2–3; "Frontend design → Home" (results show owner/name, visibility, default branch, fork marker) |
| Watch board 4 (direct confirm) | "Repository topology and routing" direct column; Checkpoint 2 "Direct preview routes all purposes to the selected repository" |
| Watch board 5 (fork confirm) | Watch preview fields (owner/name, branch, topology, upstream, monitored, dev base, push, PR base); "The confirmation explicitly names upstream monitoring and PR routing for a fork"; one canonical upstream (nested-fork rule) |
| Watch board 6 (no push access) | "If the push destination is not writable … disabled with a clear explanation"; "Explain read-only behavior when no push destination is writable" |
| Watch board 7 (already watched) | "A GitHub repository can be watched only once. A repeated watch returns the existing workspace"; idempotent watch |
| Watch board 8 (saved/preparing, failure+retry) | "Save first is intentional"; "Home shows the workspace card immediately and updates its progress"; "Failed background work remains visible and retryable" |
| Workspaces cards: pending/cloning/ready/syncing | Workspace `codeState` PENDING/CLONING/READY/SYNCING; "Cards show Preparing, Ready, Syncing, or Needs attention" |
| Workspaces card: cloning + metadata green; stale; metadata failed | "Code readiness and metadata readiness are independent"; metadata freshness (7-day contributors / 30-day labels); "A metadata failure does not downgrade ready code" |
| Workspaces cards clickable incl. failed | "A workspace row is clickable in every state"; "Do not hide failures or block opening an already-saved workspace" |
| Workspaces: no local path / no picker | "Remove the local-folder picker"; "The renderer never chooses or submits these paths" |
| Workspace Home A/B (fork/direct routing cards) | "Frontend design → Workspace Home" (selected repo, topology, monitored upstream, default branch, dev base); routing table |
| Workspace Home A (stale metadata) / D (clone failed) | Independent states, last successful sync, safe errors and retry actions |
| Workspace Home C (cloning, dimmed tabs) | "Explain why tabs are disabled when their required state is not ready"; "Commits is enabled when codeState == READY"; "Workspace Home can be opened in every state" |
| Workspace Home stat cards / activity / shortcut | "Keep its task counts, PR count, recent cherry-pick activity, and Commits shortcut" |
| PR board A (scoped upstream list) | "Pass the workspace's monitored repository as a scope"; "using upstream PRs for fork workspaces"; "the workspace tab must never show PRs from another repository" |
| PR board A header chip + subhead | "Show the monitored repository in the workspace tab header, especially for a fork"; global page stays account-centric |
| PR boards B–E (loading/empty/stale/error) | "Provide repository-specific loading, empty, stale, and error states"; "always serve the latest local snapshot while a refresh runs or fails" |
| Chip legend (Workspaces board) | Error shape: stable, sanitized, retryable messages; "Avoid exposing internal implementation terms" (design constraint) |

## Unresolved design decisions (proposed defaults)

1. **Watch a read-only fork: allow or warn-and-block?** Proposed: allow (**Watch
   anyway**) — local browsing/cherry-pick still works and matches "local development
   still works". Alternative: require confirming a checkbox.
2. **Where does "already watched" surface first?** Proposed: both — chip on the result
   row *and* the confirm-step banner with Open workspace. Alternative: jump straight to
   the workspace on selection (faster, but hides why).
3. **Home card vs Workspaces card divergence.** Proposed: identical anatomy (this doc);
   if Home must stay denser, drop the status-chip row there and keep only the footer
   status line.
4. **Retry granularity.** Proposed: per-track retry (clone vs metadata) everywhere;
   a combined "Retry all" appears only when both tracks failed.
5. **Unwatch entry point.** Settings-only (existing danger zone) vs also a card context
   menu. Proposed: Settings-only for the first slice.
6. **PR scope toggle.** Should the workspace PR tab offer "include my fork's own PRs"
   for forks? Proposed: no for v1 — upstream only, matching the monitored-repository
   rule.
7. **Naming**: "labels & people" as the user-facing term for reference metadata —
   confirm with product; alternative "repository info".
