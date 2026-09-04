# CodeGraph Service

## 1. Decision

Every repository-scoped agent task backed by a managed `TaskWorktree` may use
a code graph for structural code exploration. One worktree owns one
independent graph index for its lifetime. The graph is never shared across
worktrees because their committed and uncommitted contents may differ.

The agent decides whether, when, and what to query. ByteQuay does not select
graph context in advance. It provides one task-scoped tool, a managed usage
instruction, bounded output, and safe lifecycle integration.

Each task's index starts as a clone of the repository baseline, the index of
the remote default branch, prepared off the request path when the task is
created. Preparing a task costs about one second of CPU and a few megabytes.
The expensive builds happen once per repository, in the background, only
when a fetch shows the remote default branch gained commits. Section 13
records the measurements behind this decision and the first-version scope.

CodeGraph is an optional convenience. Expected CodeGraph operational failures
during baseline refresh, task preparation, query, or deletion must not fail
task creation, reject a turn, change an `OPEN` agent task to `FAILED`, retain
its mutation claim, project recovery required, or prevent the application
from using its normal source, search, command, compiler, and test tools.
ByteQuay programming errors and mandatory worktree, resource, and sandbox
failures remain visible.

## 2. Boundary

`WorktreeService` remains responsible only for provisioning, inspecting, and
cleaning Git worktrees. It does not create graph indexes, start graph
processes, interpret graph results, or expose graph tools.

`CodeGraphService` owns:

- initializing and incrementally refreshing one graph for a verified
  `TaskWorktree`;
- running bounded structural queries;
- serializing refresh and query work for the same task;
- deleting the derived graph index after successful worktree cleanup.

`CodeGraphService` is application-scoped and holds no durable per-worktree
registration. ByteQuay does not construct and retain a service object for
every worktree; one service may operate on many worktrees, while each
worktree's derived index remains isolated. The index is the per-task lifecycle
resource, not the Java service instance.

The service receives trusted `TaskWorktree` and task-resource values from the
application. Agent-facing tools never accept a task ID, repository path,
worktree path, commit ID, or graph database path.

The graph subsystem knows nothing about pull requests, diffs, anchors, review
findings, GitHub comments, or workflow-specific policy. Those may consume the
generic graph capability in a later design.

## 3. Storage and identity

The persisted `AgentTask` already owns the immutable `TaskWorktree` facts, so
ByteQuay adds no code-graph table, lifecycle state, or repository mapping.
The task ID deterministically identifies graph storage under the existing
owner-only task runtime:

```text
<task-runtime>/<task-id>/codegraph/
```

The repository baseline that every task index is cloned from lives beside
the task runtimes:

```text
<agent-runtime>/codegraph-baselines/<owner>/<name>/codegraph/
<agent-runtime>/codegraph-baselines/<owner>/<name>/HEAD
```

The source root is the task's canonical worktree. The index directory is
outside the Git worktree so graph files cannot make the worktree dirty, be
committed by the agent, or weaken `WorktreeService` cleanup checks.

The selected graph implementation must support separate source and index
roots. If the upstream package still requires `.codegraph/` beneath the
source root, ByteQuay must use an audited pinned adapter or patch that adds an
external index directory. The initial production design does not reserve,
ignore, or hide a graph directory inside the worktree.

Graph files are derived cache. On an incompatible version, invalid ownership
metadata, or corruption, ByteQuay may discard and rebuild them. The exact
dependency version is pinned; updates do not reuse an index unless the new
version explicitly supports it.

No in-memory registration is authoritative. After an application restart,
ByteQuay reconstructs the graph context from the persisted `TaskWorktree` and
derived task-resource path.

Upstream's own git-worktree support, read from the 1.6.0 source, is
detection only. It resolves an index by walking up from the query path to the
nearest `.codegraph` directory, and a linked worktree without its own index
silently borrows the main checkout's; since 1.6.0 it detects that case with
`git rev-parse` and warns. It never shares, links, or clones an index between
worktrees, and its expected model is one index per worktree. ByteQuay's
per-task external directory therefore matches upstream's model and removes
the borrowing failure mode entirely, because the helper always names the
worktree explicitly and never walks up.

