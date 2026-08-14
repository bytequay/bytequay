# CI Autofix

Status: **normative greenfield replacement specification**

CI Autofix repairs finalized red CI for any published Task PR. The program
observes and deduplicates CI, then dispatches one persistent CI Fixer through the
Task selector without a Task-Agent relay. The CI Fixer may inspect logs, edit, test, and commit under the Task's
sole writer lease. It never pushes. The Task Agent inspects and adversarially
reviews the exact new head before the program may publish it.

This specification replaces every earlier generic post-PR CI/harness design.
Upstream range construction belongs only to
[upstream-sync.md](./upstream-sync.md). Pre-publication history shaping belongs
there too; post-publication repair placement is owned here, as the
`RepairPlacementPolicy` below.
Read [README.md](./README.md), [workflow-runtime.md](./workflow-runtime.md),
[task-agent.md](./task-agent.md), [adversarial-reviewer.md](./adversarial-reviewer.md),
[user-gates.md](./user-gates.md), [github-integration.md](./github-integration.md),
and [remote-feedback.md](./remote-feedback.md).

## Replacement boundary

Build a new component. It must not call, wrap, translate, dual-write, or inherit
state from an old CI executor, harness watch, classifier/rule engine, stage
manager, scheduler, marker-line parser, or agent-owned push path. There is no
migration or compatibility mode.

Low-level Git, process, model-provider, and GitHub-log clients may be reused as
neutral adapters. Their old orchestration must not be reused.

## Scope

CI Autofix begins only after the Task's stable PR has GitHub identity. It owns:

- immutable CI observations and final exact-head rounds;
- bounded failed-job log evidence;
- one persistent runtime `AgentSession` for the CI Fixer per Task, created lazily;
- repair attempts and exact input/output heads;
- CI lesson candidates written after remote green; and
- repair placement, including attributed-fixup positioning and the boundary
  compile proof that gates publishing a rewritten series; and
- the dispatch contract back to the Task Agent.

It does not own upstream commit selection, cherry-pick conflicts, pre-publication
history shaping, PR publication, remote feedback, merge, or the user gate.

It does own where its own repair commits land, including positioning them behind
an attributed target under `ATTRIBUTED_FIXUP`. That is placement of repairs this
component authored, not editing of history it did not write.

## Hard invariants

- A repair round is created only for a finalized required-CI state bound to one
  exact remote head SHA, except for the compile-priority admission below.
- Compile-priority admission: when the policy marks a selector
  `perCommitCompile` and that selector's current attempt is terminal and failed,
  repair may be enqueued while other selectors are still `COLLECTING`. The round
  is still bound to one exact head. Nothing else may be admitted early: a
  compile failure is deterministic, and no later check finishing can change its
  verdict, which is the only reason waiting is not required.
- Duplicate matrix/check updates do not create duplicate rounds.
- The program observes CI and schedules repair directly. There is no CI monitor
  agent and no Task Agent relay.
- One persistent runtime `AgentSession` with role `CI_FIXER` belongs to one
  Task. It is resumed across red rounds so it retains useful working context.
- The CI Fixer gets the Task writer lease before any mutation. No other agent
  writes until its terminal execution evidence and mechanical worktree
  inspection are durable.
- The CI Fixer may edit, run commands/checks, and commit. It cannot push,
  force-push, reply, resolve, merge, or spawn another agent.
- Repair placement is a program-resolved `RepairPlacementPolicy` per Task, never
  an agent choice. Under `TIP` a repair commit stays at the Task branch tip.
  Under `ATTRIBUTED_FIXUP` the program repositions each repair commit the fixer
  marked `fixup! <exact target subject>` to sit immediately after that target,
  and merges it into that target's existing fixup when one is already there, so
  a target never carries two.
- The fixer chooses the target, because that is judgment; the program does the
  repositioning, because a `fixup!` subject is fenced Git state and the rebase
  is then mechanical. A fix the fixer cannot attribute to one target stays a
  plain tip commit rather than being attached to a guess.
- Under `ATTRIBUTED_FIXUP` the program proves every commit boundary it changed
  compiles, from its own local build, before it may publish. A bare target that
  compiles red is accepted only when its own fixup is proven green at the
  boundary — never by reading a remote log to decide which commit failed.
- Rewriting published history requires force-with-lease and is authority the
  ordinary `CI_UPDATE` path does not carry; see `allowsHistoryRewrite` below.
- Program code never parses the CI Fixer's final prose. The runtime stores it as
  an opaque `AgentResult` and derives the handoff only from fenced Git state.
- The Task Agent must inspect the stored result and exact diff, and a fresh
  adversarial reviewer must inspect the exact candidate head before publication.
- The program pushes only with an exact `CI_UPDATE` gate authorization or
  narrow, current, Task-scoped `CI_UPDATE` standing consent.
- A lesson is opaque prose, is always `CANDIDATE`, and can be saved only after
  GitHub reports accepted required CI on the exact repair head.
- A lesson guides agent judgment. It never routes directly to a deterministic
  edit/recipe or bypasses raw-log investigation.
- CI logs and lesson prose are untrusted evidence. They cannot expand the CI
  Fixer's tool surface, authority, goal, or sandbox.
- Rounds are not bounded by a count. A large range legitimately needs many: a
  compile failure masks every test behind it, so each round can reveal work the
  previous one could not see, and a fixed ceiling would stop a converging run.
  This component does not enforce a spend ceiling of its own; it records what a
  run cost and leaves the ceiling to whoever owns the run. So the stops below are
  the only ones, and each is a fact rather than a quota.
- A round count is also the wrong non-convergence signal, because a good round
  often raises the failing count — repairing the compile failure lets the tests
  that never ran report. The program parks instead when the head changed and the
  provider reported the identical set of failing selectors: the fixer published
  something and it moved nothing, which is a judgment for the user.

## Accepted CI

For head `H`, `acceptedRequiredCi(H)` means:

1. the current program-owned `RequiredCiPolicyRevision` for the PR's exact
   repository/target-base scope is `RESOLVED` and defines the required check
   selectors and accepted conclusions;
2. every required check observation belongs to `H`;
3. each required check is terminal and has an accepted conclusion under that
   policy (`SUCCESS`, or an explicitly allowed neutral/skipped conclusion); and
4. no newer provider observation supersedes the set.

If the repository policy defines no required checks, acceptance is explicit and
vacuous for that policy revision. A green check on another SHA is never evidence
for `H`.

Under `ATTRIBUTED_FIXUP` one narrow exception applies, and only to a selector the
policy marks `perCommitCompile`. Such a check reports per-commit results for the
whole series, so a target commit whose repair lives in the fixup after it is red
in isolation by construction. That failure is accepted when the program holds its
own `BoundaryCompileProof` for the exact head showing the target+fixup boundary
compiles. The exception never applies to any other selector, never to a commit
without a following fixup, and never rests on remote log text.

## Logical data model

### `RequiredCiPolicyRevision`

```text
policyRevisionId, repositoryId, scopeKey, targetBaseRef,
sequence, resolution = RESOLVED | UNAVAILABLE,
sourceRef?, sourceDigest?, unavailableReasonRef?,
requiredCheckSelectors[], acceptedConclusions[],
recordedAt
```

