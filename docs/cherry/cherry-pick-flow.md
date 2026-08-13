# Cherry-pick flow

Status: **design of record for the upstream cherry-pick surface**

One flow, three phases, one run record. A user picks a range of commits from the
upstream project this fork tracks; the program applies them locally, publishes
them as a draft pull request, drives remote CI to green, and tears down what it
held once the pull request is merged.

This document is the end-to-end design. The two normative component specs it
composes are [upstream-sync.md](../flows/upstream-sync.md) (pre-publication) and
[ci-autofix.md](../flows/ci-autofix.md) (post-publication). Where this document
and those disagree, they win — this one exists to say how they join up, and to
record decisions that belong to neither alone.

## The shape

```text
range picker
  -> filter + PR description
  -> PHASE 1  cherry-pick -x, one commit at a time
  -> user reviews locally, then authorizes the first push
  -> PHASE 2  remote CI to green, repairing as attributed fixups
  -> user merges
  -> PHASE 3  cleanup receipt
```

Only one entry point exists: the upstream commit picker. There is deliberately no
"attach autofix to an existing pull request" — a run owns a range, and a run with
no range has no phase 1 to speak of.

## Phase boundaries

The phases are not cosmetic. Each boundary is a change of owner:

| boundary | before | after |
|---|---|---|
| picking → publish | the run's own picker and agent, nothing pushed | GitHub has the branch and a draft PR |
| publish → CI | Upstream Sync owns history shaping | CI Autofix owns exact-head convergence |
| merge → cleanup | the run holds a worktree, branch, session | the run holds nothing |

The **publish boundary is the load-bearing one.** Everything before it is local
and revocable; everything after it is public. The run parks there and waits for
the user, which is not a convenience — it is the gate.

## Phase 1 — local cherry-picks

The program applies the selected range with `git cherry-pick -x`, one commit at a
time, in an isolated worktree on its own branch.

Three outcomes per commit:

- **clean** — recorded and the index advances.
- **conflicted** — git's own three-way resolution is committed first, so the
  sequencer is never left open, and then the Task Agent repairs it as a
  `fixup!` attributed to that pick. A pick never carries two fixups: a second
  repair amends the first.
- **empty** — the fork already carries the change. The pick is skipped rather
  than parked; git refuses to record an empty commit and holds the sequencer
  open, and no resolution can ever finish that, so parking a human on it is
  asking for a decision that does not exist.

Never commit unresolved index entries, and never deliberately commit conflict
markers. A repair is verified before the run moves on: the worktree must be
clean, and no file the conflict touched may still carry a marker — git's own
resolution is already committed by the time the agent starts, so a file it
reported resolved but never edited would otherwise reach the pull request with
markers in it.

Phase 1 is bounded by the run's budget and nothing else. It parks for the user on
a conflict the agent declines, on a spent budget, or on request.

## The publish boundary

Publication goes through the flow's own `INITIAL_PUBLISH` effect, not through a
direct GitHub call. This is the single most consequential decision in this
document, and it was reached by elimination:

CI Autofix's only entry point is `FlowRuntime.ensureCiObservationWatch(receiptId)`,
which is keyed on a gate-authorized publish receipt. Reaching it any other way
means synthesizing the whole chain —
`change_set_revision → task_base_revision → task → lifecycle_revision → pr →
gate_subject → initial_publish_subject → initial_publish_action → gate →
gate_revision → gate_authorization → effect_plan_envelope → initial_publish_plan
→ step → attempt → probe → step_receipt → pr_receipt_detail →
initial_publish_receipt → receipt_envelope → remote_identity` — roughly eighteen
tables of fabricated evidence, several bound by digest composite keys. That is
not an adoption seam; it is counterfeiting a gate-authorized publish, and the
airtightness is deliberate.

So the sync run becomes a normal Task, picks into that Task's worktree, and
publishes through the flow. CI Autofix then picks the pull request up by itself,
with its gates and its adversarial review intact.

**The run's existing park-before-push already is the `INITIAL_PUBLISH` gate.**
Nothing new has to be invented for the user's authorization step; it has to be
connected.

## Phase 2 — remote CI to green

Owned by generic [CI Autofix](../flows/ci-autofix.md). It observes checks on one
exact head, freezes failed-job log evidence, resumes one persistent `CI_FIXER`
session per Task, and repairs under the Task writer lease. The fixer edits, runs
checks, and commits; it never pushes. The Task Agent inspects the exact diff and
may amend it; a *fresh* read-only adversarial reviewer inspects the exact
candidate head; only then may the program push, and only with a `CI_UPDATE`
authorization.

