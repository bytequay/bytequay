# Code & Tests

Code & Tests is ByteQuay's task-bound IDE surface in [WorkBench](workbench.md).
ByteQuay owns the layout, file tree, review cards, agent actions and durable run
history. Monaco and selected VS Code services provide editing, language
navigation, tests and debugging. The [IDE integration design](code-tests-ide-refactor.md)
records the foundation and ownership decisions.

## Hosting and availability

A task WorkBench always opens its own managed worktree. A review opened by ID
uses the reviewer's worktree. Starting a linked review does not switch the
developer's editor to the review checkout. Opening this tab never creates a
worktree or starts an agent turn.

Active reviews and interactive OPEN tasks use `IdeWorkingCopy` and
`IdeSurface`. A dedicated Electron `WebContentsView` hosts the editor page;
`webview` and `BrowserView` are not used. Closed reviews and noninteractive task
inspection use `ArchivedCode`: a bounded, highlighted, read-only source view,
the same review cards and retained run logs, without starting an IDE. Its source
pager displays at most 500 lines at once. Historical review source can fall back
to the recorded Git revision after checkout cleanup; task inspection does not
invent a revision fallback for a missing worktree.

Production and test source are editable during active review. Existing backend
idle/ownership checks still govern explicit saves, reset/discard and execution:
active agent work, queued input, an existing mutation claim, development
automation or recovery can refuse those operations. Reading remains available
where the underlying checkout/revision exists. An open editor itself takes no
mutation claim and does not change agent permissions.

Changes remains the shared diff/review surface. Code & Tests reads the actual
working copy, including local experiments. Review findings and comments retain
their recorded revision even when the checkout advances.

## UI ownership

The editor page is a React composition, not a full VS Code window:

- The existing compact file tree, folder/file/test icons, package grouping,
  All files/Changed files filter and resizable file column remain ByteQuay UI.
- Monaco provides editor tabs/models, completion, syntax/semantic colors,
  diagnostics, formatting, search, definitions, usages and call hierarchy.
  The toolbar exposes file, symbol and text discovery and navigation history.
- ByteQuay positions the existing private discussion, finding, pending-comment
  and GitHub thread cards beside the source. It does not render a second HTML
  version of their content.
- Bottom tools contain native Tests and Debug, durable Run history, Terminal,
  Execution output and Git history. There is no stock activity bar.
- IDEA Light and Darcula-style presets apply across editor, native panels,
  cards and terminal. A scoped IDEA-like keymap or VS Code keymap is selectable.
  These presets do not promise every IntelliJ interaction or shortcut.

Additional ByteQuay controls, such as notifications or agent actions, belong in
this React composition. They do not require a fork of the language server or
replacing the editor engine. No notification workflow is introduced by this
refactor.

## Runtime and lifecycle

The pinned runtime manifest defines a matched modular frontend, Code OSS-compatible
server and curated Java, Go and Rust extensions. The build checks artifact hashes
and package identities and retains licenses. Stock server extensions and the
general extension gallery are excluded. Runtime activation accepts only the
curated IDs and versions. Toolchain prerequisites and import failures remain
visible; ByteQuay does not silently install a system JDK, Go or Rust SDK.

`IdeViews` in Electron main owns one lazy view per task/worktree identity.
`IdeRuntimeService` launches the matching local server through the existing
process supervisor as an `IDE` owner. The server binds to loopback with an
unguessable connection token. Backend credentials and runtime connection details
are not supplied by arbitrary renderer requests. Typed bridges validate sender,
task, session and context before routing domain actions.

Java/JDT LS, Go/gopls and Rust/rust-analyzer share their imported project state
between the editor and agent queries for that worktree. Different worktrees
have independent runtime profiles and language state. Indexing can execute
project build tools and write files, as in an ordinary IDE. Extension policy and
editor save guards are not a filesystem or process sandbox.

Hidden views remain attached to a native host with background throttling disabled
so initialization and agent queries can run. If the macOS main window is closed,
one lazy invisible host retains these views; it is destroyed when the last IDE
closes. Queries wait for language-extension registration before creating source
models. A fresh Electron session registers resource handlers without first
unregistering handlers that do not exist.

Main checks every 15 seconds. A hidden view is eligible for disposal after two
minutes only when it has no dirty source/reply drafts, busy operation, interactive
ByteQuay terminal, pending action/query or execution. Visible editors remain
active even when the agent is idle. Backend heartbeats expire after 90 seconds
when the desktop abandons a runtime. Shutdown, checkout deletion and failed
startup use verified process-tree cleanup. Unverified cleanup retains ownership
and can require the existing task recovery action; it is never reported as a
successful close. Disk profile/cache eviction is separate follow-up work.

