# Exact-Subject User Gates

Status: normative greenfield replacement specification.

This document defines the single gate model used before irreversible developer
workflow actions. It is sufficient to implement gate storage, UI commands,
freshness checks, standing consent, and effect handoff without using an older
approval, publish, override, or feedback-gate service.

Read this with the [overall architecture](./README.md),
[workflow runtime](./workflow-runtime.md),
[PR timeline](./pr-timeline.md),
[Task Agent](./task-agent.md),
[CI autofix](./ci-autofix.md), and
[GitHub integration](./github-integration.md).

## 1. Purpose

A gate lets the user review an exact candidate and knowingly authorize an exact
irreversible action. The candidate may include code, PR metadata, local review
threads, remote feedback, replies, resolutions, or merge evidence.

The gate protects four boundaries only:

1. `INITIAL_PUBLISH` — first branch/PR publication, including a freshly reviewed
   recovery of its proven partial effects;
2. `CI_UPDATE` — push of a CI repair;
3. `REMOTE_FEEDBACK` — any feedback-driven push, public reply, or thread
   resolution;
4. `MERGE` — direct merge or merge-queue submission.

There are not four gate implementations. One model freezes different typed
subjects and action manifests.

## 2. Non-goals

- No gate for local edits, planning, agent spawning, checks, or read-only review.
- No fifth gate for marking a draft PR ready.
- No program interpretation of agent prose, reviewer findings, confidence, or
  verdict.
- No `userOverride=true`, boolean bypass, or controller-only authorization.
- No approval that follows “the latest head.” Every approval names an immutable
  gate revision and exact head.
- No auto-published remote reply, review, or resolution.
- No broad repository-wide standing consent in the first implementation.
- No migration, wrapping, or dual write to an older gate system.

## 3. First-principles rules

1. Gate only an irreversible or teammate-visible effect. Reversible local work
   remains agent-driven.
2. Approval means “perform this action manifest on this frozen subject,” never
   “continue generally.”
3. The program builds subjects from program-observed records. The agent only
   calls `ready_for_review()`; it does not provide head SHAs, check IDs, thread
   lists, or gate JSON.
4. Objective readiness is program-owned. Semantic adequacy is user-owned.
5. Any relevant subject change makes the revision stale before execution.
6. Authorization and effect execution are separate durable facts. A user click
   cannot call Git or GitHub directly.
7. Every external action produces a probed success receipt. Agent prose is never
   an effect receipt.
8. Default authorization is manual. The only standing exceptions are narrow
   `CI_UPDATE` consent and optional `MERGE` consent.
9. `REMOTE_FEEDBACK` is always manual, including reply-only batches.
10. Publication opens a draft PR. Readiness policy is selected inside the
    initial gate and may create a fresh exact-head ready authorization after
    remote green for the initial head or a later program-authorized `CI_UPDATE`
    head. It never follows an external or feedback-driven head.

## 4. One gate aggregate

The logical `UserGate` aggregate has stable identity plus immutable revisions,
transitions, and authorizations.

### `UserGate`

| Field | Meaning |
|---|---|
| `gate_id` | Stable gate identity. |
| `task_id` / `pr_id` | Owning Task and stable application PR. |
| `kind` | `INITIAL_PUBLISH`, `CI_UPDATE`, `REMOTE_FEEDBACK`, or `MERGE`. |
| `current_revision` | Latest immutable revision number. |
| `created_at` | Aggregate creation time. |

One aggregate per `(pr_id, kind)` is enough. Repeated CI or feedback rounds append
revisions instead of inventing another gate framework.

Current executable scope is narrower than this full contract. The implemented
owner constructs only local `CI_UPDATE` gates from the sealed CI-review Task
continuation. It creates one stable `(pr_id, CI_UPDATE)` aggregate, immutable
subjects/actions/revisions/transitions, one exact complete-empty local-review
binding per PR/change set, and exact manual authorization for the current OPEN
revision. Authorization atomically creates one immutable GitHub-owned,
single-step `CI_UPDATE` push plan plus its runtime `PUBLISH` operation/ticket;
it performs no Git/provider call and writes no timeline event. A
different later ready run may atomically transition only the current
OPEN revision to `STALE/SUPERSEDED_BY_READY` and append a new OPEN revision. A
revision made `STALE` by authorization/effect freshness is never resurrected,
but a later exact ready run may append a fresh OPEN revision;
historical replay returns the revision created by that run.

### `GateRevision`

| Field | Meaning |
|---|---|
| `gate_id` / `revision` | Composite primary key. |
| `subject_manifest_ref` | Program-generated typed snapshot described below. |
| `subject_digest` | Hash of every exact input revision. |
| `action_manifest_ref` | Reference to the program-generated, user-visible ordered action intent owned by this gate revision. It contains domain actions and exact targets, not provider calls. |
| `action_digest` | Program hash over action kind, target references, order, and dependencies. |
| `readiness_evidence_ref` | Exact objective evidence used to open the gate. |
| `created_by_run_id` | Task Agent run that called `ready_for_review`, when applicable. |
| `created_at` | Revision time. |

The manifests may use application-generated JSON serialization. They are never
model-generated JSON. Unknown fields or schema versions fail at the program
boundary during decode, before the revision is shown or authorized.

### `GateTransition`

Immutable row:

`{gate_id, revision, sequence, from_state, to_state, actor_type, actor_id,
reason_code, detail_ref?, recorded_at}`.

Current state is a fold over these transitions; a cached latest state may be
maintained transactionally.

### `GateAuthorization`

Immutable row:

The implemented manual `CI_UPDATE` row is
`{authorization_id, gate_id, gate_revision, pr_id, subject_digest,
action_digest, authority=USER, actor_id=LOCAL_DESKTOP_USER, idempotency_key,
operation_id, effect_plan_ref, authorized_at}`. Standing-consent and
consumption fields remain part of the wider contract, not this checkpoint.

An authorization is not reusable for another gate revision or another action
digest.

### `AutomationConsentRevision`

| Field | Meaning |
|---|---|
| `consent_id` / `revision` | Stable identity and immutable revision. |
| `task_id` / `pr_id` / `branch_name` | Narrow scope. |
| `kind` | `CI_UPDATE` or `MERGE` only. |
| `constraints` | Bounded rules below. |
| `enabled` / `expires_at` | Current explicit user choice. |
| `granted_by` / `recorded_at` | Audit identity/time. |

Standing consent never bypasses a gate revision. It is authority for the program
to create a normal exact `GateAuthorization` after all current evidence is
rebuilt and checked.

### `ReadyPolicyRevision` and `ReadyAuthorization`

Mark-ready is an external effect but not another review gate. The initial gate's
explicit choice is retained as:

