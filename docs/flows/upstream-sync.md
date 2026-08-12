# Upstream sync

Status: **normative greenfield replacement specification**

Upstream Sync is an optional pre-publication producer for Tasks that bring an
ordered upstream commit range onto a fork. It owns range preview, cherry-pick
progress, conflict repair coordination, and reviewable fixup-history shape until
the first draft GitHub PR exists. After that boundary, generic
[CI Autofix](./ci-autofix.md), [remote feedback](./remote-feedback.md), and merge
flow own the PR.

Upstream Sync does not add a fifth agent role. The persistent
[Task Agent](./task-agent.md) works in `UPSTREAM_SYNC` mode whenever semantic
conflict resolution is required. Deterministic clean picks and history-safety
checks are program work.

Read [README.md](./README.md), [workflow-runtime.md](./workflow-runtime.md),
[user-gates.md](./user-gates.md), [github-integration.md](./github-integration.md),
[pr-timeline.md](./pr-timeline.md), and
[project-intelligence.md](./project-intelligence.md).

## Replacement boundary

This is a new component. It must not call, wrap, translate, dual-write, or share
lifecycle state with an old cherry-picker, CI harness watch, repair agent,
marker-line parser, history stage, or scheduler. No existing run is migrated and
there is no compatibility mode.

Neutral Git/process/model-provider adapters may be reused. The old combined
“pick range + push early + drive remote CI + retrospective” workflow must not be
recreated. Its useful Git safety lessons are restated here; its ownership model
is replaced.

## Scope boundary

```text
durable upstream preview before Task creation
  -> user confirms exact preview digest
  -> create Task at resolved target base
  -> construct branch locally
  -> resolve conflicts and shape history
  -> final checks + adversarial review
  -> user Local PR review
  -> program opens draft GitHub PR
  -> Upstream Sync stops
  -> generic GitHub / CI Autofix / feedback / merge flow
```

No remote CI exists before publication. Upstream Sync never pushes early for
“remote validation.” If local validation is unavailable, the exact initial
publish gate may open only as prominently warned and manual-only under the
canonical [User Gates](./user-gates.md) policy; a missing attempt or failed check
blocks. Agent prose cannot relabel the result or waive it. This preserves the
rule that the first push is explicitly reviewed without a hidden remote
fallback.

## Hard invariants

- One sync run belongs to one normal Task, branch, worktree, Task Agent, writer
  resource, and timeline. Its eventual Local PR identity is created only by the
  normal reviewable-draft rule.
- Preview is durable program work and completes before the Task exists. Request
  handlers persist work; they never fetch or run Git synchronously.
- Source/target refs are fetched and resolved to immutable SHAs before preview.
- The user confirms the exact ordered range and exclusions before mutation.
- Clean picks are deterministic program operations using `git cherry-pick -x`.
- A conflict is semantic work. The program preserves sequencer/conflict evidence
  and resumes the Task Agent; it never guesses a resolution.
- Never commit unresolved index entries or deliberately commit conflict markers.
  A conflicted cherry-pick is continued only after the Task Agent resolves it.
- The resulting conflicted pick records upstream provenance but is not falsely
  described as byte-for-byte upstream. Different parents require an actual
  conflict resolution.
- Post-pick fork adaptations have at most one adjacent `fixup!` commit per
  upstream semantic owner. Later adaptations for that owner combine into the
  same fixup.
- A change with no honest single owner is a standalone commit at the branch tip.
  The agent chooses semantic ownership; the program enforces the selected shape.
- Every history rewrite has a recoverable backup, a tight internal range, a
  clean-worktree precondition, and a before/after tree-equivalence proof when the
  intended operation is attribution-only.
- Never rebase a fixup-placement operation onto a moving branch name. Resolve
  concrete SHAs and use the smallest internal range.
- Fetch before comparing upstream, target, or remote tracking state.
- The Task Agent and program never mutate concurrently; every Git mutation uses
  the Task's fenced writer lease.
- A history-intent tool is terminal for the current Agent run. It seals/parks the
  agent and creates a separate runtime `UPSTREAM_SYNC` operation; that operation
  can claim its own writer lease only after the Agent run has released its lease.
  A Task-scoped mutation reservation admits only that exact successor while the
  sealed sequencer/dirty state exists.
- The Task Agent cannot push. Draft publication uses the ordinary exact
  `INITIAL_PUBLISH` user gate and GitHub executor.
- No agent final response is parsed. Conflict completion, park, and fixup
  ownership are tool calls.

## First-principles correction to the previous history model