The CI component owns this immutable normalized policy and one current pointer
per `(repositoryId, scopeKey)`, where the scope identifies the target base
branch/ruleset used by that PR. It may be refreshed from configured repository
rules or GitHub branch-protection/ruleset observations, but no agent and no
`finalizeHead` caller supplies it. A selector is a program-defined check identity
matcher, not model prose. `RESOLVED` with an empty selector list is an explicit
vacuously accepted policy; a missing pointer or `UNAVAILABLE` revision is a hard
`CI_POLICY_MISSING`/`CI_POLICY_UNAVAILABLE` blocker, never empty CI.

Publishing a new current revision stales open CI-derived gate evidence. For the
same PR/head it creates/reuses an immutable successor round under the new policy
and marks an older nonterminal round `SUPERSEDED`; historical observations and
rounds remain unchanged.

### `CiCheckObservation`

```text
observationId, prId, headSha, providerCheckId, providerRunId,
attempt, name, status, conclusion,
startedAt?, completedAt?, observedAt, rawEvidenceRef
```

The idempotency key is the provider check/run/attempt plus normalized state
revision. Updates append observations; they do not rewrite what an agent saw.

### `CiLogEvidence`

```text
logRef, observationId, contentDigest, exposedContentDigest,
rawByteCount, storedByteCount, truncated, storedAt
```

The current local-sidecar baseline rejects raw input above 4 MiB before UTF-8
decoding, removes control characters, performs best-effort redaction of known
authorization/token/secret/password/API-key/private-key/add-mask forms plus a
program-known literal list capped at 64 entries, 8-256 characters each and
4,096 characters total, and stores at most a UTF-8-safe 1 MiB head/tail
projection in SQLite. `contentDigest` covers the original raw
bytes; `exposedContentDigest` covers the stored redacted bytes. Redaction is not
a proof that arbitrary secrets were recognized. Reads use UTF-8-boundary-safe
windows capped at 64 KiB. Logs are not copied into prompts or worktree files.

### `CiRound`

```text
roundId, taskId, prId, remoteHead, policyRevisionId,
evidenceRevision, checkObservationIds[], failedLogRefs[], state,
createdAt, supersededBy?
```

`UNIQUE(prId, remoteHead, policyRevisionId, evidenceRevision)` makes provider
reruns and policy re-evaluation restart-safe. Only `COLLECTING` may refresh in
place. Once a revision reaches `FINAL_RED`, `GREEN`, `NEEDS_ATTENTION`, or a
later state, identical evidence is idempotent; changed observation IDs or
calculated state create the next immutable evidence revision and mark the old
revision `SUPERSEDED`. A new policy revision likewise never rewrites the old
round.

States are `COLLECTING`, `FINAL_RED`, `QUEUED`, `ACTIVE`, `FIX_PREPARED`,
`GREEN`, `SUPERSEDED`, and `NEEDS_ATTENTION`. Task review, gate, authorization,
and external-effect state are read from their owners rather than copied here.

### `CiRepairAttempt`

```text
attemptId, roundId, operationId?, agentRunId?, inputLocalHead, inputRemoteHead,
inputChangeSetRevisionId, outputLocalHead?, outputChangeSetRevisionId?,
localCheckRunIds[], resultRef?, state,
retryOfAttemptId?, retryOrdinal, createdAt
```

No `approved`, `confidence`, `verdict`, or parsed-summary field exists.

`UNIQUE(roundId, retryOrdinal)` and a partial unique constraint on non-null
`retryOfAttemptId` make retry-command redelivery return the same attempt instead
of creating sibling retries. `operationId` and `agentRunId` are absent while the
attempt is only a pending fact; `WorkSelector` sets the operation once, and the
operation-bound `startFresh`/`resume` transaction sets `agentRunId` once.
Attempt states are `PENDING`, `ACTIVE`, `NON_CLEAN_HANDOFF`, `FIX_PREPARED`,
`NO_HEAD_CHANGE`, and `NEEDS_ATTENTION`. Only `PENDING` permits both operation
and run to be absent; all other states require both exact IDs.

### `CiCleanupSeal` and `CiCleanupCompletion`

```text
CiCleanupSeal
  cleanupId, repairAttemptId, successorOperationId,
  actualHead, branchHead, attachmentState, kind, operations[], stateDigest,
  createdAt

CiCleanupCompletion
  cleanupId, runId?, resultRef?, outcome,
  outputHead?, outputChangeSetRevisionId?,
  finalActualHead?, finalBranchHead?, finalAttachmentState?, finalKind?,
  finalOperations[]?, finalStateDigest?, attentionReason?,
  inspectionFailureCode?, completedAt
```

There is exactly one immutable seal and at most one immutable completion per
cleanup ID. The seal is unique by predecessor repair attempt and successor
operation. The predecessor attempt remains `NON_CLEAN_HANDOFF` permanently and
retains its original operation, run, and result; cleanup never rewrites it as a
second repair result.

`FIX_PREPARED` and `NO_HEAD_CHANGE` completions require the cleanup run/result
and one mechanically adopted output revision. `NEEDS_ATTENTION` requires the
cleanup run/result plus either a second exact non-clean observation or a typed
stable final-inspection failure. `ADMISSION_BLOCKED` is the pre-body terminal
outcome for a stable seal mismatch or stable uninspectable repository state. It
has no cleanup run/result when blocked before the run transaction. If recovery
already retained a never-launched `QUEUED` run after deleting its expired fence,
the block preserves that run as `CANCELED` and stores a deterministic
program-owned `AgentResult`/never-launched stop proof; it never deletes durable
run history. `MOVED_DURING_INSPECTION`, `TIMEOUT`, and `INTERRUPTED` are
retryable observations and create no terminal completion. No outcome is derived
from model prose.

CI Autofix owns no session table. Repairs use the one runtime-owned persistent
`AgentSession(role=CI_FIXER)` defined by
[workflow-runtime.md](./workflow-runtime.md). Optional post-green learning uses
one distinct receipt-owned, one-shot `CI_LEARNER` session/run. It has no Task
writer pointer, writer lease, effect authority, or reuse as a repair session.

### `RepairPlacementPolicy`

```text
taskId, placement = TIP | ATTRIBUTED_FIXUP,
perCommitCompileSelectors[], allowsHistoryRewrite,
recordedAt
```

Program-owned and immutable per Task, resolved when the Task is created from what
produced it: an ordinary Task is `TIP`, a Task whose branch was built by
[Upstream Sync](./upstream-sync.md) is `ATTRIBUTED_FIXUP`. No agent reads or
writes it, and it is not a user setting to be toggled mid-run — the placement
decides how every round of that Task publishes, so changing it under a live
series would leave a branch shaped two different ways.

`allowsHistoryRewrite` is the standing authority a rewriting placement needs. It
is granted once, with the Task, by the same user act that chose an upstream sync;
a one-shot `CI_UPDATE` consent never confers it. Without it an
`ATTRIBUTED_FIXUP` round that would rewrite requires an explicit gate per round.

