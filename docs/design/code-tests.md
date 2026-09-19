# Code & Tests

> **Status: approved; implementation in progress.** Section 9 lists the
> approved canonical-design amendments, schema costs and dependencies. The
> [implementation checklist](code-tests-impl-tmp.md) tracks delivery.

## 1. Decision

**Code & Tests** is a fourth tab on the pull-request detail pane, beside
**PR #N**, **Changes** and **Review & Learning**. It is for reading whole
files, following symbols, running tests, editing test source and using a
terminal. It works against the ByteQuay worktree bound to the PR: the owning
**development task's worktree** when one exists (section 11), otherwise the
PR's **Agent review working copy**, the managed task worktree that
`DefaultAgentReviewService.start` already provisions at the frozen PR head.

- **Changes stays the diff.** The tab is added only to `PullsScreen`.
  `PullDetailHeader` is shared with the task window's PR panel
  (`AgentTaskScreen`), so the header itself does not change. `PullsScreen`
  injects the Code & Tests button through the header's existing `agentTab`
  slot, and passes `tab={detailTab === 'code' ? null : detailTab}`.
- **Other tabs change only where the run lock reaches them.** While a local run
  holds the working copy, the Review & Learning controls that would start agent
  work are disabled with the run's reason (section 5.3).
- **No worktree, no working copy.** For another author's PR without a
  development task or an open Agent review, the tab shows an empty state whose
  one action is the existing **Start review** (`bridge.startAgentReview`). The
  user's own PR with neither has no Code & Tests tab. ByteQuay never creates a
  second checkout.
- **Ended reviews are read-only.** `findAgentReview` returns only an OPEN
  review. So the empty state also lists this PR's ended reviews from the
  existing `listAgentReviews(workspaceId)`. Opening one loads it by ID, and its
  run history and source are shown read-only. A new review has a new ID and its
  own run history.
- **Design source.** Layout, wording and icons follow
  `PR Code and Tests.dc.html` in the Claude Design project.
  - **Taken from the older `Code & Tests.dc.html`**, because the newer design
    lacks them:
    - the unsaved bar and the on-disk conflict banner;
    - Setup missing, Disconnected and read-only after closure;
    - the Build failed and No tests ran statuses (the newer design reports an
      empty class as Passed; this design does not);
    - the blocked-reason copy.
  - **Deliberate departures from the design:**
    - Line comments to GitHub are enabled only on PR-diff lines (section 6).
    - The Files/Tests fold control sits at the column's top right, not in the
      PR-list header, which is hidden when the detail is zoomed.
    - Terminal commands create no run records (section 7).
    - No worktree path is shown. The Details row and the terminal header say
      "Agent review working copy".
- **Choices already made:**
  - the tab requires a ByteQuay worktree bound to the PR: a development task's
    first, else an Agent review's (section 11);
  - agent Maven commands are mirrored into the shared run history;
  - the terminal is a real PTY (node-pty + xterm.js).
- **Out of scope:**
  - the compact 1024×768 layout;
  - Ask agent / prepared messages;
  - **Compare versions** in the conflict banner;
  - file-level GitHub comments;
  - runners other than the Maven wrapper;
  - **Find tests** and the Repository candidates filter. These need a
    references query that `CodeGraphService` does not have; a later third port
    method would label its results as structural candidates, not coverage.

## 2. Availability

The tab derives every state from existing facts plus the run records in
section 5. It adds no lifecycle state. The backend enforces each blocked action
(sections 4.1 and 5.3); the renderer only mirrors those rules.

| State | Derived from | Reading | Save / Discard | Run | Terminal |
|---|---|---|---|---|---|
| Loading | `reviewLoading` for the selected PR | – | – | – | – |
| Load failed | `reviewError` | – | – | – | – |
| No review | context loaded, no OPEN review; ended reviews listed | – | – | – | – |
| Idle | review `OPEN`, no active turn, no queued input, no claim, and the latest `ReviewRun` is not `RUNNING` unless `followUpPaused` | yes | yes | yes | yes |
| Agent working | `activeTurn`, or latest run `RUNNING` with `followUpPaused` false | yes | "The review agent is working" | same | yes, with a notice |
| Queued question | `queuedInputIds` non-empty, no active turn | yes | "Remove or resume the queued question" | same | yes |
| Working copy busy | claim `COMPLETION` (Update, Resume, End, Save or Discard in progress) | yes | "The working copy is being updated" | same | yes |
| Run in progress | claim `LOCAL_RUN` | yes | "A run is using this working copy" | Stop only | yes, with a notice |
| Recovery | claim `RECOVERY`, or `TURN` with no running turn (the existing `reviewRecoveryRequired`) | yes | the existing recovery reason and its **Recover runtime** action | same | – |
| Review ended | review `CLOSED`, opened by ID | yes, read-only | hidden | hidden | hidden |
| Setup missing | a 422 preflight rejection from `POST …/runs` | yes | yes | reason and one action | yes |
| Disconnected | a poll to the backend fails | cached | "ByteQuay's backend is not responding" | same | reconnects |

**Notes on the table:**

- **Loading and failure.** The existing loading/error branch in `PullsScreen`
  (today guarded by `detailTab === 'review'`) is extended to `'code'`, so Start
  review never appears during a load or after a failed read.
- **Paused review.** A paused review whose run is still `RUNNING` is idle. The
  Review tab shows it as Stopped. Resume continues against the working copy as
  it now is.
- **Disconnected.** Its one action is Retry. It clears on the first successful
  poll. An `INTERRUPTED` run record changes only that run's label, never the
  tab's availability.
- **End review.** End review needs a working copy with no uncommitted changes
  (ignored files are fine), because task completion refuses uncommitted work.
  - Saved test edits must be discarded first. The backend now returns 409
    "Discard saved edits before ending the review" (section 9).
  - If only ignored `target/` output remains, End keeps the worktree and the tab
    keeps reading it.
  - Once `worktreeRemovedAt` is set, the tab reads `git show
    <latestRun.headSha>:<path>` from the managed repository. The task's
    generated branch still pins that commit.

## 3. Frontend

### 3.1 Component tree

New files live under `frontend/src/pulls/code-tests/`; styles in
`css/code-tests.css`.

```text
PullsScreen                       DetailTab gains 'code'; button via PullDetailHeader's agentTab slot
└─ CodeTestsTab                   mounted outside DetailBody, hidden when inactive (3.2)
   ├─ CodeToolbar                 SymbolPalette (⌘P) · Files|Tests · unfold icon when folded · DiscardPopover
   ├─ FileColumn                  fold icon at top right · resize (useResizablePane)
   │  ├─ FileTree                 Changed in PR (default) | All files; PR and working-copy-only markers
   │  └─ TestsTree                Touched | Local only | Repository; classes → @Test methods (lazy);
   │                              run icons; Added / Local badges
   ├─ EditorColumn
   │  ├─ FileTabs                 scrollable, closable, run-class icon, unsaved colour and dot;
   │                              PR-added names green, PR-modified names blue
   │  ├─ Breadcrumb → OutlinePopover   method jumps, run-method icons
   │  ├─ ConflictBanner · UnsavedBar · read-only notice
   │  └─ CodeView                 CodeMirror: read-only or editable, gutter run icons, draft change
   │                              marks, IntelliJ-style added / modified / removed marks against the
   │                              merge base, comment "+", ⌘-click, SymbolChooser, destination
   │                              highlight, inline cards
   └─ RunPanel                    top-edge resize; Run | Terminal (Terminal hides run tabs)
      ├─ RunTabs                  per run: status icon, "<method or class> #ordinal", user/bot icon,
      │                           stale dot, close (hides only); "Show every run" when any is closed
      ├─ RunToolbar               rerun, rerun failed, stop, show only failed tests (on by default)
      ├─ Tests: ResultTree (row "Rerun this test") beside the Log, which follows its tail until the
      │  reader scrolls up; a failure's message and trace are read in the log
      ├─ Log (streamed while running; truncation labelled) · Details (selector, revision, exit,
      │  "Agent review working copy")
      └─ TerminalView             xterm.js
```