The former design deliberately continued a conflicted pick with conflict-marker
content, then repaired it in a later fixup so it could call the pick “upstream
verbatim.” That promise is unsound:

1. an unresolved index cannot be committed safely without converting conflict
   artifacts into ordinary content;
2. marker-bearing code can break every intermediate checkout;
3. the commit is not upstream-verbatim anyway because it has a different parent
   and tree; and
4. crash recovery becomes a repository-corruption problem rather than a normal
   cherry-pick continuation.

This design keeps the real reviewer value—upstream SHA provenance and adjacent,
bounded fork adaptations—without storing knowingly broken commits. The Task
Agent resolves the conflict before `cherry-pick --continue`; any additional
fork-specific adaptation becomes the adjacent fixup.

## Logical data model

### `UpstreamSyncRequest`

```text
requestId, requestKey, repositoryId, goalText,
sourceRemote, sourceFromRef, sourceToRef, targetRef,
state, requestedByUserId, createdAt
```

The request exists before any Task. `goalText` is already self-contained; the
program does not recover meaning from a Trunk transcript. Refs are user input,
not execution authority until a preview operation resolves them.
States are `REQUESTED`, `PREVIEWING`, `PREVIEW_READY`, `STARTED`, `STALE`,
`CANCELED`, and `NEEDS_ATTENTION`.

### `UpstreamRangePreview`

```text
previewId, requestId, previewOperationId, fetchedAt,
resolvedSourceFrom, resolvedSourceTo, resolvedTargetBase,
orderedEntries[], digest, expiresAt
```

Each entry contains:

```text
upstreamSha, parentShas, subject, authoredAt,
presence = NOT_PRESENT | TRAILER_MATCH | PATCH_EQUIVALENT | AMBIGUOUS |
           UNSUPPORTED_MERGE,
presenceEvidenceRef, selected
```

The program computes mechanical evidence. It does not treat `AMBIGUOUS` as
present. The user decides selections in the preview. Range semantics are the
topologically ordered ancestry set `resolvedSourceFrom..resolvedSourceTo`: the
lower bound is exclusive and the upper bound inclusive. The first implementation
rejects selected merge commits as `UNSUPPORTED_MERGE`; choosing a merge mainline
is semantic and is not inferred by the program.

### `UpstreamRangeConfirmation`

```text
confirmationId, previewId, previewDigest,
selectedUpstreamShas[], targetBaseSha, confirmedBy, confirmedAt
```

This is an exact local-work confirmation, not an external-effect authorization.
Changing selection creates another preview/confirmation.

### `UpstreamSyncRun`

```text
runId, taskId, confirmationId, state,
targetBaseRevisionId, targetBaseSha, currentIndex, currentHead,
createdAt, updatedAt
```

States are `READY`, `PICKING`, `WAITING_CONFLICT_REPAIR`, `WAITING_USER`,
`NORMALIZING_HISTORY`, `FINAL_REVIEW`, `WAITING_INITIAL_PUBLISH`, `HANDED_OFF`,
`CANCELED`, and `NEEDS_ATTENTION`.

### `UpstreamPickRecord`

```text
pickId, runId, ordinal, upstreamSha, preHead,
resultHead?, resultCommitSha?, state,
conflictedPaths[], conflictEvidenceRef?, provenanceVerified,
agentRunId?, localCheckRunIds[]
```

States are `PENDING`, `APPLYING`, `CLEAN`, `CONFLICTED`, `RESOLVED`,
`SKIPPED_PRESENT`, `SKIPPED_EMPTY`, and `NEEDS_ATTENTION`.

### `UpstreamFixupRecord`

```text
fixupId, runId, ownerUpstreamSha?, ownerPickId?,
kind = ADJACENT_FIXUP | STANDALONE,
currentCommitSha, changedPaths[], createdByAgentRunId,
rewriteProofRef?, supersedesFixupId?
```

The record preserves logical identity when a local rewrite changes commit SHAs.

### `HistorySafetyReceipt`

```text
receiptId, runId, operation, oldTip, newTip,
backupRef, affectedRange, expectedTreeDigest,
actualTreeDigest, mappingEvidenceRef, completedAt
```

### `UpstreamHistoryCommand`

One terminal Task Agent tool call creates an immutable semantic command:

```text
commandId, runId, agentRunId, operationId,
kind = CONTINUE_PICK | PLACE_FIXUP | APPEND_STANDALONE,
pickId?, ownerPickId?, paths?, message?, expectedHead,
sealedWorktreeDigest, createdAt
```

The command records the agent's semantic choice. It is not proof that Git ran.
Its separate runtime `Operation(kind=UPSTREAM_SYNC)` owns execution/claim state.

