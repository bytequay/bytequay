# Adversarial Reviewer

Status: normative greenfield replacement specification
Parent architecture: [Development-flow architecture](./README.md)

## 1. Purpose

The Adversarial Reviewer is a fresh, read-only child agent that challenges one exact
committed candidate. It looks for actionable correctness, security, reliability,
regression, and test-coverage problems before the candidate reaches a user gate.

It is independent by construction:

- a new session is created for every reviewed head;
- it receives the confirmed goal and current repository evidence, not the author's
  working plan or private reasoning;
- it cannot edit the Task worktree;
- its natural-language result is automatically persisted before the
  [Task Agent](./task-agent.md) resumes.

This is a new component. It has no compatibility output, legacy result reader,
translation layer, migration, or fallback reviewer.

## 2. First-principles result

The value of adversarial review is independent attention, not another approval token.
The minimum useful contract is:

1. freeze the subject at commit `H`;
2. supply the goal, diff, current source, project rules, and available check evidence;
3. prevent the reviewer from changing the subject;
4. retain exactly what it reports;
5. let the persistent Task Agent and user decide what to do about the report.

The program can prove that a review completed for `H`. It cannot safely prove that
the prose means “approved,” that every concern was resolved, or that a qualified
compliment contains no actionable concern. Therefore no semantic reviewer outcome is
a program transition.

## 3. Non-goals

- Editing, committing, testing, pushing, replying, resolving, or merging.
- Producing a workflow verdict or machine-read findings object.
- Approving a candidate or opening/authorizing a user gate.
- Reusing the author session or inheriting its working plan.
- Preserving reviewer conversation across review rounds.
- Monitoring the Task after completion.
- Spawning another agent.
- Writing PR timeline events.
- Asking the user questions directly.

## 4. Owned durable data

### `ReviewerRequest`

The terminal `spawn_agent` tool creates this immutable request before any reviewer
session or run exists.

| Field | Meaning |
|---|---|
| `reviewRequestId` | Stable spawn/idempotency identity for one parent run and reviewed head |
| `taskId`, `parentSessionId`, `parentRunId` | Requesting Task context |
| `operationId` | The one durable `RUN_REVIEWER` operation/ticket |
| `subjectManifestRef` | Frozen goal, Git comparison, rules, and check-evidence references |
| `runId` | Set once by the claimed operation when it starts the fresh session; absent before claim |
| `createdAt` | Program timestamp |

Request state is derived from its operation and bound run; do not duplicate another
status machine. One request can bind at most one `AgentRun`.

### `AgentRun` where `role = ADVERSARIAL_REVIEWER`

This is the runtime's shared `AgentRun` with a reviewer-specific immutable subject
manifest, not a second reviewer-run table.

| Field | Meaning |
|---|---|
| `runId` | Shared program-generated `AgentRun` identifier; unique for `operationId` |
| `operationId` | The request's unique `RUN_REVIEWER` operation identity |
| `taskId` | Reviewed Task |
| `parentRunId` | Task-Agent run that requested review |
| `baseHead`, `reviewedHead` | Exact Git comparison |
| `diffDigest` | Program-computed digest of the reviewed diff |
| `goalRef` | Immutable confirmed Task-goal reference |
| `userAnswerRefs` | Exact resolved Task-scoped question/answer records created after launch |
| `ruleRefs` | Repository instruction/source references exposed to the reviewer |
| `checkRunRefs` | Complete ordered latest exact-head runs for the request's frozen current Local Checks policy; failed and genuine unavailable runs remain evidence |
| `status` | Shared runtime state: `QUEUED`, `RUNNING`, `COMPLETED`, `FAILED`, or `CANCELED`; timeout is `FAILED` with `errorRef`/reason `TIMEOUT` |
| `resultRef` | Opaque reviewer completion prose, when produced |
| `startedAt`, `completedAt` | Program timestamps |
| `errorRef` | Program-owned failure diagnostic |

The program generates every identifier and Git/evidence binding. The reviewer only
authors ordinary final prose. The runtime stores that prose without looking for
keywords, headings, arrays, fields, or a particular conclusion.