Four things this flow needs that generic CI Autofix did not have. All four are
now in its spec rather than forked into a second engine.

### Attributed fixups

A sync branch is a reviewable series — each upstream commit, with the fork's
repair attached to it. A CI repair landing as an opaque tip commit that touches
several picks destroys the property the whole range exists for.

So repair placement is a program-resolved `RepairPlacementPolicy` per Task:
`TIP` for an ordinary Task (unchanged behaviour), `ATTRIBUTED_FIXUP` for a Task
whose branch Upstream Sync built.

Under `ATTRIBUTED_FIXUP`:

- the fixer names the target in its commit subject — `fixup! <exact target
  subject>`. Choosing the target is judgment, which is the agent's job; the
  subject is fenced Git state, so this does not put the program back to parsing
  agent prose.
- the program then repositions mechanically: a generated rebase todo moves each
  `fixup!` behind its target, and merges it into that target's *existing* fixup
  when one is already there — so a target keeps exactly one fixup and stays
  byte-comparable to upstream.
- a fix the fixer cannot attribute to one target stays a plain tip commit rather
  than being attached to a guess.

### Compile before everything

A series that does not compile makes every test result behind it meaningless, so
compile failures are settled first and downstream test failures are handed over
marked *unjudged*.

The compile check is identified from the repository's own CI configuration, never
from a name heuristic, and carried as `perCommitCompileSelectors`. If it cannot
be determined there is no compile priority and no acceptance exception — the flow
degrades to plain finalized-red behaviour. Fail-safe, not fail-open: a guessed
compile selector would excuse red checks it has no business excusing.

### Don't wait for a long board

A `perCommitCompile` selector that is terminal and failed admits repair
immediately, at `PARTIAL_RED_COMPILE`, while every other selector is still
collecting. Sound for exactly one reason: a compile failure is deterministic, so
no later check finishing can change its verdict. Nothing else gets that
shortcut.

### The excused target, and how it is proven

`check-commit`-style per-commit checks report one red for the whole series, and a
target commit whose repair lives in the fixup *after* it is red in isolation by
construction.

That red is excused — but only from the program's own evidence. Before pushing, a
rebase runs boundary builds whose `exec` lines the program generates, placed only
at boundaries: after a fixup, and after a target that has no fixup. A bare target
followed by its fixup is deliberately **not** a boundary, which is exactly what
makes the exception provable rather than assumed. The result is a
`BoundaryCompileProof`; missing or red blocks the push and never degrades to a
warning.

What is never done: parse `check-commit`'s log to work out which commit failed.
That habit is what rotted the retired harness, and it would make program
correctness depend on CI log formatting.

### Publishing a rewrite

A rewriting round pushes with force-with-lease against the head the round was
bound to, so a remote that moved underneath rejects rather than discards; a
rejected lease becomes a fresh observation, never a retry with the lease dropped.
Authorized by `allowsHistoryRewrite`, granted once with the Task by the same user
act that chose an upstream sync — so unattended convergence works, but a one-shot
`CI_UPDATE` consent cannot rewrite history by accident.

Each rewrite restarts the whole remote board. That is the cost that keeps
attribution a per-Task policy rather than a default.

### When phase 2 stops

| stop | trigger |
|---|---|
| done | accepted required CI on the exact head |
| park | budget spent |
| park | the agent judged the remaining failures not fork-owned |
| park | the head changed and the identical set of failing selectors came back |

**No round count.** A large range legitimately needs many rounds, because a
compile failure masks every test behind it and each round can reveal work the
previous one could not see. A count-based ceiling would stop a converging run.

A round count is also the wrong non-convergence signal, because a *good* round
often raises the failing count — repairing the compile failure lets the tests
that never ran report. The repeat-identical-failures rule is scale-free: it is as
valid on round 300 as on round 3.

## Phase 3 — cleanup

Merging is the user's act, not the run's. Once the pull request is observed
merged, the run releases what it still holds and records what it released:

1. the isolated worktree is removed;
2. the local result branch is deleted — the remote copy is the one that matters;
3. the agent session and its stored transcripts are dropped;
4. the remote branch is deleted; and
5. the run is closed.