```text
ReadyPolicyRevision {
  readyPolicyRevisionId, prId,
  policy = KEEP_DRAFT | MARK_READY_ON_EXACT_GREEN,
  sourceInitialGateId, sourceInitialGateRevision,
  enabled, recordedBy, recordedAt
}
```

Every automatic or direct ready attempt requires a fresh immutable authority:

```text
ReadyAuthorization {
  readyAuthorizationId, prId, exactRemoteHead,
  authority = USER | INITIAL_READY_POLICY | MERGE_CONSENT,
  readyPolicyRevisionId?, sourcePublicationAuthorizationId?,
  requiredCiPolicyRevisionId, remoteCheckObservationIds[],
  blockerDigest, actionDigest,
  operationId, effectPlanRef,
  authorizedAt, consumedByOperationId?
}
```

For `INITIAL_READY_POLICY`, `sourcePublicationAuthorizationId` must be either the
initial publication authorization or an authorized `CI_UPDATE` that froze the
same current ready-policy revision. External and `REMOTE_FEEDBACK` publications
are ineligible. Creating `ReadyAuthorization`, one runtime operation referencing
the GitHub-owned ready payload, and its dispatch ticket is one transaction.

## 5. Gate state machine

```text
OPEN -> CHANGES_REQUESTED
  |  -> AUTHORIZED -> EXECUTING -> CONSUMED
  |         |              |  \-> NEEDS_ATTENTION
  |         |              \----> AUTHORIZED   (safe transport retry)
  |         \-------------------> STALE
  \------------------------------> STALE
  \------------------------------> CANCELED
NEEDS_ATTENTION -> CONSUMED | AUTHORIZED | STALE | CANCELED
```

- `CHANGES_REQUESTED`, `CONSUMED`, `STALE`, and `CANCELED` are terminal for that
  revision. A later `ready_for_review()` appends a new revision.
- `EXECUTING -> STALE` is valid when a partial plan's remaining effects lose
  freshness, such as red CI after an approved feedback push. Proven receipts
  remain facts; unexecuted steps lose eligibility and the barrier is released.
- `NEEDS_ATTENTION` means the authorized subject cannot safely continue and the
  program cannot prove absence/success of an effect. It never broadens authority.
- `NEEDS_ATTENTION -> CONSUMED` requires typed provider proof that all remaining
  effects succeeded; `-> AUTHORIZED` requires proof the uncertain effect is
  absent and retries the same frozen operation; `-> STALE` requires objective
  subject mismatch; `-> CANCELED` is explicit user abandonment. Each transition
  atomically settles/re-enables the same runtime operation/plan and publication
  barrier and asks the Task lifecycle owner to leave `NEEDS_ATTENTION` when no
  other blocker remains, so the per-PR effect queue cannot deadlock.
- A retry from `EXECUTING` to `AUTHORIZED` uses the same immutable authorization
  and uncompleted effects. It cannot add an action.

## 6. Subject contracts

Every gate that can publish code freezes one `CodePublicationReviewBinding`:

```text
CodePublicationReviewBinding {
  candidateChangeSetRevisionId,
  localReviewBatchIds[],
  latestLocalReviewRevisionIds[],
  localReviewDigest
}
```

The implemented `CI_UPDATE` subset owns only the complete-empty case:

```text
LocalReviewBinding {
  bindingId, prId, candidateChangeSetRevisionId, digest, createdAt
}
```

Existence means the exact candidate currently has no private local-review batch
or revision references. Identity and digest are deterministic from `prId`, the
exact change-set revision, and the two empty ordered lists; `createdAt` is audit
metadata and is excluded from identity/replay equality. There is no status,
current pointer, thread, comment, or batch table in this subset. Every new
`CI_UPDATE` subject has `ownerPresent=true` and an exact composite reference to
this row. A historical subject with the explicit absent sentinel remains
unreviewed; it is never synthesized or upgraded and a future authorization must
reject it. The authenticated manual authorization click supplies the user's
semantic review; the binding proves only objective absence of private items.

This binding is required by `INITIAL_PUBLISH`, `CI_UPDATE`, and a code-changing
`REMOTE_FEEDBACK` gate. It references the latest immutable private review facts
for the exact candidate head and requires every bound thread to be objectively
closed. A reply-only feedback gate publishes no code and does not require this
binding. Any later local comment/reply/state revision for the candidate changes
the digest and makes the relevant gate stale.

### 6.1 `INITIAL_PUBLISH`

Freeze:

- Task, repository, branch, current `TaskBaseRevision`/base SHA, current local
  head, and diff digest;
- current PR title/body revision;
- exact candidate `ChangeSetRevision` and `CodePublicationReviewBinding`;
- exact-head local check policy/profile/run IDs and conclusions;
- exact-head adversarial reviewer run/result reference;
- optional `upstreamVerificationRef`, required only when the candidate came from
  the optional upstream-sync producer and bound to this exact change set;
- publication target repository/base branch;
- `readyPolicy = KEEP_DRAFT | MARK_READY_ON_EXACT_GREEN`;
- current publication/automation policy revision.

For the narrow recovery of a prior partially executed initial publication, the
subject additionally freezes only program-owned proven facts:

```text
PartialInitialPublishBinding {
  mode = BRANCH_ONLY | BOUND_DRAFT,
  priorEffectPlanRef, retainedReceiptRefs[],
  remoteBranchRef, expectedPartialRemoteHead,
  expectedRecoveryBaseRef, expectedRecoveryBaseSha,
  remoteIdentityRef?, draftPrObservationRef?,
  observedDraftHead?, observedDraftBaseRef?, observedDraftBaseSha?
}
```

`BRANCH_ONLY` requires a proven branch and a fresh exhaustive probe showing no
PR/identity. `BOUND_DRAFT` requires the set-once `RemoteIdentity`, create
receipt, and a fresh observation of that exact open draft PR. The current local
head/base, diff, checks, adversarial review, Local PR review, and draft metadata
are still fresh ordinary `INITIAL_PUBLISH` inputs. Retained receipts prove only
what already happened; the prior authorization never approves the reconciled
head or base.

Action manifest:

1. push the exact branch head: normal publication uses `CREATE_REF`; either
   recovery mode must use `FAST_FORWARD` from `expectedPartialRemoteHead`;
2. create the GitHub PR as **draft** with the frozen title/body/base for normal
   publication or `BRANCH_ONLY` only after probing that no PR exists;
   `BOUND_DRAFT` instead probes/reuses the frozen identity and may never issue a
   create call;
3. bind a newly proven remote identity to the same application PR, or verify
   the already-bound set-once identity in `BOUND_DRAFT`;
4. persist the chosen ready-policy revision.