**How the Tests tree is built.** It comes from `GET /files`, limited to
Surefire-named files under `src/test/`.

| Section or badge | Rule |
|---|---|
| Touched | `changedInPr` |
| Local only | `onlyInWorkingCopy` |
| Repository | every other test file |
| Class **Added** badge | the frozen `ReviewRun.diff` adds the file |
| Method **Added** badge | every line of the method's outline range is an added line in `prPatch` |
| **Local** badge | `onlyInWorkingCopy` |

No other origin label is shown, and none is guessed from a filename.

### 3.2 State ownership

**`PullsScreen`** keeps what the tab shares with its siblings:

- `detailTab`;
- the existing `pendingReviewComments`, so a comment made here also appears on
  Changes and in Submit review;
- the review loaded by `loadAgentReviewContext`.

**`CodeTestsTab` stays mounted.** `DetailBody` renders one tab at a time, so
`PullsScreen` renders `CodeTestsTab` outside that switch.

- **Mount and unmount.** It mounts on the first visit for the selected review,
  keyed by `reviewId`, and is hidden with `hidden` while another tab is active.
  It unmounts only when the selected review changes or the pane closes.
- **What survives.** Drafts, open tabs, history, run tabs and the xterm view
  survive switching tabs, for example to press Update review.
- **Unsaved-edit prompt.** When any draft is unsaved, `pick()` for another PR
  and closing the pane (button or Esc) first ask "Discard unsaved changes to
  N files?". `CodeTestsTab` reports its unsaved count through a callback.

**`CodeTestsTab`** owns local UI state in one reducer (`codeModel.ts`):

- **Location and history.** Open tabs, the current location `{path, line}` with
  its Back/Forward history.
  - Every navigation sets the current location, and CodeView gives that line
    the destination highlight. Navigations include palette results, outline
    methods, ⌘-click (one match or a chooser pick) and Back/Forward.
- **Panes and runs.** The active pane, scope and fold state; the selected run;
  the set of closed run-tab IDs. Closing a run tab only hides it.
- **Drafts.** File drafts are a `Map<path, {base, text, baseHash}>`, so an edit
  in one file never drops another file's draft.
- **Comment drafts.** `Map<"path:side:start-end", ComposerDraft>`. CodeMirror
  recreates widget DOM when lines scroll out of view, so an inline card never
  keeps editable state itself.
- **Not in the reducer.**
  - Panel sizes come from `useResizablePane` (file column) and one small
    vertical variant (run panel), both called in `CodeTestsTab`.
  - The in-view Back/Forward history does not use the global
    `navigationHistory`.

**Hooks:**

- **Keeping the review live.** `DetailBody` mounts `AgentReviewTab`, the only
  current review subscriber, only on the Review tab. So while Code & Tests is
  the *visible* tab and the review is OPEN, `CodeTestsTab` holds a status-only
  `subscribeAgentReview(reviewId, 0, listener, true)`.
  - On a `changed` or `events` message it calls `getAgentReview` and passes the
    result to `onReviewChange`, including `AgentReviewTab`'s one 200 ms
    follow-up read.
  - It subscribes only while visible, because main keeps one stream reader per
    (window, review), and a hidden subscriber would replace the Review tab's
    reader.
- `useCodeWorkspace(reviewId)`: files, `sourceFingerprint`, `savedExperiment`.
  Refreshed on review changes, after Save and Discard, and when a run finishes.
- `useCodeFile(reviewId, path)`
- `useLocalRuns(reviewId)`: polls every 1 s while a run is live, so the log
  keeps up, and every 5 s otherwise, so a mirrored agent run appears without
  constant reads.
- `useReviewTerminal(reviewId)`

**Pure functions** in `codeModel.ts`, each covered by a unit test:

- tree building and markers, from generalizing `buildFileTree`;
- the Tests sections and badges;
- the history reducer;
- the identifier under the caret;
- draft changed lines, which drive the gutter marks and the "⟨n⟩ lines changed"
  count;
- status labels;
- the range check for which lines accept a GitHub comment.

**Keyboard.**

- ⌘P opens the palette. It is unbound today.
- App's ⌘[ / ⌘] route navigation and `PullsScreen`'s Esc and ⌘F / ⌘1…N
  handlers ignore events whose target is inside `.cm-editor` or `.xterm`.

### 3.3 Editor

Whole-file viewing, in-place editing with undo, gutter markers, block widgets
for inline cards, and ⌘-click on files of several thousand lines are what a
code editor provides. This design proposes **CodeMirror 6**:

- **Packages** (all MIT): `@codemirror/state`, `@codemirror/view`,
  `@codemirror/commands` (history, default keymap), `@codemirror/language`,
  `@codemirror/lang-java`, `@codemirror/legacy-modes`.
- **Production source** uses `EditorState.readOnly`.
- **Inline cards** mount through React portals and are fully controlled
  (section 3.2).
- **In-file find** is out of scope, so `@codemirror/search` is not added; ⌘F
  keeps its current meaning.

The alternative without a dependency is a highlight.js table for reading plus a
transparent textarea overlay for test files, as in the prototype. Its inline
cards cannot reflow inside the textarea, so they would float over lines.
Switching to it later would change no backend contract.

### 3.4 Reused frontend pieces

| Need | Reuse |
|---|---|
| Line comment card | Extract the composer from `DiffFileCard` (`PullChanges.tsx`: the `is-review-composer` JSX, `addPending`, `postNow`, `askAgent`) into a controlled `ReviewLineComposer`. `DiffFileCard` keeps its local state and passes it through the same props. |
| Pending, GitHub and local cards | `PendingReviewCard`: the `<details className="pull-diff-pending">` body split out of `PendingReviewCommentRow`, which keeps its `<tr>` wrapper. Also `ReviewThread`, `LocalReviewThreadCard`, `localReviewMatchesRevision`. |
| Post now / Send to agent | `submitInlineReviewComment`, `useLocalReviewThreads(...).create` |
| Diff line mapping | `parseUnifiedDiff` (`diffParse.ts`) |
| Discard confirmation | `ConfirmDialog` |
| Palette keyboard list | The `RecentPalette` list pattern |
| Icons | `lucide-react` at the existing tab-row (14/1.8) and panel (16/1.7) sizes |

## 4. Backend: review code access

### 4.1 Routes

A new controller, `web/review/ReviewCodeController`, is mounted at
`/api/agent-review/{reviewId}/code`. That prefix already sits behind
`AgentRequestFilter`. The service is a concrete `app/review/ReviewCodeService`.

**Resolving a review:**