### `UpstreamVerification`

```text
verificationId, runId, headSha, targetBaseRevisionId, targetBaseSha, rangeDigest,
orderedPickRecordIds[], fixupRecordIds[], historyTreeDigest,
provenanceEvidenceRef, historySafetyReceiptIds[], createdAt
```

This immutable record proves the exact upstream construction shown at local
review. Any head, range, base, mapping, or history-shape change requires a new
verification. An upstream Task's `INITIAL_PUBLISH` gate must freeze its
`upstreamVerificationRef`; gate authorization revalidates that the reference is
still exact and current.

## Program APIs

### Preview before Task creation

```text
UpstreamSyncCommands.requestPreview(
  requestKey, repositoryId, goalText,
  sourceRemote, fromExclusive, toInclusive, targetRef
) -> requestId
UpstreamPreviewRunner.execute(operationId, runtimeClaim) -> previewId
UpstreamSyncCommands.requestPreviewRefresh(requestId) -> refreshOperationId
UpstreamSyncCommands.startConfirmed(
  userId, previewId, previewDigest, selectedShas, startRequestKey
) -> StartReceipt(taskId, runId)
```

`requestPreview` performs one database transaction: persist the request, runtime
`Operation(ownerKind=UPSTREAM_REQUEST, ownerId=requestId,
kind=PREVIEW_UPSTREAM_SYNC, taskId=null)`, and unique `DispatchTicket`. It
does no fetch, process launch, Task creation, branch creation, or Git work. The
runtime dispatcher claims the operation and invokes `UpstreamPreviewRunner`,
which fetches refs, validates ancestry/range bounds, calculates ordered commits,
and finds presence through recorded `-x` trailers and stable patch equivalence.
It operates only in an operation-scoped app-owned scratch checkout/cache created
from the configured repository; it never fetches, checks out, or mutates the
user checkout or a Task worktree. Scratch cleanup is idempotent after the preview
evidence is stored. A preview expires when any resolved boundary no longer
matches.

`startConfirmed` is the user's exact confirmation, not an agent verdict. It
validates the stored preview digest, expiry, and selection,
then atomically creates the Task with `goalText` and
`launchBaseSha=currentBaseSha=resolvedTargetBase`, its initial
`TaskLifecycleRevision(toStatus=CREATED)` plus current pointer, initial
`TaskBaseRevision`, `UpstreamRangeConfirmation`, `UpstreamSyncRun`, and one
runtime Task-provision operation/ticket. No
generic `start_task` call and no synchronous Git exists in this handler. The
idempotency key prevents duplicate Tasks.

No Local PR exists at this point. It materializes through the normal
`save_pr_draft` path only after the Task has a clean, non-empty, reviewable diff
during final review.

Each command may best-effort nudge the in-process dispatcher only after commit.
Durable `DispatchTicket` polling is the correctness path when that nudge is lost.

### Deterministic picking

```text
UpstreamSyncRunner.execute(operationId, runtimeClaim)
  -> CLEAN_PROGRESS | CONFLICTED | COMPLETE | NEEDS_ATTENTION
UpstreamSync.checkTargetMovement(runId) -> SAME | MOVED(newBase)
UpstreamSync.verifyHistory(runId, expectedHead) -> upstreamVerificationRef
UpstreamSync.handoffAfterDraftPublish(runId, consumedInitialPublicationResultRef)
```

After Task provisioning, and after every accepted history command, the runtime
owns a separate `Operation(kind=UPSTREAM_SYNC)`. The dispatcher claims that
operation and its writer lease, then `UpstreamSyncRunner` verifies expected
head/worktree evidence and performs the next deterministic action. It may run
successive clean `git cherry-pick -x <sha>` steps inside the same claimed
operation until conflict, completion, cancellation, or a bounded checkpoint.
Conflict evidence is stored before the operation completes and releases its
lease; only then may the runtime resume the Task Agent.
These deterministic APIs are internal operation-runner calls. Controllers and
agent tool handlers cannot invoke them outside a valid runtime claim.

If Git reports that a selected commit is empty because its change already
exists in the constructed tree, the program proves no tree change, runs
`cherry-pick --skip`, records `SKIPPED_EMPTY`, and continues. It does not ask an
agent to invent a commit.

### History safety primitives

```text
HistorySafety.createBackup(runId, expectedTip) -> backupRef
HistorySafety.placeOrCombineFixup(runId, ownerPickId, fixCommit) -> receipt
HistorySafety.appendStandalone(runId, fixCommit) -> receipt
HistorySafety.integrateBase(runId, oldBase, newBase) -> receipt
HistorySafety.restore(receiptOrBackupRef) -> restoredHead
```