`MARK_READY_ON_EXACT_GREEN` is explicit user authority, not a fifth gate. The
program may later create a fresh exact-head `MARK_READY` effect authorization
only after [CI Autofix](./ci-autofix.md) resolves the current
`RequiredCiPolicyRevision` and proves required CI green with exact observation
refs and no pending blocker on the initial published head. The same ready-policy revision may carry
forward only to a later head produced by an authorized `CI_UPDATE` gate (manual
or current CI standing consent); that later head gets a new exact authorization
and fresh proof. It never follows an external push or a `REMOTE_FEEDBACK` head.
`KEEP_DRAFT` grants no such authority. A user may always invoke an explicit
exact-head “mark ready now” UI command; that direct action is not an approval of
code publication and does not need another review gate.

### 6.2 `CI_UPDATE`

Freeze:

- expected current remote head;
- proposed clean committed `ChangeSetRevision`, head, and diff digest;
- `CodePublicationReviewBinding` for that exact candidate;
- failed CI observation revisions that caused the repair;
- exact-proposed-head local check policy/profile/run IDs;
- exact-proposed-head adversarial reviewer run/result reference;
- push refspec and force-push flag;
- exact CI repair result, optional cleanup result, and current memory references
  when a memory owner exists;
- current `RequiredCiPolicyRevision` for post-push CI/ready evaluation;
- current consent revision, if automatic authorization is considered;
- current ready-policy revision, so any permitted carry-forward is exact and
  revocation-safe.

Action manifest: push only the proposed exact head. It cannot contain a GitHub
comment, review, title/body edit, thread resolution, ready action, or merge.

The current CI implementation freezes the exact repair attempt/result and, for
a cleanup-produced candidate, the cleanup ID/result as separate causal facts.
It also freezes the full ordered failed observation/log lists. The implemented
greenfield local-review owner stores the exact complete-empty binding above;
because no CI-memory owner exists yet, memory references remain canonically
empty. Neither path reads legacy data. The implemented manual
authorization/freshness owner rejects an absent local-review sentinel,
revalidates the exact binding and
all frozen current facts, and transitions an obsolete OPEN snapshot to terminal
STALE. OPEN alone is neither authorization nor executable work.

### 6.3 `REMOTE_FEEDBACK`

Freeze:

- expected current remote head;
- proposed clean committed local head, which may equal remote head for a
  reply-only batch;
- diff digest when code changed;
- when code changed, the proposed `ChangeSetRevision` and
  `CodePublicationReviewBinding`;
- every included feedback item/thread revision and anchor;
- every proposed reply draft revision and proposed resolution;
- exact-proposed-head checks and reviewer result when code changed;
- exact current `RequiredCiPolicyRevision` and latest observations for the
  current exact remote head;
- current remote PR/open/draft observation;
- ordered action manifest and provider targets.

The original feedback is always visible in the gate. Missing reply/action drafts
are shown as “no proposed action”; the program does not invent or reject a
semantic classification. The user may approve the complete visible manifest or
request a revision. The same Task Agent creates a superseding
`FeedbackDraftAction`, then a new gate revision shows it. V1 does not support
directly deleting or editing an agent-authored action in the gate UI; the user
can ask the agent to replace it, and the old action remains current until that
superseding action exists. An approval never contains an ad hoc partial
selection.

Action order:

1. push the exact proposed head if code changed;
2. prove GitHub observes that head;
3. wait for required CI to be accepted on the current exact remote head, even
   for a reply-only batch;
4. refetch every included feedback revision;
5. post approved replies, each with its own effect receipt;
6. refetch each target thread;
7. resolve only still-eligible unchanged threads, each with its own receipt.

The gate is approved **before step 1**. A reply-only batch skips the push but not
the exact-remote-head CI requirement. If CI is red, no public reply or resolution
is posted. The revision becomes stale, CI repair proceeds, and the combined new
head/replies return through a new manual feedback gate revision. This uniform
rule avoids asking the program to infer from agent prose whether a reply claims a
code fix.

An edit/reopen to an included item stales the revision. A new independent comment
is queued for the next batch and does not interrupt an already executing batch;
otherwise active reviewers could starve every publication forever.

### 6.4 `MERGE`

Freeze:

- exact remote head and base branch;
- fresh remote PR state showing open and non-draft;
- exact current `RequiredCiPolicyRevision` plus required CI/check-suite
  observation revisions and accepted conclusions;
- current review decisions and live thread/feedback revision digest;
- mergeability or merge-queue capability proof;
- direct merge method (`merge`, `squash`, or `rebase`) or queue action;
- current merge policy and standing-consent revision, if any.

Action manifest: one direct merge or one merge-queue submission. Unknown
mergeability/capability fails closed.

If the user grants auto-merge while the PR is draft, the consent UI must
explicitly include `MARK_READY_ON_EXACT_GREEN`. It is never implied merely by
auto-merge. That explicit merge-consent policy can create its own fresh exact-head
ready authorization when merge readiness is otherwise proven; it is not an
extension of the initial ready policy to feedback/external heads. Without it the
PR remains draft and the auto-merge gate cannot become ready.

## 7. Objective readiness

`GateReadiness.evaluate(subject)` returns typed blocker codes, warning codes,
evidence refs, and `manualOnly`. It does not read result prose.

A local check attempt has an exact-head conclusion `PASSED`, `FAILED`, or
`UNAVAILABLE`. `UNAVAILABLE` means the program captured a real attempted command
and objective reason such as absent local credentials/toolchain. It is evidence,
not a boolean override. Missing/never-attempted and `FAILED` block. `UNAVAILABLE`
may open a prominently warned, manual-only gate; it can never use CI standing
consent. Required remote CI remains authoritative before feedback replies and
merge. A missing or unavailable `RequiredCiPolicyRevision` for the PR's exact
target-base/ruleset scope is a hard blocker and is never treated as an explicit
empty required-check set.

The current Local Checks owner freezes `FAILED` and genuine tool/environment
`UNAVAILABLE` attempts as reviewer evidence and blocks reviewer reservation
only for missing/stale evidence or an unproven process boundary. The implemented
local `CI_UPDATE` gate then blocks `FAILED`, records genuine `UNAVAILABLE` as a
manual-only warning, and rejects missing/stale/process-boundary evidence. Its
complete-empty local-review binding is implemented; private comments/threads,
other gate kinds, standing consent, and general provider observation remain
deferred. Manual current-OPEN `CI_UPDATE` authorization, its immutable one-step
push plan/runtime ticket, and exact GitHub attempt/probe/receipt settlement are
implemented.