### `BoundaryCompileProof`

```text
proofId, taskId, attemptId, headSha,
boundaries[] = { commitSha, kind = TARGET_WITH_FIXUP | FIXUP | PLAIN,
                 exitState, evidenceRef },
profileRevisionId, provedAt
```

The program's own evidence that a rewritten series compiles where it matters. It
is produced by one rebase whose `exec` lines the program generates, placed only at
boundaries: after a fixup, and after a target that has no fixup. A bare target
followed by its fixup is deliberately not a boundary, which is exactly what makes
the acceptance exception provable rather than assumed. A missing or failed
boundary blocks publication; it never downgrades to a warning.

### `CiLesson`

```text
lessonId, repositoryId, learningOperationId, runId, subjectId,
status=CANDIDATE, title, markdown, contentDigest, createdAt
```

The current owner stores only the immutable `CANDIDATE`. Repair admission offers
at most the deterministic newest five candidates for the Task's exact
repository, ordered by creation time then lesson identity. The model sees only
an index, title, digest, and bounded opaque markdown; every read revalidates the
stored repository and content. Search, ranking, promotion, and supersession are
deferred. The program does not parse cause, commands, paths, or a success
verdict out of the prose.

## Observation and dispatch APIs

```text
CiAutofix.observeCi(prId, normalizedCheck) -> ObservationResult
CiAutofix.attachLog(observationId, rawLog, programKnownLiteralSecrets) -> logRef
RequiredCiPolicies.current(repositoryId, targetBaseRef) -> RESOLVED(policy) | UNAVAILABLE(policy) | MISSING
RequiredCiPolicies.record(repositoryId, scopeKey, targetBaseRef, sourceRef?, normalizedPolicyOrUnavailable) -> policyRevisionId
CiAutofix.finalizeHead(prId, headSha) -> roundId?
CiAutofix.acceptedRequiredCi(prId, headSha, policyRevisionId) -> AcceptedCiEvidence
CiAutofix.enqueueRepair(roundId) -> reconciliationOperationId
CiAutofix.finalizeAttempt(runId, resultRef, terminalOutcome, writerFence) -> AttemptFinalization
CiCleanupCoordinator.beginCleanup(claim, repositoryRoot, leaseTtl) -> CleanupBinding?
CiCleanupCoordinator.launchCleanup(binding, claim, repositoryRoot, body) -> ExecutionHandle
CiCleanupCoordinator.awaitCleanup(binding, handle, timeout) -> AgentResult
CiAutofix.enqueueRepairRetry(failedAttemptId, reasonCode) -> reconciliationOperationId?
CiObservationCoordinator.acceptCiObservation(activation, providerBatch) -> CiRound?
FlowRuntime.claimNextCiLearning(workerId, ttl) -> Claim?
CiLearningCoordinator.beginCiLearning(claim) -> CiLearningStart?
CiLearningCoordinator.saveLesson(start, claim, attemptId, title, markdown) -> contentDigest
CiLearningCoordinator.finish(start, claim, opaqueCompletion) -> AgentResult
CiLearningCoordinator.recoverExpiredCiLearning(operationId, generation) -> retryable
```

`finalizeHead` derives the PR's observed target base and resolves/freezes
`RequiredCiPolicies.current(repositoryId, targetBaseRef)` itself. Missing or
unavailable policy returns a blocker and creates no false green/red round. With
a resolved policy it derives requiredness and creates one `FINAL_RED`
round only after all required checks for the current head are terminal.
Infrastructure/provider-unavailable conclusions are recorded but do not
automatically ask an agent to modify code; policy may retry or surface them as
attention.
`observeCi` records provider facts only; a caller-supplied `required` flag is
ignored/rejected and cannot change policy.
`acceptedRequiredCi` verifies that the supplied revision is the current
`RESOLVED` revision for the PR's observed target-base scope; an old, missing, or
unavailable revision returns a typed blocker rather than evidence.

`enqueueRepair` is the sole `FINAL_RED` to `QUEUED` transition. It revalidates
the current policy, observation IDs, exact head, and complete frozen failed-log
references even on redelivery, then creates/reuses one deduplicated runtime
reconciliation `Operation`/`DispatchTicket`. There is no separate
mutation-intent record. It does not invoke the Task Agent or an agent scheduler
directly. `WAITING_USER` and `NEEDS_ATTENTION` Tasks retain the queued cause but
the runtime parks reconciliation until the Task resumes. If the Task is already
`COMPLETED` or `CANCELED`, the program preserves an idempotent terminal inbox
audit fact and creates no reconciliation or CI writer. A Task terminal before
first enqueue leaves the round `FINAL_RED`; a round already made `QUEUED`
remains historical `QUEUED` evidence when the Task later becomes terminal.
The runtime first resolves the unique `AgentRun.operationId` after
`WorkSelector` creates the selected `RUN_CI_FIXER` operation and the dispatcher
claims it. Redelivery always reuses that run. Only when no operation-bound run
exists does it resolve the Task's `AgentSession(role=CI_FIXER)`: if no session
exists, `AgentSessions.startFresh(...)` atomically creates the persistent session
and first run; later rounds use `AgentSessions.resume(...)`. Both methods are
idempotent by operation. No CI session or run is created speculatively.

`finalizeAttempt` is the ordinary `CI_ROUND`/`RUN_CI_FIXER` branch of the
runtime's role-specific finish transaction. With the still-valid claim and
writer fence, it mechanically inspects the worktree, appends/updates the
attempt, adopts any clean committed change set, and persists exactly one
`CI_FIX_READY` continuation whose program-owned payload distinguishes
`FIX_PREPARED` from `NO_HEAD_CHANGE`, or performs the non-clean handoff below.
Only after the owned finalization facts are durable may the outer finalizer
release or transfer the lease/selected pointer and return the persistent CI
session to `IDLE`. It never interprets `resultRef` prose. The generic runtime
agent finalizer is not a valid escape hatch for either CI role.
`InProcessWriterAgentSupervisor.launch` binds this CI finalizer before exposing
the handle or fixer body. `awaitAndFinalize` invokes that stored route only after
durable exact-thread `STOPPED` proof; cancellation and retry cannot replace it
with ordinary Task finalization or rerun the fixer body.
Once `beginRepair` has atomically bound the attempt, run, and lease on exact
head H1, a newer same-H1 policy/evidence revision does not interrupt it. This
includes exact redelivery while the bound run is still `QUEUED`. The bound round
may become `SUPERSEDED`, but the stopped run still stores its opaque result and
inspected candidate while the new evidence revision remains independently
current. This can spend one unnecessary fixer turn if H1 becomes green, but it
avoids an unsafe half-start cancellation protocol; Task review and fresh remote
gates still prevent publication. A remote-head, local-head, Task-lifecycle,
claim, or fence change still fails closed.