- `AgentReviewStore.findReview(reviewId)` gives the review state.
  `AgentReviewStore.listRuns(reviewId)`'s last entry gives the latest
  `ReviewRun`: its `headSha` and frozen `diff`, with `DiffFile.patch`. This is
  the same approach as `ReviewToolAssignment.resolve`. The projection is not
  used, because it cuts the diff down to a count. A review with no run yet
  answers 409.
- `AgentReviewService.task(reviewId)` gives the task: `taskWorktree()`,
  `worktreeRemovedAt()`, `version()` and `codegraph()`.
- In revision mode, the managed repository comes from
  `WorkspaceRepositoryService.requireReadyRepository`, read at the run's
  `headSha`.

**Endpoints:**

| Method and path | Request | Response |
|---|---|---|
| `GET /files` | – | `CodeFilesResponse{source: WORKING_COPY\|REVISION, revision, codeGraph: READY\|OFF\|UNAVAILABLE, sourceFingerprint?, savedExperiment, files: [{path, changedInPr, addedInPr, onlyInWorkingCopy, uncommitted}], truncated}` |
| `GET /file?path=` | – | `CodeFileResponse{path, content, contentHash, editable, readOnlyReason?, prPatch?, lineChanges: [{kind: ADDED\|MODIFIED\|DELETED, line, count}]}` |
| `PUT /file` | `{path, content, expectedHash}` | `CodeFileResponse`, or 409 `{currentHash, currentContent}` |
| `POST /discard` | `{expectedHead}` | `CodeFilesResponse`, or 409 |
| `GET /symbols?query=&limit=` | – | `{status: READY\|BUSY\|UPDATING\|UNAVAILABLE, symbols: [{name, kind, qualifiedName?, path, startLine, startColumn, endLine, signature?, test}]}` |
| `GET /outline?path=` | – | the same shape, with `test` and `added` (every line of the symbol is an added line of the PR patch) computed |
| `POST /terminal` | – | `{workingDirectory}` for Electron main only (section 7) |

**Reads never lock the index.** `GET /files` and `GET /file` do not call
`WorktreeService.inspect`, whose status takes Git's optional index lock and
fails on truncated output. A concurrent poll could otherwise make Update's
`stash` or `merge` fail on `index.lock`. They follow
`ManagedWorktreeService.changes`:

- the same worktree ownership checks;
- `git rev-parse --verify HEAD^{commit}`;
- `git ls-files -z --cached --others --exclude-standard`;
- `git --no-optional-locks status --porcelain=v1 -z --untracked-files=all`, so untracked
  directories list their files.

All go through `GitCommandRunner.run`.

- **Truncation.** A truncated result drops its last partial entry and sets
  `truncated`.
- **Revision mode.** `git ls-tree -r -z --name-only <headSha>`, and files read with
  `git cat-file blob <headSha>:<path>` (`git show` would print a directory listing).
- **Markers.** `changedInPr` and `prPatch` come from the frozen run diff.
- **`lineChanges`.** Display only. `git -c diff.autoRefreshIndex=false -c
  core.quotePath=false --no-optional-locks --literal-pathspecs diff --no-ext-diff
  --no-textconv -M -U0 --inter-hunk-context=0 --src-prefix=a/ --dst-prefix=b/
  <mergeBaseSha> -- [<renameSource>] <path>` against the working tree, so saved local
  edits count, or `<mergeBaseSha> <headSha>` in revision mode.
  - `--no-optional-locks` alone does not stop porcelain diff from rewriting a
    stat-dirty index; `diff.autoRefreshIndex=false` does.
  - A file the PR renamed adds its source path, found with `git diff-tree -r -z -M
    --name-status --diff-filter=R <mergeBaseSha> <headSha>`, so only edited lines
    are marked.
  - Only hunks under `+++ b/<path>` count; a deletion of the path writes
    `/dev/null` and yields nothing.
  - Each hunk is one change: ADDED or MODIFIED `line`/`count` name the new lines;
    DELETED names the line the removed lines followed (0 for the top).
  - Empty when Git fails or truncates, and for an untracked file.

  The gutter keeps a line's mark only while the line equals the saved line at its
  index, the same test as draft marks, and shows no marks while the saved text is not
  the content they were read with (after a save conflict).
- **`sourceFingerprint`.** SHA-256 over HEAD plus each uncommitted or
  working-copy-only path and the hash of its bytes, sorted by path. It is null
  in revision mode.
- **`savedExperiment`.** True when an owned `bytequay-review-experiment:`
  stash exists. That is the stash a failed Update leaves "saved locally". It
  comes from a small public `ReviewExperiments.hasSavedExperiment(worktree)`.

**Paths and editability:**

- Paths are repo-relative, with no `..`, no absolute paths and no NUL. The real
  path must stay under the canonical worktree. Reads are capped at 2 MiB.
- A path is **editable** only when all of these hold:
  - it is in the working copy of an OPEN review;
  - it has a `src/test/` segment and no `.git` component;
  - neither the file nor any parent below the worktree is a symlink (checked
    with `LinkOption.NOFOLLOW_LINKS`), so a PR cannot point a test path at
    production source or Git metadata.
- `PUT` repeats the check immediately before writing.

**Save and discard** go through a new `AgentReviewService.mutateWorkingCopy`,
held under the review lock (`locked(reviewId)`), the same lock `reconcile`,
`resume` and `update` take.