Phase 3 is a **receipt, not a surface**: nothing to steer, nothing to approve, no
composer. Each step records what actually happened, so a step that could not be
completed — a branch GitHub already auto-deleted, a worktree already gone — reads
as *not released* rather than being ticked. A receipt that claims a step the
program skipped is the one thing on this surface that would be a lie.

A pull request **closed without merging** cleans up identically except that the
remote branch is left alone: unmerged commits on it are the only copy of work
someone may still want.

## Prerequisite — CLI agent transport

Phase 1 requires a CLI agent: resolving a conflict needs a shell and an editor,
and an in-JVM API turn has neither. Phase 2 in CI Autofix currently requires the
opposite — `NewFlowEngineResolver` refuses a `cli:` engine with
`ENGINE_TRANSPORT_UNSUPPORTED`, and deliberately will not silently downgrade to
an API engine, because a user who picked a CLI agent made a billing and privacy
choice the program may not overrule.

So the two phases would run on two different engines, breaking the run's
one-session story. The fix is the out-of-process supervisor the runtime is
already waiting on: one that owns a complete process group and produces a
mechanical death receipt. Until proven process death and revoked tool capability
exist, the runtime cannot safely admit a successor writer — which is why the gate
is there and why a partial implementation is worse than none.

**This is the first prerequisite for the end-to-end flow.**

> **Superseded in part.** The two subsections below argued that tree burial can
> be the authoritative stop proof and that a CLI body needs no tool bridge. Both
> are wrong; see "Corrections" at the end of this document, which governs.

### It does not need to be out-of-process

The spec calls for "an out-of-process supervisor [that] owns a complete process
group and a mechanical death receipt", and that phrasing reads as a new
supervisor alongside the in-process one. Building it that way would be a large
mistake, because the runtime's identity model is a Java thread:
`flow_runtime_agent_process_attempt` stores `jvm_pid`, `jvm_started_at`,
`thread_id` and `thread_name`, with state CHECKs built around them. An external
process identity has no columns and no constraints — adding them means reshaping
the most safety-critical table in the schema.

The requirement it actually states is weaker than the phrasing: *own the process
group, and prove its death before a successor writer is admitted.* That is
satisfiable inside the existing in-process supervisor, because the invariant is
transitive. Let the dormant Java thread own the subprocess and not return until
it has buried the group:

- spawn the CLI as the leader of **its own process group**, so the whole tree can
  be signalled as one;
- on cancellation signal the group, not the child — `destroyForcibly()` kills a
  shell and orphans everything under it — escalating TERM then KILL;
- before the thread returns, **prove the group no longer exists** (a zero-signal
  probe against the group id failing is the receipt; `Process.descendants()` is a
  live snapshot and proves nothing about the moment after you read it);
- record that probe as the attempt's `stop_proof_ref`.

The existing thread-stop path then revokes and drains the capability exactly as
it does today, and `recover()`'s refusal to inspect a worktree against a live
writer keeps its meaning. **No schema change, no second supervisor, no new
identity model.**

One consequence worth stating: a CLI agent does not call `WriterToolCapability`
at all — it edits the worktree directly, the way phase 1's conflict repair
already does. So there is no tool-mediated evidence trail for a CLI body, and the
outcome must be derived from mechanical worktree inspection afterwards. That is
already how `finalizeAttempt` reads a fixer's turn, so the model exists; what
changes is that for a CLI body it is the *only* source, which makes the clean /
no-head-change / dirty trichotomy load-bearing rather than a fallback.

Its cheaper half is already solved and should be reused rather than rewritten:
`CliReviewRunner` spawns `claude`/`codex` today with a `ProcessBuilder`, drains
stdout/stderr on virtual threads, enforces a timeout, and streams lines back —
that is the transport phase 1 already runs on. What it does *not* provide is the
discipline the flow requires: `destroyForcibly()` on the direct process kills a
shell, not the process group beneath it, and `waitFor` returning is not proof
that every descendant is gone. So the missing work is specifically

- spawn in its own process group so the whole tree can be signalled;
- prove death of that group, not just of the direct child
  (`Process.descendants()` is a snapshot, not a receipt — the proof has to be
  that the group no longer exists); and
- revoke and drain the run's tool capability before any successor writer is
  admitted, which is the invariant `recover()` refuses to proceed without.

Reaching for a new subprocess layer instead would duplicate the one that already
works and would still leave the receipt unbuilt.