For `DIRTY` or `GIT_OPERATION_IN_PROGRESS`, the runtime mints a private prepared
token only after exact `STOPPED` proof and two matching bounded worktree
observations. One transaction stores the predecessor `AgentResult`, immutable
`CiCleanupSeal`, and `NON_CLEAN_HANDOFF` attempt; settles the old run, session,
operation, ticket, and inbox; creates one direct `CI_CLEANUP` operation/ticket;
swaps the Task writer pointer old-to-successor; and only then releases the old
lease. The Task's adopted head remains H1 even when the sealed actual worktree
head is H2. The successor starts `READY`/`AVAILABLE` with no run or lease. This
transaction reserves that exact mutation only; cleanup execution and its new
fence are deferred to the cleanup dispatcher. An unsafe or oversized state is
instead durably blocked as `NEEDS_ATTENTION` while retaining ownership; a
transient observation or stale authority does not rewrite the attempt.

The claimed cleanup successor cannot use generic writer admission. Before its
first body, the runtime recomputes the operation's full cleanup subject and
freshly re-inspects the worktree for exact equality with the immutable seal.
Only a runtime-minted private admission token can create the cleanup fence and
run. The Task's logical adopted state, fence head, and run head remain H1/C1;
the physical H2 and all index/untracked/control/config state are carried by the
seal digest and evidence reference. The same persistent CI Fixer session is
resumed for one new operation-bound run. Exact redelivery reuses that run. If a
never-launched claim expires after the run transaction, the new claim
re-inspects the seal, mints only a new claim-bound fence, and reuses the queued
run. Stable admission mismatch or unsafe state stores one `ADMISSION_BLOCKED`
completion, settles the successor without a body, and installs the unresolved
mutation barrier. If that stable block follows never-launched recovery, the
queued run becomes a retained canceled run with its program-owned result and the
same session returns to `IDLE`. Transient inspection failure leaves the claim
retryable.

The cleanup launch binds `CI_CLEANUP:<cleanupId>` as its immutable stopped
finalizer. Only after exact thread termination, tool revocation/drain, and
durable `STOPPED` proof does the runtime mint the private final-state token. A
clean final state is adopted once, directly from C1/H1, with source operation
and run equal to the cleanup. H3 produces `FIX_PREPARED`; an exact restoration
to H1 produces a new objective `NO_HEAD_CHANGE` revision. One outer transaction
stores the opaque `AgentResult` and `CiCleanupCompletion`, settles the run,
session, operation, and ticket, releases the fence/pointer, advances an active
round to `FIX_PREPARED`, and emits exactly one cleanup-keyed `CI_FIX_READY`.
Rollback leaves all of those facts unchanged, and exact retry reruns only the
finalizer, never the body or tools.

A second `DIRTY`/`GIT_OPERATION_IN_PROGRESS` observation stores its exact final
digest; another stable final-inspection failure stores only its typed failure
code. Both produce one `NEEDS_ATTENTION` completion, settle and release cleanup
authority, emit no `CI_FIX_READY`, create no third cleanup, and set
`Task.waiting_mutation_state_ref` to the exact cleanup-attention reference.
While that barrier remains, every ordinary Task lifecycle transition and every
writer admission fail closed. Only a future typed recovery that proves the
physical worktree safe may clear it. Exact replay returns the stored predecessor
and cleanup results even after either cleanup terminal outcome.

Repair retry remains a bounded CI-owned semantic retry. Post-green learning has
no semantic retry API in this checkpoint. Acceptance of a nonempty exact
source-bound `GREEN` round atomically creates or verifies one receipt-owned
`Operation(kind=RUN_CI_LEARNING)` and ticket. Its immutable subject freezes the
APPLIED receipt/plan/authorization/gate, originating red round and failed-log
digests, repair/optional-cleanup results and stopped-process proofs, published
change-set/diff, and the later current GREEN policy/round/ordered observations.
The publication policy and later GREEN policy are validated separately; policy
advance is allowed.

The learner is optional and lower priority than current `FINAL_RED`, `QUEUED`,
or `ACTIVE` repair work. It bypasses Task writer selection and uses a distinct
one-shot `CI_LEARNER` session. A running or quarantined learner never blocks Task
completion/cancellation or a repair writer. An explicit-empty current policy is
valid vacuous GREEN but creates no learning opportunity; a later nonempty
source-bound GREEN may create the one receipt-owned opportunity.

## CI Fixer launch contract

When the writer is available and the failed remote head is still the relevant
base, the runtime resumes or lazily creates the Task's CI Fixer with:

```text
CiFixLaunch
  taskId, prId, roundId
  taskGoal
  inputRemoteHead, inputLocalHead
  failedCheckObservationRefs[]
  failedLogRefs[]
  relevantCandidateLessonRefs[]
  currentDiffRef
  repositoryId, worktreePath, requiredCiPolicyRevisionId
  toolPolicy = CI_FIX
```

The program may retrieve the small exact-repository candidate projection above.
It labels it as prior evidence, never as an instruction or guaranteed fix.
The agent reads raw logs before changing code, including when a lesson appears
to match.

The launch contains references, not megabytes of logs or a strict output schema.
`taskGoal` is the exact self-contained goal stored when the Task started. The
program supplies objective repository/head/policy facts only; it does not
semantically assemble a Trunk transcript, repository interpretation, or Project
Intelligence projection for the fixer.
The runtime binds the writer fencing token below every mutating tool; the model
does not receive or return that authority value.

The CI Fixer's standing instruction says: when the evidence clearly includes a
build/compile failure, investigate that before downstream test/style failures,
which may be consequences of a tree that never built. This preserves a useful
ordering heuristic without making the program classify arbitrary log prose.

## Engine selection

The CI Fixer's engine is user configuration, not a program constant. No engine,
model, reasoning effort, provider endpoint, or credential name is compiled in as
a default or supplied by a deployment property file. A machine with no
configured and no discoverable engine has no CI Fixer, and says so.

### Audience resolution

Every new-flow agent role resolves exactly one workspace audience:

| Role | Audience |
| --- | --- |
| `TASK_AGENT` | `dev` |
| `CI_FIXER` | `ci-fix` |
| `CI_LEARNER` | `ci-fix` |
| `ADVERSARIAL_REVIEWER` | `review` |

The workspace is derived from the Task's frozen `repositoryId` against the
workspace repository list. A repository claimed by more than one workspace
resolves to the lowest non-scratch workspace ID. The rule is deterministic and
is never "most recently used".

### Resolution order

1. the workspace's row for the role's audience;
2. the workspace's default row;
3. the workspace's stored engine column;
4. discovery — the first installed CLI agent in catalog order, then the first
   API provider holding a stored credential, skipping any engine whose
   transport the runtime does not admit;
5. none — a stable non-effect `LaunchUnavailableException`, the Task moved to
   `NEEDS_ATTENTION` with `NO_ENGINE_CONFIGURED`, and its selected pointer and
   writer lease retained for a later retry.

Discovery probes each CLI agent's `--version` under a bounded timeout and
memoizes the outcome briefly. A probe never runs inside the launch-binding
transaction: an uninstalled CLI must not lengthen a database write.

### Choice grammar

Workspace settings store one picker choice id per audience. The grammar gains an
optional trailing effort segment and stays backward compatible — a value with
fewer segments means the omitted fields take the engine's own default.

```text
cli:<agent>[:<model>][:<effort>]
api:<provider>[:<account>][:<effort>]
local
```