| Gate | Required objective facts |
|---|---|
| `INITIAL_PUBLISH` | Current clean committed `ChangeSetRevision`; non-empty diff; title/body revision; complete local-review binding with no open thread; current-policy exact-head local check profiles are `PASSED` or manual-only `UNAVAILABLE`; adversarial review completed on exact head; required upstream verification is current when applicable; no conflicting publication except the exact branch-only or bound-draft state frozen from proven partial initial-publication receipts and a fresh observation. |
| `CI_UPDATE` | Failed CI input still describes expected remote head under the frozen current `RequiredCiPolicyRevision`; proposed `ChangeSetRevision` is current/clean; complete local-review binding with no open thread; current-policy exact-head local check profiles are `PASSED` or manual-only `UNAVAILABLE`; adversarial review completed; refspec still targets the Task branch. |
| `REMOTE_FEEDBACK` | Included remote feedback revisions, expected remote head, and frozen current `RequiredCiPolicyRevision` remain current; PR remains open; if code changes, proposed `ChangeSetRevision` is current/clean, its local-review binding has no open thread, current-policy exact-head local check profiles are `PASSED` or manual-only `UNAVAILABLE`, and adversarial review completed. |
| `MERGE` | PR open and non-draft; remote head exact; current frozen `RequiredCiPolicyRevision` is still current and its exact observations prove accepted CI; required approvals met; no effective changes request; no unresolved live blocking thread/batch; mergeability/capability proven. |

“Adversarial review completed” proves only that the reviewer ran against the
exact subject. The Task Agent and user interpret the prose. There is no
`APPROVED`, `OK`, `CONFLICT`, or findings-count parser.

## 8. APIs and tool boundary

### Agent tool

```text
ready_for_review()
```

The tool has no model-supplied gate document. The program identifies the Task,
current pending work, current head, and relevant owner revisions from the
authenticated session. Before accepting the call it performs a synchronous
read-only preflight under the current fence. Missing known facts such as a clean
commit, current checks, or exact-head reviewer return typed blockers while the
run remains active.

An accepted call is terminal for the active writer run. The runtime immediately
seals its mutating tool capability, asks the model to finish, stores its opaque
result, adopts the clean committed head as the final `ChangeSetRevision` under
the still-valid writer fence, and releases the writer lease. Only after those
facts are durable does the post-run readiness operation run any required
objective producer finalization and then execute:

```text
GateSubjects.buildCurrent(taskId)
GateReadiness.evaluate(subject)
GateCommands.openOrRevise(subject, actionManifest, evidence)
```

If post-run revalidation discovers a race or finalization failure, it opens no
gate. It persists a typed `REVIEW_READINESS_FAILED` cause and ensures
reconciliation so a new turn of the same persistent Task session can fix it; it
never resurrects the completed run. After acceptance, any further mutation tool
fails `RUN_SEALED_FOR_REVIEW`. This prevents the agent from changing the head
after the candidate was declared. Tool-schema validation is deterministic and
immediate; this is not parsing its eventual final response.

### User commands

| Method | Meaning |
|---|---|
| `UserGates.authorizeCiUpdate(gateId, gateRevision, expectedSubjectDigest, expectedActionDigest, idempotencyKey)` | Approve exactly the displayed local `CI_UPDATE` revision and digests. The local-sidecar program supplies fixed `USER/LOCAL_DESKTOP_USER` authority; callers cannot choose an actor or authority kind. |
| `GateCommands.requestChanges(userId, gateId, revision, localRevisionIds[], summary?)` | Submit the selected owned local-review revisions, terminally mark this gate revision `CHANGES_REQUESTED`, persist pending Task work, and ensure the one reconciliation ticket in the same transaction. It does not directly create a writer turn. |
| `GateCommands.cancel(userId, gateId, revision)` | Cancel this revision without authorizing anything. |
| `GateCommands.cancelAttention(userId, gateId, revision, attentionRevision)` | Request abandonment of this exact attention state and retain proven receipts. If no executor/remote call can still act, atomically cancel plan/runtime operation and release the barrier; otherwise keep attention while stop/probe proof is obtained, then settle `CANCELED`. |
| `ConsentCommands.grantCiUpdate(userId, taskId, constraints)` | Append narrow CI standing-consent revision. |
| `ConsentCommands.grantAutoMerge(userId, prId, method, readyPolicy?)` | Append exact PR-scoped merge consent. Draft PR requires explicit mark-ready policy. |
| `ConsentCommands.revoke(userId, consentId, expectedRevision)` | Prevent future authorizations; already executing exact effects retain only their frozen authority. |
| `DirectPrCommands.markReady(userId, prId, expectedRemoteHead)` | Immediate explicit exact-head ready action regardless of stored ready policy; no-op if already ready and deduplicate against an existing same-head ready operation. |
| `ReadyPolicyCommands.revoke(userId, prId, expectedRevision)` | Disable future policy-derived ready authorizations; it cannot undo a proven ready effect. |

### Program methods

All manual and standing-consent paths normalize to one program command:

```text
AuthorizeGateCommand {
  gateId, gateRevision,
  expectedSubjectDigest, expectedActionDigest,
  authorityRef, idempotencyKey
}
```

`authorityRef` is program-created from the authenticated user or a current
eligible consent revision; clients cannot choose it. `CI_UPDATE` is the only
name and authorization path for the CI repair-push gate/consent subject.