**The platform detail to settle first.** Java has no API for putting a child in a
new process group: `ProcessBuilder` cannot do it, and neither `/bin/sh -c` nor
`exec` creates one. The options, on this app's macOS target, are

- call `setsid()` through the FFM API (available on Java 25) before exec — clean
  group semantics, but real platform code and the riskiest to get wrong;
- shell out to `setsid`, which **is not present on macOS by default**, so it is
  not portable here; or
- drop process *groups* and bury the process *tree* instead: walk children
  recursively, signal from the leaves up, and make the receipt "no pid in the
  recorded tree still exists". No native code, works today, and slightly weaker —
  a process that reparents itself between the walk and the signal escapes.

**None of the three was needed.** A shell in job-control mode (`set -m`) puts each
background job in its own process group, with the group id equal to the job's pid.
So a three-line `/bin/sh` wrapper gets real group semantics with no native code,
no FFM call, and no bundled executable — and `ProcessGroup` is built on it.

That matters beyond convenience: **a process group survives reparenting where a
tree walk does not.** Leaving a group requires a deliberate `setpgid`, whereas
outliving your parent is free. So the group is the authoritative receipt and
`ProcessTree` is diagnostics — it stays useful for inspecting what a turn held,
and it still pins each pid to its start time so a recycled pid cannot report a
dead agent as alive forever, but it is not what admits a successor writer.

The group id is written to a file rather than stdout, so the agent's output stays
exactly what the CLI produced, and it is readable before the prompt is delivered —
an id learned only at the end would be lost by the crash that makes it matter.

### The launch binding, and what a CLI run cannot prove

`flow_runtime_agent_launch_binding` is the digest-sealed record of exactly what
engine ran, and it is HTTP-shaped: `transport CHECK IN ('ANTHROPIC',
'OPENAI_COMPAT')`, `endpoint NOT NULL`, all four credential columns `NOT NULL`,
and `max_output_tokens` / `max_tool_iterations` `NOT NULL`.

A CLI engine has none of those. It authenticates with **the user's own CLI login,
which this app never sees and must not** — that is the billing and privacy choice
the admission gate's comment refers to. So a sealed CLI binding can pin the
binary, the version it reported, the model flag, the sandbox mode and the
worktree, but it can never name the account that answered. **The receipt proves
strictly less for CLI than for API, permanently.** That is a property to state,
not a gap to close.

Decided shape: one table, nullable per transport, with a state-dependent CHECK so
neither shape can borrow the other's fields —

- `transport` CHECK gains `'CLI'`;
- `endpoint`, all four `credential_*`, `max_output_tokens`, `max_tool_iterations`
  become nullable;
- `cli_binary` (NOT NULL for CLI) and `cli_version` are added;
- one CHECK: API implies endpoint + credentials + caps present and `cli_*` null;
  CLI implies all of those null and `cli_binary` present.

The nullability then documents which transport sealed a row before you read it.

This lands **atomically with its code**, not before: changing `runtime.sql` alone
changes the bundle digest and makes the app refuse to start until the new-flow
database is deleted, for no functional gain. Four coordinated changes go with it —
a `CLI` member on `Transport`, branching validation in `NewFlowAgentLaunches.Config`
(whose constructor currently *requires* an HTTPS-or-loopback endpoint),
`bind()` skipping its mandatory credential lookup, and a binding digest over the
per-transport field set. `TestNewFlowConfiguration`, `TestNewFlowEngineResolver`
and `TestNewFlowAgentRuntimeBoundaries` assert the current shape and move with
it. Whichever is chosen, it needs a test that actually forks a
grandchild and orphans it, because the failure this guards against — a successor
writer entering a worktree the previous agent's leftover child still writes to —
does not reproduce without one.

## The surfaces

**Sync runs home** — the list. Running runs as cards (run number, range
endpoints, commit count, status, phase progress, detail, PR, elapsed, cost);
finished runs as a table (result, PR, rounds, cost, when). Finished rows order by
when they finished, not when they were created — those differ, and the column
says *finished*.

**Run details** — three-phase rail on the left (identity, phase nodes and their
state, pinned status card, worktree footer), the run's conversation in the middle
(picks, program steps behind chips, agent prose with its transcript one click
away, moments, decision card), and the pull request beside it.

Phase 1 folds to a summary **only once the run has left it**. While it is still
picking, that summary is the whole page and says nothing a reader can use — and
it would hide the conflict repairs, which is what someone parked on a conflict
came to read.