An empty segment reads as unset, so `cli:claude-code::xhigh` selects the agent's
default model at `xhigh` effort.

### Reasoning effort

Effort vocabulary belongs to the engine and the model, not to a shared enum.
Claude and Codex do not accept the same values, and within Claude the ladder
narrows by model family:

| Engine | Model family | Accepted efforts | Default |
| --- | --- | --- | --- |
| `claude-code`, `anthropic` | Opus | low, medium, high, xhigh, max | high |
| `claude-code`, `anthropic` | Sonnet | low, medium, high, max | high |
| `claude-code`, `anthropic` | Haiku | none | — |
| `codex`, `openai` | GPT-5 family | minimal, low, medium, high | medium |
| any | other | none | — |

The stored effort is validated against the *selected model's* list during
resolution. When the choice omits a model — the common workspace case — the list
is the one belonging to that engine's default model, which is what the settings
page must offer too. A value that model does not accept is dropped to the engine default,
never passed through and never raised: a red build is not left unrepaired
because a saved effort outlived a model change.

Effort reaches the transport in that engine's native form:

- Claude Code CLI — `--effort <value>`, after `--model`.
- Codex CLI — `-c model_reasoning_effort="<value>"`, positioned before the
  `exec` subcommand so it survives `exec resume`, which rejects first-turn flags.
- Anthropic and OpenAI API — the transport's own effort field.
- Any other API provider — effort is rejected at resolution, not silently sent.

### Binding immutability

Resolution happens once, at launch binding. The binding freezes engine kind,
agent/provider, model, effort, endpoint, and the exact credential row and
revision, and every one of those values is part of the binding digest. A
settings change during an open repair therefore cannot move that run, and a
redelivery that resolves differently fails the identity check instead of
quietly running a second engine. The next CI round resolves fresh.

### CLI admission gate

Workflow Runtime did not admit an OS-process agent transport (see
[workflow-runtime.md](./workflow-runtime.md)), so a resolved `cli:` engine parked
the operation with `ENGINE_TRANSPORT_UNSUPPORTED`. It must never silently
downgrade to an API engine: a user who picked a CLI agent has made a billing and
privacy choice the program may not overrule, and that rule stands regardless of
what the transport can do.

What the gate demanded is now built, so the gate is closing rather than standing:

- a CLI turn leads **its own process group**, and the group — not a descendant
  walk — is the death receipt, because leaving a group takes a deliberate
  `setpgid` while outliving a parent is free;
- a writer turn is **mechanically unable to publish**: no push destination, no
  credential helper, no agent socket, no prompt, and the program separately
  verifies the remote head did not move and quarantines the turn if it did; and
- execution kind (`API` or `CLI`) is its own dimension, **not** a member of the
  wire-dialect enum, and a CLI launch binding records what it can honestly pin —
  binary and version — while naming no credential, because the login lives in the
  user's CLI where this program cannot see it.

The consequence to keep in view: a CLI run's sealed binding proves strictly less
than an API run's, permanently. That is a property of delegating authentication,
not a gap to be closed later.

Both CLI engines are supported writers, and which one runs is workspace
configuration resolved through the same chain every other agent uses — not a
safety decision. Containment removes the capability rather than policing the
command, so it does not depend on a particular vendor's sandbox.

Discovery is the exception, and only because nobody chose anything there: an
installed CLI it cannot launch is passed over for a runnable API engine rather
than parking a red build on a preference no user expressed.

### Build order

1. Per-run resolution replacing the constructor-injected launch config, API
   engines only. Workspace picks take effect for every new-flow role.
2. Removal of the compiled-in engine defaults, plus the discovery and
   `NO_ENGINE_CONFIGURED` rungs.
3. Effort segment in the choice grammar, catalog-aware validation, and the
   settings-page effort control beside each audience's model control.
4. The out-of-process supervisor and CLI lane, which clears the admission gate
   above and is the only step that changes Workflow Runtime invariants.

## CI Fixer tools

```text
read_ci_failure_context()
read_ci_log(index, offset)
list_candidate_lessons()
read_candidate_lesson(index)
list_repository()
read_file(path)
search_repository(query)
write_file(path, content)
delete_file(path)
run_checks(command[], working_directory)
commit_repair()
```

No generic shell, raw Git, owner ID, claim, fence, or arbitrary commit message is
model-visible. The `run_checks` MCP tool accepts the exact argv and
worktree-relative working directory Claude selected for one narrow useful
command; the program validates
the executable against current policy, cwd containment, and the exact clean head,
executes without a shell, and records the actual command/cwd/evidence. A turn
permits up to ten valid attempts for repairing and retrying `FAILED` checks; one
`UNAVAILABLE` result ends further check attempts but may remain manual-only
evidence. Repository traversal, text I/O, search, checks, and the fixed commit
remain bounded program operations. Candidate tools accept only a program-derived
index and never apply edits or claim a lesson matches.

The cleanup run uses the distinct program-owned
`ci-cleanup-capabilities:v1` set. It may use bounded read/edit and its fixed
commit tool, but it cannot call the adopting `run_checks` tool or generic
change-set adoption. Cleanup supports exactly one adoption, performed by its
STOPPED finalizer from C1/H1 to the final clean state. Formal `LocalCheckRun`
evidence for the final adopted revision is produced later by the Task
review/gate path. This prevents
an intermediate C2 followed by more cleanup edits from stranding finalization.

The fixer ends its turn normally; there is no `finish`, `passed`, `nothing`, or
`park` semantic tool. The runtime always stores the opaque final response, then
mechanically inspects the fenced worktree before releasing the lease:

- clean with a new committed head: capture head/diff/check evidence, persist
  `CI_FIX_READY`, and ensure Task reconciliation;
- clean with no head change: persist the opaque result plus objective
  `NO_HEAD_CHANGE`, then ensure Task reconciliation; or
- dirty/unmerged: reserve one bounded cleanup resume to the same fixer session;
  if that successor still cannot leave a committed or restored worktree, store
  its immutable attention completion and block the Task. The original attempt
  remains `NON_CLEAN_HANDOFF`.

Budget exhaustion and execution failure follow the same mechanical worktree
inspection, but only after the supervisor proves the exact in-process thread
ended and its tool capability is revoked and drained. Lease expiry alone never
permits inspection or a successor writer. New-flow CLI execution remains
unsupported. No missing model call can block recovery.

The learning turn has a narrower tool policy:

```text
read_repair_evidence()
read_ci_log(index, offset)
save_ci_lesson(title, markdown) -> contentDigest
```

The runtime binds every provenance input; the model chooses no attempt, receipt,
round, policy, observation, or log reference outside the frozen subject.
Repair/cleanup result prose is a bounded opaque view and grants no authority.
`save_ci_lesson` is terminal: one immutable request/seal is stored in the same
transaction that revokes all tools. Identical response-loss replay returns the
seal; conflicting content and every later tool call fail. The turn has no
writer lease and no code, Git, GitHub, lesson-read, or supersession tools.

## End-to-end lifecycle

### 1. Observe final red CI

