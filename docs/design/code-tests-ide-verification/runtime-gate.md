# Native runtime verification

The production Code & Tests tab now uses the modular IDE for active worktrees
and a bounded read-only archive viewer for closed/noninteractive inspection.
CodeMirror and CodeGraph are removed. The execution boundary uses the real task
worktree, permits production/test edits in active reviews and preserves agent
behavior. An open IDE does not hold an exclusive mutation claim.

The sections below retain successive verification checkpoints; their earlier
pending lists describe those checkpoints. The current
[implementation checklist](../code-tests-impl-tmp.md) identifies remaining native
acceptance work. In particular, fixture UI and separate backend tests are not a
production end-to-end pass. Final Go/Rust debugging, larger semantic fixtures and
native lifecycle/resource measurements remain incomplete.

## Verified foundation

The implementation pins `@codingame/monaco-vscode-*` to **33.0.9**, which uses
Code OSS **1.121.0**, and pairs it with VSCodium REH **1.121.03429**. The client
uses the server's product commit for the remote handshake. The exact versions,
artifact checksums and license notices are in
[`runtimeManifest.json`](../../../frontend/src/ide/runtimeManifest.json).

[`ide-bundle.mjs`](../../../scripts/ide-bundle.mjs) assembles the runtime at build
time, checks every download's SHA-256 and the extracted package identities, and
retains extension licenses and upstream notices. It removes stock server
extensions and the extension gallery. The assembled Apple Silicon bundle is
about **528 MB**, excluding the frontend. Intel artifacts are pinned but have not
been run on Intel hardware. Go and Rust SDKs and Go's gopls/Delve are separate
toolchain prerequisites, not included in that size.

The Vite 5 TextMate failure is fixed by a narrowly scoped import transformation
in [`ideBuild.ts`](../../../frontend/ideBuild.ts). Its regression test compiles a
real Vite production bundle and executes the transformed import in a fresh Node
process. The original failed prototype remains recorded as historical evidence.

The library's default extension enablement service returns enabled for every
extension; `disabledExtensions` alone does not enforce the bundle. ByteQuay's
`ExtensionPolicy` checks the exact extension IDs and versions. The native proof
showed only the six curated language/test/debug extensions, local grammar/theme
contributions and the fixture extension. Stock Git and authentication extensions
were absent. This is an activation policy, **not a filesystem or process sandbox**.

## Native proof

A production Vite bundle ran in an Electron `WebContentsView` with Node
integration off, context isolation and the renderer sandbox on. It used an
isolated Electron session and temporary workspace/profile. A session-specific
protocol served the bundled page. A session-specific HTTPS handler served only
allowlisted, realpath-checked extension assets; no external HTTPS request was
forwarded. The resource loader expects HTTP(S) for fetched grammar resources, so
an arbitrary custom asset scheme does not work without another filesystem
provider. Neither the remote token nor backend credentials appeared in URLs.

The server's extension-directory metadata belongs in a writable runtime profile,
with links to immutable bundled extensions. Pointing `--extensions-dir` directly
at the shipped bundle caused the server to create `extensions.json` there.

| Check | Result | Remaining limit |
| --- | --- | --- |
| Native loading and highlighting | Curated extensions, editor and native panels loaded; Java colors visible with the CSP enabled | This is an isolated native fixture, not the signed/packaged ByteQuay application |
| Incoming callers | Java: `caller()` and its test; Go: `caller` and `TestTwice`; Rust: `caller` and `doubles` | Simple fixture; overloads, interfaces/traits, multi-module workspaces and two divergent Git worktrees remain unverified |
| Java tests | Stable Red Hat Java 1.56.0 plus Java Test 0.46.0 reported deliberate expected 7 / actual 6 failure with source location | Test discovery is lazy: Run Current File before discovery can report no tests |
| Java debug test | Real breakpoint pause, local value 3, stack, Step Over returning 6, and Stop | Use executable line breakpoints. A column breakpoint on the method name remained unverified and did not pause |
| Go and Rust tests | Earlier production-browser proof ran actual Go/Cargo failing tests and captured logs/locations | Rust's structured assertion text was empty; retaining raw output is required |
| Native Go/Rust debugging | Not verified | Go debugging encountered macOS authorization; the user explicitly left debugger verification pending. Rust native debugging also remains pending |
| Private cards/theme/history | Earlier fixture verified the actual React local card, theme switching and a real three-commit history provider | Backend integration, durable drafts, full finding cards and historical/merge/rename diffs are not complete |