These methods use explicit SHAs, never an unresolved moving branch name. The
program verifies expected head, clean index, intended commit mapping, and final
tree. A failed proof restores or quarantines; it never reports success from exit
code alone.

## Task Agent tools in upstream-sync mode

```text
read_upstream_pick(pick_id)
read_upstream_diff(upstream_sha, path?, range?)
read_conflict_file(pick_id, path, range?)
read_fork_context(query, paths?)
run_checks(profile?) -> LocalCheckRunRef[]
continue_upstream_pick(pick_id) -> ACCEPTED_SEALED or actionable tool error
commit_upstream_fixup(pick_id, paths) -> ACCEPTED_SEALED or actionable tool error
commit_upstream_standalone(paths, message) -> ACCEPTED_SEALED or actionable tool error
request_user_input(question) -> userRequestId
spawn_agent(role="adversarial_reviewer") -> reviewRequestId
ready_for_review() -> ACCEPTED_SEALED or actionable tool error
```

The optional Trunk/UI tool
`request_upstream_sync_preview(...) -> ACCEPTED(requestId)` is only a thin call
to `requestPreview`; it persists asynchronous work and returns. It does not
start a Task or execute Git.

The Task Agent edits with its normal code tools. Each specialized mutation tool
makes history intent explicit and is terminal for that Agent run:

The `UPSTREAM_SYNC` capability policy permits file edits but denies direct
commit, cherry-pick, rebase, reset, and history-rewrite commands. Only the
terminal tools below can request those mutations from the later fenced program
operation.

- `continue_upstream_pick` records the selected current pick; the later
  operation verifies the resolved index, runs `cherry-pick --continue`, and
  verifies `-x` provenance.
- `commit_upstream_fixup` records changed paths and an owner pick; the later
  operation creates the path-scoped commit and safely places/combines the one
  logical fixup adjacent to its owner.
- `commit_upstream_standalone` records the explicit judgment that no one pick
  owns the change; the later operation appends it at the current tip.

On acceptance, the handler binds the authenticated Task/run/head/fence, records
the `UpstreamHistoryCommand`, seals the worktree digest, and atomically creates
the separate `UPSTREAM_SYNC` operation/nonclaimable ticket **and** sets
`Task.reserved_mutation_operation_id` to that successor. It then seals the
Agent run and disables further tools. `AgentRuns.finish` alone persists the run
result, releases its fence/selected pointer, and returns the persistent session
to `IDLE` while preserving the reservation. The reservation is a durable
admission barrier: after the Agent lease is released, no Task, CI, or recovery
writer except the named successor can acquire. Only after the opaque Agent
result is durable, the predecessor selected-writer pointer is cleared, and its
lease is released does the runtime make that successor ticket eligible to claim
a fresh writer lease. The runner
rechecks the sealed digest before Git. There is never a nested operation or two
valid writer leases.

For a material question the agent uses the one shared terminal
`request_user_input` contract. It stores the question and a program-measured
sealed worktree/sequencer state; the runtime blocks every writer until the exact
answer-bound successor resumes it. `ready_for_review()` similarly seals the
final candidate; `ACCEPTED_SEALED` is not a gate ID or authority.

The agent supplies semantic owner/path/message choices, not expected heads,
lease fences, Task IDs, or a JSON result. Those come from the authenticated run.

### Conflict launch envelope

The runtime resumes the existing Task Agent with references, not a generated
summary protocol:

```text
UpstreamConflictLaunch
  taskGoal
  runId, pickId, ordinal, upstreamSha
  targetBaseSha, preHead, measuredCurrentHead
  upstreamDiffRef, conflictEvidenceRef, conflictedPaths[]
  repositoryId, worktreePath, policyRevision
  toolPolicy = UPSTREAM_SYNC
```

`taskGoal` is the exact self-contained `goalText` stored by `startConfirmed`.
The runtime binds Task identity and the writer fence below the tool layer. It does not inject
Trunk discussion, a hidden Project Intelligence selection, or a prior agent's
plan. The Task Agent reads repository instructions/code and queries Project
Intelligence itself when useful.

## End-to-end lifecycle

### 1. Define the range

1. Trunk confirms the source, end points, target branch, and intended exclusions
   in one self-contained goal. No Task exists yet.
2. Trunk or the UI calls `requestPreview`; the handler durably persists the
   request plus preview operation/ticket and returns `requestId` without
   running Git.