Source drafts and navigation are retained outside individual editor components.
Recovery checks the original content identity: a conflicting draft is offered
separately rather than overwriting newer disk contents. Private reply drafts
have separate storage and survive source reset. Storage failure is shown and
keeps the view dirty. An interactive terminal or paused debugger is not stopped
merely because its agent became idle.

## Source reads and saves

Both review and task code routes resolve the canonical managed worktree on each
request. File APIs reject Git internals, path traversal and symlink escapes;
source reads/listings are bounded and identify truncation. Reads validate the
expected revision so a checkout change cannot pair new text with old anchors.

The native file adapter preserves encoded bytes and sends a save with the
expected content hash to the backend. Save rechecks task/review ownership and
the existing worktree mutation gate before writing. An external edit produces a
conflict, preserving the draft. Create-only writes cannot overwrite an existing
file. Native language tools may write independently of editor saves; file
watchers and clean-model reloads observe those changes.

A remote review's stale-worktree indicator opens the existing **Discard and
reset** confirmation. The backend rechecks the expected head, idle state and Git
operation state. Only successful reset clears source drafts and rereads files;
cancellation/failure preserves them. Review experiment discard remains a review
operation. Development tasks do not gain a destructive reset/clean action.
Ending a review still follows its uncommitted-work and completion rules.

## Review cards and agent actions

The same `LocalReviewThreadCard`, `ReviewFindingCard` and comment composer serve
Changes and Code & Tests. Monaco view zones reserve space; interactive React
cards live in accessible overlay widgets. Placement uses revision, excerpt and
line-range checks. Dirty/stale source does not acquire misleading inline cards;
unplaced or obsolete evidence retains its existing treatment.

GitHub line publication is available only when the displayed content matches
the reviewed revision and every selected line belongs to the appropriate PR
diff hunk. Existing can-review, current-head and direct-user-action checks stay
in force. Private local discussion can refer to saved working-copy lines outside
the PR diff; its context identifies uncommitted content rather than assigning
it to a false commit. Source drafts must be saved or reverted before sending
those lines as evidence.

Run history can place selected output or failure details in the existing agent
composer with task, run, source revision, fingerprint and selector. This prepares
an unsent draft. It does not start, enqueue or steer an agent turn. Historical
output remains usable after the runtime stops. Selection text is evidence, not
trusted instructions or executable markup.

## Tests, running and debugging

Curated language extensions own test discovery, profiles and debug adapters.
ByteQuay uses the native Test Explorer, failure/location UI, breakpoints,
variables, watch, stack and console. Discovery readiness is distinct from an
empty test suite. A provider error or missing tool is shown directly.

Explicit Test Explorer runs, debug launches and task dispatch/rerun pass through
one execution coordinator. It saves first, then obtains the existing `LOCAL_RUN`
worktree claim before dispatching to extensions. Nested test-debug operations
share that execution. A paused or restarting debug session retains the claim.
External-process attach and continuous test profiles are not enabled.

`IdeExecutions` persists existing `LocalRun` records, source identity, normalized
test rows and bounded output. Output frames are sequenced and retried without
silently duplicating accepted data. Failure messages, locations and raw logs are
retained; Java Markdown failure text is rendered as plain text. An application
completion is not a passing test, missing test results are not success, and an
unknown adapter exit code stays unknown.

The IDE server owns adapter/build descendants; the `LOCAL_RUN` claim coordinates
the working copy, not a second server. Before dispatch the backend records the
IDE's observed process baseline. After protocol completion, the claim stays held
until new observed descendants are gone. A newly created import/build daemon is
conservatively part of that execution. Execution-created terminal shells close
at completion; pre-existing shells remain. **Stop code tools** can stop the shared
runtime when ordinary completion cannot establish quiescence. The runtime limit
is one hour. Crash, interrupted records and uncertain termination preserve the
existing recovery path. Process observation is not OS containment and cannot
promise discovery of escaped, unobserved descendants.

Existing agent Maven-command mirroring remains separate: it observes completed
review-agent commands, records bounded output and reads fresh Surefire reports
within the command window. It changes no agent tool or execution behavior,
creates no second claim and does not guess results for other command types.
Background Claude commands and unknown Maven outcomes do not become passing
runs. Retained old runs remain readable. The unused Maven user-run endpoint and
its transport are removed; all new user executions launch through native
profiles. Run history reads the live IDE output buffer and reloads the compacted
final output from offset zero when the run settles, preserving final failures.