- **Refusals.** Under that lock it refuses with 409 when:
  - the review is not OPEN;
  - the latest `ReviewRun` is `RUNNING` and not paused ("The review agent is
    working").

  Only then does it call `AgentTurnService.synchronizeWorktree(taskId,
  task.version(), …)`, which also excludes turns, queued input and any held
  claim.
- **Why the lock is needed.** Between review steps, and after Stop until
  `settle` records the cancellation, the task holds no claim. A claim taken in
  that gap makes the next `turns.start` fail the run, or makes `settle` return
  without continuing. The lock serializes against both.
- **Save** compares a SHA-256 of the on-disk bytes with `expectedHash` before
  writing, overwrites in place (the file mode stays), and never creates a file
  (404). A path that could never be editable (production source, Git metadata,
  over 2 MiB) is refused with 400 before the lock; refusals that depend on the
  working copy's state are 409. A stale hash answers 409 `{currentHash,
  currentContent}`, with `currentContent` null for a binary or oversized file.
- **Idle rules.** Save and discard share the local-run idle checks and their
  messages (claims, queued input, a running review pass that is not paused).
- **Discard.**
  - It calls `ReviewExperiments.discard(worktree, expectedHead)`, which resets,
    cleans (keeping ignored files) and drops the owned stashes.
  - `expectedHead` is the last `GET /files` `revision`.
  - A mismatch, an open Git operation, or an `IllegalStateException` becomes a
    409.
  - The confirmation names every file marked `uncommitted` or
    `onlyInWorkingCopy`, every unsaved draft, and, when `savedExperiment` is
    set, the experiment a failed Update left saved.
  - Afterwards the renderer clears drafts and closes tabs whose paths are gone.

### 4.2 CodeGraph

The UI reads the same per-task index that the agent uses. It adds no indexer or
schema.

**Port additions** to `CodeGraphService` (`agent-core`), with a top-level
`CodeSymbol` record:

```java
CodeSymbolResult searchSymbols(TaskWorktree worktree, TaskResources resources, String query, int limit);  // query --json --limit N -- <q>
CodeSymbolResult fileSymbols(TaskWorktree worktree, TaskResources resources, String path);               // node --file=<path> --symbols-only --json
```

`CodeSymbolResult` carries a `Status` (`READY`, `BUSY`, `UPDATING`, `UNAVAILABLE`) and the
symbols; an expected failure is a status, never an exception. File mode also returns `file`,
`namespace` and `import` nodes, which the renderer drops.

**Parsing.** The adapter parses JSON; it does not pass text through.

- `query --json` prints `[{node, score, highlights?}]`. The patched file mode
  prints bare `Node[]`.
- Both map `node.filePath` to `path`. `signature` is nullable. Unknown fields
  are ignored.
- These reads bypass the 16 KiB `cap()` used for the agent tool, whose text
  marker would corrupt JSON.
- A read is `UNAVAILABLE` on an unsuccessful exit, on truncated `CodeGraphCli`
  output (1 MiB), or when the output does not parse.

**The `fileSymbols` patch.** The upstream CLI prints file symbols only as
markdown. A second anchored patch in `scripts/codegraph-bundle.mjs` adds
`--json` to file mode and prints the library's existing `getNodesInFile(path)`.
It uses the same refuse-if-anchor-missing rule. The bundle stamp gains a patch
revision, so a bundle already vendored by `dev.sh` or Forge is fetched and
patched again instead of staying on a CLI that rejects `--json`.

**Test methods.** CodeGraph 1.6.0 does not record Java annotations on nodes;
`@Test` exists only as an unresolved `decorates` reference. So `/outline` finds
test methods from the file text:

- The backend reads the file under the rules in section 4.1.
- For each `method` symbol, it scans the lines from `startLine` (the first
  annotation) to the line containing the method name, looking for
  `@(?:[\w.]+\.)?(Test|ParameterizedTest|RepeatedTest|TestFactory|TestTemplate)\b`.
- The result is returned as `test: boolean`.

**Read contract:**

- **Lock.** A UI query takes the task's graph lock with `tryLock` (about 2 s).
  If it is busy, the query reports "CodeGraph is busy" and never blocks file
  reading.
- **Under the lock, never rebuild.** A UI query never runs `init` or `index` on
  the request thread, and never discards the index.
  - **In place.** When the index is usable and `codegraph.head` equals the
    worktree HEAD, it runs a plain `sync` (about 0.5 s) and queries, even when the
    size rule would prefer a rebuild: uncommitted edits never clear, so a rebuild
    would not move the rule and every read would queue another. A failed `sync`
    reports the graph unavailable and leaves the index alone.
  - **Otherwise.** The index is missing or HEAD moved. The query queues the
    existing `prepare` on the CodeGraph executor and reports "CodeGraph is updating". Reads that arrive while that rebuild
    holds the lock report `BUSY`. The renderer retries on its next open or expand.
- **Setting and removal.** The UI follows the task stamp (`task.codegraph()`).
  Queries are refused once `worktreeRemovedAt` is set.
- **Go-to-definition.** It matches by name. The renderer takes the identifier
  under the caret and calls `searchSymbols`, keeping exact name matches:
  - no match: the click only places the caret;
  - one match: jump;
  - several matches: `SymbolChooser`, with the design's caveat that structural
    matches are not proof.
- **Without graph data.** Files, the tree, reading and **Run class** keep
  working; Run class needs only a Surefire-named file under `src/test/`. The
  outline, symbol links, method gutter icons and symbol results are hidden,
  with the design's notice.

## 5. Backend: local runs

### 5.1 Run record

A new table, `local_run`, mapped as `core/review/LocalRun` and `LocalRunStore`,
with `SqliteLocalRunStore`. It is not a `ReviewRun`: that record is an agent
review pass, and it stays unchanged.

| Column | Meaning |
|---|---|
| `id`, `task_id`, `workspace_id`, `ordinal`, `version` | Identity; `#n` on run tabs is `ordinal`, counted per task. A review's runs are its task's runs (a review owns exactly one task). |
| `initiator` | `USER` \| `AGENT` |
| `scope` | `CLASS` \| `METHOD` \| `COMMAND` |
| `test_class`, `test_method`, `module` | Optional selector facts; `module` is null for a root-module project |
| `command` | Worktree-relative display argv (`./mvnw …`), shown only in Details |
| `source_head_sha`, `local_paths_json`, `source_fingerprint` | Worktree HEAD, the uncommitted or working-copy-only paths ("+ 1 local reproduction file"), and the section 4.1 fingerprint, all taken at start. RunTabs shows the stale dot "Code changed since this run — kept as evidence" when the fingerprint differs from the current one. |
| `status` | `RUNNING`, `PASSED`, `FAILED_ASSERTION`, `BUILD_FAILED`, `NO_TESTS_RAN`, `STOPPED`, `TIMED_OUT`, `RESULTS_UNAVAILABLE`, `INTERRUPTED` |
| `started_at`, `ended_at`, `exit_code` | Timing and exit |
| `output`, `output_bytes`, `omitted_bytes`, `output_truncated` | The first 64 KiB plus the last 1 MiB of stdout, with a labelled gap, and stderr up to 64 KiB. `omitted_bytes` is null when the amount is unknown (provider truncation). |
| `results_json` | Classes → methods: `{name, status PASSED\|FAILED\|ERROR\|SKIPPED, durationMs, message?, trace?}` |
| `tests`, `failures`, `errors`, `skipped` | Counts from the runner only |
| `agent_event_id` | Unique; makes mirroring idempotent |
| `launch_id` | The supervised process launch |

**Lifecycle rules:**

- Rows are written only at start, finish and stop. The single SQLite
  connection must never be held while output streams; live output lives in
  memory.
- `SqliteWorkspacePurge.delete` also removes `local_run` rows, by
  `workspace_id`.
- Records outlive End review and task runtime retention.

### 5.2 Status

The status comes only from runner facts, never from agent prose. A run that
has not finished shows no aggregate.

| Facts at finish | Status (label) |
|---|---|
| Stop requested | `STOPPED`: partial output, no aggregate |
| `ProcessExit.Reason.TIMED_OUT` | `TIMED_OUT` ("Timed out after 60 min"): partial output, no aggregate |
| A fresh report contains a failure or error | `FAILED_ASSERTION` ("Failed assertion"); rows keep FAILED vs ERROR |
| Fresh reports have at least one passed case, no failure, exit 0 | `PASSED` |
| Fresh reports all pass, exit non-zero | `BUILD_FAILED`, rows kept |
| No fresh reports, exit non-zero | `BUILD_FAILED`, no counts |
| No fresh cases, or only skipped cases, exit 0 | `NO_TESTS_RAN` |
| Reports unreadable, or a `COMMAND` run without a known Maven exit | `RESULTS_UNAVAILABLE`: output and exit only; a mirrored run keeps any rows, without an aggregate |
| Backend restarted mid-run | `INTERRUPTED` ("The backend restarted during this run, so its outcome is unknown"), with Rerun |

**Fresh reports.** A report is fresh when its file is modified at or after
`started_at`, stored at millisecond precision. For `CLASS` and `METHOD` scope,
only `TEST-<selected class>*.xml` counts. Reports live under `<module or
worktree root>/target/surefire-reports/` and are read with the JDK's StAX
reader.

### 5.3 Starting a user run