| Method | Responsibility |
|---|---|
| `GateSubjects.buildCurrent(taskId)` | Read authoritative records, including code-publication local-review bindings, and build the typed subject. |
| `GateReadiness.evaluate(subject)` | Return objective blockers/evidence. |
| `GateCommands.openOrRevise(...)` | Append one deduplicated gate revision/`OPEN` transition with the exact visible action manifest and digest. It creates no effect plan, operation, or ticket before authority exists. |
| `GateFreshness.revalidate(gateId, revision)` | Rebuild relevant digests and compare before authorization and every effect. |
| `GateCommands.authorize(AuthorizeGateCommand)` | In one transaction verify authority/revision/digests, append authorization, reserve the operation ID, lock `prId` and allocate the plan's next monotonic `prSequence`, create the GitHub-owned immutable plan from the frozen action manifest, and create that runtime `Operation` plus its `DispatchTicket`. |
| `UserGates.beginCiUpdateEffect(claim)` | Revalidate the claimed exact graph and every current owner fact, then append `AUTHORIZED -> EXECUTING` without calling Git or GitHub. Stable drift may append terminal `STALE` and cancel only before any attempt exists. With historical attempt uncertainty it enters durable probe-only `NEEDS_ATTENTION` and retains the barrier. |
| `UserGates.recoverExpiredCiUpdateEffect(operationId, generation)` | Prove the expired generation has no durable provider attempt, return `EXECUTING -> AUTHORIZED` when needed, and redrive the same operation/ticket. Generic claim recovery rejects `PUBLISH`; a generation with an attempt uses probe-only recovery. |
| `GitHubCiUpdateExecutor.execute(claim)` | Outside owner transactions, probe the exact frozen ref, commit each possible mutation attempt before its call (maximum two), execute the exact-lease fast-forward, then hand a sealed provider observation/failure to User Gates for atomic settlement. |
| `UserGates.applyCiUpdateObservation(...)` | Consume only a provider-minted claim/plan/target/attempt-bound remote observation. `APPLIED` consumes with a receipt, `DIVERGED` stales, exact-expected `ABSENT` schedules the bounded retry unless authority uncertainty must remain probe-only, and `UNKNOWN` retains the barrier. |
| `ConsentEvaluator.maybeAuthorize(gateId, gateRevision)` | If current narrow consent is eligible, construct the same `AuthorizeGateCommand`; otherwise do nothing. |
| `ReadyPolicyEvaluator.maybeAuthorize(prId, exactRemoteHead)` | Resolve the current `RequiredCiPolicyRevision`, require eligible lineage plus exact-head accepted CI/no blocker, and under the same per-PR allocator atomically create `ReadyAuthorization` freezing that policy/evidence, its next-sequence GitHub-owned plan, and the runtime operation/ticket. |
| `GateExecution.acceptResult(operationId, githubResultRef)` | Consume only after GitHub integration returns typed references proving every required plan step, then release any exact reconciliation wait on this gate/operation revision; User Gates does not copy attempts/probes/receipts. |
| `GateAttention.applyProbe(operationId, attentionRevision, githubProbeResultRef)` | Validate a typed GitHub-owned probe and transition `NEEDS_ATTENTION` to `CONSUMED`, same-operation `AUTHORIZED`, or `STALE`; call runtime `OperationCommands.settle`, release any exact reconciliation wait on this gate/operation revision, and atomically settle/re-enable plan/barrier. It never accepts a user/model claim as provider proof. |
| `GateFreshness.onOwnerRevision(ownerRef)` | Best-effort immediate invalidation; claim-time revalidation remains authoritative. |

### Local-review records and commands

User Gates owns private pre-push review conversation for any code-publishing
candidate displayed by `INITIAL_PUBLISH`, `CI_UPDATE`, or code-changing
`REMOTE_FEEDBACK`. GitHub feedback remains owned by
[Remote Feedback](./remote-feedback.md); the two stores do not mirror or
translate one another.

`LocalReviewThread` is stable identity:

```text
threadId, prId, createdGateId, createdGateRevision,
createdHead, initialAnchor?, createdAt
```

`LocalReviewRevision` is an immutable append:

```text
revisionId, threadId, sequence,
kind = COMMENT | COMMENT_EDITED | REPLY | RESOLVED | DISMISSED | REOPENED,
actor = USER | TASK_AGENT,
body?, headSha, anchor?, createdByRunId?, createdAt
```

Current open/resolved/dismissed state is a fold over revisions. No row is edited
to “resolve” a thread. A line anchor identifies the exact local diff/head and may
become objectively outdated; it is never translated into a GitHub comment.

`LocalReviewBatch` freezes one submitted set:

```text
batchId, gateId, gateRevision, prId, reviewedHead,
revisionIds[], submittedBy, submittedAt
```

The owner commands are:

```text
LocalReviewCommands.addComment(gateId, gateRevision, expectedHead, anchor?, body) -> revisionId
LocalReviewCommands.editComment(revisionId, body) -> revisionId
LocalReviewCommands.submit(gateId, gateRevision, revisionIds[]) -> batchId
LocalReviewCommands.dismiss(threadId, expectedRevision, reason) -> revisionId
LocalReviewCommands.reopen(threadId, expectedRevision, reason?) -> revisionId
LocalReviewCommands.replyFromTask(runId, threadId, expectedRevisionId, body) -> revisionId
LocalReviewCommands.resolveFromTask(runId, threadId, expectedRevisionId) -> revisionId
```

and the Task Agent tool surface is:

```text
list_local_review_items(batch_id?)
read_local_review_item(revision_id)
reply_local_thread(thread_id, expected_revision_id, body)
resolve_local_thread(thread_id, expected_revision_id)
```

Tool handlers derive Task, PR, run, and current head from the authenticated
session. `expected_revision_id` is compare-and-set: if any user/agent revision
landed after the read, the command returns `LOCAL_REVIEW_STALE` and the agent
must reread before replying or resolving. A Task Agent cannot read another
Task's thread or author a user action.

`addComment` locks and validates the exact displayed gate revision: it must be
`OPEN`, its candidate head must equal `expectedHead`, and it must be
`INITIAL_PUBLISH`, `CI_UPDATE`, or code-changing `REMOTE_FEEDBACK`. The command
derives `prId` from that gate, writes `createdGateId/createdGateRevision`, and
atomically makes the gate non-authorizable because its local-review digest has
changed. A stale, authorized, executing, consumed, or reply-only feedback gate
cannot receive a new code-review comment through this API.

Adding/editing a server-side review draft changes the gate subject digest, so an
approval for the old displayed subject fails freshness. `requestChanges` is
allowed to narrow that same displayed revision: in one transaction it submits
the selected drafts as a `LocalReviewBatch`, appends `CHANGES_REQUESTED`, stores
a pending local-feedback fact, and calls `Reconciliation.ensure`. `WorkSelector`
later chooses the Task Agent turn under the Task lock. It performs no external effect. Later
comments, replies, resolutions, dismissals, or reopenings change the review
digest and stale the referencing `INITIAL_PUBLISH`, `CI_UPDATE`, or code-changing
`REMOTE_FEEDBACK` gate. Submitted items resume the same Task Agent only after its
current run ends; they never interrupt it. See the [Task Agent
contract](./task-agent.md) for consumption.

## 9. Manual and standing authority

Default policy:

| Gate | Default | Standing consent allowed? |
|---|---|---|
| `INITIAL_PUBLISH` | Manual | No. |
| `CI_UPDATE` | Manual | Yes, narrowly. |
| `REMOTE_FEEDBACK` | Manual | Never. |
| `MERGE` | Manual | Optional exact-PR auto-merge. |

### Narrow CI consent

Standing consent is a deferred policy owner. The implemented executable
`CI_UPDATE` path uses only an exact authenticated manual authorization.

The first implementation scopes consent to one Task, one branch, an expiry, and
a maximum automatic use count. It permits only normal push of a reviewed,
exact-head CI repair with `PASSED` local checks. An `UNAVAILABLE` check candidate
is always manual. Consent does not permit force push, history rewrite, remote
reply, resolution, ready action, metadata edit, or merge. Any such candidate
stays manual.

The program still creates and records an exact `CI_UPDATE` gate revision and
authorization for each use. This makes the timeline and recovery path identical
to manual authorization.

### Optional auto-merge