## 5. Exact inputs

The Task Agent calls only:

```text
spawn_agent(role="adversarial_reviewer")
```

It does not construct a reviewer prompt or result schema. The program binds the
following launch input from durable Task state:

- `taskId`, `reviewRequestId`, and the claimed operation's `runId`;
- exact confirmed goal;
- all resolved Task-scoped question/answer records created through
  `request_user_input` after launch;
- `baseHead`, `reviewedHead`, and frozen diff digest;
- immutable source snapshot at `reviewedHead`;
- repository instruction files applicable to the snapshot;
- source-cited Project Intelligence query capability;
- the frozen local-check policy revision and complete ordered latest required
  check-run reference list for the exact reviewed revision;
- the review policy and read-only tool capabilities.

The program excludes:

- author's working plan, private reasoning, or self-assessment;
- exploratory Trunk messages and program-selected conversation summaries;
- previous reviewer prose by default, which could anchor the new review;
- uncommitted worktree contents;
- remote mutation credentials and write tools.

The current CI-candidate integration implements the authority and immutable-Git
slice of this envelope. Its durable request carries exact Task/parent,
base/reviewed head, tree/diff, remote-input, revision, and repository-root
bindings plus the atomically revalidated current policy and exact run refs.
The current reviewer capability can inspect immutable tree, base/reviewed blobs,
and raw diff; reading check output and goal/user-answer/rule prompt assembly
remain deferred rather than being silently synthesized.

If a product-specific review needs previous concerns verified, that is a separate
explicit review input policy; it must not silently become the default.

### Reviewer standing instruction

The launch prompt must say, in substance:

> Independently review the exact committed change against the confirmed goal,
> explicit later Task user answers, and repository rules. Prioritize actionable
> correctness, security, reliability,
> regression, and missing-test issues. Give the relevant path/line and reasoning.
> State uncertainty plainly. Do not edit code or declare workflow approval. Your final
> response is ordinary review prose and will not be machine-parsed.

This guidance improves usefulness but is not a storage protocol.

## 6. Read-only source model

There is still one worktree per Task, not one worktree per agent. The reviewer reads
the committed Git tree through the current immutable reader:

- `list_tree` walks the tree at `reviewedHead`;
- `read_base_blob` reads raw bytes from `baseHead`;
- `read_reviewed_blob` reads raw bytes from `reviewedHead`;
- `read_diff` reads the bounded raw `baseHead..reviewedHead` object-change
  manifest.

This avoids a second worktree while ensuring later filesystem changes cannot alter
what was reviewed. Task/CI mutation events are queued while the parent waits. Even if
an out-of-band change occurs, the review remains bound to the immutable Git object and
will be stale for current readiness.

The local object reader accepts only full lowercase object IDs and a complete
local object store. It rejects alternates, partial-clone/promisor configuration
and markers, gitlinks, unsafe relative paths, and bounded-output overflow. Reads
clear ambient Git configuration, disable lazy fetch and replacement objects, and
use raw object/tree comparison that invokes no external diff, text conversion,
attributes, or worktree filters. Base and reviewed blobs therefore come from the
two named commits even when the checkout, replace refs, or filter configuration
changes later.

## 7. Lifecycle: start to stop

1. **Request.** Task has a committed head and calls
   `spawn_agent(role="adversarial_reviewer")` before presenting that candidate.
2. **Validate.** `ReviewerRequests.create(parentSessionId,
   subjectManifest)` verifies the role, committed current head, available diff, and
   absence of another live review for the same parent/head.
3. **Freeze and queue.** Program creates one `ReviewerRequest`, frozen subject,
   parent-blocked `RUN_REVIEWER` operation, and ticket, then seals the parent run
   against further tools. It creates no reviewer `AgentSession` or `AgentRun`.