## State of implementation

Built and green:

- phase 1 end to end — range preview, picking, conflict repair, empty-pick skip,
  local compile gate, budget, park/resume/retry, push and draft PR
- both surfaces, on real data
- phase 3 teardown including merge-only remote branch deletion, with the receipt
  reading what the teardown recorded
- the run number, range endpoints, and pull-request result the surfaces need
- the budget ceiling at $1,000, and a total-budget clamp on raises
- the CLI safety foundation — `ProcessGroup` as the death receipt,
  `CliWriterContainment` as the publish barrier, `ProcessTree` demoted to
  diagnostics, and the launch-binding columns for both execution kinds
- engine resolution per run from the workspace's per-audience pick, replacing
  the compiled-in provider properties
- the entry point on the new records: the range picker resolves its selection
  to an explicit commit list and starts a Task-backed run, and the run's park
  before the first push is authorized from the run view against the exact gate
  revision it displayed
- both surfaces on the new records, beside the retired ones — the rounds rail,
  the finished list's ROUNDS column, the fixup attribution block and the
  excused-check card all read real rows, and a run that reports none of them
  says so rather than showing a zero
- the CLI launch binding itself: an explicit CLI pick now resolves and binds as
  `CLI`, recording binary and version and no credential, and the in-JVM turn
  path refuses such a binding at its single choke point rather than in each body

Remaining, in dependency order:

1. **The CLI agent body** — the operation-scoped loopback tool bridge, role
   capabilities, provider-session/usage persistence, and restart recovery that
   buries a persisted group before admitting a writer. Blocks everything below.
   Until it lands, a workspace configured for a CLI engine binds its run and
   then fails the in-JVM turn path on every attempt — the same retryable
   non-effect failure resolution itself raised before, moved one step later, now
   with the engine choice durably recorded instead of discarded.
2. **Phase 1 onto the flow runtime** — Task at enqueue with `ATTRIBUTED_FIXUP`,
   picks in the Task worktree, change-set revisions, PR materialization, and the
   existing park wired to `INITIAL_PUBLISH`.
3. **The phase-2 amendments** — placement policy and boundary proof storage,
   rebase-todo repositioning and squash, compile selector discovery,
   `PARTIAL_RED_COMPILE` admission, the boundary acceptance exception,
   force-with-lease publishing, repeat-failure park.
4. **What a run on the new records still cannot do.** Its surface reads
   everything phases 1 and 2 record, but four of the retired path's controls
   have no equivalent behind them and are therefore absent rather than inert:
   pause, skip-this-commit, resume, and close/delete. Steering renders as a
   disabled composer saying so, and the live turn stream — which makes a pick
   that compiles for minutes look alive — belongs to the retired runner.
5. **Phase 3 on the new records.** Nothing observes the pull request being
   merged or closed there, so a run that has published stays in RUNNING as
   "parked for your review" — true, but it never reaches the finished table and
   its cleanup receipt has nothing to read. Phase 3 is built and green on the
   retired path only.

Adding tables to `db/new-flow/*.sql` changes the schema bundle digest and makes
`NewFlowDatabase.bootstrap()` refuse to start. That is intentional — it is a
tamper detector, not a migration gate — and the remedy is to delete the
new-flow database file, which carries no history worth migrating until a Task
exists. The error message says so.

## Decisions worth not re-litigating

| decision | why | reversed from |
|---|---|---|
| One entry point: the range picker | A run owns a range; a run with no range has no phase 1. | an earlier attach-to-any-PR affordance |
| Publish through `INITIAL_PUBLISH` | The only door into CI Autofix is a gate-authorized receipt; the alternative is fabricating ~18 tables of publish evidence. | two earlier attempts, one to adopt a pushed PR and one to write a second CI loop |
| CI Autofix owns fixup placement | A sync branch is a reviewable series. Amending its spec beat forking a second engine with weaker guarantees. | `ci-autofix.md`'s "never chooses a `fixup!` owner" and `upstream-sync.md`'s matching decision row |
| Attribution every round, not once at the end | The user's call, made knowing each rewrite restarts the board. | a proposal to converge at the tip and shape history once before ready |
| No round ceiling | A large range legitimately needs many rounds; the budget is the hard stop. | an 8-round then 6-round proposal |
| Excuse the bare target from local proof, never from the remote log | Log parsing is what rotted the retired harness. | — |
| Phase 3 is a receipt | Nothing to steer once the merge happened. | the mockup's inert "Review & merge · waiting on you" node |


