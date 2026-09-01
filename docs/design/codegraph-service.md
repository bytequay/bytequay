# CodeGraph Service

## 1. Decision

Every repository-scoped agent task backed by a managed `TaskWorktree` may use
a code graph for structural code exploration. One worktree owns one
independent graph index for its lifetime. The graph is never shared across
worktrees because their committed and uncommitted contents may differ.

The agent decides whether, when, and what to query. ByteQuay does not select
graph context in advance. It provides two task-scoped tools, managed usage
instructions, bounded output, and safe lifecycle integration.

An unused task builds no graph. The first graph tool call initializes the
index when absent; later turns best-effort synchronize that existing index
before provider execution. This keeps the initial cost under agent control and
avoids a background graph-build lifecycle.

CodeGraph is an optional convenience. Expected CodeGraph operational failures
during initialization, refresh, query, or deletion must not fail task
creation, reject a turn, change an `OPEN` agent task to `FAILED`, retain its
mutation claim, project recovery required, or prevent the application from
using its normal source, search, command,
compiler, and test tools. ByteQuay programming errors and mandatory worktree,
resource, and sandbox failures remain visible.

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

During the managed-worktree rollout, a task whose persisted
`taskWorktree()` is empty receives no CodeGraph capability. ByteQuay never
adopts or reconstructs worktree ownership from the task's legacy bare path.

## 4. Service contract

The logical application contract is deliberately small:

```java
public interface CodeGraphService
{
    boolean hasUsableIndex(
            TaskWorktree worktree,
            TaskResources resources);

    void refreshIfNeeded(TaskWorktree worktree, TaskResources resources);

    String explore(
            TaskWorktree worktree,
            TaskResources resources,
            String query);

    void deleteIndex(TaskResources resources);
}
```

`hasUsableIndex` checks only whether the derived cache contains a complete,
compatible index bound to that canonical worktree. It does not report
freshness, create a lifecycle state, or consult Git. A partial, corrupt,
incompatible, or wrong-worktree index is not usable.

`refreshIfNeeded` is one atomic operation: an explicit tool call initializes
an absent index, while an existing index is incrementally synchronized or
left unchanged. Callers do not perform a separate Git commit, `HEAD`,
dirty-state, or timestamp freshness check first. The automatic pre-turn path
first calls `hasUsableIndex` and skips `refreshIfNeeded` when the task has
never used the graph.

Commit comparison is insufficient because agent changes are commonly staged,
unstaged, untracked, deleted, or made while `HEAD` remains unchanged. The
graph implementation's own incremental content check is the source of truth.

The benchmarked upstream implementation first filters files by size and
modification time before hashing them. It can therefore miss a content change
that deliberately preserves both values. V1 accepts this upstream limitation:
graph results remain hints, and current source plus compiler/test/runtime
evidence stays authoritative. The compatibility fixture records the behavior;
ByteQuay does not add a second file manifest or full-repository hash pass.

The first implementation uses a bounded short-lived graph process for each
operation and retains no open per-worktree handle or daemon. Add an in-memory
handle cache only if measurement shows reopening is material; any such cache
remains reconstructable and is never durable ownership state.

Expected operational failures use one narrow `CodeGraphException`. The
service does not swallow them; the pre-turn, tool, and cleanup adapters catch
that exception at the optional-capability boundary. Programming errors remain
visible rather than being mislabeled as graph unavailability.

Any later application workflow that calls this port follows the same rule:
catch `CodeGraphException` at its optional-capability boundary, record the
bounded diagnostic, and continue without graph data. It must not catch
arbitrary `RuntimeException` or make CodeGraph a hidden prerequisite.

The port belongs in `agent-core`, where turn and completion orchestration use
it. The first concrete adapter belongs in `app`; this feature adds no Maven
module or general-purpose tool framework.