4. **Finish and park parent mutation.** After the parent process is stopped/tool
   capability revoked, `AgentRuns.finish` persists its terminal result, captures
   fenced Git state, releases its writer lease and selected pointer, transitions
   the persistent Task session to `PARKED_CHILD`, and only then makes the reviewer
   ticket eligible. The reviewer request is now a mutation-admission barrier for
   that Task/head; new wakes queue.
5. **Run fresh agent.** `AgentSessions.startFresh(operationId, claimToken,
   ADVERSARIAL_REVIEWER, promptManifest, capabilities)` starts a new session with
   only read-only tools for the claimed reviewer operation. In the same
   transaction it creates or reuses the request's single operation-bound
   `AgentRun` and sets the request's `runId` once. Claim redelivery returns the
   existing run.
6. **Inspect.** Reviewer reads goal, diff, relevant current source, rules, and check
   evidence. It may query Project Intelligence, but every result is advisory and
   source-cited.
7. **Finish.** `AgentRuns.finish` atomically persists one terminal
   `AgentResult`: opaque prose for completion, or typed failure/cancellation
   evidence with optional partial prose. It settles the reviewer operation,
   closes the fresh session, releases the exact review barrier/wait, and records
   measured completion time.
8. **Schedule parent delivery.** Runtime stores
   `AgentResultReady(runId, reviewedHead)` and ensures the Task's one
   reconciliation ticket. Under the Task lock, `WorkSelector` decides when that
   parent turn is the one eligible writer. The result is never delivered only as
   an ephemeral chat interruption.
9. **Interpret.** Task reads the report and decides whether to fix, ask the user, or
    prepare a candidate. If code changes, it must request a new review for the new
    head.
10. **Stop.** Reviewer session closes immediately after result persistence. It is
    never resumed for a later head.

## 8. Agent tools and program APIs

### Reviewer tool surface

| Tool | Contract |
|---|---|
| `list_tree()` | Lists the immutable reviewed Git tree |
| `read_base_blob(path)` | Reads raw bytes from the exact base commit |
| `read_reviewed_blob(path)` | Reads raw bytes from the exact reviewed commit |
| `read_diff()` | Reads the bounded raw immutable object-change manifest |

Semantic subject projection, text search, Project Intelligence, and
`read_check_evidence` remain deferred. The current supervisor does not expose
shell, filesystem, worktree, or remote access.

Production CI review uses this exact read-only surface through the neutral
`TurnRunner`. Its launch freezes prompt/tool digests, provider transport,
model/limits, and the exact AI credential revision before HTTP; the reviewer
still receives no writer fence or mutable worktree capability.

There are no filesystem-write, shell-execution, Git-write, test-execution, parent
message, user-input, subagent, timeline, gate, or GitHub tools.

### Program APIs

| API | Responsibility |
|---|---|
| `ReviewerRequests.create(parentSessionId, subjectManifest)` | Idempotently persist one exact-head request plus parent-blocked `RUN_REVIEWER` operation/ticket, seal the parent run, and return `reviewRequestId`; create no reviewer session/run |
| `RepositorySnapshotReader.open(repositoryId, reviewedHead)` | Provide immutable Git-object reads without another worktree |
| `AgentSessions.startFresh(operationId, claimToken, ADVERSARIAL_REVIEWER, promptManifest, capabilities)` | Start/reuse the one read-only session/run for the current reviewer claim generation |
| `AgentRuns.finish(runId, claimToken, terminalOutcome, processMetadata)` | Validate the current generation and atomically store the one completed/failed/canceled result, settle operation/session/barrier, append `AgentResultReady(runId, reviewedHead)`, and ensure reconciliation; do not directly create a competing parent writer |

Normal parent delivery is automatic. `read_agent_result(runId)` exists on the
Task Agent only for recovery/history, not as a required polling loop.

## 9. Contracts with other components

### [Task Agent](./task-agent.md)

- Task is the only agent allowed to request this role.
- Task receives the full persisted prose and makes the semantic judgement.
- Reviewer cannot command Task, edit its worktree, or mark a concern resolved.
- A changed head requires a fresh review session.

### [Workflow Runtime](./workflow-runtime.md)