`POST /api/agent-review/{reviewId}/code/runs {path, method?}` goes to
`LocalRunService.start`. `path` is a test source; the class comes from it and
each package segment and the simple name must be Java identifiers, as must
`method`, so a file name cannot smuggle Surefire patterns into `-Dtest`.
Anything else returns 400. Scope is `METHOD` with a method, else `CLASS`.

The service holds `ReviewLocks.lock(reviewId)` from preflight until the claim
is taken, then releases it. Update, Resume, End, ask and follow-up turns
already run under that lock. `followHead` releases its sync claim before
`startRun` reaches `turns.start`, so a claim landing in that gap would
otherwise fail the new review run.

1. **Preflight.** Each failure returns 422 `{reason, action}` and creates no
   record; RunPanel shows it as Setup missing where the run would have
   appeared.
   - The review must be OPEN with its worktree present, and idle under the
     section 2 rules, checked here under the lock.
   - **Maven wrapper.** `mvnw` must be executable at the worktree root.
     Otherwise: "No Maven wrapper (mvnw) in this working copy", action
     **Open Terminal**.
   - **Maven module.** `MavenModules.findModule(worktree, <test source path>)`
     gives the module.
     - An empty result with a root `pom.xml` is a root-module run.
     - An empty result without one fails: "No Maven module for this test".
   - **Java.** Accept, in order:
     1. an executable `$JAVA_HOME/bin/java` from the login environment;
     2. a `java` on the login `PATH` other than `/usr/bin/java`;
     3. `/usr/libexec/java_home` exiting 0.

     Otherwise: "No Java runtime in your login shell environment", action
     **Open Terminal**. `/usr/bin/java` is a macOS stub that exists even with
     no JDK, and running it can open an install dialog, so it is never probed.
2. **Claim.** `AgentTurnService.claimLocalRun(taskId, runId)` takes a new
   `WorktreeMutationClaim.Kind.LOCAL_RUN`.
   - **Gate.** It honours the agent serving gate and the same start rules as a
     turn.
   - **What the claim blocks.** Turn start, enqueue and `synchronizeWorktree`
     already refuse while any claim is held.
   - **409s.** `DefaultAgentReviewService.update`, `resume` and `end` gain an
     explicit 409, "A run is using this working copy"; `followHead` would
     otherwise return silently.
   - **Record.** `source_head_sha`, `local_paths_json` and `source_fingerprint`
     are read after the claim succeeds.
   - **Review & Learning.** `AgentReviewTab` disables Resume, Update review,
     End review and Run review again with that reason while
     `review.task.mutationClaim === 'LOCAL_RUN'`. Send to agent is disabled the
     same way. `runtimeStatus` (`agents/model.ts`) treats `LOCAL_RUN` as a busy
     working copy, not as recovery.
3. **Launch.** `ProcessSupervisor.prepare(ProcessSpec(...))`:
   - **owner.** `ProcessOwner(Kind.LOCAL_RUN, "<taskId>/<runId>")`.
   - **argv.** `/bin/bash -c '"$0" "$@"; exit $?' <worktree>/mvnw -B [-pl <module>] -Dtest=<Class>[#method] -Dsurefire.failIfNoSpecifiedTests=false test`.
     `mvnw` execs `mvn`, which execs `java` under the same PID. That erases the
     executable that supervisor recovery matches on. The trailing `exit` keeps
     `/bin/bash` as the recorded leader, so recovery can match and signal the
     group.
   - **cwd.** The worktree.
   - **environment.** From `LoginShellEnvironment`, which runs `$SHELL -ilc
     'env -0'` once, with a 10 s timeout, and caches the result. Output goes to
     an owner-only temporary file rather than a pipe, because a background job
     a startup file leaves behind can hold a pipe open. A failed probe is not
     cached, and a refusal for a missing Java runtime drops the cache so a
     newly installed JDK is found on the next attempt. The probe runs
     without `BYTEQUAY_*` variables, so the session token never reaches PR code,
     and with `LANG` defaulted to `en_US.UTF-8` when unset.
   - **stdout.** `stdoutCaptureLimit = OptionalInt.empty()` (lossless,
     backpressured). A present limit would keep only the head and drop Maven's
     summary. The head/tail cut happens in the run's in-memory
     `ProcessOutputSink`, whose `onOutput` never blocks.
   - **stderr.** `maximumStderrBytes` = 64 KiB, head only, marked through
     `onTruncated(STDERR)`. Maven `-B` writes its log and summary to stdout.
   - **Limits.** Maximum runtime 60 min, cancellation grace 10 s.
4. **Stream, finish, release.**
   - **Stream.** `GET …/runs/{runId}/output?after=<offset>` serves the
     in-memory buffer.
   - **Finish.** At exit the service reads reports, derives the status and
     saves the row. It then checks `ProcessExit.treeTermination()`:
     - **`VERIFIED_STOPPED`:** `releaseLocalRun`, then
       `ProcessSupervisor.acknowledge(launchId)`, in that order, as
       `CodexCliDriver` does.
     - **`UNVERIFIABLE`:** keep the claim and the marker. The tab shows "The
       run's processes could not be verified stopped" with Stop only. Releasing
       early would let End complete the task. An unacknowledged launch of a
       terminal task then blocks every agent operation at the next startup.
   - **Failed save or release.** The finished record and output are held in
     memory before the save. If the save or the release fails, the run is
     marked unverified (in memory, and best effort in the store), so the tab
     keeps Stop, and Stop saves the held record and releases the claim instead
     of the claim holding until a restart.
   - **Failed start.** When `PreparedProcess.start` throws, the caller cannot
     tell whether the gate already ran the payload, so the claim is kept and
     the row is saved `RESULTS_UNAVAILABLE` and unverified, with output asking
     for a restart. Startup recovery then checks the launch receipt and
     releases the claim. A failure before the start (working-copy read,
     `prepare`, insert) releases the claim at once.
   - **Stop.** `POST …/runs/{runId}/stop` sets the stop flag and calls
     `ManagedProcess.stopAndVerify()`, which can be retried. It does not take
     the review lock, and it reads the in-memory run, not the stored row.
   - **Memory.** A review's settled runs drop their in-memory buffer when its
     next run starts; their stored, bounded output remains.
   - **Shutdown.** `LocalRunService` is a `SmartLifecycle` that stops before
     the datasource closes. It calls `stopAndVerify()` on each live run, saves
     `STOPPED`, and releases after verification. Quitting mid-run therefore
     leaves no recovery claim.
5. **Reruns** post a new user run built from the selected run.
   - **Rerun** repeats its scope and selector.
   - **Row rerun** is a `METHOD` run for that row.
   - **Rerun failed** is a `METHOD` run for the first `FAILED` or `ERROR` row,
     as in the design.
   - All three are disabled while a run is live and for `COMMAND` runs.

**Recovery.**

- `AgentStartupRecovery.ownerTask` (both overloads) learns `LOCAL_RUN` and
  parses the task from `<taskId>/<runId>`.
- `AgentMaintenance.recover` releases a `LOCAL_RUN` claim at startup, as it
  does a `COMPLETION` claim. This is safe because the serving gate stays closed
  until process recovery settles.
- `settleAfterProcessRecovery` then gives a task whose run launch is
  unverifiable a `RECOVERY(launchId)` claim through its existing empty-claim
  branch, so only that task is blocked.
- The existing **Recover runtime** action (`stopRecoveredRuntime`, review
  `runtime/stop`) retries verification of every launch attributable to the
  task, including run launches.