1. [GitHub integration](./github-integration.md) normalizes check updates for
   remote head `H1` and calls `observeCi`.
2. Required checks remain `COLLECTING` until their current attempts are terminal.
3. The program fetches each failed job log once and stores a `CiLogEvidence`.
4. `finalizeHead` creates one immutable exact-head `FINAL_RED` evidence revision.
5. `enqueueRepair` freezes its failed logs, moves it to `QUEUED`, and ensures the
   Task's deduplicated reconciliation ticket, even while another writer runs.
   `WorkSelector` waits for writer admission and gives current final red CI
   priority over feedback; event ingestion itself never drops or delays the fact.
6. Compile-priority admission short-circuits steps 2-4 for one case only: a
   `perCommitCompile` selector whose current attempt is terminal and failed
   enqueues repair immediately, at `PARTIAL_RED_COMPILE`, with only that
   selector's logs frozen. The round stays bound to the same exact head and is
   superseded normally once the repair publishes and the head moves. Every other
   selector still waits to be terminal, so nothing else is judged early.
7. Within a round the fixer is given compile failures first and told the rest of
   the board is unjudged. A series that does not compile makes every downstream
   test result meaningless, so spending a round on those results first is waste.

### 2. Diagnose and commit locally

1. The runtime rechecks that the PR is open, remote head is still `H1`, and no
   newer local unpublished work supersedes the round.
2. It grants the fenced writer lease and resumes the persistent CI Fixer.
3. The fixer reads complete relevant log windows and candidate lessons.
4. It diagnoses, edits, selects a narrow useful local command, and calls
   `run_checks` with its exact argv and worktree-relative directory. It may repair
   and retry `FAILED` checks within the ten-attempt turn bound, but cannot retry
   after `UNAVAILABLE`, then commits at least one coherent repair commit.
5. It ends the turn with ordinary prose.
6. `AgentRuns.finish` stores that prose as its tagged terminal result and invokes
   `CiAutofix.finalizeAttempt` under the live claim/fence. For a clean new commit
   the finalizer stores actual output head `H2`, diff/check evidence, and the
   attempt before releasing/transferring the lease and returning the session to
   `IDLE`.
7. A clean unchanged head still persists a Task-inspection continuation and
   ensures reconciliation; a dirty head receives bounded cleanup recovery and
   otherwise becomes attention.
8. Under `ATTRIBUTED_FIXUP`, and only after the attempt's output head is durable,
   the program rewrites: it generates a rebase todo that moves each `fixup!`
   commit behind its target, merges it into that target's existing fixup if one
   is present, and places `exec` boundary builds. The result is a new head `H2'`
   and a `BoundaryCompileProof`. A rewrite that cannot be generated
   deterministically, or whose proof is incomplete or red, leaves the attempt at
   its unrewritten head and becomes attention — the program never publishes a
   series it could not prove.

### 3. Task Agent inspection

When `WorkSelector` selects that continuation, the runtime resumes the persistent
Task Agent with:

```text
CiFixReviewLaunch
  roundId, attemptId, inputHead=H1, candidateHead=H2?,
  diffRef, localCheckPolicyRevisionId, localCheckRunRefs[], ciFixerResultRef,
  pendingFeedbackContentRevisionRefs[], pendingFeedbackObservationRefs[]
```

The Task Agent:

1. reads the exact diff and CI Fixer result;
2. corrects or reverts anything it judges wrong;
3. runs checks on the final committed candidate;
4. spawns a fresh read-only adversarial reviewer for that exact head;
5. acts on the review prose; and
6. finishes normally. Program preflight then verifies the report was consumed,
   Git is clean and committed, no Git operation remains, and the candidate diff
   introduces no conflict markers. It resumes the same Task session at most
   five times for mechanical repair.

The reviewer request is a zero-argument terminal Task tool: the program freezes
the current revision, exact head/tree/diff, the Task turn's frozen remote input,
and the complete ordered latest run refs for every profile required by the
current local-check policy. It executes no checks; Claude must invoke
`run_checks` first. Initial reservation atomically revalidates that exact
policy/revision/head set; a subset or older attempt cannot be supplied. A durable
request replays its frozen policy/refs even if policy later advances. Reviewer
check-output reads remain deferred. The implemented local `CI_UPDATE` gate
blocks `FAILED`, treats genuine `UNAVAILABLE` as manual-only, and rejects
missing/stale/process-boundary evidence. An unchanged
`AgentResultReady` continuation may
end without requesting another reviewer; that only records that the opaque child
result was consumed and returns the persistent Task session to idle. It is not
review approval, readiness, or permission to construct a gate. If that
continuation adopts any Task-owned descendant revision, it must request a fresh
reviewer before stopping. A changed turn without that request consumes its input
and enters typed `NEEDS_ATTENTION`; ordinary lifecycle resume is forbidden until
the future recovery owner resolves that exact reason.

`ACCEPTED_SEALED` only seals the candidate against more model mutation. The
implemented stopped finalizer locks and revalidates the exact PR remote,
current Local Checks policy/latest runs, current required-CI policy/actionable
round, clean current Task change set, and completed same-head reviewer. It then
opens or revises one local `CI_UPDATE` gate whose exact subject references the
User Gates-owned deterministic complete-empty local-review binding for this
PR/change set, and settles the Task result/session/input/pointer/lease
atomically. Parent `FAILED` or `CANCELED` prose/outcome does
not undo an already accepted command. Stable post-seal drift records typed
attention and no gate; a transaction failure retries the stopped finalizer and
does not rerun the agent body.

The implemented manual command supplies the local user's semantic review for an
exact current OPEN `CI_UPDATE` revision and rejects a historical absent binding.
A fixed-local-user one-shot Task consent lasting at most 24 hours can authorize
only a newly opened exact revision whose local checks are all `PASSED`;
manual-only `UNAVAILABLE` remains manual. Granting consent never scans an
existing gate. Both paths atomically store the immutable authorization, one-step GitHub push plan,
runtime `PUBLISH` operation, and ticket; that commit installs the publication
barrier. Claim locks the PR and admits the exact stored graph only at its oldest
nonterminal sequence. Begin revalidates all current owners and records
`EXECUTING` without calling Git or GitHub. The concrete GitHub executor then
commits a distinct immutable attempt before each possible exact-lease call
(maximum two) and accepts only an exact remote probe as success proof.
Configurable/multi-use consent, consent UI, general observation routing, and
timeline events remain deferred.

Under `ATTRIBUTED_FIXUP` the Task Agent and the adversarial reviewer inspect the
rewritten series and `H2'`, not the pre-rewrite tip. The rewrite is what will be
published, so reviewing anything else reviews a head that will never exist
remotely.

### 4. Authorize and push

If there is pending remote feedback, a local user-review comment, or any non-CI
work in the candidate, CI standing consent cannot apply; the combined candidate
uses the appropriate explicit gate. V1 does not edit PR title/body after first
publication.

For a CI-only candidate:

- `GateCommands.openOrRevise(...)` persists the exact `CI_UPDATE` gate revision;
- without standing consent, the user calls the canonical exact-digest
  `UserGates.authorizeCiUpdate(...)` defined by
  [User Gates](./user-gates.md); and