## Corrections

Three parts of this document were wrong when written. They are corrected here
rather than edited away, because the reasoning that produced them is the kind
that will recur.

### `ProcessTree` is diagnostics, not the stop proof

`workflow-runtime.md` requires that an OS-process transport "own a complete
process group and mechanical death receipt before it can be admitted."
`ProcessTree` captures a tree and admits that a process reparenting itself
between capture and signal escapes — so it cannot be the authority that admits a
successor writer. It stays useful for diagnosis and cleanup.

The authoritative mechanism is a small bundled helper that creates a new process
group, emits a machine-readable *group ready* handshake, and execs the CLI as
group leader. The program persists pid, process-group id and process identity
**before** sending the prompt; completion requires the whole group to be absent.
After a restart the program kills and proves the persisted group dead before
admitting another writer, and uncertain identity is `NEEDS_ATTENTION`, never
automatic continuation.

### What the CLI body is actually made of

Measured rather than estimated, because the first estimate was wrong by an order
of magnitude. Three of the four pieces already exist:

- **The vendor command line** is `CliAgentArgv`, shared by both flows. The flags
  are the expensive part and there is now one copy.
- **The stream parsers** (`CliStreamParser`, `CodexJsonParser`,
  `StreamJsonParser`) were already flow-neutral in `service/threads`. Nothing
  had to move; the greenfield calls them directly.
- **The process lifecycle** is `ProcessGroup` plus `CliWriterContainment`, which
  is strictly better than the old adapter's own — that one has no process group,
  so it cannot prove a reparented grandchild dead.

So the old adapter never needed extracting wholesale. What remains is the tool
bridge and the body that joins these:

- **An operation-scoped MCP endpoint.** Every existing turn kind has its own
  ~100-line controller (`StageTurnMcpController`, `ThreadTurnMcpController`, and
  three others) that does nothing but delegate JSON-RPC to
  `LoopbackOwnerMcpClient`. The greenfield needs the same shape, pathed on the
  run rather than a stage, listing `NewFlowAgentLaunches.Program.tools` and
  dispatching to the same `ToolExecutor` the in-JVM body already builds. That
  reuse is what keeps a CLI turn and an API turn from drifting into two
  different tool surfaces for the same role.
- **The body.** Apply containment, launch through `ProcessGroup`, persist the
  group id before the prompt, parse, map to the same `AgentCompletion` the
  in-JVM bodies return, and hold the writer fence until the group is proven
  absent.
- **Provider session and usage**, then **restart recovery** that buries a
  persisted group before admitting a successor writer. Both are unobservable
  until the body above runs, which is why they moved here.

### A CLI body still needs operation-scoped tools

The earlier claim — that a CLI agent bypasses `WriterToolCapability` entirely and
its outcome comes only from worktree inspection — is true of the *outcome* and
false of everything else. The agent still needs to read CI logs and failure
context, run formal checks, record a conflict resolution, request a reviewer, and
declare readiness. Without a bridge, phase 1's Task Agent cannot call its semantic
tools at all and the CI Fixer has no bounded tool surface.

So a CLI run gets an **operation-scoped loopback MCP endpoint**. Each call
resolves run, live capability, role and writer fence internally; the CLI never
receives raw fence material. Shell edits inside the worktree are allowed, but
formal commits and terminal transitions go through tools — an unrecorded commit
is grounds for quarantine. `LoopbackOwnerMcpClient` in the old flow is the
pattern to follow.

### Containment: every engine, by removing the capability

A clean worktree does not prove the agent did not push. Today's conflict-repair
prompt merely *asks* ("never push, never touch any branch"), which is prose, not
enforcement — and the flow's contract states the fixer *cannot* push, as a
property.

The tempting remedy is to allow only an engine with a vendor sandbox. That is the
wrong shape: it makes the guarantee depend on which CLI the user picked, leaves
the other engine permanently unsupported, and still proves nothing — a sandbox
that denies network denies the *build's* network too, which a Maven or npm build
frequently needs.

**Which engine runs is workspace configuration, not a safety decision.** It comes
from the one chain every other agent already uses,
`WorkModelResolver.resolveForWorkspace(workspaceId, audience)`. Claude Code and
Codex are both supported writers.