- Rows still `RUNNING` at startup become `INTERRUPTED`.

**Routes:** `GET …/runs`, `GET …/runs/{runId}`, `POST …/runs`,
`POST …/runs/{runId}/stop`, `GET …/runs/{runId}/output?after=`.

### 5.4 Mirroring agent runs

The review agent keeps running tests in its own sandbox. Its tools do not
change.

- **Trigger.**
  - `ReviewTaskObserver`'s subscriber already receives the review task's
    envelopes. Its `onNext` only filters. It must never throw, because an
    exception cancels the subscription without `onError`, and the stale entry
    then blocks `watch()`.
  - Matching events are queued as `LocalRunService.recordAgentCommand(review,
    envelope)` on the observer's executor, inside a try/catch that logs, like
    `reconcileLater`.
  - Reports are read and parsed outside any transaction; the step ends with one
    short insert.
- **Which commands match.** Neither provider tokenizes argv.
  - Codex sends one string wrapped in the user's shell (`/bin/zsh -lc '…'`).
  - Claude sends the bare Bash script (`cd core && ../mvnw test -Dtest=Foo |
    tail`).

  Detection shell-word-splits the joined argv and unwraps a leading `<shell>
  -c`/`-lc` script. It matches when any `&&`, `||`, `;` or `|` segment has a
  command word whose basename is `mvn` or `mvnw`.
- **Skipped commands.**
  - A Codex command that ended `FAILED` with no exit code never ran (declined or
    interrupted).
  - A Claude Bash call with `run_in_background`: its tool result arrives at
    launch and carries no result. `CommandActivity` gains `boolean background`
    for this (section 9).
- **Scope.** `CLASS` or `METHOD` when the words contain exactly one
  `-Dtest=Class[#method]`; otherwise `COMMAND`.
- **Source facts.** When a matching `STARTED` `CommandActivity` arrives, the
  service reads the worktree HEAD, local paths and fingerprint. It keeps them
  in memory, keyed by provider item ID, and writes them with the row on
  completion.
- **Rows.** Rows come from Surefire reports modified within the command's
  window, which ends at the completion event's `occurredAt`. An agent's
  command names no reliable module, so every `target/surefire-reports`
  directory in the working copy up to six levels deep is read; hidden
  directories, `src` and `node_modules` are not entered and links are not
  followed. The claim that serializes runs and turns keeps another command's
  reports out of the window.
  - The window starts at `occurredAt − durationMs` when that is present (Codex),
    otherwise at the matching `STARTED` event's `occurredAt` (Claude, which
    supplies no duration).
  - With no known start, no reports are read.
- **Mirrored status.** The section 5.2 table applies, with one exception:
  `PASSED` needs a known exit code of 0 from a command whose exit status is
  Maven's. That means a lone `mvn`/`mvnw` invocation with no pipe, `;`, `&&`,
  `||` or redirection.
  - Otherwise, fresh rows that all pass give `RESULTS_UNAVAILABLE`: rows and
    counts visible, no Passed aggregate.
  - With no rows, the status is `BUILD_FAILED` only for a class or method scope
    with a known non-zero Maven exit. Otherwise it is `RESULTS_UNAVAILABLE`.
  - Claude reports no exit code, so a mirrored Claude run is never `PASSED`.
- **Output.** `CommandActivity.output`, bounded as in section 5.1. The
  provider's `truncated` flag sets `output_truncated` and leaves
  `omitted_bytes` null. The Log says "Output truncated by the agent; the end is
  not available".
- **Timing.** Agent runs appear when the command completes, not live. There is
  no catch-up after a restart: a command whose mirroring had not committed when
  the backend stopped is not mirrored. A start whose completion never arrives is
  forgotten after 24 hours.
- **Record.** `initiator` AGENT, no `path` or `module` (so Rerun and Open test
  source stay unavailable), `agent_event_id` from the completion event, which
  keeps a replayed event from inserting a second row. Mirroring takes no claim.
- **Providers.** The Codex skip is decided by the task's lane, because a failed
  Claude Bash call also has no exit code but did run.

## 6. Review comments

The composer is the extracted `ReviewLineComposer`. The user opens it by
hovering a line number and pressing **+**.

- **Anchor.** The reviewed revision is the latest run's `headSha`, with
  `side: 'RIGHT'` and the line range.
- **Add to review and Post now are enabled only when:**
  - the displayed text is the file at `headSha`: in revision mode by
    construction; in the working copy when `CodeFilesResponse.revision` equals
    `headSha`, the file is not uncommitted, and there is no draft;
  - `headSha` equals the review's `observedHeadSha`;
  - every line in the range is an add or context row of one hunk in `prPatch`.
- **Why this departs from the design.** The design offers **+** on every line.
  GitHub accepts line comments only on diff lines, and today a bad line fails
  the whole Submit review.
- **Disabled reasons:**
  - "GitHub accepts line comments only on lines in this pull request's diff"
  - "Update the review to comment on its latest revision"
  - "The working copy is not at the reviewed revision"
  - "Save or revert your edits to comment on the reviewed revision"
- **Send to agent** uses the existing local discussion on any line while the
  review is OPEN.
  - It is disabled while the task holds a `LOCAL_RUN` or `RECOVERY` claim, with
    that state's reason, because the dispatch would come back as a failed
    delivery.
  - It is disabled on a range with a draft: "Save or revert your edits to send
    them to the agent".
  - On uncommitted or working-copy-only lines it sends `headSha: null` with the
    worktree text as `code`, so Changes shows the card as outdated instead of
    placing it.
- **Placement.** Add to review appends to `PullsScreen.pendingReviewComments`
  with `commitId = headSha`. Post now calls `submitInlineReviewComment`. Cards
  are placed inline only when the displayed text is the clean revision:
  - a GitHub thread, when it is not outdated and `headSha` equals
    `observedHeadSha`;
  - a pending comment, when its `commitId` equals `headSha`;
  - a local thread, when `localReviewMatchesRevision` holds and its stored code
    matches the displayed lines;
  - an Agent review finding, at its end line as the same `ReviewFindingCard`
    Changes draws, when it is RIGHT-side, its `validatedHeadSha` equals
    `headSha` and its excerpt matches the displayed lines.

  Everything else is listed below the file as unplaced cards (the
  `UnplacedReviewThreads` pattern). Each pending card is marked pending. Like
  Changes, withdrawn and outdated findings stay in Review & Learning only.

## 7. Terminal

**Dependencies:** `node-pty` (pinned exactly) in main; `@xterm/xterm` and
`@xterm/addon-fit` in the renderer.

**Flow:**

1. The renderer calls `bridge.openReviewTerminal(reviewId, cols, rows)`.
2. Main calls `POST /api/agent-review/{id}/code/terminal`. The backend checks
   that the review is OPEN and the worktree is present, then returns
   `{workingDirectory}`. Main never forwards the path; the renderer receives a
   terminal ID.
3. Main runs `pty.spawn(os.userInfo().shell, ['-l'], {cwd, env, cols, rows})`.
   - `env` is `process.env` with every `BYTEQUAY_*` key removed, plus
     `TERM=xterm-256color`, plus `LANG=en_US.UTF-8` when `LANG` is unset.
   - A Finder-launched app inherits launchd's environment, which has no locale.
     The interactive login shell rebuilds the user's own `PATH` and environment.

**IPC:**

