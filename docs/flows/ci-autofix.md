# CI Autofix

Status: **normative greenfield replacement specification**

CI Autofix repairs finalized red CI for any published Task PR. The program
observes and deduplicates CI, then dispatches one persistent CI Fixer through the
Task selector without a Task-Agent relay. The CI Fixer may inspect logs, edit, test, and commit under the Task's
sole writer lease. It never pushes. The Task Agent inspects and adversarially
reviews the exact new head before the program may publish it.

This specification replaces every earlier generic post-PR CI/harness design.
Upstream range construction and special history shaping now belong only to
[upstream-sync.md](./upstream-sync.md).
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
- the dispatch contract back to the Task Agent.

It does not own upstream commit selection, cherry-pick conflicts, fixup-history
placement, PR publication, remote feedback, merge, or the user gate.

## Hard invariants

- A repair round is created only for a finalized required-CI state bound to one
  exact remote head SHA.
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
- A normal repair commit stays at the Task branch tip. Generic CI Autofix never
  rewrites upstream-sync history or chooses a `fixup!` owner.
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

CI Autofix owns no session table. The Task references the one runtime-owned
`AgentSession(role=CI_FIXER)` defined by
[workflow-runtime.md](./workflow-runtime.md). Every repair/learning turn is a
new `AgentRun` in that session; a replacement process therefore cannot create a
second logical fixer or writer. Session admission permits only one live turn.

### `CiLesson`

```text
lessonId, repositoryId, status=CANDIDATE, title, markdown,
sourceRoundId, sourceAttemptId, confirmedHead,
failedLogDigests[], createdByAgentRunId, createdAt,
supersedesLessonId?
```

The program indexes title/markdown for relevance retrieval. It does not parse
cause, commands, paths, or a success verdict out of the prose.

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
CiAutofixCoordinator.beginCleanup(claim, repositoryRoot, leaseTtl) -> CleanupBinding?
CiAutofixCoordinator.launchCleanup(binding, claim, repositoryRoot, body) -> ExecutionHandle
CiAutofixCoordinator.awaitCleanup(binding, handle, timeout) -> AgentResult
CiAutofix.enqueueRepairRetry(failedAttemptId, reasonCode) -> reconciliationOperationId?
CiAutofix.observeRemoteGreen(prId, headSha) -> GreenResult
CiAutofix.enqueueLearning(attemptId, confirmedHead, acceptedCiEvidenceRef) -> operationId
CiAutofix.enqueueLearningRetry(failedRunId, reasonCode) -> operationId?
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

The two retry commands are bounded, CI-owned semantic retries. They require the
prior run/result terminal. A repair retry idempotently appends or returns the
unique linked `CiRepairAttempt` revision and pending fact; `WorkSelector` later
sets its new operation once and `resume` sets its new operation-bound run once.
A learning retry creates a new linked `RUN_CI_LEARNING` operation whose subject
includes the next retry ordinal. Neither command reopens a terminal run or
reuses its operation.

`enqueueLearning` atomically creates/reuses one
`Operation(kind=RUN_CI_LEARNING)` plus its unique `DispatchTicket`, with subject
digest bound to `(attemptId, confirmedHead, acceptedCiEvidenceRef)`. It is
read-only and bypasses Task writer selection/lease, but the dispatcher may claim
it only while the persistent CI session is `IDLE` and, under the Task/CI-owner
lock, no current eligible `FINAL_RED`/repair cause is pending or selected. Thus a queued repair has priority
over queued learning even before its writer operation is materialized. The
successful claim atomically reserves the CI session before process start. If learning is already running when a new red round
arrives, it is not interrupted; reconciliation waits on that exact bounded
AgentRun, whose terminal transition releases the wait once, then repair starts.

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

The program may retrieve a small relevance-ranked candidate lesson projection.
It must label it as prior evidence, never as an instruction or guaranteed fix.
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

## CI Fixer tools

```text
list_failed_checks(round_id)
read_ci_log(log_ref, query?, before?, after?, max_bytes?)
use_ci_lesson(lesson_id)
read_file(path, range?)
search_repository(query, paths?)
run_command(argv, timeout?)
edit_file(...)
commit_changes(message) -> commitRef
```

`run_command` executes only inside the Task worktree under the current fixer
capability policy. The host denies Git remote writes, GitHub mutation, branch
switching, and destructive history commands regardless of the command text;
`commit_changes` is the only fixer commit boundary.