Auto-merge consent is scoped to one application PR, one merge method/queue mode,
and one policy revision. It creates an exact `MERGE` authorization only after
fresh readiness is proven for the current remote head. A new head requires a new
gate revision and proof; the standing consent may authorize it again while still
enabled.

Auto-merge never implies remote feedback publication. An unresolved feedback
batch prevents merge readiness.

## 10. Staleness and admission

The following changes stale the named open/authorized revision:

| Change | Gates made stale |
|---|---|
| Local/proposed head, diff, base, or dirty-state change | Any gate referencing it. |
| PR title/body revision | `INITIAL_PUBLISH`. |
| Exact-head local-check conclusion/configuration or `RequiredCiPolicyRevision` change | Any gate/ready authorization using that evidence. A remote-CI policy change re-evaluates nonterminal CI rounds and stales feedback/merge/CI-update evidence frozen under the prior revision. |
| New adversarial review subject/result revision | Gate referencing the prior review. |
| Local review batch/thread revision for the candidate | `INITIAL_PUBLISH`, `CI_UPDATE`, and code-changing `REMOTE_FEEDBACK` that reference it. |
| Included remote semantic-content edit or thread resolution/dismissal/reopen | `REMOTE_FEEDBACK`, and possibly `MERGE`. An anchor-only observation produced by the gate's own authorized push may continue only under the exact proof rule in Remote Feedback: same item/thread identities, unchanged content revision, exact authorized remote head, and still-eligible action. Any other anchor change stales. |
| Unexpected remote head or PR open/draft state | `CI_UPDATE`, `REMOTE_FEEDBACK`, `MERGE`. The exact precondition-head -> authorized-target-head transition proven from this operation's own push is expected; any other head change stales. |
| Partial remote branch/identity/PR head or base differs from its frozen recovery binding | Recovery `INITIAL_PUBLISH`. No create, fast-forward, ready, or later effect may run. |
| CI/review/mergeability observation revision | `MERGE`; feedback gate when waiting for its post-push green. |
| Action draft or action order change | Gate whose action digest changes. |
| Applicable consent/policy revoked | Unclaimed automatic authorization; manual authorization remains only for its frozen action unless safety evidence changed. |

From the authorization commit until the operation becomes terminal, the runtime
installs a logical publication
barrier for the Task. It does **not** hold a writer lease across CI waiting. The
begin boundary makes no external call. The concrete GitHub executor later
commits an attempt and pushes the frozen commit SHA without changing the local
worktree or Task branch. The
barrier blocks WorkSelector, writer claims, leases/fences, and Task lifecycle
changes. Stable begin-time drift atomically stales and cancels only a
never-attempted operation. If an attempt exists, local-authority uncertainty
enters durable probe-only attention and retains the barrier until an exact
`APPLIED` or `DIVERGED` remote observation settles it.

External effects do not add a second queue. Every immutable GitHub effect plan
has a per-`prId` monotonic `prSequence`. Before claiming its runtime ticket, the
runtime takes the per-`prId` ordering lock and admits only the lowest-sequence
eligible nonterminal plan. An earlier nonterminal plan that is blocked or
executing prevents later plans from overtaking it; terminal plans remain audit
history but no longer block.

## 11. GitHub effect handoff and recovery

User Gates owns the authorization plus the GitHub effect-plan reference/digest.
It does not own step payload, attempt, probe, or receipt rows. The
[GitHub integration](./github-integration.md) owns an immutable ordered plan tied
to the one reserved runtime `Operation` identity and gives its steps stable keys
such as:

- `publish:<pr_id>:<gate_revision>:push`;
- `publish:<pr_id>:<gate_revision>:create-pr`;
- `ready:<pr_id>:<remote_head>`;
- `feedback:<batch_id>:reply:<remote_comment_revision>`;
- `feedback:<batch_id>:resolve:<remote_thread_revision>`;
- `merge:<pr_id>:<remote_head>:<method>`.

An unapproved gate, or a gate canceled before authorization, has no GitHub plan,
runtime `Operation`, or `DispatchTicket`. An authorized plan that later becomes
stale remains retained with any completed receipts, but its unexecuted steps are
terminally ineligible.

Before retrying an uncertain action, the GitHub executor probes the provider:

- branch ref equals the expected SHA;
- PR exists with the publication marker and expected head;
- draft/ready state is observed;
- reply exists by provider identity/idempotency marker;
- thread is resolved;
- merge or queue entry exists for the expected head.

If success is proven, GitHub integration stores its receipt without repeating.
If absence is proven, it retries the same step. If neither is provable, it
returns a typed uncertain result and the gate transitions to `NEEDS_ATTENTION`.
Scheduled recovery keeps probing that same plan. `GateAttention.applyProbe`
owns every proof-based exit; the user may instead call `cancelAttention` to
abandon only the uncompleted steps. Generic runtime attention commands cannot
write Gate state.

Partial remote feedback progress does not require a second approval after a
restart: GitHub-owned completed receipts are retained and the same runtime
operation continues only unchanged actions covered by the same authorization.
Any disallowed subject revision change instead makes the gate stale and stops
remaining public effects. The only allowed derived change is the proven
anchor-only transition caused by this operation's own authorized push under the
rule above; it grants no new action.

Partial `INITIAL_PUBLISH` recovery is intentionally different when a base or
subject race staled the old gate. A new manual gate must freeze the retained
receipts and exact current local head/base. `BRANCH_ONLY` fast-forwards the one
proven branch and may then create one probed draft PR. `BOUND_DRAFT`
fast-forwards the branch and reuses/probes the one bound open draft PR. Neither
mode retries `CREATE_REF`, chooses another PR, or inherits semantic authority
from the old gate.

## 12. Timeline contract

Gate revisions, transitions, and authorizations are immutable User Gates owner
rows. Effect plans, attempts, probes, and receipts are immutable GitHub owner
rows tied to the runtime operation. The [timeline
projector](./pr-timeline.md) reads each owner directly to show:

- what exact candidate opened;
- who or which explicit consent authorized it;
- why it became stale or needed attention;
- which external effects were proven;
- whether marking ready came from an initial/CI-carried ready policy, explicit
  user action, or explicit auto-merge ready authority;
- whether merge used manual or standing authority.

The gate component never writes a generic timeline event.

## 13. Acceptance traces

Insert a process restart after every numbered step in test variants.

### A. Local review before first push

1. Task Agent calls `ready_for_review()` for local head `H1`.
2. Program opens `INITIAL_PUBLISH` revision 1; no Git remote is touched.
3. User adds inline revision `L1`; `requestChanges` atomically creates local
   batch `B1`, marks gate revision 1 `CHANGES_REQUESTED`, and enqueues Task work.
4. Task Agent lists/reads `B1`, commits `H2`, replies and resolves through typed
   tools, checks it, receives a fresh adversarial review, and calls
   `ready_for_review()`.