3. The runtime dispatcher claims `PREVIEW_UPSTREAM_SYNC`; its runner fetches and
   resolves refs and stores the immutable preview.
4. The UI displays every selected, already-present, and ambiguous commit plus
   the resolved source/target SHAs.
5. The user calls `startConfirmed` on the exact preview digest and selection.
6. One transaction creates the Task at the preview's exact target-base SHA,
   initial `CREATED` lifecycle revision, confirmation, sync run, and
   Task-provision operation/ticket.
7. The dispatcher provisions the Task branch/worktree at that SHA. On success,
   it persists the first deterministic upstream work cause and ensures Task
   reconciliation. `WorkSelector` creates the first separate `UPSTREAM_SYNC`
   writer operation. It does not create a harness worktree or second agent
   session.

### 2. Apply clean commits

For each selected commit:

1. the runtime claims the `UPSTREAM_SYNC` operation and writer lease and verifies
   expected Task head;
2. `git cherry-pick -x <upstreamSha>`;
3. if clean, store the pick record and continue;
4. if conflicted, store complete conflict evidence and stop deterministic work;
5. complete/checkpoint the operation and release fenced ownership only through
   the runtime.

Clean picks do not require an agent narration. Hundreds of commits should not
create hundreds of model turns.

### 3. Repair a conflict

1. The runtime resumes the same persistent Task Agent with a launch referencing
   the exact pick, upstream diff, conflicting paths, pre-head, current sequencer
   evidence, the Task's exact self-contained goal, and objective repository/head
   facts. It does not inject interpreted Trunk conversation or a selected
   Project Intelligence projection; the Task Agent queries Project Intelligence
   itself when useful.
2. The agent reads both upstream intent and fork context, edits the conflict,
   and runs useful local checks.
3. It calls terminal `continue_upstream_pick(pickId)`. The handler records the
   command and separate operation/ticket, seals the Agent run, and returns
   `ACCEPTED_SEALED`; it runs no Git.
4. After the Agent result is durable and its lease is released, the dispatcher
   claims the separate `UPSTREAM_SYNC` operation and writer lease. The runner
   verifies the sealed resolved index, continues the pick, checks `-x`
   provenance, captures the resulting head, and records execution evidence.
5. If further fork adaptation is necessary, the Task Agent edits and calls
   `commit_upstream_fixup` for the honest semantic owner, or
   `commit_upstream_standalone` when no owner exists.
6. Program picking resumes at the next selected commit.

If the agent cannot make a sound decision, it calls `request_user_input`. It
does not hide a marker in final prose or use an upstream-specific waiting path.

### 4. Keep fixups reviewable

For an owner that already has a logical fixup, a later
`commit_upstream_fixup`:

1. terminally seals/parks the Agent run and persists its semantic command plus a
   separate `UPSTREAM_SYNC` operation;
2. after lease handoff, the operation creates a backup at the exact current tip;
3. creates the new path-scoped repair commit;
4. combines old and new repair content into one logical fixup;
5. places it directly after the mapped picked commit using a tight internal
   range;
6. updates all affected logical-to-current SHA mappings;
7. proves the final tree equals the pre-rewrite intended tree; and
8. records a `HistorySafetyReceipt` before dropping the temporary backup.

An attribution-only rewrite with a changed final tree is failure and restores
the backup.

### 5. Target branch moves

At safe checkpoints and before final review, the program fetches and compares
the target's resolved SHA.

- If unchanged, continue.
- If moved, persist a base-integration intent and stop normal picking.
- Integrating the new base is a deliberate semantic rebase, distinct from
  fixup placement. A separate `UPSTREAM_SYNC` operation claims the writer lease
  and begins it under backup; conflicts return to the Task Agent using the same
  terminal-command handoff.
- After the integrated tree/head is proven, that operation calls
  `TaskBases.advance(...)` with the integration receipt, then
  `ChangeSets.adopt(...)`. The resulting change set freezes the new exact
  `baseRevisionId/baseSha`; the immutable launch base remains historical.
- After integration, previous local check/reviewer evidence is stale and must be
  rerun.

No remote force-push exists yet because the draft PR has not been created.

### 6. Final local review

After the last pick:

1. The Task Agent runs the broadest locally available repository checks.
2. It calls the normal `save_pr_draft` flow, which materializes the Local PR
   only for a clean, non-empty, reviewable diff and saves its title/body draft.
3. It spawns a fresh read-only adversarial reviewer for the exact final head.
4. It fixes actionable findings through the same terminal history-command
   boundary and repeats exact-head checks/review as needed.