- with a current unused one-shot Task-scoped `CI_UPDATE` consent, the
  stopped-ready transaction may authorize the newly opened exact revision only
  after all normal readiness facts are rechecked.

Either authorization path atomically persists the authorization, frozen GitHub
effect plan, runtime `Operation`, and unique `DispatchTicket`. A best-effort
in-process dispatcher nudge occurs only after commit; ticket polling recovers a
lost nudge. There is no separate GitHub-effect submission or claim protocol.

A rewriting round publishes with force-with-lease against the remote head the
round was bound to, so a remote that moved underneath rejects the push instead of
discarding it; a rejected lease becomes a fresh observation, never a retry with
the lease dropped. The gate action carries that it rewrites, plus the outgoing and
incoming heads, and is authorized by `allowsHistoryRewrite` or an explicit
per-round gate. Each rewrite restarts the whole remote board, which is the cost
that makes attribution a placement policy rather than a default.

[GitHub integration](./github-integration.md) pushes exact `H2`, confirms the
remote head, and atomically installs a receipt-owned `OBSERVE_CI` watch. Its
private exhaustive provider batch is accepted here as exact source-bound
observations, logs, and the existing `CiRound`; there is no second CI snapshot
engine. Neither agent pushes or constructs provider facts.

### 5. Wait for remote proof

1. CI for `H2` is a source-bound round. Old-head and old-receipt checks are
   historical evidence only and cannot satisfy it.
2. `FINAL_RED` with every selected required failure log commits the round,
   inbox fact, reconciliation wake, and watch rearm atomically. The existing
   persistent CI Fixer loop consumes that round.
3. `COLLECTING`, `NEEDS_ATTENTION`, and `GREEN` are stored without a repair wake
   and rearm the same receipt watch. Stable unsupported log provenance also
   rearms without partial facts so a later supported rerun or green result can
   be observed.
4. Current-policy advance waits for a new provider batch; it never reinterprets
   an old sourced batch as unsourced evidence.

For an exact nonempty source-bound GREEN after an APPLIED repair publication,
acceptance atomically reserves/replays the optional receipt-owned learner. It
does not complete the Task or authorize ready/merge. Ready/merge decisions,
test-merge and legacy-status selection, webhooks, timeline projection, general
observation routing, learning retries, lesson search/ranking, supersession, and
promotion remain deferred.

## Lesson contract

A useful lesson normally records:

- the recognizable failure/log title;
- what caused it in this repository;
- the resolution that remote CI confirmed;
- why that resolution worked;
- approaches attempted that did not work; and
- the limits that should make a future agent reread raw logs.

This is guidance, not a required mini-schema. The `markdown` body remains
opaque. The tool call supplies only program-owned provenance links.

When a future round resembles a lesson:

1. the bounded latest-candidate list offers the prior hint;
2. the CI Fixer still reads the current raw log and code;
3. if following it fails, the failed attempt remains evidence; and
4. after eventual exact-head remote green, the isolated learner may save a new
   independent candidate. Supersession remains deferred.

Old candidates are retained. Nothing automatically mutates or executes code
because lesson text matched.

## Concurrent CI and feedback

- Observation is concurrent; mutation is not.
- If both are queued before a writer starts, final red CI runs first.
- Feedback arriving during a CI Fixer turn waits and is included in the
  subsequent Task Agent review launch.
- CI arriving during a Task Agent feedback turn does not interrupt it. At turn
  end the runtime compares heads. An old-head red result cannot prove the new
  unpublished head is red; the Task Agent sees it as evidence, then the next
  authorized push obtains exact-head CI.
- A CI Fixer never applies a patch produced against a different live worktree
  state. Each launch records both failed remote head and actual input local head.
- A combined CI + feedback candidate always uses the explicit
  `REMOTE_FEEDBACK` gate; CI standing consent cannot publish human-facing work.

## Recovery

- **Duplicate check updates:** immutable observation key prevents duplicate
  rounds or wakes.
- **Matrix still running:** keep `COLLECTING`; never wake on the first failure
  while required sibling checks are nonterminal unless policy explicitly
  cancels them.
- **CI Fixer process dies before a terminal result:** after old-generation death
  proof, issue a new claim/process attempt for the same operation-bound run. Do
  not create a semantic repair attempt or another `AgentRun`.
- **Crash with dirty worktree:** quarantine the writer lease. Restore from the
  attempt's input head or ask the user; never dispatch another writer on top.
- **Commit exists after process failure:** store failed execution evidence and
  mechanically inspect the fenced worktree. A clean new committed head is sent
  to the Task Agent as an untrusted candidate; dirty state follows bounded
  cleanup/quarantine. No missing agent call blocks recovery.
- **Task Agent rejects the fix:** its new commit/revert becomes the candidate;
  the original attempt remains visible and cannot be published directly.
- **Push timeout:** the effect executor probes remote head before retry.
- **New remote head:** supersede the old round and reconcile. Never force over
  external history.
- **Terminal failed repair run:** retain its result/attempt. If bounded retry is
  justified, `enqueueRepairRetry` creates a linked attempt/pending subject; the
  selector produces a new operation/run. Exhaustion becomes `NEEDS_ATTENTION`.
- **Green observed, terminal learning run fails/cancels:** CI remains green and
  Task progress is not blocked. An accepted durable lesson request still wins
  as `CANDIDATE` if the exact GREEN remains current; otherwise completion is
  `MISSED`. Opaque final prose never becomes a lesson.
- **New red round while learning is queued/running:** repair admission outranks
  an unstarted learner. A running learner is isolated and does not block the
  repair writer; its finalizer may only save while its bound GREEN is current.
- **Expired learner:** no-attempt recovery redrives the same operation; a
  `RESERVED` recovery also reuses its already-created run/session under the next
  claim generation. Neither branch requires current GREEN; the next begin
  cancels if authority became stale. `ACTIVATED` without a STOPPED proof is
  fail-closed and quarantines only learner process/run/session/dispatch facts,
  with no successor generation. `STOPPED` recovery replays the exact completion
  sealed beside the stop proof; the CI owner locks and requires current GREEN
  before creating a candidate from an already accepted lesson seal, otherwise
  it records `MISSED`. It never mutates Task lifecycle or writer
  state.

## Timeline projection

The deferred PR timeline projector will project meaningful owner facts:

- finalized red CI round;
- CI Fixer started/completed/parked;
- local repair candidate and Task Agent inspection;
- exact update authorization and proven push;
- new exact-head CI result; and
- optional “lesson captured” link.

Raw matrix updates, shell commands, log windows, token use, and agent transcript
remain execution details. The agent never records timeline events. See
[pr-timeline.md](./pr-timeline.md).

## Required acceptance traces

1. **One-round repair:** final red `H1` -> CI Fixer commit `H2` -> Task inspection
   -> adversarial review -> user gate -> program push -> remote green `H2` ->
   lesson candidate.
2. **Standing consent:** the same trace creates a fresh exact authorization and
   pushes without another click only when the diff is CI-only and consent is
   current.
