# Native runtime verification

The design is committed as `0aaf03806`. Implementation has started in
`codex/modular-code-tests`; the production editor and CodeGraph have not been
replaced. Final integration and lifecycle verification remain incomplete. The
execution boundary is decided: use the real task worktree, allow production and
test edits during active review, and preserve existing agent behavior. An open
IDE does not hold an exclusive claim against the agent.

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
| Native Go/Rust debugging | Not verified | Go debugging encountered macOS authorization; the user was asked to handle it. Rust native debugging remains pending |
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

Cold/warm timings, a stable idle sample, hidden agent queries, crash recovery and
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