5. It calls `ready_for_review()` and receives `ACCEPTED_SEALED` or an actionable
   error; no gate ID is returned to the model.
6. After result/head/lease evidence is durable, a final `UPSTREAM_SYNC`
   verification operation calls `verifyHistory` and stores an immutable
   `UpstreamVerification` proving selection/provenance/order,
   one-fixup-per-owner shape, standalone-tip placement, clean worktree, and
   current target base.
7. The ordinary `INITIAL_PUBLISH` gate freezes that
   `upstreamVerificationRef` and shows the complete range summary,
   conflict/fixup history, missing validation, exact diff, checks, and review.
   Authorization revalidates the verification's head, base, range, mappings,
   and safety receipts.
8. User local comments return to the same Task Agent, stale the gate, and require
   a fresh verification after the next sealed candidate.

### 7. Publish and hand off

After exact user approval:

1. [GitHub integration](./github-integration.md) pushes the exact head and opens
   a draft PR;
2. the existing Local PR gains remote identity and the `INITIAL_PUBLISH` effect
   returns a fully consumed success result;
3. `handoffAfterDraftPublish` verifies that consumed result plus its identity,
   then marks the sync run `HANDED_OFF` and releases sync-only resources;
4. generic [CI Autofix](./ci-autofix.md) owns all final red CI rounds, under the
   `ATTRIBUTED_FIXUP` placement this Task was created with — so a post-publication
   repair is still positioned behind the pick it belongs to, and the series stays
   reviewable commit by commit;
5. generic [remote feedback](./remote-feedback.md) owns reviews/comments; and
6. Upstream Sync never resumes for that PR.

### 8. Cleanup after merge

Merging is the user's act, not the run's. Once the pull request is observed
merged, the run tears down what it still holds and records what it released:

1. the isolated worktree is removed;
2. the local result branch is deleted, because the remote copy is now the one
   that matters;
3. the agent session and its stored transcripts are dropped, which is the bulk of
   the run's disk;
4. the remote branch is deleted, since a merged cherry-pick range has no reader
   left; and
5. the run is closed with a durable receipt of the above.

Cleanup is a receipt, not a surface: there is nothing to steer and nothing to
approve, so it carries no composer and no action. A step that cannot be completed
is recorded as not released rather than retried forever — a branch someone else
already deleted, or a worktree already gone, is a normal outcome and not a
failure of the run.

A run whose pull request was closed without merging cleans up identically except
that the remote branch is left alone: unmerged commits on it are the only copy of
work someone may still want.

Post-publication base movement is ordinary Task reconciliation. It does not
reopen the pre-PR upstream range engine.

## Validation policy

The current executable replacement does not yet expose `run_checks` on an
Upstream Sync Task turn. That binding remains deferred until its finalizer can
durably retain process-boundary uncertainty without releasing a successor
writer; the policy below is the eventual component contract.

The Task Agent discovers useful commands from repository instructions, build
files, CI configuration, and Project Intelligence, then calls `run_checks`.
The program records the policy/profile revision, allowlisted environment names
and availability, exit state, bounded fail-closed output
evidence, and exact head; it does not infer a semantic verdict.

- Clean picks do not run full CI locally one by one.
- A conflict/fixup should run the narrow useful check when available.
- The final candidate runs the broadest practical local validation.
- A program-captured `UNAVAILABLE` result for a required local profile is shown
  prominently and makes the initial gate manual-only; a missing attempt or
  `FAILED` result blocks.
- Missing validation never causes a hidden early push.
- Remote CI after draft publication is the final provider authority and uses
  [ci-autofix.md](./ci-autofix.md).

## Concurrency

- One Upstream Sync run may be active per Task.
- It uses the Task worktree; no agent or harness worktree is created.
- Program picks, Task Agent edits, history normalization, checks, and final
  review contend for one Task writer resource. Each Agent run or runtime
  operation gets its own mutually exclusive fenced lease; a fence is never
  transferred or nested.
- Read-only adversarial review requires a committed clean head and no writer.
- New project knowledge or target movement is recorded while an agent runs, but
  never injected as an interruption. It is applied at the next safe boundary.
- Cancellation stops at a durable boundary, restores/finishes an in-progress
  sequencer according to evidence, and never deletes the user's only recovery
  reference.

## Recovery

- **Crash between clean picks:** expected head and next ordinal make restart
  idempotent; inspect Git before deciding whether the pick completed.
- **Crash during cherry-pick:** inspect sequencer/index and `UpstreamPickRecord`;
  first prove the old runner/process group is dead and its capability revoked,
  then resume the recorded operation or restore pre-head. Do not inspect against
  a live writer or run the next pick.