5. Revision 2 freezes `H2`, current threads, draft metadata, and ready policy.
6. User authorizes revision 2. Only then may the program push/open the draft PR.

### B. Wrong revision cannot publish

1. Open revision 4 for `H4`.
2. Change the title or head, producing revision 5.
3. Submit `GateCommands.authorize` for revision 4 and its displayed digests.
4. Receive `GATE_STALE`; observe no Git/GitHub call and no authorization row for
   revision 5.

### C. Explicit mark-ready policy

1. Authorize initial publication with `MARK_READY_ON_EXACT_GREEN` for `H1`.
2. Observe green CI for an external or feedback-produced head; assert no ready
   call under the initial policy.
3. Observe fresh accepted CI for remote `H1` and still-draft/open PR state.
4. Program creates one exact ready authorization/effect/receipt without another
   gate; redelivery cannot create a second effect.
5. In a variant, red CI on `H1` leads to an authorized `CI_UPDATE` head `H2`.
   Fresh accepted CI/no blockers on exactly `H2` may create a new exact ready
   authorization under the carried policy.

### D. Remote feedback is reviewed before push

1. Ingest comment revision `C1`; Task Agent prepares head `H2`, reply `R1`, and
   proposed resolution.
2. Assert no push/reply/resolve occurs before `REMOTE_FEEDBACK` authorization.
3. User authorizes the complete revision.
4. Push `H2`, prove remote head, and receive green CI for `H2`.
5. Refetch `C1`, post `R1`, refetch the thread, then resolve; record each receipt.

### E. Feedback goes red after approved push

1. Authorize feedback revision for `H2` and push it.
2. CI reports red on `H2`.
3. Assert no public reply/resolution occurred; mark the gate stale and release
   the publication barrier.
4. CI Fixer produces `H3`; Task Agent rechecks/reviews the combined candidate.
5. Require a new manual feedback revision before pushing/replying again.

### F. Concurrent reviewer update

1. Authorize a batch containing remote comment revision `C1`.
2. Before its reply, ingest edit/reopen revision `C2` for that item.
3. Claim-time/refetch validation stales the gate; assert no stale reply or
   resolution is posted.
4. A separate new comment on another thread is queued for the next batch but does
   not interrupt an unchanged executing batch.

### G. Narrow CI consent

1. User grants Task-scoped consent with one remaining use.
2. Open an eligible exact `CI_UPDATE` gate; `ConsentEvaluator` constructs the
   same `AuthorizeGateCommand`, creates authorization, and decrements use
   atomically.
3. Try a force-push or action containing a reply; automatic authorization is
   refused and remains manual.
4. Redelivery/restart cannot consume a second use for the same gate revision.

### H. Auto-merge freshness

1. Grant PR-scoped auto-merge and explicit mark-ready authority if draft.
2. Prove readiness for head `H5`; create exact merge authorization.
3. Observe head `H6` before claim; mark the `H5` gate stale.
4. Merge only after a new `H6` revision re-proves every readiness fact.

### I. Review declaration seals the writer

1. Task Agent holds the writer lease on clean committed `H7` and successfully
   calls `ready_for_review()`.
2. A subsequent edit/commit tool call in that run fails
   `RUN_SEALED_FOR_REVIEW`.
3. Runtime stores the opaque run result, measures `H7`, and releases the lease.
4. Only then does it build/open the gate revision for `H7`.
5. A malformed/rejected `ready_for_review()` variant leaves the run unsealed so
   the agent can fix the reported objective blocker.

### J. Local checks unavailable

1. `run_checks()` records an attempted exact-head check as `UNAVAILABLE` with
   captured reason/output.
2. Gate opens with that evidence, a prominent warning, and `manualOnly=true`.
3. Assert `ConsentEvaluator` cannot authorize it through CI standing consent.
4. A user may manually authorize the exact candidate; a `FAILED` or
   missing/never-attempted check variant cannot open the gate.

### K. Reply-only still waits for green

1. Prepare and manually authorize a reply-only batch on remote head `H8`.
2. Skip push and refetch `H8` plus required checks.
3. While `H8` is red or incomplete, assert no reply/resolution call occurs.
4. Only a fresh accepted-CI observation for still-current `H8` permits the
   approved reply/resolution sequence.

### L. Local review binds every code push

1. Open a `CI_UPDATE` gate whose subject binds change set `C9` and the current
   local-review digest.
2. Add a local inline comment; the CI gate becomes stale and cannot use manual
   or standing authorization.
3. Task reads revision `L9`; before its reply, append user revision `L10`.
   `reply_local_thread(thread, L9, body)` fails CAS and Task must reread.
4. After all threads close, open a new exact CI gate with the latest batch and
   revision IDs.
5. Repeat with a code-changing `REMOTE_FEEDBACK` gate and assert the same stale
   behavior; a reply-only feedback gate requires no code-review binding.

### M. Optional upstream verification

1. An ordinary initial candidate opens with `upstreamVerificationRef = null`.
2. A candidate produced by Upstream Sync cannot open `INITIAL_PUBLISH` without
   a verification reference bound to the exact `ChangeSetRevision`.
3. A mismatched/stale reference blocks; a current proven reference is frozen in
   the authorized gate subject.

### N. Branch-only partial initial publication

1. Authorize `H1/B1`; branch push succeeds, then base movement stales the gate
   before PR creation. Retain the push receipt and observe no PR/identity.
2. Task integrates current `B2`, produces `H2`, and repeats checks, adversarial
   review, Local PR review, and draft review.
3. A new manual `INITIAL_PUBLISH` gate freezes `BRANCH_ONLY`, branch/head `H1`,
   recovery base `B2`, and the retained receipt.
4. Authorization fast-forwards `H1 -> H2`, rechecks `B2`, creates/probes one
   draft PR, and binds one identity. Assert no `CREATE_REF` retry or duplicate PR.

### O. Draft-PR base-race partial initial publication

1. The branch and draft PR are created, but a provider base race yields an
   unexpected observed base. Bind the one identity, retain both receipts, stale
   the gate, and block ready/follow-on effects.
2. Task reconciles the observed current base, produces a fresh exact candidate,
   and repeats every manual review input.
3. A new manual gate freezes `BOUND_DRAFT`, the existing identity/observation,
   partial remote head, and exact recovery base.
4. Authorization fast-forwards from the partial head and probes/reuses that same
   draft PR. Assert the executor never calls create again.

### P. Per-PR effect order

1. Concurrently authorize two otherwise-valid effects for one `prId`.
2. Assert their plans receive unique increasing `prSequence` values in commit
   order.
3. While the earlier plan is nonterminal, the runtime cannot claim the later
   ticket. After the earlier plan becomes terminal, the later plan becomes
   eligible without a second queue record.