See the [native Java debug screenshot](java-native-debug.png) and captured
[Java](java-native-results.json), [Go](go-native-results.json) and
[Rust](rust-native-results.json) caller results. These reports also contain prior
test runs from the same fixture; a Go/Rust caller report is not evidence that a
Java run in its history was a Go/Rust test.

[`native-probe-source.zip`](native-probe-source.zip) retains the small native
fixture and authored Java/Go/Rust sources. It excludes tokens, profiles,
dependencies, downloaded tools and build outputs. Its local paths must be adapted
when reproducing elsewhere. Assemble the pinned runtime first, provide the
language toolchains, build the fixture with Vite, then launch `electron.cjs` with
Electron. This proof deliberately trusts only its authored fixture; it is not a
production worktree launcher.

## Automatic project writes: verified behavior

The native fixture enabled `files.readonlyInclude: { "**/*": true }`. The editor
displayed its read-only indicator and rejected a typing attempt. Opening the
workspace nevertheless activated rust-analyzer, which executed the fixture's
`build.rs` during automatic project import. Without pressing Run, it created:

- `src/generated_by_import_probe.rs` inside the temporary workspace;
- `../import-write-outside-workspace.txt` inside the enclosing temporary fixture.

Both marker writes were deliberately confined to our disposable fixture. No
production repository or personal file was targeted. The reproduction source is
[`import-write-probe.rs`](import-write-probe.rs), with the
[observed result](readonly-import-result.json) and
[native read-only view](readonly-import.png).