- **Agent crash during conflict:** keep conflict evidence and dirty worktree
  quarantined; only after process death/capability revocation is proven may a
  replacement Task Agent run receive a fresh fence.
- **Crash during history rewrite:** restore the durable backup, verify tree/head,
  then retry from the logical SHA mapping.
- **Tree-equivalence proof fails:** restore, mark `NEEDS_ATTENTION`, and retain
  both evidence sets for user inspection.
- **Range preview goes stale before confirmation:** reject the confirmation and
  produce a new preview.
- **Crash during preview:** reclaim the same `PREVIEW_UPSTREAM_SYNC` ticket and
  store one idempotent preview; no partial Task exists.
- **Crash after a terminal history tool:** do not claim its `UPSTREAM_SYNC`
  ticket until the Agent result is finalized, the old lease is released, and
  the sealed worktree digest still matches. Its mutation-admission reservation
  blocks every unrelated writer. Clear the reservation only after success or a
  proven clean restore; otherwise transfer it to a typed recovery operation or
  quarantine the Task.
- **Target moves repeatedly:** finish or restore the active internal operation,
  then enqueue one latest-base integration. Do not stack rebases.
- **Budget exhausted/agent parks:** preserve worktree, run, conflict, and session;
  notify the user to raise budget, give guidance, or cancel.
- **Initial publication partly succeeds:** GitHub effect recovery probes branch
  and PR identity, but Upstream Sync remains active while the Task is
  `NEEDS_ATTENTION` or the gate is stale. It hands off only after a later
  `INITIAL_PUBLISH` recovery revision is fully consumed; identity alone is not
  success.
- **PR closed before merge:** generic PR cleanup owns it; the handed-off sync run
  remains historical and never restarts.

## Timeline projection

The single Task PR timeline projects:

- range preview confirmed;
- compact pick progress/digest, not one noisy tool row per clean command;
- conflict detected/resolved/parked;
- fixup or standalone-history decision and safety proof;
- target-base integration;
- final local review gate; and
- proven draft publication/handoff.

Commands, raw conflict files, model transcript, and rewrite internals remain
linked execution evidence. Upstream Sync and its agent never write timeline
events. See [pr-timeline.md](./pr-timeline.md).

## Required acceptance traces

1. **Durable launch:** preview request commits an upstream-request-owned
   operation/ticket before any fetch and before a Task exists; crash/reclaim in
   app-owned scratch storage produces one preview and never changes a user/Task
   checkout. Confirming its current digest creates exactly one Task at the
   resolved target-base SHA, one run, and one provision operation.
2. **All clean:** confirm 100-commit range -> deterministic `-x` picks -> one
   final review/gate -> one draft PR -> generic CI ownership.
3. **Already present:** trailer/patch-equivalent entries are shown and excluded
   only by the confirmed preview; duplicate delivery does not enter history.
4. **Ambiguous presence:** program refuses to infer; user selection controls the
   exact confirmation.
5. **Merge in range:** preview marks it unsupported and confirmation rejects it;
   no implicit `-m` mainline is chosen.
6. **Conflict:** clean picks stop -> Task Agent resolves -> terminal continue
   tool seals/parks the Agent run -> separate `UPSTREAM_SYNC` operation claims a
   later writer lease -> provenance verified -> picking resumes; no nested lease
   and no committed conflict markers.
7. **Repeated owner fix:** two adaptations for one pick result in one adjacent
   logical fixup and an unchanged intended final tree.
8. **No semantic owner:** Task Agent creates a standalone tip commit; program
   does not invent a target.
9. **Rewrite failure:** changed-tree proof restores the exact backup and parks.
10. **Target moves:** program fetches, integrates latest base under backup,
   returns conflicts to Task Agent, and invalidates old evidence.
11. **No local toolchain:** no early push occurs; a captured `UNAVAILABLE`
    attempt is visible and manual-only at the explicit initial gate, while a
    missing attempt or failed check blocks it.
12. **Local review comment:** the gate stales, Task Agent updates the range
    candidate, exact checks/review repeat, and a new gate opens.
13. **Verification binding:** initial review freezes an immutable current
    `upstreamVerificationRef`; any head/base/range/mapping change stales it and
    cannot authorize publication until a new verification exists.
14. **Publication timeout:** effect probe binds one existing draft PR; no second
    PR is opened and sync hands off once.
15. **Red remote CI:** generic CI Autofix creates the repair round; Upstream Sync
    neither wakes nor rewrites history.

## First-principles challenge