3. **No premature wake:** partial/matrix CI updates create no repair turn until
   the required set is final red.
4. **Duplicate events:** repeated reconciliation observations create one round
   and one fixer wake; an optional webhook signal cannot add another.
5. **Informal final prose:** any text is stored; actual fenced Git state alone
   determines whether a candidate head exists.
6. **No commit:** a clean unchanged head wakes the Task Agent with
   `NO_HEAD_CHANGE`; a dirty head receives one cleanup resume, then quarantines
   without another writer.
7. **Second red round:** exact red `H2` resumes the same CI Fixer session with
   prior attempt/result references.
8. **Lesson restraint:** local green followed by remote red saves no lesson;
   exact remote green permits only a `CANDIDATE` lesson.
9. **Bad remembered fix:** a relevant lesson is read, current logs disprove it,
   eventual green creates a linked replacement candidate; no recipe auto-runs.
10. **CI plus feedback:** one writer at a time; Task Agent sees both durable
    inputs; one explicit feedback gate publishes the combined head.
11. **Crash after push:** remote-head probe recovers without a duplicate push.
12. **Upstream-sync PR:** after its draft PR exists, red CI follows this exact
    generic path; no upstream history rewrite occurs here.
13. **Ready policy across CI repair:** an authorized CI-only `H2` may be marked
    ready after fresh exact green and blocker checks under the current policy;
    an external, stale, unauthorized, or feedback-driven head cannot inherit it.
14. **Required-policy revision changes:** finalize `H2` under policy `P1`, then
    publish current same-target scope `P2` before ready/merge. The unique `P1`
    round remains historical; the program creates/supersedes to the unique
    `(PR,H2,P2)` round, stales `P1` gate evidence, and cannot accept, ready, or
    merge until `P2` is satisfied. A missing/unavailable scope blocks rather
    than behaving like explicit empty policy.
15. **Learning isolation:** exact nonempty source-bound green creates one
    receipt-owned `RUN_CI_LEARNING` operation/ticket. Repair outranks an
    unclaimed learner; a claimed learner has no Task pointer/lease and cannot
    block Task lifecycle or repair admission. Empty-policy green creates none.
16. **Run-start crash:** stop after the one-shot learner session/run commits but
    before process activation. If no run exists, stale GREEN cancels only the
    operation/ticket and creates no `AgentResult` or learning completion. If a
    `QUEUED` run exists, it stores a program-owned never-launched canceled result
    plus `MISSED`. Expiry reclaims the same operation and reuses a created
    run/session. Neither path creates a duplicate turn.
17. **Live-claim expiry:** let the learner process remain alive after its
    dispatch claim expires. An uncertain `ACTIVATED` attempt is quarantined
    without a successor generation or Task mutation; it is never declared dead
    or retried. `RESERVED` redrives as above. A durable
    `STOPPED(NORMAL_RETURN)` proof lets the runtime store the lost-completion
    result while the CI owner alone decides candidate versus `MISSED`.
18. **Process recovery versus semantic retry:** crash one fixer process before
    any terminal result and assert a new process attempt reuses the same run.
    The optional learner has no semantic retry: reserved recovery reuses its one
    run, activated uncertainty quarantines it, and STOPPED recovery produces one
    exact completion without inventing prose.

## First-principles challenge

| Question | Decision | Tradeoff |
|---|---|---|
| Is CI observation an agent job? | No. Provider check IDs, heads and conclusions are deterministic inputs. | Program must maintain adapters/reconciliation. |
| Does CI repair deserve a separate persistent agent? | Yes. Logs and unsuccessful attempts repeat across long remote waits; retained specialist context has value. | Another session and budget to manage. |
| Should the CI Fixer push to close its own loop? | No. Publication is authority, not diagnosis. Program push plus exact consent prevents accidental external effects. | One Task Agent review handoff per candidate. |
| Should the program parse logs into a root-cause verdict? | No. It may bound/index logs; the agent judges cause and scope. | More agent reading, fewer brittle parsers. |
| Should a matching old lesson auto-apply a fix? | No. Similar logs can have different causes. Lessons remain candidate prose. | Repeated fixes still use tokens. |
| Why save only after remote green? | The authority being optimized is remote CI. Local success or agent confidence is insufficient evidence. | Learning is later and optional; once its terminal save seal commits, restart recovery preserves that accepted command. |
| Why Task Agent review after the specialist? | The specialist knows CI; the Task Agent owns product intent and accumulated implementation decisions. | Slower than direct push, but prevents locally green semantic regressions. |
| Can each check observation store a mutable `required` flag? | No. Requiredness comes from one immutable current `RequiredCiPolicyRevision`. | One extra policy owner, but every red/green/gate decision is reproducible after configuration changes. |
| Is a generic recipe/classifier engine needed? | No. It previously duplicated agent judgment and learned from weak evidence. Retrieval plus raw logs is the minimum reliable memory. | No deterministic “zero-token” replay path in this version. |

## Evidence and adopted/rejected ideas

- **Accept Codex's narrow subagent roles and read/write boundaries:** Codex
  supports role-specific agent configuration and explicit collaboration
  lifecycle. ByteQuay gives the CI Fixer a narrow purpose and tool surface, but
  adds the product's durable lease and remote proof.
  [Codex subagents](https://learn.chatgpt.com/docs/agent-configuration/subagents),
  [Codex spawn implementation](https://github.com/openai/codex/blob/3aae5d885bac39c1262491aa3fd100dfd8b3919f/codex-rs/core/src/tools/handlers/multi_agents/spawn.rs#L124-L224)
- **Accept Grok's resumable independent sessions:** Grok supports
  `resume_from`, capability modes, and retrieving background results. That
  supports one persistent specialist across rounds. ByteQuay rejects Grok's
  optional shared concurrent writes and serializes the Task worktree.
  [Grok subagents](https://github.com/xai-org/grok-build/blob/8a14c91d88875a831a38b3a066b1683116bcb31c/crates/codegen/xai-grok-pager/docs/user-guide/16-subagents.md)
- **Accept Grok's visible background lifecycle:** long CI waits belong to
  program-owned pending tasks, not a blocked agent turn.
  [Grok background tasks](https://github.com/xai-org/grok-build/blob/8a14c91d88875a831a38b3a066b1683116bcb31c/crates/codegen/xai-grok-pager/docs/user-guide/20-background-tasks.md)
- **Preserve the useful prior ByteQuay lesson:** the persistent CI Fixer retains
  repair context across rounds, while an isolated post-green learner may save
  one candidate explaining a confirmed repair. **Reject** the
  prior agent-owned push, `COMMITTED:`/`PARKED:` marker lines, combined
  cherry-pick+CI session, confidence/rule engine, and worktree log files.
- **Reject the former generic harness thesis that a deterministic program should
  apply model-proposed edits:** the semantic split is cleaner when the CI Fixer
  owns diagnosis and local editing while the program owns facts, leases, tools,
  and external effects.

## Implementation completion rule

The component is complete only when all eighteen traces pass with arbitrary agent
prose and injected process/provider failures. A flow that depends on marker
lines, final JSON, the CI Fixer pushing, or an old harness table/service is not
this design.