Containment is engine-agnostic because it removes the *capability* rather than
policing the *command*. Pattern-matching shell strings for `git push` is
defeatable in a dozen ways (`g=push; git $g`, a script file, a base64 blob) and
should not be attempted. Instead the writer turn runs with:

- **no push destination** — the worktree's remote push URL is poisoned
  (`git remote set-url --push origin` to a refusing scheme). The program pushes
  from the target clone, which keeps the real URL. Whatever command the agent
  constructs, there is nowhere to send it.
- **no credential** — `GIT_CONFIG_GLOBAL` and `GIT_CONFIG_SYSTEM` pointed at an
  empty file so no credential helper is configured, no `SSH_AUTH_SOCK`,
  `GIT_ASKPASS=/bin/false`, and the existing `GIT_TERMINAL_PROMPT=0`. This is the
  part that matters most: `GitRunner` deliberately shells out so the user's own
  keychain and SSH keys apply, which means an unscrubbed environment hands the
  agent working push credentials.
- **a refusing `pre-push` hook** via `core.hooksPath`, as a cheap third layer —
  bypassable with `--no-verify`, so it is a backstop and never the argument.
- **the vendor sandbox where the engine has one.** Codex gets
  `workspace-write`; Claude Code gets its permission configuration. Defence in
  depth, not the foundation.

### Detection is what makes it provable

Prevention cannot prove a negative, so the program also measures. It records the
exact remote head before the turn and verifies it is unchanged afterwards; a
remote that moved under a writer turn is quarantined, not reconciled. That turns
"we believe it did not push" into a program-measured fact, which is the standard
the rest of this design already holds itself to — Git state, CI results, process
death and authorization are all measured, never asserted.

The tests that matter: attempted push under the scrubbed environment, writes
outside the worktree, a moved remote head detected and quarantined, cancellation,
orphaned children, and restart recovery. Both engines run the same suite; an
engine that cannot pass it is not a supported writer, and that is a property of
the engine rather than a name on a list.

### Two more corrections

- **No legacy link.** An earlier plan put `flow_task_id` on
  `upstream_cherry_pick_job`. That is exactly the dual-write and shared lifecycle
  `upstream-sync.md`'s replacement boundary forbids. Cherry-pick synchronization
  uses new-flow records only (`UpstreamSyncRequest` / `Run` / `Pick` / `Fixup`),
  and the old job must not remain an execution owner. Consequence to plan for:
  the two shipped surfaces read the legacy model and will need repointing.
- **Two sessions, always.** The Task Agent (phase 1) and the CI Fixer (phase 2)
  are separate persistent sessions, even on the same configured engine. The
  mock's single `SESSION` row is a mock, not the contract. Provider session ids
  and cumulative usage need durable storage for resume to actually work.
- **Selector identity is not a Maven command.** `CiJobScriptReader` extracts a
  build command from a named workflow job; it cannot establish the GitHub
  `(appId, check-name)` identity required by CI policy. That needs an explicit
  resolver mapping the workflow job to both the provider selector and the
  boundary-build profile.

### Revised order

Schema lands with each vertical slice rather than pre-batched — batching couples
unrelated slices, and the database is empty so resets are free.

1. Record the seven-failure baseline. **Done.**
2. CLI safety foundation: process group, stop proof, containment, provider-session
   persistence, adapter tests. **Done**, except provider-session and usage
   persistence, which moved to the step below — they are only observable once a
   CLI body runs.
3. New-flow CLI binding: config, resolver, MCP bridge, role capabilities, recovery.
   **Config, resolver, bridge and restart recovery done.** Recovery persists the
   agent's process group on the attempt and buries it when a later launch finds
   the owning JVM gone; a group that cannot be identified is left unsignalled
   rather than killed, since by then the number may be a stranger's. Role
   capabilities, the body itself and provider-session persistence remain.
4. Greenfield upstream synchronization: new records, Task worktree, picks, local
   checks, Local PR draft, fresh reviewer, history verification, `INITIAL_PUBLISH`.
5. Attributed repair: selector policy, typed attribution, rewrite operation,
   boundary proof, force-with-lease gate.
6. Surfaces and cleanup, including the legacy entry-point cutover. **Entry
   point and surfaces done**; the retired path stays until no run depends on
   it, and the home page reads both sources meanwhile. A run number is per
   source, so while both are listed the same number can appear twice — it is a
   display label, and the ambiguity drains with the old runs.

No slice may add a test failure.