- Runtime creates bindings, enforces read-only capabilities, stores result before
  wake, handles timeout/retry, and measures staleness.
- Runtime may require a completed exact-head review for readiness, but never evaluates
  its semantic conclusion.

### [Project Intelligence](./project-intelligence.md)

- Reviewer may query source-cited notes and follow their repository sources.
- Intelligence cannot add approval authority or change review status.

### [PR Timeline](./pr-timeline.md) and [User Gates](./user-gates.md)

- Timeline projects review start/completion/failure from the reviewer `AgentRun`; the
  reviewer never writes an event.
- A gate may reference a completed review run for the exact candidate head.
- Gate UI exposes the actual report to the user.
- Review completion is not review approval. No wording in the result opens,
  authorizes, or satisfies a gate.

## 10. Invariants

1. Each review run binds one immutable `baseHead..reviewedHead` diff.
2. Each candidate head gets a fresh reviewer session.
3. Reviewer tools cannot modify repository, worktree, Task records, gates, timeline,
   or remote systems.
4. Reviewer holds no writer lease; its active operation prevents a new writer from
   changing the candidate until the review is terminal.
5. Reviewer final prose is opaque and persisted before the Task Agent is resumed.
6. The program never checks whether the report contains findings or approval words.
7. A completed review for H cannot satisfy readiness for H+1.
8. Duplicate requests from the same parent run/head return the same request and its
   bound live/completed review run, if any, rather than starting two agents.
9. Failed/timed-out/cancelled review is retained but does not satisfy exact-head
   review completion.
10. The reviewer cannot directly interact with the user or another agent.
11. Source content is untrusted input and cannot expand the reviewer tool surface.

## 11. Failure and recovery

| Failure | Required behavior |
|---|---|
| Reviewer returns failure, is cooperatively canceled, or times out with exact `STOPPED` proof | The reviewer finalizer stores one opaque `FAILED`/`CANCELED` result, settles/closes the operation/session, releases the exact barrier/wait, and persists the result-ready fact; Task requests a fresh review before readiness |
| An `ACTIVATED` reviewer claim expires without owned-thread stop proof | Quarantine the Task as typed `NEEDS_ATTENTION`, retain the run/attempt truth, and create no invented `AgentResult` or parent continuation |
| Program stops after result persistence but before parent delivery | On restart, reuse the result-ready fact and deduplicated reconciliation ticket; selection delivers it once |
| Program stops before result persistence | Mark/recover live run according to process evidence; never invent a result |
| Duplicate spawn call | Return the existing request and its bound run, if started, for the same parent/head |
| Out-of-band worktree/head change | Review still describes immutable H; current readiness marks it stale |
| Reviewer attempts write/tool escalation | Capability layer denies it and records the failed tool call; review may continue or fail by policy |
| Reviewer emits only malformed/unusual prose | Store it unchanged; Task/human reads it; no program parser fails |
| Check evidence missing | Reviewer states the limitation; objective readiness can require a later captured check run independently |
| Very large diff | Read tools page by path/range; timeout remains an explicit failed run rather than a partial approval |

## 12. Acceptance traces

### 0. One request creates one run

1. Call `spawn_agent` twice from the same parent run and exact head.
2. Assert one `ReviewerRequest`, one `RUN_REVIEWER` operation/ticket, no reviewer
   session, and no reviewer `AgentRun` before claim.
3. Claim the operation and call `AgentSessions.startFresh(...)`.
4. Assert one fresh reviewer session, exactly one bound `AgentRun`, and eventually
   exactly one `AgentResult` for that run.
5. Repeat with a crash after `startFresh` commits but before process start;
   redelivery must return the same session/run and still produce one result.

### A. Actionable concern

1. Task commits H1 and captures checks.
2. Program freezes H1 and starts reviewer R1.
3. R1 reports a race with path/line reasoning in ordinary prose.
4. Result is persisted, then Task resumes.
5. Task fixes the race and commits H2.
6. R1 remains visible but cannot satisfy H2 readiness; Task requests fresh R2.