- **Open.** `terminal:open(reviewId, cols, rows)` is idempotent. Main keys PTYs
  by (webContents, reviewId). For an existing PTY it returns `{terminalId,
  scrollback}`, the last 256 KiB of output kept in a ring buffer, instead of a
  second shell.
- **Other channels.** `terminal:write` and `terminal:resize` (fire-and-forget,
  honoured only for the window that owns the terminal), and pushes
  `terminal:data` and `terminal:exit {exitCode}`. When the shell exits, the view
  prints its exit status and offers a new shell. There is no close channel:
  typing `exit` ends the shell.
- **Unmount.** Unmounting `TerminalView` only detaches; it never closes the PTY.

**Lifetime.** Main owns one terminal per review per window and kills it:

- when the shell exits;
- in main's `review:end` handler, before calling the backend, because End is
  pressed on another tab and the backend then removes the shell's cwd;
- when its webContents emits `destroyed` or a main-frame `did-navigate`
  (reload), registered like `AgentSubscriptions.watch`;
- in `before-quit`.

**Not a run launcher.** Terminal commands create no run records; parsing typed
commands would be a guess. The terminal header shows a notice while the agent or
a run holds the working copy.

**Packaging:**

- `vite.main.config.ts` externalizes `node-pty`.
- A Forge `packageAfterCopy` hook copies node-pty's `package.json`, `LICENSE`,
  `lib` and the target's `prebuilds/<platform>-<arch>` into the build path,
  because the Vite plugin otherwise ships only `.vite` (sources and other
  platforms are 62 MB; the copy is about 400 KB).
- node-pty 1.1.0 publishes `spawn-helper` without its execute bit, and every
  spawn fails with `posix_spawnp failed` until it has one. A `postinstall`
  script fixes it for development, and the hook sets it again on the copy.
- `packagerConfig.asar = { unpack: '**/node_modules/node-pty/**' }`. On macOS,
  node-pty execs `spawn-helper` next to `pty.node` from `app.asar.unpacked`.
- No Electron ABI rebuild: `rebuildConfig: { ignoreModules: ['node-pty'] }`.
  node-pty 1.1.0 is N-API with darwin prebuilds.
- **Gate.** `npm run package`, then the packaged app opens a review terminal
  and runs `echo ok`. Confirmed so far: the packaged layout (helper `0755` in
  `app.asar.unpacked`, `lib` in the archive, main requiring node-pty
  externally), and a login shell spawned from the packaged archive under
  Electron 41's runtime printing `ok`.

## 8. Build order

Each phase is runnable and reviewable on its own. Each runs the full gates
(`mvn verify`; `npm run lint`, `npx tsc --noEmit`, `npm test`,
`npm run package`) and is verified in the browser pane with a fixture bridge.

| Phase | Scope |
|---|---|
| 0: mechanical | Extract a controlled `ReviewLineComposer` from `DiffFileCard`. Split `PendingReviewCard` from `PendingReviewCommentRow`. Generalize `buildFileTree` to path lists. Move `MavenModules` out of `cherrypick` and make it public. No behaviour change. |
| 1: read | Tab button, mounting, loading/empty/ended states. `GET /files` and `/file`. FileTree with scope and markers. CodeMirror read-only view, tabs, Back/Forward, fold and resize, revision mode. |
| 2: navigate | CodeGraph port additions, bundle patch and stamp. `/symbols`, `/outline`. Palette, breadcrumb outline, ⌘-click with chooser, graph states, Tests tree. |
| 3: run and log | `local_run` store. `LOCAL_RUN` claim, owner kind and recovery. Runner, preflight and report reader. Run routes, polling log, RunPanel, run icons wherever the design shows them. Blocked reasons on Review & Learning controls. Agent mirroring. **Gate addition:** start the backend against a copy of a populated pre-phase-3 database after the chosen data step (section 9), then start and stop one user run. |
| 4: comment and edit | Inline composer and cards. `PUT /file` with conflict. Unsaved bar and gutter marks, Save, Save and rerun, Discard with confirmation. End review's uncommitted refusal. |
| 5: terminal | node-pty, xterm, main IPC and lifetime, packaging. |

## 9. Needs approval

1. **`agent-review.md`:**
   - review-scoped `/code` routes;
   - `mutateWorkingCopy` under the review lock;
   - Update, Resume and End refusing during a local run, and the matching
     disabled reasons on Review & Learning;
   - End review checking for uncommitted changes before `observer.unwatch`,
     returning 409 "Discard saved edits before ending the review". Today `end`
     unwatches first, so a refused completion leaves an OPEN review that is no
     longer observed;
   - mirrored agent Maven commands;
   - local runs as review-owned records that are not evidence cards;
   - §4's "optional agent-led queries" also allowing read-only UI graph queries.
2. **`agent-service.md` §5:**
   - `ProcessOwner.Kind.LOCAL_RUN` with a new `ExecutionProfile.LOCAL_COMMAND`
     (a user-requested command in the review working copy, login environment,
     no provider sandbox), and `LaunchOwnerKind.LOCAL_RUN` with its recovery
     attribution;
   - `WorktreeMutationClaim.Kind.LOCAL_RUN` with
     `AgentTurnService.claimLocalRun` and `releaseLocalRun`. `AgentMaintenance`
     releases the claim at startup and relies on `settleAfterProcessRecovery`
     for unverifiable launches;
   - `AgentEventPayload.CommandActivity` gains `boolean background`, set by
     `ClaudeEventMapper` from `run_in_background`.
3. **Schema cost of the new enum values.** `agent_task.mutation_claim_kind` and
   `agent_process_launch.owner_kind` carry Hibernate-generated CHECK lists.
   `ddl-auto=update` never rewrites them, and SQLite cannot alter one in place.
   On an existing database every `LOCAL_RUN` claim and launch insert fails until
   one of these happens:
   - `./dev.sh --reset-data` (the old database moves to a backup);
   - a one-off rebuild of both tables with the widened CHECK.

   Which one applies to your current runtime database is your call. The new
   `local_run` table needs neither.