## Terminal

The interactive Terminal reuses ByteQuay's `CodeTerminals`, node-pty and xterm.
Main validates the owning task/review before resolving its cwd, creates one PTY
per host and owning webContents, strips `BYTEQUAY_*` environment variables and
starts the user's login shell. The renderer receives a terminal identity rather
than choosing an arbitrary working directory. Input and resize require the
owning sender. The bounded scrollback survives panel switches.

Unmounting a panel detaches its view; it does not silently kill the shell.
Explicit task/review completion, checkout cleanup, owner destruction and app
shutdown stop owned terminals. Terminal commands are ordinary user shell input;
they do not automatically become test records. Native execution output has its
own panel and belongs to the coordinated execution.

Packaging keeps node-pty external to the main bundle, copies the target native
prebuild and ensures the spawn helper is executable in the unpacked archive.
The runtime proof includes actual keyboard input and ANSI output using the
packaged module.

## Code overview, blame and Git history

The right overview/scroll strip previews the corresponding source in a read-only,
syntax-highlighted code card on hover without moving the caret. A click jumps
there. Folded ranges and review-card spacing participate in line mapping. The
toolbar exposes a keyboard-accessible line preview and jump. This is document
navigation, not an additional code index.

Blame adds an optional colored author/date gutter at the left. Annotation layout
follows Monaco lines, scrolling and folding. Repository metadata is escaped.
The result is bound to revision and content hash; dirty or stale buffers discard
it, and saved uncommitted lines are identified. A gutter selection or the toolbar
opens the line's commit details.

The bottom Git history uses the native SCM graph with a bounded, read-only
provider over existing ByteQuay Git services. Repository/file history is paged
at an exact revision; file history follows renames. Parent-to-commit diffs and
snapshots support root commits, merges, renames, deleted files and Unicode paths.
History navigation does not checkout, fetch, mutate refs or write to GitHub.
Stock Git mutation commands are not enabled to obtain this UI.

## Shared code queries and retired code

The fixed task-bound `code_query` tool uses the same runtime as the editor for
bounded symbols, definitions, references, implementations, incoming/outgoing
calls and diagnostics. Source paths are relative and positions are one-based
UTF-16 columns. The backend queue checks task/worktree identity, revision and
content fingerprint before returning a result. Main can start a hidden view for
a query and later attach the visible editor to it. Cancellation and timeouts
release the request lease; late replies cannot cross tasks.

Queries refuse unsaved source state, reload clean models before dispatch and
invalidate results if source changes during evaluation. READY means a provider
answered for its imported projects, not that indexing is globally complete.
Unavailable/unsupported results are explicit; agents can continue with ordinary
source/text reads. Empty diagnostics are not evidence of a passing build.

Goal planning uses bounded Git text excerpts at the fetched monitored baseline,
with exact commit and truncation; it does not create an IDE or semantic index.
CodeGraph, its tool alias, eager prepare/cleanup hooks, settings, metrics, flags
and packaging are retired. CodeMirror and custom Java symbol/test discovery UI
are removed. No second persistent graph is kept.

## Verification and development data

Standard gates are backend `mvn verify` and frontend lint,
TypeScript, tests and packaging. Mocked Electron/backend checks and an isolated
native fixture are not described as a single production end-to-end pass.

Remaining acceptance boundaries are extended Java module/overload, Go interface
and Rust trait coverage; native Go/Rust debugging; and native test/debug execution
against the production backend. macOS Developer Tools authorization for the
debugger is not granted by this design. Fixture dispatch and evidence checks,
separate backend claim/history tests, and successful shared queries must not be
reported as completion of those boundaries.

Retiring the graph removes its JPA fields. An existing development SQLite schema
can retain a NOT NULL `agent_task.codegraph` column without a default, which
Hibernate update does not remove; that old schema can reject new task inserts.
Older CHECK constraints can also reject `IDE` in `agent_process_launch.owner_kind`
and the native execution scopes/statuses in `local_run`. Hibernate update does
not rewrite these checks. Check the existing schema before restarting an upgraded
local app; source integration alone does not make those databases compatible.
The repository has no released-data migration contract. Follow CONTRIBUTING's
existing `./dev.sh --reset-data` backup/reset procedure for an incompatible old
development schema. This refactor does not reset the user's database or install
migration-history machinery.