### Q. Attention cannot deadlock the effect queue

1. Lose an effect response and make its gate/plan `NEEDS_ATTENTION`; authorize a
   later plan for the same PR and assert it remains blocked.
2. A typed provider probe proving success moves the first gate to `CONSUMED`,
   settles its runtime operation/barrier, and admits the later plan.
3. Repeat with proof of absence and assert only the same frozen operation retries.
4. Repeat with explicit user `cancelAttention`; retain completed receipts, cancel
   remaining steps only after executor/remote stop proof, release the barrier,
   and admit the later sequence.

## 14. First-principles challenge

| Question | Decision | Why | Trade-off |
|---|---|---|---|
| Why not trust `ready_for_review()` as approval? | It only requests a candidate gate. | The agent cannot authorize the user's public/irreversible action. | One explicit user step at important boundaries. |
| Why freeze many revisions instead of only `head`? | Comments, metadata, CI and action drafts can change without a new local head. | The user must approve what was displayed, not merely the same commit. | More digest inputs and more honest reapprovals. |
| Why bind private local review to CI/feedback code pushes too? | A push is irreversible regardless of why the code changed. | The exact user-visible comments and closures must match every published code candidate. | More gate staleness when the user comments late. |
| Why not parse reviewer “passed”? | Require exact-head completion, then let Task Agent/user judge prose. | Model wording is probabilistic and cannot be a safety interlock. | Less automatic semantic gating. |
| Why one model for four gates? | All four are exact subject + action + authority + receipts. | Separate implementations drift on stale/retry rules. | Kind-specific subject builders still require focused tests. |
| Why no gate for ready? | Initial policy or direct user command already carries explicit exact-head authority. | A fifth review screen adds no new judgment. | Ready authority must be clearly visible in the initial/auto-merge UI. |
| Why require green even for reply-only feedback? | The program cannot safely infer whether prose claims a fix; one objective rule covers every public reply/resolution. | Avoids misleading public claims on a red exact head. | Pure discussion replies may wait for CI. |
| Why allow captured local-check `UNAVAILABLE`? | Some repositories can validate only in remote CI; hiding that behind an override is worse than explicit evidence. | Manual review can knowingly publish while failed or never-attempted checks still block. | More red-CI loops; automatic CI consent is ineligible. |
| Why allow narrow CI standing consent? | CI repair can be repetitive and has no teammate-facing prose. | Preserves speed while every exact push remains evidenced. | Less per-round user inspection; default remains off and scope is tight. |
| Why is remote feedback always manual? | Replies/resolutions speak to another human and may close their concern. | This is a social judgment, not merely code transport. | User waits on each feedback batch. |
| Why not hold writer lease while waiting for CI? | A logical publication barrier protects subject identity without occupying a renewable filesystem lease. | Leases prove active mutation, not a minutes-long remote wait. | Admission must understand the barrier. |
| Why not resume partial publication under its old approval? | Use retained receipts only as proof of existing remote state; require a fresh manual gate for the reconciled exact head/base. | Authority never silently expands after a race. | The user reviews once more, but recovery cannot duplicate a branch or PR. |
| Why no separate external-effect queue? | Monotonic plan sequence plus runtime tickets already provide durable order. | One source of truth avoids queue/operation drift. | Claim admission must lock the PR and inspect the oldest nonterminal plan. |
| Can `NEEDS_ATTENTION` be left for a generic Task command? | No. The Gate owner consumes typed provider proof or explicit cancellation and settles its exact plan/barrier. | Cross-owner guessing would strand or incorrectly retry remote effects. | More owner-specific recovery commands, but the per-PR queue always has a tested exit. |

## 15. Evidence and adopted/rejected ideas

- **Accepted from Codex:** the review pane presents the actual Git diff and lets
  the user inspect, comment, stage/revert, commit, or push. This supports showing
  exact artifacts before the user's publication action rather than asking a model
  for an approval verdict.
  [Official Codex code-review documentation](https://learn.chatgpt.com/docs/code-review)
- **Accepted from Codex:** typed approval/tool events are program protocol, while
  model content remains conversation content. **Merged:** ByteQuay persists the
  exact authorization and external receipts because the desktop process can
  restart between approval and completion.
  [Official Codex App Server documentation](https://learn.chatgpt.com/docs/app-server)
- **Accepted from Grok Build:** an approval UI can offer approve, request changes,
  and comment rather than requiring a magic final response. **Rejected:** using
  plan approval as a mandatory development handoff; ByteQuay gates only public or
  irreversible effects.
  [Grok Build Plan Mode](https://github.com/xai-org/grok-build/blob/8a14c91d88875a831a38b3a066b1683116bcb31c/crates/codegen/xai-grok-pager/docs/user-guide/19-plan-mode.md)
- **Rejected from Grok Build:** headless examples that parse model output with
  tools such as `jq` or check for a text `OK`. That recreates the exact malformed
  verdict failure this model removes.
  [Grok Build headless-mode example](https://github.com/xai-org/grok-build/blob/8a14c91d88875a831a38b3a066b1683116bcb31c/crates/codegen/xai-grok-pager/docs/user-guide/14-headless-mode.md)
- **Accepted product evidence from ByteQuay:** local activity remains private,
  first publication is user-controlled, and remote reviewer interaction requires
  explicit review. **Replaced:** older phase/gate services and override paths are
  not implementation dependencies.
  [Local PR research](../mockups/local-pr-design.md),
  [Application privacy principle](../mockups/app-design.md)

## 16. Definition of done

- All four gate kinds use the same revision/transition/authorization machinery.
- Every code-publishing gate binds one exact `ChangeSetRevision`, local-review
  batches/latest revisions, and current local-check policy/profile/run evidence.
- Local review reply/resolve tools enforce compare-and-set on the revision the
  Task Agent actually read.
- Every new local review thread records and validates the exact open gate
  revision and candidate head the user was viewing.
- `INITIAL_PUBLISH` and `REMOTE_FEEDBACK` cannot be auto-authorized.
- Every approval is rejected after any relevant subject/action change.
- No Git push occurs for initial or feedback publication before authorization.
- No remote feedback reply/resolution occurs without manual authorization and
  exact-current-remote-head green, including reply-only batches.
- Mark-ready automation exists only under explicit initial/auto-merge authority,
  fresh exact-head green/no blockers, and the permitted publication lineage
  (initial or authorized CI-update successor for the initial policy).
- CI and merge standing consent create ordinary exact authorizations and are
  narrow, revisioned, revocable, and visible in the timeline.
- Crash tests prove partial-effect recovery without duplicate GitHub actions.
- No code parses agent final output to open, authorize, or consume a gate.
- The new implementation reads and writes no old approval/gate storage.