4. **`worktree-service.md` §6:**
   - one exception to "Renderer and HTTP values never contain a worktree or
     repository path": the session-authenticated response of `POST
     …/code/terminal` may carry the worktree path to Electron main, never the
     renderer;
   - review-task discard reuses `ReviewExperiments.discard`, which is the
     "separate future design" §6 asks for.
5. **`codegraph-service.md` §2, §4, §5, §10, §11 #12 and §12:**
   - renderer-facing, read-only `searchSymbols` and `fileSymbols`;
   - the port grows beyond its six methods, and `fileSymbols` takes a
     repo-relative path already validated by `ReviewCodeService`. The port
     javadoc changes to match;
   - UI queries take only the graph lock with a short `tryLock`, never rebuild
     in place, and are refused after worktree removal;
   - the UI follows the task stamp;
   - the second bundle patch and the stamp revision.
6. **`design.md` principle** that a project-aware command needs a validation
   profile: the Maven wrapper is hard-wired for this first integration. The run
   record and UI carry scope and selector text, so another runner needs only a
   second preflight and argv builder.
7. **Dependencies:**
   - CodeMirror 6 packages (section 3.3; the no-dependency alternative is
     described there);
   - `node-pty`;
   - `@xterm/xterm` and `@xterm/addon-fit`.

## 10. Risks and limits

- **PR code runs on the host.** A user run or terminal executes the PR's build
  and tests unsandboxed, with network access and the user's environment.
  - Both are direct user actions only; an agent never starts a local run.
  - Removing `BYTEQUAY_*` keeps the session token out of that environment. It is
    not isolation, because same-user code can still read the backend's process
    environment.
  - Run records label local execution as distinct from GitHub CI.
- **End review with build output.** A test run leaves ignored `target/`. End
  review then keeps the worktree "for inspection", and the tab keeps reading
  it. Saved test edits must be discarded before End.
- **Backend crash mid-run.** Maven keeps running, because the JVM ignores
  SIGPIPE and the runtime limit dies with the backend. Startup recovery stops
  the group. If the stop cannot be verified, only that task stays in Recovery.
- **Maven limits.**
  - `-pl` without `-am` fails when the PR changes an upstream module; the result
    is Build failed with output. The upgrade path is a selector profile.
  - A `mvn test` typed in the Terminal during a run writes the same report
    files and is attributed to the run.
- **Mirroring limits.**
  - Mirroring runs after the command completes, behind other observer work. A
    report that a later Maven command already rewrote falls outside the
    command's window, and that run records no rows.
  - Backgrounded Claude builds are not mirrored.
- **CodeGraph limits.**
  - It indexes the working files, not the frozen head, so symbol lines can
    differ from the reviewed revision while edits exist.
  - Name-based resolution needs the chooser for overloads.
  - A background rebuild after Update review holds the graph lock for about
    40 s on Trino-sized repositories. Meanwhile UI graph features show
    "updating", and an agent `codegraph_explore` waits up to 60 s.
- **Update review with saved edits.** Update stashes and restores saved test
  edits, and discards them on conflict, which the Review tab already discloses.
  Unsaved drafts stay in the mounted tab. When the file changes on disk, the
  next refresh or save shows the conflict banner instead of overwriting.
- **Large repositories.** A truncated `ls-files` or `status` result drops its
  last partial entry and marks the list `truncated`. That happens at about
  60,000 paths, the 5 MiB `GitCommandRunner` cap.
- **Pending comments are renderer state.** They clear on revision change, as on
  Changes today. The durable-draft migration in `agent-review.md` §2–3 is still
  outstanding.
- **Unsaved drafts outside the prompts.** Leaving the Pull requests screen from
  the sidebar is not guarded, so unsaved drafts are lost there. Only PR
  selection and closing the pane prompt.

## 11. Development-task host

Approved 2026-09-17. A pull request developed in ByteQuay already has a
worktree and an agent, so Code & Tests opens on it directly, with no Agent
review.

**Which worktree.** The code is bound to the worktree, not to the kind of task:

1. `bridge.findPullRequestTask(workspaceId, repo, number)` returns an
   interactive development task, and `getAgentTask` shows its worktree still
   present (`checkoutRemovedAt === null`): Code & Tests reads that task.
2. Otherwise an Agent review (open, or ended and opened read-only) is read as
   today.
3. Otherwise the user's own PR has no Code & Tests tab, and another author's PR
   shows the Start review empty state.

The lookup runs for every PR with a known workspace, not only authored ones,
including a review opened by ID. It is separate from loading the review, so a
slow or failed lookup never holds up Review & Learning: Code & Tests waits for
it ("Loading working copy…") and falls back to the review when it fails.

**Routes.** `/agent/tasks/{taskId}/code/...?workspaceId=&repo=&number=`, with
the same paths, bodies and responses as the review routes of section 4.1
except `POST /discard`, which the task host does not have. Review routes stay
review-only (agent-review.md: review routes do not become interactive task
routes). Every task request re-resolves:

- `getInteractive(workspaceId, taskId)`;
- that the PR belongs to the task (`TaskPullRequestOwnership`): its
  `pull_request_task` assignment, or the stored PR's `headRef` equals the
  task's generated branch, or its current branch when the PR is the user's own
  (`AUTHORED`), since a renamed branch can share a common name with another
  author's fork. `PullRequestTaskService.find` applies the same rule. Local
  review uses the same check and also requires the PR to be open, assigned or
  not;
- the worktree is present, or 409 "The task's worktree was removed". There is
  no revision fallback.

**Working-copy facts for a task.** `ReviewCodeService` builds one source record
from either host:

| Fact | Review host | Task host |
|---|---|---|
| Changed/added files, `prPatch` | frozen run diff | stored PR diff (`getPullRequestDiffFiles`, database only); approximate when HEAD is ahead of the pushed head |
| Head for rename pairing | run `headSha` | worktree HEAD |
| Merge base for `lineChanges` | run `mergeBaseSha` | `git merge-base HEAD refs/remotes/<remote>/<baseRef>` in the worktree, no fetch; the remote follows `PullRequestTaskService.assign`; empty marks when it fails |
| Writable | review OPEN | task OPEN |
| Editable paths | `src/test/` only | every path |
| Always refused | `.git` components, symlinks in the chain, creating files | same |

**Edits.** Save goes through `turns.synchronizeWorktree` directly; the core
start rules already refuse an active turn, queued input, any held claim, an
unfinished development continuation or in-memory development owner, and CI
Autofix owning the branch. There is no review lock on this path. A saved edit
is ordinary uncommitted work: a later development turn's "commit finished
changes" step may commit it, and it blocks Review locally until
committed. Discard is not offered: `ReviewExperiments.discard` resets
and cleans the worktree, which on a development task would delete unfinished
work (worktree-service.md: destructive operations need their own authority).

**Local runs.** Keyed by task. Deleting a development task deletes its runs;
an Agent review's runs are kept with the review. Start checks the task is OPEN with its worktree,
then `claimLocalRun`, which is atomic under the task lock, so no review lock is
needed. The idle pre-check reasons are task-worded ("The task's agent is
working", "Development automation is working"). Agent Maven commands of a
development task are not mirrored: its transcript already shows them.

**Terminal.** `POST /agent/tasks/{taskId}/code/terminal` returns the worktree
path to Electron main only. Main keys terminals by host (`review:<id>` or
`task:<id>`) and closes a task's terminals before complete, mark failed,
cleanup and delete, each of which can remove the worktree.

**Frontend.**

- The task window's pull-request panel (`AgentTaskScreen` side panel) also has
  a **Code & Tests** tab beside PR #, Changes and Subagents while the task is
  interactive and keeps its worktree. It hosts the same `CodeTestsTab` on the
  task, is mounted on first visit and kept (hidden) across tab switches inside
  the panel's one frame and header, and shares the panel's pending comments,
  local threads and Send to agent. Closing the panel or going back to Tasks
  with unsaved drafts asks first, as the PR page does.
- The task panel's pull-request list applies the ownership rule: a PR on the
  task's renamed current branch is listed only when it is the user's own.
- `CodeTestsTab` takes a host, `{kind: 'review'}` or `{kind: 'task'}`; every
  code, run and terminal bridge call takes the host reference.
- The task host polls `getAgentTask` on the run-poll tick for its run gate. It
  never calls `subscribeAgentTask`, which would replace the task window's live
  stream.
- The run gate hides for a task that is not OPEN or has no worktree, and is
  blocked by a held claim or an unfinished development continuation.
- GitHub line comments stay off on the user's own PR (`canReview` is false).
  **Send to agent** calls `localReview.create` with the task, as the task
  window does; the backend enforces its local-review entry rules.
- No findings are shown for a task host. Wording says "working copy", not
  "review working copy".