During the managed-worktree rollout, a task whose persisted
`taskWorktree()` is empty receives no CodeGraph capability. ByteQuay never
adopts or reconstructs worktree ownership from the task's legacy bare path.

## 4. Service contract

The logical application contract is deliberately small:

```java
public interface CodeGraphService
{
    void refreshBaseline(RepositoryIdentity repository, Path repositoryDirectory, String defaultBranchCommit);

    void prepare(TaskWorktree worktree, TaskResources resources);

    String explore(TaskWorktree worktree, TaskResources resources, String query);

    void deleteIndex(TaskResources resources);

    void deleteBaseline(RepositoryIdentity repository);
}
```

`refreshBaseline` is idempotent: it returns immediately when the baseline
`HEAD` already equals the commit. Otherwise it takes the per-repository lock,
creates a temporary detached checkout of the commit, clones the current
baseline beside itself, synchronizes or rebuilds it by the size rule below,
renames it into place atomically, writes `HEAD`, and removes the checkout.
With no baseline it builds one from scratch. The application calls it after
every fetch that moves the remote default branch and from the scheduled
fetch job; it never runs on a request or turn path.

`prepare` waits for any refresh in progress on the task's repository, clones
the baseline into the task runtime with an APFS clone or a plain copy, and
synchronizes the clone against the task worktree. Without a usable baseline
it builds the task index directly and never writes a baseline: only a fetch
that moved the remote default branch does that, and the fetch that precedes
provisioning normally has. The application calls `prepare` on a virtual
thread after the task is persisted.

`explore` waits for a running `prepare` on the same task, bounded, then
synchronizes or rebuilds the task index by the size rule, runs the bounded
query, and returns capped text with an explicit truncation marker. Because
every query synchronizes first, the agent never refreshes anything.

The size rule is the one place git is consulted, and only to choose between
two correct operations: the index directory records the commit it was last
synchronized to, and `git diff --name-only <last> HEAD` plus
`git status --porcelain` count the changed files in about 0.1 s. At most
five percent of the indexed file count runs `sync`; more runs a full
`codegraph index`. Section 5 records the measurements behind the threshold.
Synchronization itself stays unscoped: the graph implementation's own
content check is the source of truth for what changed, so committed,
uncommitted, untracked, deleted, and rebased changes are all seen without a
caller-side freshness gate.

A usable-index check is internal to the adapter: a partial, corrupt,
incompatible, escaped, or wrong-worktree index is discarded and rebuilt,
never persisted as a state.

The first implementation uses a bounded short-lived graph process for each
operation and retains no open per-worktree handle or daemon. Add an
in-memory handle cache only if measurement shows reopening is material; any
such cache remains reconstructable and is never durable ownership state.

Expected operational failures use one narrow `CodeGraphException`. The
service does not swallow them; the refresh, prepare, tool, and cleanup
adapters catch that exception at the optional-capability boundary.
Programming errors remain visible rather than being mislabeled as graph
unavailability.

Any later application workflow that calls this port follows the same rule:
catch `CodeGraphException` at its optional-capability boundary, record the
bounded diagnostic, and continue without graph data. It must not catch
arbitrary `RuntimeException` or make CodeGraph a hidden prerequisite.

The port belongs in `agent-core`, where task and completion orchestration use
it. The first concrete adapter belongs in `app`; this feature adds no Maven
module or general-purpose tool framework.