The first adapter targets a pinned, audited version of
[colbymchenry/codegraph](https://github.com/colbymchenry/codegraph), provided
the external-index requirement in Section 3 is met. Its CLI, MCP, storage, and
result types remain behind this port so ByteQuay can replace it without
changing agent or worktree contracts.

## 5. Lifecycle

### Task creation

1. The owning task service provisions and verifies the `TaskWorktree`.
2. It allocates normal task runtime resources.
3. It persists the task and worktree facts.

Task creation does not create a graph directory or wait for a full graph
build. The first agent graph-tool call derives and creates its external index
directory. This keeps an optional dependency from consuming time and disk for
tasks that never use it.

### Before a turn

ByteQuay preserves the normal turn-ordering contract. After a new or resumed
turn is durably accepted and owns the task's worktree-mutation claim, it
requires the persisted `TaskWorktree` and `TaskResources` and performs the
mandatory `WorktreeService.inspect` validation. Immediately before provider
session open/start, it checks `hasUsableIndex`. When true, it calls
`refreshIfNeeded` while still owning that task boundary; when false, it starts
the provider normally without initializing the graph.

Resolving task resources and worktree ownership and calling `inspect` occur
outside the `catch (CodeGraphException)` block. Only the bounded
`hasUsableIndex` and `refreshIfNeeded` calls are caught and recorded as
optional-capability activity; the provider turn then starts normally.
Worktree ownership, resource, and sandbox validation remain mandatory and fail
closed.

There is no second commit/status gate before this call and no automatic
post-turn refresh. The next pre-turn call covers completed, failed, cancelled,
interrupted, and resumed turns.

### During a turn

The agent may explore the graph at any time. The first
`codegraph_explore` or `codegraph_refresh` initializes an absent index inside
that bounded tool call. If the agent changes code and needs the graph to
include those changes, it calls `codegraph_refresh` before the next relevant
graph query.

Refresh and explore calls for one task are serialized. A concurrent source
mutation may make a refresh fail normally; the agent may retry or use current
source tools. It does not block unrelated agent work.

### Task and worktree cleanup

Cleanup follows this order after provider calls are quiescent. Graph tool
callbacks use the same existing task-local execution lock as turn and
completion orchestration. Completion holds that lock and its completion
mutation claim across the worktree and graph cleanup calls, so no new graph
operation can start once cleanup owns the task boundary.

1. Attempt normal `WorktreeService.cleanup(worktree)`.
2. If worktree cleanup succeeds, request deletion of the external graph index.
3. If worktree cleanup refuses or fails, skip immediate graph-index deletion.

The short-lived graph process has read-only access to source and write access
only to its external index. Index-deletion failure is reported but creates no
graph-specific state machine. The derived index remains inside the task
runtime and is removed when the existing
`AgentResourceService.delete(taskId)` retention cleanup deletes that runtime.
This does not turn a clean completed task into failed work or undo successful
worktree cleanup. The same retention rule may later remove an index left after
worktree cleanup failed; preserving an optional cache does not change the
existing retention contract.

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
baseline, not a cross-machine service-level objective. They establish two V1
decisions: an absent index is built only after the agent chooses a graph tool,
and an existing index may use unscoped pre-turn synchronization. ByteQuay adds
no watcher, exact-path cursor, shared baseline, or eager background build until
field measurements show that the simple path is insufficient.

## 6. Agent tools

ByteQuay exposes exactly two logical tools:

```text
codegraph_explore(query)
codegraph_refresh()
```

`codegraph_explore` has one required bounded string. `codegraph_refresh` has
no arguments. The task-scoped tool handler obtains the canonical worktree and
index directory from trusted application context.

Query output is size-bounded and says when it was truncated. Graph resolution
is navigation help rather than compiler-grade proof; ambiguous or missing
relationships do not establish runtime behavior or correctness.

The initial tool surface has no status, initialization, full-rebuild,
configuration, project-path, multi-project, or deletion tool. Rebuild and
cleanup are application-owned operations.

Tool failure is an ordinary recoverable tool result. The ByteQuay tool wrapper
remains available even when the underlying executable, index, or query fails.
It briefly explains that CodeGraph is unavailable and tells the agent to
continue with its normal source/search tools. The harness does not retry
indefinitely, fail the turn, or require the agent to use CodeGraph.

### Provider bridge

The current no-business-tool boundary in `agent-service.md` remains the
default; this design supersedes it only for these two optional tools on a lane
whose task-scoped bridge passes the reference compatibility fixture and its
running preflight. ByteQuay adds one narrow, optional task tool bridge to
`AgentSessionContext`, containing the two fixed schemas, their task-bound
callback, and the managed instruction. It does not add a model-controlled
registry or a general application-tool framework.

`agent-cli` translates that bridge through the provider's audited task-scoped
transport: a dynamic-tool callback for Codex and the existing strict,
capability-token-protected task MCP boundary for Claude. `agent-core` owns the
task binding and callback; `app` supplies the concrete `CodeGraphService`.
The model never receives the callback token or filesystem identity.

Before constructing provider session configuration, ByteQuay starts and
self-tests the optional bridge and its fixed schemas; this does not start the
graph executable or build an index. If registration or self-test fails, it
omits both graph tools and their managed instruction and opens the normal
provider session. If the provider explicitly rejects that optional tool
configuration, ByteQuay discards it and retries the provider open once without
the tools or instruction. Unrelated provider failures remain provider
failures. CodeGraph is not part of `AgentDriver.probe()` or lane readiness, so
an absent executable or unsupported graph transport never disables a lane.

The initial version guarantees the bridge for the task's root agent only.
Provider-managed child agents continue with normal source tools unless a
later provider-specific compatibility test proves task-bound inheritance.

## 7. Managed instructions

ByteQuay supplies graph guidance through its provider-managed system or
developer instruction channel. It never edits repository `AGENTS.md`,
`CLAUDE.md`, or another project file, never installs global agent
configuration, and does not disguise managed guidance as user text.

The initial managed instruction is:

> ByteQuay provides an optional CodeGraph for the current task worktree. Use
> `codegraph_explore` when callers, callees, implementations, routes,
> dependencies, or cross-file structure would help. You decide whether and
> when it is useful. The first graph tool call may take longer while ByteQuay
> builds this worktree's index. After that, ByteQuay attempts to update the
> existing index before each turn. If you change code and need the graph to
> include that change, call
> `codegraph_refresh` before querying it. Graph results are navigation help;
> inspect current source and use compiler, test, linter, or runtime evidence
> when correctness matters. The task does not depend on CodeGraph. If either
> graph tool fails, continue with normal source and search tools. Do not
> install, configure, or upgrade CodeGraph yourself, invoke its CLI directly,
> or provide repository paths to it; use only the two managed tools.

The instruction is versioned by ByteQuay and applied on initial and resumed
provider sessions only when the task-scoped tool bridge passed preflight. A
provider lane that cannot guarantee the managed instruction and task-scoped
tool boundary omits CodeGraph without becoming unavailable.

## 8. Failure contract

| Failure | Required behavior |
| --- | --- |
| First-use graph initialization | Return a recoverable tool error on failure; task and turn behavior remain unchanged and a later tool call may retry |
| Pre-turn refresh | Start the turn normally and retain normal code tools |
| Explicit refresh | Return a recoverable tool error; agent continues |
| Explore query | Return a recoverable tool error; agent continues |
| Corrupt/incompatible index | Delete the derived index and return unavailable; a later call may rebuild |
| Tool-bridge registration or preflight | Construct provider configuration without CodeGraph tools or instructions and start the turn normally |
| Index deletion | Keep the successfully cleaned worktree result; existing task-runtime retention later removes the derived index |

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

The initial version has no:

- live filesystem watcher or daemon;
- shared baseline or cross-worktree index;
- eager graph build at worktree provisioning;
- eager or background graph initialization;
- persistent graph lifecycle/state table;
- renderer-facing graph status or management UI;
- automatic graph-context injection;
- review-specific evidence or anchor contract;
- multi-repository graph query;
- child-agent tool inheritance; or
- model-controlled graph configuration.

Add one only after observed usage demonstrates that the two-tool, best-effort
capability is insufficient.

## 11. Acceptance criteria

1. Two tasks for the same repository use distinct indexes bound to their exact
   managed worktrees.
2. Graph files never appear in Git status and cannot block a clean worktree's
   normal cleanup.
3. After an index exists, incremental refresh sees committed and uncommitted
   additions, modifications, and deletions without a caller-provided Git
   change check.
4. An unavailable graph does not block task creation, turn start, provider
   resume, task completion, or unrelated application behavior.
5. Agent tools expose no filesystem or task identity parameters and remain
   bounded to the current task.
6. The managed instruction is applied without modifying repository or global
   provider instruction files.
7. After restart, ByteQuay can reopen or rebuild the graph from persisted
   `TaskWorktree` facts and the derived task-resource path.
8. A task without a persisted `TaskWorktree` receives no graph tools and no
   graph managed instruction; a legacy bare path is never adopted.
9. Successful worktree cleanup requests graph-index deletion; failed worktree
   cleanup skips that immediate deletion, and normal retention may later
   delete either derived index with the task runtime.
10. Missing graph binaries and initialization, refresh, and query failures
    become bounded unavailable/tool-error results without terminating the
    provider turn or changing lane readiness.
11. Tool-bridge preflight failure starts the provider turn with both graph
    tools and the graph managed instruction omitted.
12. Completion owns the task-local execution boundary across worktree cleanup
    and best-effort graph deletion, so no graph query races worktree removal.
13. Graph deletion failure is observable but does not fail already-successful
    agent or worktree behavior or add graph-specific retry state.
14. No code-graph type depends on pull-request review, GitHub, diff, or comment
    types.
15. A task that never calls a graph tool creates no index and pays no full-index
    time or disk cost; its automatic pre-turn graph check is a no-op.
16. Performance tests record version, repository commit, hardware, wall time,
    and index size without turning one reference machine's numbers into brittle
    cross-machine assertions.