`use_ci_lesson` returns one relevance-selected candidate's opaque prose and
records that the attempt consulted it. It does not apply edits or claim the
lesson matches.

The cleanup run uses the distinct program-owned
`ci-cleanup-capabilities:v1` set. It may use bounded read/edit/command/commit
tools, but it cannot call the generic adopting `run_checks` tool or generic
change-set adoption. Cleanup supports exactly one adoption, performed by its
STOPPED finalizer from C1/H1 to the final clean state. Commands may run tests
inside the live cleanup turn, but formal `LocalCheckRun` evidence for the final
adopted revision is produced later by the Task review/gate path. This prevents
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
read_ci_repair_attempt(attempt_id)
read_ci_log(log_ref, query?, before?, after?)
use_ci_lesson(lesson_id)
save_ci_lesson(title, markdown, supersedes_lesson_id?) -> lessonId
```

The runtime binds the current confirmed repair attempt; the model does not pass an
attempt ID or other provenance metadata. The turn has no writer lease and no
code/Git/GitHub mutation tools.

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

### 2. Diagnose and commit locally

1. The runtime rechecks that the PR is open, remote head is still `H1`, and no
   newer local unpublished work supersedes the round.
2. It grants the fenced writer lease and resumes the persistent CI Fixer.
3. The fixer reads complete relevant log windows and candidate lessons.
4. It diagnoses, edits, runs useful local checks, and commits at least one
   coherent repair commit.
5. It ends the turn with ordinary prose.
6. `AgentRuns.finish` stores that prose as its tagged terminal result and invokes
   `CiAutofix.finalizeAttempt` under the live claim/fence. For a clean new commit
   the finalizer stores actual output head `H2`, diff/check evidence, and the
   attempt before releasing/transferring the lease and returning the session to
   `IDLE`.
7. A clean unchanged head still persists a Task-inspection continuation and
   ensures reconciliation; a dirty head receives bounded cleanup recovery and
   otherwise becomes attention.

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
6. calls `ready_for_review()`, which returns `ACCEPTED_SEALED` or an actionable
   tool error.

The reviewer request is a zero-argument terminal Task tool: the program freezes
the current revision, exact head/tree/diff, the Task turn's frozen remote input,
and the complete ordered latest run refs for every profile required by the
current local-check policy. Initial reservation atomically revalidates that exact
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
It atomically stores the immutable authorization, one-step GitHub push plan,
runtime `PUBLISH` operation, and ticket; that commit installs the publication
barrier. Claim locks the PR and admits the exact stored graph only at its oldest
nonterminal sequence. Begin revalidates all current owners and records
`EXECUTING` without calling Git or GitHub. The concrete GitHub executor then
commits a distinct immutable attempt before each possible exact-lease call
(maximum two) and accepts only an exact remote probe as success proof. Standing
consent, general observation
routing, and timeline events remain deferred.

### 4. Authorize and push

If there is pending remote feedback, a local user-review comment, or any non-CI
work in the candidate, CI standing consent cannot apply; the combined candidate
uses the appropriate explicit gate. V1 does not edit PR title/body after first
publication.

For a CI-only candidate:

- `GateCommands.openOrRevise(...)` persists the exact `CI_UPDATE` gate revision;
- without standing consent, the user calls the canonical exact-digest
  `GateCommands.authorize(...)` defined by [User Gates](./user-gates.md); and
- with current Task-scoped `CI_UPDATE` standing consent,
  `ConsentEvaluator.maybeAuthorize(...)` may authorize that same exact revision
  only after all normal readiness facts are rechecked.

Either authorization path atomically persists the authorization, frozen GitHub
effect plan, runtime `Operation`, and unique `DispatchTicket`. A best-effort
in-process dispatcher nudge occurs only after commit; ticket polling recovers a
lost nudge. There is no separate GitHub-effect submission or claim protocol.

[GitHub integration](./github-integration.md) pushes exact `H2`, confirms the
remote head, and returns observation ownership to this component. Neither agent
pushes.

### 5. Wait for remote proof

1. CI for `H2` is a new round. Old `H1` checks are historical evidence only.
2. If `H2` is red, the persistent CI Fixer is resumed with the new exact-head
   round and its prior attempt context.
3. If `H2` is accepted green, `observeRemoteGreen` marks every qualifying repair
   attempt whose output is `H2` as remotely confirmed.
4. `enqueueLearning` durably schedules one idempotent read-only
   `RUN_CI_LEARNING` operation/ticket for the persistent CI Fixer.
5. The fixer writes zero or more concise lesson candidates with
   `save_ci_lesson`. Only now may a lesson be stored.
6. If the current narrow policy is `MARK_READY_ON_EXACT_GREEN`, `H2` was
   published through this authorized CI-only operation, and no blocking work is
   pending, the program may create a fresh exact `MARK_READY` authorization for
   green `H2`. At claim it rechecks the exact remote head, CI and policy. Neither
   agent marks it ready.

No lesson is created from a local pass, an agent claim, or a partially green
matrix.

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

1. relevance search offers the candidate;
2. the CI Fixer still reads the current raw log and code;
3. if following it fails, the failed attempt remains evidence; and
4. after eventual exact-head remote green, the fixer may save a new candidate
   linked through `supersedesLessonId`.

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
- **Green observed, terminal learning run fails:** CI remains green and Task
  progress is not blocked. `enqueueLearningRetry` may create a new linked
  operation/run within bounded policy; exhaustion records `MISSED_LEARNING` and
  returns the session to `IDLE`. Never retry the terminal operation.
- **New red round while learning is queued/running:** queued repair outranks
  unstarted learning. Running learning is not interrupted; the reconciliation
  operation waits on its exact AgentRun and is released once when the bounded
  turn completes/fails, then selects repair.
- **Budget exhausted:** park with `NEEDS_ATTENTION`; user may raise the budget
  and resume the same logical session.

## Timeline projection

The PR timeline projects meaningful owner facts:

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
15. **Learning serialization:** remote green creates one subject-bound
    `RUN_CI_LEARNING` operation/ticket. If a new final-red round arrives before
    claim, repair runs first. If it arrives while learning is running, no second
    CI-session turn starts; reconciliation waits once, learning ends or records
    `MISSED_LEARNING`, then repair resumes without interruption or lost work.
16. **Run-start crash:** stop after `startFresh` or `resume` commits but before
    the CI model process starts. Claim redelivery returns the same operation-bound
    session/run; the round produces one terminal result and no duplicate turn.
17. **Live-claim expiry:** let the CI process remain alive after its dispatch
    claim expires. No new generation starts until the supervisor revokes the old
    claim/tools and proves that process dead; only then may the same run resume
    under a new claim/fence, with one final attempt/result.
18. **Process retry versus semantic retry:** crash one fixer process before any
    terminal result and assert a new process attempt reuses the same run. Then
    terminally fail another fixer/learning run and assert bounded retry creates a
    linked new operation/run while retaining the failed result; exhaustion
    creates attention/`MISSED_LEARNING`, never a second run for one operation.

## First-principles challenge

| Question | Decision | Tradeoff |
|---|---|---|
| Is CI observation an agent job? | No. Provider check IDs, heads and conclusions are deterministic inputs. | Program must maintain adapters/reconciliation. |
| Does CI repair deserve a separate persistent agent? | Yes. Logs and unsuccessful attempts repeat across long remote waits; retained specialist context has value. | Another session and budget to manage. |
| Should the CI Fixer push to close its own loop? | No. Publication is authority, not diagnosis. Program push plus exact consent prevents accidental external effects. | One Task Agent review handoff per candidate. |
| Should the program parse logs into a root-cause verdict? | No. It may bound/index logs; the agent judges cause and scope. | More agent reading, fewer brittle parsers. |
| Should a matching old lesson auto-apply a fix? | No. Similar logs can have different causes. Lessons remain candidate prose. | Repeated fixes still use tokens. |
| Why save only after remote green? | The authority being optimized is remote CI. Local success or agent confidence is insufficient evidence. | Lessons arrive later and can be lost if the learning turn fails. |
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
- **Preserve the useful prior ByteQuay lesson:** one agent session across CI
  rounds and prose memory capture why an earlier attempt failed. **Reject** the
  prior agent-owned push, `COMMITTED:`/`PARKED:` marker lines, combined
  cherry-pick+CI session, confidence/rule engine, and worktree log files.
- **Reject the former generic harness thesis that a deterministic program should
  apply model-proposed edits:** the semantic split is cleaner when the CI Fixer
  owns diagnosis and local editing while the program owns facts, leases, tools,
  and external effects.

## Implementation completion rule

The component is complete only when all fifteen traces pass with arbitrary agent
prose and injected process/provider failures. A flow that depends on marker
lines, final JSON, the CI Fixer pushing, or an old harness table/service is not
this design.