The first adapter targets the pinned, audited 1.6.0 release of
[colbymchenry/codegraph](https://github.com/colbymchenry/codegraph) with the
five-line external-index patch recorded in the implementation tracker. Its
CLI, storage, and result types remain behind this port so ByteQuay can
replace it without changing agent or worktree contracts.

## 5. Lifecycle

### Baseline refresh

The baseline for a repository is the index of `refs/remotes/origin/<default-branch>`.
It changes only when a fetch shows that branch gained commits. Two things
fetch: the fetches ByteQuay already performs before provisioning a worktree
for an interactive task, CI Autofix, or cherry-pick, and one scheduled job
for repositories whose workspace has the graph on, at application start and
then hourly by default. After either, the application compares the fetched
commit with the baseline `HEAD` and calls `refreshBaseline` on a virtual
thread when they differ. The refresh is the only network-adjacent activity
the feature adds; it never runs on a turn path.

### Task creation

1. The owning task service provisions and verifies the `TaskWorktree`.
2. It allocates normal task runtime resources.
3. It persists the task and worktree facts.
4. It starts `prepare` on a virtual thread and returns.

The request never waits for the graph. An interactive task starts at the
fetched default-branch commit, so its clone needs a same-commit sync of
about a second. A CI Autofix or cherry-pick task starts on another branch,
so its clone is synchronized or rebuilt by the size rule. A failed or
interrupted `prepare` leaves nothing durable; the first tool call builds
lazily.

### During a turn

The agent may explore the graph at any time. `codegraph_explore` waits for a
running `prepare` on its task, bounded at 60 s, after which it returns
"index still building, retry". It then synchronizes or rebuilds by the size
rule and queries, so the agent's own uncommitted edits, a rebase, or a
checkout are all visible without any refresh step. Calls for one task are
serialized. A concurrent source mutation may make a synchronization fail
normally; the agent may retry or use current source tools.

There is no pre-turn refresh and no post-turn refresh: the synchronization
inside every query covers completed, failed, cancelled, interrupted, and
resumed turns alike.

### Task and worktree cleanup

Cleanup follows this order after provider calls are quiescent. Graph tool
callbacks use the same existing task-local execution lock as turn and
completion orchestration. Completion holds that lock and its completion
mutation claim across the worktree and graph cleanup calls, so no new graph
operation can start once cleanup owns the task boundary.

1. Attempt normal `WorktreeService.cleanup(worktree)`.
2. If worktree cleanup succeeds, request deletion of the task's graph index.
3. If worktree cleanup refuses or fails, skip immediate graph-index deletion.

Index-deletion failure is reported but creates no graph-specific state
machine. The derived index remains inside the task runtime and is removed
when the existing `AgentResourceService.delete(taskId)` retention cleanup
deletes that runtime. The repository baseline is deleted when its workspace
is removed; a staleness sweep is not part of the first version.

### Performance baseline and consequence

CodeGraph 1.6.0 was measured against an isolated clone of Trino commit
`ce0884ad4dbc2cc1f7f1e0fb644cd11612e92d0c` on an Apple M4 Max with 16 cores,
128 GB RAM, and local SSD storage. Trino contained 15,497 tracked files,
10,864 Java files, and about 1.93 million Java lines. CodeGraph indexed 11,440
supported files into about 360,000 nodes and 1.13 million edges.

| Operation | End-to-end wall time |
| --- | ---: |
| Initial full index | about 39–40 seconds |
| Existing index, no source change | about 0.49–0.54 seconds |
| Existing index, one small Java edit | about 0.94–1.06 seconds |

The resulting index occupied about 1.28 GB. These measurements are a reference
baseline, not a cross-machine service-level objective.

The resulting index occupied about 1.28 GB. These measurements are a reference
baseline, not a cross-machine service-level objective. They originally
justified a lazy per-task build; the measurements below replaced that with
the baseline design, because a 38 s first call is the common case on a
repository of this size, not a corner case. ByteQuay still adds no watcher,
daemon, or exact-path cursor.

### Measured on 2026-09-04 with the pinned bundle

The same machine, macOS 26.5.1, the pinned 1.6.0 darwin-arm64 bundle with
the five-line external-index patch, and the index outside the worktree.
Trino at the same commit reproduced the baseline: initial index 37.9 s and
1.28 GB, unchanged sync 0.48 s, one-file sync 1.8 s, `explore` 1.6 s and
about 25 KB. This repository, 873 files on a linked worktree: initial index
2.6–5.3 s and 111 MB, unchanged sync 0.14 s, `explore` 0.25 s.

**Default branch advanced and the task branch moved onto it.** The index was
left at `B0−N`, the worktree checked out `B0`, and one unscoped sync ran; a
rebase ends in the same working tree and costs the same.

| Repository | Commits | Files changed | Sync |
| --- | ---: | ---: | ---: |
| Trino, 11,440 indexed files | 10 | 72 | 9.6 s |
| Trino | 50 | 924 | 46.9 s |
| Trino | 200 | 1,365 | 59.3 s |
| Trino | 1,000 | 3,112 | 89.9 s |
| This repository, 873 files | 10 | 168 | 5.1 s |
| This repository | 200 | 607 | 8.2 s |

A full in-place rebuild with `codegraph index` took 42.1 s on Trino and
2.6 s here. Sync cost grows with the changed set far faster than the
one-file case suggests: above roughly three percent of the indexed files a
rebuild is cheaper. Sync also leaves garbage; eight syncs grew the Trino
index from 1.28 GB to 1.6 GB and one rebuild brought it back to 1.3 GB.

**New worktree or branch checkout.** Git copies nothing, because the index
lives outside the worktree and the database stores only relative paths; a
copied index served a different worktree path without complaint. A new
worktree starts empty and pays a fresh build. Seeding by copying works and
was timed on Trino:

| Seeding step | Time | Disk actually used |
| --- | ---: | ---: |
| APFS clone of the 1.3 GB index | 0.03 s | 8 MB |
| sync on the clone, same commit | 0.81 s | included |
| sync on the clone after 10 commits moved | 10.7 s | 603 MB |
| sync on the clone after 200 commits moved | 59.5 s | 1,381 MB |
| plain copy, for reference | 0.65 s | 1,585 MB |

A clone is free only until its first sync, so every task ends up owning a
full-size index either way. The time saving is real only when the seed sits
at the task's start commit: 0.84 s instead of 38.4 s on Trino, 0.1 s instead
of 2.6 s here. None of this costs model tokens; the cost is latency and disk.

**Same-size, same-mtime edits are detected.** An in-place rewrite that kept
inode, size, and mtime was reflected by the next sync; 1.6.0 keeps a per-file
content hash and uses `git status` as a fast path. The Section 4 caveat
stays as a floor, not a claim.

**Benefit.** Twelve headless Claude runs on this repository, three cross-file
trace questions, two repetitions per arm, every answer correct on all
required anchors. Medians per run: 4.5 tool calls and 25 s with the graph
against 9.5 and 37 s without, 139k against 174k input tokens. The deepest
trace needed 7–13 tool calls with the graph against 26–29 without. Details
are in the implementation tracker.

## 6. Agent tools

ByteQuay exposes exactly one logical tool:

```text
codegraph_explore(query)
```

It has one required bounded string. The task-scoped tool handler obtains the
canonical worktree and index directory from trusted application context and
synchronizes the index before every query, so there is no refresh tool.

Query output is capped at 16 KB and says when it was truncated; the upstream
`explore` returns about 25 KB per call. Graph resolution is navigation help
rather than compiler-grade proof; ambiguous or missing relationships do not
establish runtime behavior or correctness.

The tool surface has no status, initialization, refresh, full-rebuild,
configuration, project-path, multi-project, or deletion tool. Refresh,
rebuild, and cleanup are application-owned operations.

Tool failure is an ordinary recoverable tool result. The ByteQuay tool wrapper
remains available even when the underlying executable, index, or query
fails. It briefly explains that CodeGraph is unavailable and tells the agent
to continue with its normal source/search tools. The harness does not retry
indefinitely, fail the turn, or require the agent to use CodeGraph.

### Provider bridge

The current no-business-tool boundary in `agent-service.md` remains the
default; this design supersedes it only for this one optional tool on a lane
whose task-scoped bridge passes the reference compatibility fixture and its
running preflight. ByteQuay adds one narrow, optional task tool bridge to
`AgentSessionContext`, containing the fixed schema, its task-bound callback,
and the managed instruction. It does not add a model-controlled registry or
a general application-tool framework.

Both lanes reach the tool through the same ByteQuay task MCP endpoint, the
strict, capability-token-protected boundary that already serves Claude's
permission prompt. Claude sees the tool through the existing broker. For
Codex the pinned v0.151.0 bindings carry the `dynamicToolCall` item but no
dynamic-tool registration method, while Codex 0.152.1 supports
streamable-HTTP MCP servers with `bearer_token_env_var` and `http_headers`;
the per-task Codex config therefore points at the same endpoint, and the
Codex isolation check changes from "no MCP servers" to "exactly the ByteQuay
task server". `agent-core` owns the task binding and callback; `app`
supplies the concrete `CodeGraphService`. The model never receives the
callback token or filesystem identity.

Before constructing provider session configuration, ByteQuay starts and
self-tests the optional bridge and its fixed schema; this does not start the
graph executable or touch an index. If registration or self-test fails, it
omits the tool and its managed instruction and opens the normal provider
session. If the provider explicitly rejects that optional tool configuration,
ByteQuay discards it and retries the provider open once without the tool or
instruction. Unrelated provider failures remain provider failures. CodeGraph
is not part of `AgentDriver.probe()` or lane readiness, so an absent
executable or unsupported graph transport never disables a lane.

The initial version guarantees the bridge for the task's root agent only.
Provider-managed child agents continue with normal source tools unless a
later provider-specific compatibility test proves task-bound inheritance.

## 7. Managed instructions

ByteQuay supplies graph guidance through its provider-managed system or
developer instruction channel. It never edits repository `AGENTS.md`,
`CLAUDE.md`, or another project file, never installs global agent
configuration, and does not disguise managed guidance as user text.

The managed instruction is:

> ByteQuay provides an optional CodeGraph for the current task worktree. Use
> `codegraph_explore` when callers, callees, implementations, routes,
> dependencies, or cross-file structure would help. You decide whether and
> when it is useful. ByteQuay keeps the index current before every query,
> including your own uncommitted edits, so there is nothing to refresh. The
> first call in a task may take longer while the index is prepared. Graph
> results are navigation help; inspect current source and use compiler,
> test, linter, or runtime evidence when correctness matters. The task does
> not depend on CodeGraph. If the tool fails, continue with normal source
> and search tools. Do not install, configure, or upgrade CodeGraph
> yourself, invoke its CLI directly, or provide repository paths to it; use
> only the managed tool.

The instruction is versioned by ByteQuay and applied on initial and resumed
provider sessions only when the task-scoped tool bridge passed preflight and
the workspace's graph setting is on. A provider lane that cannot guarantee
the managed instruction and task-scoped tool boundary omits CodeGraph
without becoming unavailable.

## 8. Failure contract

| Failure | Required behavior |
| --- | --- |
| Baseline refresh | Keep the previous baseline; the atomic rename never exposes a partial one; log and retry on the next fetch that moves the branch |
| Task preparation | Task creation and turns are unchanged; the first tool call builds lazily and may retry |
| Explore synchronization, rebuild, or query | Return a recoverable tool error; agent continues |
| Corrupt/incompatible task index | Delete the derived index and return unavailable; a later call may rebuild from the baseline |
| Tool-bridge registration or preflight | Construct provider configuration without the CodeGraph tool or instruction and start the turn normally |
| Index or baseline deletion | Keep the successfully cleaned worktree or removed workspace result; task-runtime retention later removes a leftover task index |

No graph failure creates a task recovery mode. Repeated graph failure does not
cause an automatic retry loop. The graph may be absent for an entire task
without changing the meaning or outcome of that task.

## 9. Security and privacy

- Bind every operation server-side to one persisted canonical `TaskWorktree`.
- Give the graph process read-only source access and write access only to its
  task-owned index directory.
- Do not expose a model-controlled project path or cross-project query.
- Deny network access and disable telemetry, update checks, and downloads.
- Pin and verify the graph executable or library version.
- Bound query length, execution time, response size, and concurrent calls.
- Terminate and drain every short-lived graph process on timeout or failure.
- Keep secrets, task logs, provider runtime files, and other worktrees outside
  graph-readable roots.
- Record tool invocation and bounded output through the existing agent event
  and blob infrastructure rather than a new graph history store.

## 10. Deliberately deferred

The first version has no:

- live filesystem watcher or daemon;
- `codegraph_refresh` tool, because every query synchronizes first;
- baseline staleness sweep; the baseline is deleted with its workspace;
- persistent graph lifecycle/state table;
- renderer-facing graph management UI; the read-only counters of Section 12
  are the only renderer surface;
- `compare` arm or Settings comparison table (Section 12, CG7);
- automatic graph-context injection;
- review-specific evidence or anchor contract;
- multi-repository graph query;
- child-agent tool inheritance;
- model-controlled graph configuration; or
- Electron-hosted helper execution, which would save 117 MB of bundle.

Add one only after observed usage demonstrates that the first version is
insufficient.

## 11. Acceptance criteria

1. Two tasks for the same repository use distinct indexes bound to their exact
   managed worktrees, both cloned from the same baseline.
2. Graph files never appear in Git status and cannot block a clean worktree's
   normal cleanup.
3. A query reflects committed and uncommitted additions, modifications, and
   deletions, and a rebase or checkout that moved the base, without any
   caller-provided freshness gate; git is consulted only to choose between
   `sync` and a full rebuild.
4. An unavailable graph does not block task creation, turn start, provider
   resume, task completion, or unrelated application behavior.
5. The tool exposes no filesystem or task identity parameters and remains
   bounded to the current task; its output is capped and marks truncation.
6. The managed instruction is applied without modifying repository or global
   provider instruction files.
7. After restart, ByteQuay can reopen or rebuild a task index from persisted
   `TaskWorktree` facts, the derived task-resource path, and the baseline.
8. A task without a persisted `TaskWorktree` receives no graph tool and no
   graph managed instruction; a legacy bare path is never adopted.
9. Successful worktree cleanup requests task-index deletion; failed worktree
   cleanup skips that immediate deletion, and normal retention may later
   delete either derived index with the task runtime. Removing a workspace
   deletes its baseline.
10. Missing graph binaries and refresh, preparation, and query failures
    become bounded unavailable/tool-error results without terminating the
    provider turn or changing lane readiness.
11. Tool-bridge preflight failure starts the provider turn with the graph
    tool and the graph managed instruction omitted.
12. Completion owns the task-local execution boundary across worktree cleanup
    and best-effort graph deletion, so no graph query races worktree removal.
13. Graph deletion failure is observable but does not fail already-successful
    agent or worktree behavior or add graph-specific retry state.
14. No code-graph type depends on pull-request review, GitHub, diff, or comment
    types.
15. A task that never calls the graph costs one clone and one same-commit
    synchronization, about a second, and never a full build.
16. The baseline changes only when a fetch shows the remote default branch
    gained commits; tasks never modify it; a refresh replaces it atomically
    or leaves it untouched.
17. With a current baseline, the first graph call in an interactive task
    costs the query alone.
18. The scheduled fetch never runs on a turn path and is skipped for a
    workspace whose graph setting is off.
19. The Section 12 counters fold from persisted events, so replay and
    recovery produce the same numbers.
20. Performance tests record version, repository commit, hardware, wall time,
    and index size without turning one reference machine's numbers into brittle
    cross-machine assertions.

## 12. Benefit measurement

Status: approved 2026-09-05 in the first-version form of Section 13 (the
`on`/`off` setting and counters); the `compare` arm and the Settings table
follow as CG7.

CodeGraph earns its cost only if a turn that can use it needs fewer tokens,
fewer exploration calls, and less time than a comparable turn that cannot. No
single task can show that, because a task never runs twice. ByteQuay therefore
records the same per-turn cost counters for every turn and lets the user
compare tasks that had the graph with tasks that did not, inside one
workspace.

### Arm

A `codegraph` setting selects `on` (default), `off`, or `compare`. At task
creation ByteQuay stamps an immutable `codegraphArm` of `ON` or `OFF` on the
task: `on` and `off` map directly, and `compare` assigns each new task at
random. Every turn of a task, including resumed turns, offers the tool bridge
and managed instruction only when the arm is `ON`; `OFF` uses the existing
omission path and therefore never builds an index. Changing the setting
affects new tasks only. Tasks created before the setting existed have no arm
and are excluded from comparison.

### Counters

`TurnMetrics` gains five counters folded from that turn's persisted events
when the turn reaches a terminal state, so recovery and replay produce the
same numbers:

| Counter | Source |
| --- | --- |
| `toolCalls` | terminal `ToolActivity` and `CommandActivity` events |
| `exploreActions` | `CommandAction` kinds `READ`, `LIST_FILES`, and `SEARCH`, plus Claude `Read`, `Grep`, `Glob`, and `LS` tool activity |
| `codegraphCalls` | terminal `ToolActivity` named `codegraph_explore` |
| `codegraphMillis` | their summed `durationMs`; the first call's index build is included deliberately |
| `codegraphFailures` | those that ended in a failed state |

Child-agent events count toward the turn that owns them. Task totals sum the
turn counters through the existing totals path. No new event kind, table, or
history store is added.

### Presentation

- The task metrics footer and each finished turn's summary line append tool
  calls, exploration actions, and either the graph calls with their seconds or
  `CodeGraph off`.
- A CodeGraph section in Settings holds the mode picker and one read-only
  table per workspace: for each arm, task count, turn count, turns per task,
  and medians per finished turn of input tokens, output tokens, tool calls,
  exploration actions, and minutes, plus graph calls and seconds per turn for
  `ON`. The table states that it is observational and flags an arm with fewer
  than five tasks.

The numbers are a comparison, not a verdict; different tasks, models, and
prompts confound them. The opt-in developer benchmark in CG5 runs a fixed
prompt set on a fixed repository commit under both arms and additionally
records whether the produced change passes the repository's tests. Only that
benchmark supports a claim in documentation.

This section narrows the Section 10 exclusion of renderer-facing graph UI to
management UI; the read-only readout and comparison table are permitted.

Deliberately not included: statistical tests, agent self-assessment,
per-model or per-effort breakdown, a benefit badge, live per-turn charts, and
export.

## 13. First version

Status: approved 2026-09-05. Redesigned after the 2026-09-04 measurements
showed that a fresh per-task index costs 38 s on a Trino-sized repository,
which is the common case, not a corner case. The design keeps one disposable
index per task worktree and adds one derived cache per repository so that
the common first call costs about one second.

### Baseline index per repository

ByteQuay keeps one baseline index per physical repository: the index of
`refs/remotes/origin/<default-branch>`. It lives under the agent runtime
root, beside the task runtimes, keyed by the repository's `owner/name`:

```text
<agent-runtime>/codegraph-baselines/<owner>/<name>/codegraph/
<agent-runtime>/codegraph-baselines/<owner>/<name>/HEAD
```

`HEAD` holds the default-branch commit the baseline is synchronized to. The
baseline is derived cache like every task index: no database row, no
lifecycle state, and a missing, unreadable, or incompatible baseline simply
means "none". Tasks never modify it.

### The baseline follows the remote default branch

The baseline is refreshed only when a fetch shows that the remote default
branch gained commits. Two things fetch:

1. The fetches ByteQuay already performs. Interactive task creation, CI
   Autofix, and cherry-pick each fetch their exact commit on the managed
   repository before provisioning a worktree. After any of them, ByteQuay
   compares `origin/<default-branch>` with the baseline `HEAD` and schedules
   a refresh on a virtual thread when they differ.
2. One scheduled job for repositories whose workspace has the graph on: it
   fetches the default branch at application start and then hourly, a
   configurable interval, and runs the same comparison. This is the only
   network activity the feature adds, it never runs on a turn path, and it
   follows the earlier decision that freshness comes from a background fetch
   rather than a per-turn one.

A refresh runs under a per-repository lock:

1. Create a temporary detached checkout of the new commit with
   `git worktree add --detach` under the baseline directory: 1.9 s and
   190 MB on Trino.
2. Clone the current baseline beside itself as `codegraph.next` (0.03 s),
   count changed files between `HEAD` and the new commit with
   `git diff --name-only`, and run `sync` when the count is at most five
   percent of the indexed file count, otherwise `codegraph index`. With no
   baseline yet, run `init`: 38 s on Trino, 2.6–5.3 s on this repository.
3. Rename `codegraph.next` into place, write `HEAD`, and remove the
   temporary checkout (1.0 s). The rename is atomic, so a failed refresh
   leaves the previous baseline untouched.

Measured on Trino from a temporary checkout: same commit 0.9 s, 10 commits
9.6 s, 924 files 47 s, rebuild 42 s. Trino's default branch gains a few
commits an hour, so hourly refreshes cost seconds each while the application
runs; a day away costs one rebuild at application start.

### Task creation clones the baseline

After `provision` returns and the task is persisted, ByteQuay starts
`prepare(task)` on a virtual thread, the same pattern the queued-turn
auto-start uses. The request returns immediately. `prepare` holds the task's
graph lock and:

1. Waits for any refresh in progress on the repository. When no usable
   baseline exists it builds the task index directly instead; the baseline is
   written only by the fetch-triggered refresh, which the fetch before
   provisioning normally schedules first.
2. Clones the baseline into `<task-runtime>/codegraph/` with an APFS clone
   (0.03 s and 8 MB measured), falling back to a plain copy when the volume
   refuses clones (0.65 s, full size).
3. Synchronizes it against the task worktree by the same size rule. An
   interactive task starts at the fetched default-branch commit, so the
   delta is zero and the sync takes 0.8 s. A CI Autofix or cherry-pick task
   starts on another branch, so the delta is that branch's divergence.

Every failure inside `prepare` or a refresh is a logged `CodeGraphException`
with no task effect; the first tool call then builds lazily as Section 5
describes. A restart during either leaves an incomplete directory that
the adapter's usable-index check rejects, with the same lazy fallback. Nothing durable
records that a prepare or refresh ran.

### What the agent sees

- **One tool.** `codegraph_explore(query)` waits for a running `prepare` on
  its task, bounded at 60 s, after which it returns "index still building,
  retry", then synchronizes or rebuilds by the same size rule and queries.
  No `codegraph_refresh`, no pre-turn hook, no turn-service seam. An
  unchanged sync costs 0.14 s here and 0.5 s on Trino.
- **Expected first-call latency.** Baseline current, the normal case because
  the hourly job and the creation fetch keep it so: the query itself,
  0.25–1.6 s. Remote moved since the last refresh: the creation fetch
  triggers a refresh of seconds before the clone, usually finished before the
  agent's first call. Drift above five percent, about a day of Trino
  commits with the application closed, or a repository with no baseline
  yet: a 38–42 s build in the background at creation, hidden whenever the
  first graph call comes later than that.
- **Bounded output.** Explore returns about 25 KB per call; the adapter caps
  the tool result at 16 KB and marks truncation.

### Cost of a task that never uses the graph

An APFS clone plus a same-commit sync: about one second of CPU and 8 MB of
disk that shares blocks with the baseline until the task's first real sync.
This replaces the Section 1 rule that an unused task builds no graph; the
rule protected against a 38 s eager build, and the eager cost is now a
second.

### Storage and cleanup

One baseline per repository plus one index per task: 1.3 GB and 1.3 GB per
active task after its first divergence on Trino, 111 MB each here, plus a
transient 190 MB checkout while a Trino refresh runs. The task
index is deleted after successful worktree cleanup, otherwise by task-runtime
retention. The baseline is deleted when its workspace is removed; a
staleness sweep is not part of the first version.

### Provider bridge, metrics, packaging

- **One bridge.** The existing ByteQuay task MCP endpoint gains the tool;
  Claude sees it through the current broker, Codex through one `mcp_servers`
  entry in its per-task config with the bearer header, and the compatibility
  fixture asserts exactly that server. Root agent only.
- **Metrics.** An `on`/`off` setting and the five Section 12 counters shown
  in the task footer. `off` also skips `prepare`. The `compare` arm and the
  Settings table wait for CG7.
- **Packaging.** Ship the pinned darwin-arm64 platform bundle with its own
  Node runtime, 278 MB unpacked and 58 MB compressed, through the same
  `extraResource` path as the backend jar. Running the bundle on Electron's
  own Node would save 117 MB, but commander misparses the arguments under
  `ELECTRON_RUN_AS_NODE` and five wrapper variants did not fix it; deferred.

### Reconciled

Sections 1, 4, 5, 6, 7, 8, 10, and 11 were rewritten on 2026-09-05 to match
this section; the per-worktree index, the fail-open contract, the security
bounds, and the storage-outside-the-worktree rule were unchanged by the
redesign.