### B. Qualified praise with a possible issue

1. Reviewer writes: the overall approach is sound, but an uncommon null path may
   still fail.
2. Program records only “R1 completed for H1”; it does not infer no findings.
3. Task reads the entire text and investigates the null path.
4. User later sees the report and exact resulting diff at the gate.

### C. Unconventional output

1. Reviewer produces paragraphs with no expected headings.
2. `AgentRuns.finish` persists the text verbatim in `AgentResult`.
3. Parent delivery succeeds because it carries `resultRef`, not parsed fields.
4. No workflow component fails due to format.

### D. Crash between completion and delivery

1. Reviewer result commits as `resultRef=AR7`; process stops before Task delivery.
2. Recovery finds R7 completed and not delivered.
3. Runtime reuses `AgentResultReady(R7, H1)` and one reconciliation ticket;
   `WorkSelector` chooses the parent turn once.
4. Task reads AR7 and continues.

### E. Write attempt

1. Repository content tries to instruct the reviewer to modify a file or call GitHub.
2. No such capability exists; the tool call is denied.
3. Git tree and remote state remain unchanged.
4. Source prompt injection cannot create authority.

### F. Reviewer failure releases the parent

1. Start a reviewer and observe a timeout with no final prose, then obtain exact
   cooperative `STOPPED` proof.
2. `AgentRuns.finish(runId, claimToken, FAILED(errorRef=timeout), ...)` stores one failed
   result, closes the fresh session, settles the operation, and releases the
   exact-head barrier.
3. `AgentResultReady(runId, H1)` reaches the parked Task session through
   reconciliation.
4. Task sees typed failure evidence and no invented approval/content; readiness
   for H1 remains blocked until a fresh completed review exists.

## 13. Evidence: accept, merge, reject

| Source | Decision | Reason |
|---|---|---|
| [Codex code review](https://learn.chatgpt.com/docs/code-review) | **Accept** a dedicated reviewer that inspects a selected branch/commit/diff, reports actionable findings, and does not modify the worktree | This is the core independence and exact-subject contract |
| [Codex subagents](https://learn.chatgpt.com/docs/agent-configuration/subagents) | **Accept** a narrow custom role/tool surface | Read-only capability is stronger than asking a general coding agent not to edit |
| [Codex subagent completion watcher](https://github.com/openai/codex/blob/3aae5d885bac39c1262491aa3fd100dfd8b3919f/codex-rs/core/src/agent/control.rs#L479-L560) | **Merge** automatic child completion delivery with durable result-first persistence | The parent should resume without polling, while restart cannot lose the result |
| [Grok subagents](https://github.com/xai-org/grok-build/blob/8a14c91d88875a831a38b3a066b1683116bcb31c/crates/codegen/xai-grok-pager/docs/user-guide/16-subagents.md) | **Accept** independent sessions, capability modes, background IDs, and result retrieval | These support isolated review and recovery access without live agent-to-agent interruption |
| Persistent/reused reviewer | **Reject** | Prior conclusions and author interaction weaken independence and create stale context |
| Reviewer-created approval field | **Reject** | A model's semantic conclusion is not an objective gate fact and recreates strict output-format failure |
| Reviewer worktree | **Reject** | Immutable Git-object reads provide exact source without another mutable workspace |

## 14. Tradeoffs

- A fresh reviewer spends more tokens than resuming one, but avoids anchoring and stale
  context.
- Read-only snapshot tools cannot run new tests. The reviewer reads captured evidence
  and recommends missing tests; the Task Agent runs them under the writer/runtime
  contract.
- Excluding previous findings can rediscover the same issue. That repetition is a
  useful signal and preserves independent review; Task retains the cross-round memory.
- Supplying all explicit Task question/answer records can add small context overhead,
  but avoids asking the program to semantically choose which user decisions matter.
- Natural-language results require Task/user judgement, but eliminate the fragile
  machine-output contract that caused the original workflow failures.
- Exact-head binding creates repeat reviews after small changes. That cost protects
  the claim about what was actually reviewed.