| Question | Decision | Tradeoff |
|---|---|---|
| Does upstream sync need a separate agent? | No. It is the Task's implementation work and needs the same goal/context. Use Task Agent mode. | The Task Agent session can be long; durable pick records and references bound each resume. |
| Should a Task exist while an unconfirmed range is only being previewed? | No. A durable program operation can resolve objective Git facts first; Task identity starts only from the user's exact digest. | Preview has its own small pre-Task lifecycle and may need refresh. |
| Should an agent perform every clean pick? | No. Clean `cherry-pick -x` is deterministic. | Program owns careful crash recovery. |
| Should a history tool run Git while its caller owns the worktree? | No. The tool stores semantic intent and seals the run; a later runtime operation obtains a distinct lease. | One extra dispatch boundary, but crash and writer ownership are unambiguous. |
| Should the program resolve conflicts? | No. Conflict resolution is semantic and fork-specific. | Human/agent waits can slow a large range. |
| Should conflict markers be committed to preserve an “upstream” commit? | No. It stores knowingly broken content and the claim is false. Resolve before continuing, retain provenance, then use honest fixups. | The conflicted pick is not byte-for-byte comparable to upstream; evidence must show the resolution. |
| Should remote CI be used per conflicted pick? | No. It violates initial local-review-before-push authority and can create hundreds of remote rounds. | Some failures are discovered only after the draft PR. |
| Does generic CI Autofix need fixup-history knowledge? | Yes, as a program-resolved placement policy, not as agent judgment. A sync-built branch is a reviewable series, and a repair landing as an opaque tip commit that touches several picks destroys the property the whole range exists for — so CI Autofix gained `ATTRIBUTED_FIXUP`. This reverses the earlier answer; the alternative was a second CI engine, which is worse. | Every rewriting round force-pushes and restarts the whole remote board, so attribution costs one full CI cycle per round. It stays opt-in per Task for that reason, and ordinary Task PRs keep `TIP`. |
| Is a generic forge/ecosystem/history-policy abstraction required? | No. Build the approved GitHub/Git path; add a second adapter only with a second real target. | Future extraction is deliberate. |
| Is a post-merge sync-agent retrospective required? | No. It crosses the draft-PR ownership boundary. Project Intelligence and CI lessons already have durable learning paths. | Sync-specific human corrections are not summarized by this agent automatically. |

## Evidence and adopted/rejected ideas

- **Accept Codex's task/worktree isolation and exact diff review:** Codex keeps
  code work scoped to a workspace and lets a dedicated reviewer inspect a
  selected exact diff before commit/push. ByteQuay applies one worktree per Task
  and one final exact-head adversarial review.
  [Codex code review](https://learn.chatgpt.com/docs/code-review)
- **Accept Grok's resumable context, reject needless child handoff:** Grok
  supports resumed sessions and warns against delegation when context transfer
  costs more than parallelism. Conflict decisions and final implementation stay
  with the persistent Task Agent.
  [Grok subagents](https://github.com/xai-org/grok-build/blob/8a14c91d88875a831a38b3a066b1683116bcb31c/crates/codegen/xai-grok-pager/docs/user-guide/16-subagents.md)
- **Accept host-owned deterministic background work:** Grok's background-task
  model makes commands observable without turning them into autonomous agents.
  ByteQuay uses program-owned clean picks and durable progress.
  [Grok background tasks](https://github.com/xai-org/grok-build/blob/8a14c91d88875a831a38b3a066b1683116bcb31c/crates/codegen/xai-grok-pager/docs/user-guide/20-background-tasks.md)
- **Accept Git's provenance mechanism:** `git cherry-pick -x` appends the source
  commit reference for non-conflicting picks and is the correct auditable link;
  ByteQuay verifies equivalent provenance for continued conflict picks.
  [Git cherry-pick documentation](https://git-scm.com/docs/git-cherry-pick)
- **Preserve prior ByteQuay safety lessons:** fetch before compare, explicit
  backups, tight internal rewrite ranges, path-scoped repair commits, semantic
  ownership, one fixup per owner, and final-tree proof remain hard invariants.
- **Reject the previous combined lifecycle:** agent-owned push, early remote
  validation, one session spanning pre-PR and CI, marker-line verdicts, and
  post-merge teardown/retrospective made one component own unrelated authority.

## Implementation completion rule

The component is complete only when all fifteen traces pass in disposable real
Git repositories with injected process crashes and moving refs. Unit tests that
mock Git exit codes without checking resulting commits, index, sequencer,
recovery ref, and tree equivalence do not satisfy this contract.