This is expected language-tool behavior, not an assertion that rust-analyzer is
malicious: build scripts and procedural macros are part of Rust analysis.
The [rust-analyzer configuration](https://rust-analyzer.github.io/book/configuration.html#rust-analyzer.cargo.buildScripts.enable)
documents automatic build-script execution. Disabling it reduces analysis
coverage; moving `targetDir` only relocates build artifacts and does not confine
the build script's own writes.

The old Code & Tests editor's review save endpoint permitted test sources only.
That was an editor limitation, not a whole-review or agent permission policy.
Reviews already support source changes for experiments and verification. The
refactor removes the test-source-only editor restriction and uses the actual
worktree, with normal IDE import/build behavior. Existing explicit-save conflict
checks and execution controls still apply to their respective operations.
A Node extension host has direct filesystem and process access and does not call
the editor save endpoint; those guards must not be described as a sandbox.
[VS Code Workspace Trust](https://code.visualstudio.com/docs/editing/workspaces/workspace-trust)
also does not establish an OS filesystem sandbox. Trust was disabled only for
our authored fixtures; that setting must not be copied into production as an
ownership solution.

Theia was revisited after this result. Its
[extension architecture](https://theia-ide.org/docs/extensions/)
also runs VS Code plugins in a Node process. It offers useful product extension
points, but switching UI foundations alone does not prevent a language server's
build script from writing files. No Theia runtime pass is claimed.

## Execution boundary

Use a normal IDE session on the real task worktree. Active reviews allow source
and test edits, runs and debugging. Project import can execute build tools.
Do not add a shadow checkout, automatic edit reconciliation, or a session-wide
mutation claim. In particular, merely opening the IDE must not block an agent
or change its permissions. Keep existing coordination for explicit saves, runs
and Git operations, and detect external changes and dirty-buffer conflicts.

The earlier proposal treated the editor's test-source-only restriction as a
whole-review rule. The user corrected that interpretation; no execution-policy
approval is pending. The import-write experiment remains useful evidence about
actual extension behavior, rather than a reason to redesign the review workflow.
CodeGraph retirement still depends on replacing all of its consumers and testing
the shared semantic-query path.

## Resource observations

One native sample after loading Java and Rust, running Java tests and reloading
the view summed to **2693.9 MiB RSS across 16 processes**, including the standalone
Electron harness. JDT's JVM accounted for about 867 MiB and rust-analyzer 527 MiB.
This is not a steady-state benchmark, incremental ByteQuay memory, or physical
memory total: shared pages can be counted repeatedly, a renderer had reloaded,
and the fixture deliberately contains multiple languages.
The [process sample](native-memory.json) retains executable names and RSS values.

The stock server advertised a three-hour reconnection grace period; the fixture
uses zero. Reloads still require attention to extension-host and terminal
ownership. Normal fixture shutdown left no processes matching its runtime roots
in the observed check, but a DAP stop alone and a root-PID exit are not general
process-tree proofs. Production should reuse ByteQuay's existing
`ProcessSupervisor` recovery and verified-stop facilities.

Cold/warm timings, a stable idle sample, crash recovery and
final lifecycle measurements remain open. The measured footprint is sufficient
to reject any assumption that adopting VS Code services automatically uses less
memory.

## Repository checks for the foundation

- Backend `mvn verify`: passed.
- Frontend `npm run lint` and `npx tsc --noEmit`: passed.
- Frontend tests: all **155 files / 1498 tests passed** with four workers.
  The initial default-parallel run timed out in browser-heavy tests; the rerun
  used `npm test -- --maxWorkers=4 --minWorkers=1` after stopping IDE fixtures.
- `npm run package`: passed for macOS arm64.
- The pinned IDE bundle assembled successfully; production-bundle native
  interaction is recorded above.
- The frontend production dependency license check passes without widening the
  allowlist. The modular API's original `jschardet@3.1.4` (`LGPL-2.1+`) dependency
  is replaced with pinned `chardet@2.2.0` (MIT). A small adapter provides the
  result shape and candidate filtering used by the VS Code file service; the
  production Vite build selects that adapter. A regression test runs the actual
  file service from its built browser bundle and covers UTF-8, UTF-16 BOM,
  ASCII, binary data and unsupported candidates. Detection remains heuristic;
  unsupported legacy candidates keep the configured encoding, and explicit
  file decoding still uses VS Code's existing decoder. The native runtime has
  its own retained upstream/extension notices, separate from this npm check.

The app packaging check still packages the current production UI. It does not
mean the IDE proof has been wired into that application or that the open runtime
gate has passed.

The review editor now accepts production, test and project-file saves through
the existing review mutation path. Regression coverage includes Java, Rust, Go
and Cargo files, stale edits after an agent write, busy worktrees, ended reviews,
and path/symlink checks. No agent permission, turn, queue or runtime behavior was
changed by this correction.

## Integrated editor checkpoint

The application now packages a separate modular IDE renderer and a sandboxed
native view with a dedicated preload. The production Code & Tests tab has not
switched to it yet. Main validates a closed set of task/review actions, encoded
file saves and current parent context; the IDE renderer has no full desktop
bridge or backend session credential.

The renderer reuses the current ByteQuay file tree and icons, package grouping,
Changed files filter, private React review cards, and its own compact toolbars.
IDEA Light and Darcula presets and IDEA/VS Code keymaps are selectable. File and
tool panels resize with pointer and keyboard controls. Source and review drafts
are retained outside the lifetime of individual editor cards; source recovery
keeps conflicting external edits separately rather than overwriting them.

Native fixture checks exercised the current production renderer, real language
extensions, and temporary source files. Go file tests displayed the deliberate
failure with its message, line decoration and raw logs. Private card Reply and
Resolve worked with fixture callbacks. A real native Go save reached the encoded
save bridge with the expected content hash. This caught and corrected a delayed
VS Code service proxy retaining a cached original write method; the adapter now
updates both the underlying method and that cache. Unit coverage retains this
regression, encoded-byte preservation, stale saves and create-only writes.

View ownership checks cover deduplicated opens, parent/child IPC separation,
hidden-idle disposal, retention of dirty or busy views, and retrying failed
process cleanup before reporting closure. These tests use mocked Electron;
final packaged application lifecycle and resource measurements remain required.
The backend full verification passes. Frontend lint, type checks, 1,519 tests,
and macOS arm64 packaging pass for this checkpoint; later additions retain their
own verification requirement.

Still required: production cutover, durable run/debug evidence and execution
coordination, selected-log agent context, final full-app history/blame and preview-strip
acceptance, agent and GoalPlanner query migration, CodeGraph
removal, and final native lifecycle/resource verification. Native fixture saves
verify the renderer adapter; the backend claim/save behavior is covered by the
separate service tests, not claimed as a single full-app end-to-end test here.

## Code preview, blame and local history

The modular editor now includes an optional, colored author/date gutter. Its
line numbers use Monaco's line layout, so annotations follow the same scrolling
and folding as the source. Repository-controlled metadata is escaped before
Monaco's HTML line-number rendering. Blame is tied to the saved content hash and
revision; dirty or stale buffers do not retain those annotations. Saved local
changes are identified as uncommitted. Keyboard users can open the selected
line's details from the toolbar.

A read-only Monaco preview shares the source model and syntax services. The
right overview/scroll strip maps pointer position through document line offsets,
including folded ranges and review-card space. The toolbar offers the same
preview with a line input and a jump action. Native verification exercised a
long Go source file, previewed line 170, jumped there, and confirmed its blame
metadata identified line 170. The colored gutter, native highlighted preview,
commit details and parent-to-commit diff were visually inspected. Native pointer verification also previewed line 219 while the caret stayed on
170, then clicked the strip and confirmed the caret reached 219 through its
line-history details. A longer pointer drag did not change the caret. The native
scrollbar uses pointer capture, so the click observer listens before that
handler and reads the mounted editor bounds when the gesture starts. The
[combined blame and hover view](code-preview-blame.png) records the rendered
result. The line mapping and metadata escaping have runnable regression checks.

Blame opens full local commit history, including commits older than the task
comparison base. The bottom panel uses the native SCM graph with a read-only
provider over ByteQuay's existing bounded Git reader. Repository and current-file
history use exact revisions and paging; file history follows renames. Native
snapshot/diff editors handle the selected parent, renamed paths, additions and
deletions. Stock Git mutation commands remain disabled.

Real-Git backend checks cover Java/Go/Rust blame, repeated commit metadata,
uncommitted lines, untracked files, links, path traversal, root commits, Unicode
renames, deleted files, both merge parents, dirty/detached worktrees and paging
while HEAD changes. They verify unchanged Git status and HEAD. The focused
backend gate passes all 40 ReviewCodeService tests. Native fixture checks opened
an actual commit from blame, inspected its read-only diff, and expanded the root
commit in the reused graph. The fixture still uses a temporary local transport;
these checks do not claim production tab cutover or full-app backend integration.

Native graph file history does not advertise repository HEAD as its current
item: HEAD may not have touched that file, and the pinned graph's reveal loop
otherwise searches indefinitely for an absent item. Regression coverage also
checks revision-pinned page requests and immutable rename/deletion snapshots.

The full backend verification passes (2,575 app tests plus the other reactor
modules). Frontend lint and type checks pass; the full suite passes 1,524 tests
in 160 files, with the subsequent focused navigation/history gate passing all
16 tests, including the new history-provider test. The final macOS arm64 package
build passes. The production tab still awaits the remaining integration gates.


## Shared semantic query transport

The native IDE now accepts a closed set of semantic requests against the same
models and language providers used by its editor. Operations cover workspace and
document symbols, definitions, references, implementations, incoming/outgoing
calls, and published diagnostics. Queries name a repository-relative source file
so its language can activate without opening a visible editor. Positions use
one-based UTF-16 columns; responses contain at most 200 locations and 40 call
sites per symbol. Locations outside the worktree are omitted.

An authenticated, versioned backend queue delivers requests only through Electron
main to the owning isolated view. Main starts after backend readiness, reuses the
worktree's existing runtime and gives active requests an idle-disposal lease. A
query-only runtime can later acquire the visible review context. Request removal,
cancellation, timeout and view shutdown cancel provider work; late replies cannot
complete a request for another task. Runtime startup failures return an explicit
unavailable result. An agent query does not take a worktree mutation claim.

The backend revalidates checkout ownership, HEAD and the existing working-copy
content fingerprint before returning a result. Source and returned locations must
remain inside the real worktree; symlink escapes and Git internals are rejected.
The adapter refuses unsaved source buffers, reloads clean open models before a
query and invalidates results if source models change during it. Provider readiness
and language support are explicit. READY means the provider answered for its
imported projects; it is not a guarantee of complete indexing. Empty diagnostics
are not a passing build or test result.

The [native result record](semantic-query-results.json) contains successful Java,
Go and Rust symbol/caller queries with no visible editor context. Go definitions,
outgoing calls and cross-language symbol search also answered; XML reported
unsupported. The check used the built renderer and real pinned extensions through
the isolated preload. Backend queue and Electron routing were checked separately,
including task binding, source edits without a new commit, symlink escapes,
concurrency limits, cancellation, idle disposal and reuse by a visible review.
The temporary native runtime and its language-service children exited afterward.

This delivers the shared query path, not CodeGraph retirement: agent tool schemas,
GoalPlanner evidence, remaining UI consumers and graph-specific lifecycle/settings
still need migration before deleting the old engine. Production CodeTestsTab and
the run/debug ownership integration remain outstanding.

Shared-query verification passes the full backend reactor, frontend lint and
type checks, all 1,530 frontend tests in 162 files, and macOS arm64 packaging.
The final focused check also covers cancellation after the editor renderer has
already crashed. No native fixture processes remain running.


## Agent query integration

The Codex and Claude task bridge now exposes `code_query` with the same validated
request contract as the IDE queue. The old `codegraph_explore` bridge and its
natural-language graph instructions are removed. Fixed operation, relative-path,
position and result limits are enforced before dispatch. Task identity comes
from the provider session; callers cannot choose another task or invoke arbitrary
IDE commands. Review child guards advertise the new read tool, while their
mutation restrictions and helper/DeepSeek exclusions remain unchanged.

Agent callbacks return task/revision/fingerprint evidence from the shared queue;
startup and service failures remain fail-open. They do not depend on the old
CodeGraph task flag or eagerly create a second index. Provider fixtures exercise
both adapters, redaction, malformed/extra arguments, task binding, child guards
and failure fallback. The focused backend gate passes 337 tests; the application-context and stored-task
composition checks also pass. This is bridge
and native-provider evidence from separate checks, not a real-model end-to-end
agent execution. The legacy UI and GoalPlanner still use CodeGraph pending the
remaining migration.


## Goal baseline evidence

`GoalPlanner` now uses `GoalSourceContext` for lesson and course-map evidence.
It reads the already provisioned repository's monitored remote/default-branch
commit, then bounded fixed-string Git matches with nearby source lines. It does
not fetch, create a worktree, start an IDE or maintain an index. The prompt calls
these text excerpts rather than semantic relationships, includes their exact
commit and truncation, and falls back to saved knowledge notes when unavailable.

Real Git verification covers fork/upstream routing, a different local HEAD,
dirty and untracked files, missing baselines, absent matches, bounded Unicode
output and unchanged checkout state. The full backend `mvn verify` gate passes,
including the agent-query migration and application composition. Goal planning
no longer consumes CodeGraph. The legacy editor, its status/settings and graph
lifecycle still need removal when the production IDE cutover is ready.


## Native execution coordination

The production renderer now routes native Test Explorer, debug launch, task menu
and task rerun dispatch through the existing ByteQuay local-run claim. Saving
precedes launch. Paused or restarting debug sessions retain the claim. Test
controller launch errors are retained even when the upstream UI only displays a
notification. Native test IDs, outcomes, durations, failure messages and source
locations are normalized into existing durable run records. Application/debug
completion is distinct from a passing test.

A temporary native Electron fixture used the production renderer and preload,
the pinned server/extensions and real Java, Go and Rust projects. It observed:

- Java, Go and Rust test failures with structured outcomes and source locations;
  Go and Rust also produced captured build/test output.
- Java test debugging paused at a breakpoint, displayed variables and call stack,
  stepped into application code and stopped. A private React review card remained
  usable in the same editor.
- Java application launch produced output in its execution terminal. Completion
  closed the terminal shell created by the execution while preserving the shell
  already open before it. Its adapter did not supply an exit code in that mode;
  the record therefore retains an unknown exit code.

The fixture supplied the execution IPC responses; these observations do not
constitute an end-to-end test against the production backend. Backend tests cover
claim ownership, source identity, output retries, interrupted sessions, uncertain
process tracking and delayed cleanup. Frontend tests cover remote adapter event
capture, launch failure retention, output sequencing and native result bounds.
Go and Rust debugger acceptance and final application integration remain open.

Completion compares observed descendant process identities with the IDE's
pre-execution baseline. The persistent PTY host is warmed before that snapshot.
Finished protocol sessions keep the claim until new observed processes have
exited. A newly created long-lived import/build daemon is conservatively treated
as part of that execution; explicit Stop code tools stops the shared runtime when
cleanup cannot otherwise be proved. This is process observation, not operating
system containment or a promise to detect escaped, unobserved descendants.


## Active Code & Tests cutover

Active reviews and interactive development tasks now open the isolated modular
IDE from the production tab. The existing task/WorkBench shell retains the view
when switching tabs or opening Subagents. Review reset/discard uses the existing
backend mutation and clears source drafts only after it succeeds; cancellation
and backend failure preserve them. Private reply drafts remain separate.

The bottom tools include native Tests and Debug, durable Run history, Git history,
and the existing ByteQuay terminal. Native execution terminals remain available
under Execution output. Run history renders ANSI output through xterm, displays
normalized failure details, and prepares selected output or failure text in the
existing conversation composer with run/source identity. Preparing a draft does
not start, queue or steer an agent turn. Java Markdown failure messages use the
pinned native plain-text renderer, preserving text without executable links.

Browser checks cover reset success/failure/cancellation, retained native-view
identity across task navigation, and appending evidence to the real composer
without submitting it. Main-process tests cover terminal ownership and blocking
idle disposal while that view owns an interactive shell. Closed reviews and
noninteractive tasks still use the legacy viewer; deleting it and CodeGraph
remains part of the refactor, not a completed migration.


The packaged native fixture confirmed readable Java expected/actual and stack
messages, failure-to-draft handoff, interactive shell input, ANSI colors, and
Darcula switching without losing terminal output. The fixture uses the packaged
node-pty module through its archive path; directly loading its unpacked path
incorrectly duplicates node-pty's own archive-path translation. The full frontend
gate passes lint, TypeScript, 1,543 tests in 163 files and macOS arm64 packaging.
The backend is unchanged since the passing native-execution reactor gate.


## Legacy editor and graph retirement

Active worktrees use the production modular IDE. Closed/noninteractive inspection
uses the bounded archive source view with shared cards and retained run logs.
CodeMirror/Lezer packages, custom Java navigation/test discovery and graph settings
are removed. Agent queries use the IDE; GoalPlanner uses baseline Git excerpts.
Backend graph services, eager lifecycle hooks, flags, counters and symbol routes
are deleted. The task tool transport keeps its authorization and secret handling
while replacing the obsolete graph-specific token variable name.

The frontend retirement gate passes lint, TypeScript, 1,511 tests in 160 files and
macOS arm64 packaging. The backend retirement passes the complete `mvn verify`
reactor. Probe script syntax and `git diff --check` pass. Native fixture processes
were checked after shutdown and none remained. These results do not complete the
remaining debugger, production-backend composition or resource acceptance gates.
The development SQLite compatibility consequence is documented in the canonical
Code & Tests design; no user database was reset.


## Native execution history cleanup

The Maven user-launch endpoint and IPC transport are removed. Agent command
mirroring and stored run evidence remain. Run history now reads active IDE
output through the production service and reloads compacted final output when
a run completes. Host ownership, stop routing and final-output regression checks
pass. The full backend reactor passes `mvn verify`; frontend lint, TypeScript,
1,511 tests in 160 files and macOS arm64 packaging pass.

The user left macOS-gated debugger verification pending. The isolated debugger
fixture was closed, and none of its 17 recorded owned process identities remained.
This verifies fixture shutdown, not yet the production idle lifecycle.
