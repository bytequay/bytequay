# Development flow architecture

Status: **normative greenfield replacement specification**

This directory is the complete implementation contract for ByteQuay's new
development flow. Each component has one self-contained document. This file
defines the system-wide rules and the contracts between them.

## Replacement boundary

This is a greenfield replacement, not a migration or an extension of the old
flow.

- New Tasks use only the components and records defined here.
- New components do not call, wrap, translate, dual-write, or share lifecycle
  state with old flow services.
- There is no legacy data migration, compatibility mode, version switch, or
  fallback path in this design.
- Development and test databases are recreated from one new-flow baseline
  schema. Do not add Flyway backfills, compatibility migrations, or data-copy
  jobs for old workflow tables.
- Old flow services and storage can be deleted after the new entry point is
  connected and the acceptance traces in this suite pass.
- Neutral adapters such as the model provider, Git process runner, database
  connection, and GitHub client may be reused. Old workflow ownership and
  orchestration may not.

The shortest correct replacement is preferred. Do not recreate the former
stage graph under new names.

### Current production composition foundation

The application now composes the greenfield owners on one qualified,
non-primary SQLite data source at `new-flow.db`. Startup installs the four
tracked new-flow schema resources in one `BEGIN IMMEDIATE` transaction, records
a length/path-framed resource digest and a recomputed `sqlite_schema` catalog
digest, and fails closed on a partial install, drift, foreign-key violation, or
failed integrity check. The existing application data source, JPA/Flyway
history, and legacy readers/writers remain separate and unchanged.

This is an executable greenfield composition, not live product cutover. A
program-owned `TaskCommands` bean accepts a bounded request key, repository ID,
and self-contained goal, calls the durable Task provisioning owner, and wakes
the polling lanes after commit. No controller, Trunk tool, frontend, flag, old
service adapter, or dual write calls that bean yet.
The generic dispatcher wires only local `PROVISION_TASK`.
The handler resolves the frozen configured remote ref from the already-local
object store, binds the exact SHA before mutation, and creates one derived
branch/worktree without fetch, credentials, provider calls, or model calls.

The generic worker selects only its wired kind set under capacity one. Separate
bounded GitHub owner lanes claim exact `INITIAL_PUBLISH`, `CI_UPDATE`, and
receipt-owned `OBSERVE_CI` work. A fourth owner lane handles only greenfield CI
reconciliation, fixer/cleanup, CI Task continuations, read-only reviewer, and
isolated learner operations. Each uses durable polling, cooperative shutdown,
and owner-specific expiry recovery. The optional read-only learner is serialized
by its sole CI lane but consumes no shared writer/effect capacity, so committed
observations and repair work can preempt it. All other lanes share the runtime's
global capacity predicate. Agent
runs freeze the exact provider transport/model/limits, prompt content, tool
manifest, and AI credential revision before the first request; secrets remain
ephemeral. One disjoint INITIAL Task lane claims only the provisioned first
turn and its exact initial-review continuations. It owns bounded workspace
editing, fixed commit/adoption, local PR/draft/check evidence, fresh read-only
reviewer lineage, and the stopped-finalizer request for a manual initial gate.
The GitHub lanes
read the configured `REPO` credential for the frozen canonical owner/name when
one exists, and otherwise the app's `ACCOUNT` GitHub token,
and perform a fresh authenticated numeric-ID/owner/name check before provider
use. The generic dispatcher continues to reject `PUBLISH` and `OBSERVE_CI`.
Old execution beans remain active until a later explicit cutover proves the
complete greenfield command and body graph.

## System in one sentence

One Task owns one branch, one worktree, one persistent Task Agent, at most one
writer, one Local-to-Remote PR identity, and one durable timeline; specialist
agents do bounded work while the program owns facts, scheduling, gates, and
external effects.

## Component index

| Component | Contract |
|---|---|
| Project Intelligence | [project-intelligence.md](./project-intelligence.md) |
| Trunk Agent | [trunk-agent.md](./trunk-agent.md) |
| Flow Runtime | [workflow-runtime.md](./workflow-runtime.md) |
| Task Agent | [task-agent.md](./task-agent.md) |
| Adversarial Reviewer | [adversarial-reviewer.md](./adversarial-reviewer.md) |
| PR Timeline | [pr-timeline.md](./pr-timeline.md) |
| User Gates | [user-gates.md](./user-gates.md) |
| GitHub observation and effects | [github-integration.md](./github-integration.md) |
| Remote feedback | [remote-feedback.md](./remote-feedback.md) |
| CI Autofix | [ci-autofix.md](./ci-autofix.md) |
| Optional upstream-sync producer | [upstream-sync.md](./upstream-sync.md) |

All linked documents are normative. When two documents appear to conflict,
the ownership and communication rules in this file win and the component
document must be corrected.

## Core Task-flow roles

The normal Task flow has four agent roles:

1. **Trunk Agent** — clarifies the user's real request and starts a Task.
2. **Task Agent** — the persistent semantic owner of the change.
3. **Adversarial Reviewer** — a fresh, read-only critic for one exact head.
4. **CI Fixer** — a persistent specialist resumed for bounded red-CI rounds.

There is no Plan Agent, Development subagent, review-comment fixer, CI monitor
agent, workflow Brain, or verdict agent. Remote review feedback returns to the
Task Agent because it requires the original product and implementation context.

Project Intelligence also uses a bounded read-only learner outside Task writer
and persistent-session authority. Its receipt-owned operation still carries the
exact Task/PR lineage it learns from.
Optional Upstream Sync uses the existing Task Agent plus deterministic program
Git operations; it does not introduce another agent role.

## Overall principles

### 1. Agents own meaning; the program owns facts

Agents decide what the user means, what code should change, whether review
feedback is actionable, and how a failure should be fixed.

The program owns identities, revisions, heads, fingerprints, persistence,
leases, retries, scheduling, staleness, Git and GitHub effects, and completion.
It never infers semantic workflow state from agent prose.

### 2. Tool calls are commands, final prose is evidence

An agent makes a durable semantic decision through a small tool call such as
`start_task(goal)`, `spawn_agent(role)`, or `ready_for_review()`. Tool arguments
are validated while the agent is still running, so an invalid call can be
corrected immediately.

An agent's final response is ordinary text. The runtime stores it unchanged
under an `AgentResult` and never parses it for `APPROVED`, `CONFLICT`, JSON
fields, XML blocks, headings, or magic words.

### 3. Communication is persisted, never interrupt-driven

Every delegated run receives immutable input references. The runtime stores
the child's result and produced-file manifest before it resumes the consumer.
Agents do not inject messages into another running agent.

If a deterministic external event arrives while an agent is active, the
program records it and queues the appropriate next turn. It does not interrupt
the active turn or ask an agent to relay the event to another agent.

### 4. One Task worktree, one writer

A Task owns one branch and one worktree. The Task Agent and CI Fixer may each
write it; optional deterministic Upstream Sync operations may also mutate it.
All three use the same exclusive fenced writer lease. The Task Agent is parked
while another mutation owner holds the lease. The adversarial reviewer never
writes.

An agent is not a workspace. Per-agent worktrees would create merge and context
transfer work that the product does not need.

### 5. External effects require exact authority

Agents cannot push, post GitHub replies, resolve GitHub threads, request
reviewers, mark ready, or merge. They only prepare local state.

The program executes an effect after an exact user authorization or a narrowly
scoped standing consent. The authorization freezes the relevant head,
revisions, action digest, and policy revision. A changed subject makes it
stale.

The current executable subset implements this boundary for manual
`INITIAL_PUBLISH`, manual local `CI_UPDATE`, and one fixed-local-user, one-shot
Task consent lasting at most 24 hours. INITIAL uses two ordered proven steps,
atomic final/partial settlement, and currently only `KEEP_DRAFT`. Consent is
considered only while a stopped-ready transaction opens a new
all-`PASSED` exact gate revision; it never scans an existing gate. Each
authorization atomically creates its exact immutable plan and runtime
`PUBLISH` operation/ticket, installing the barrier: CI update has one push step
and INITIAL has two ordered create-ref/create-draft-PR steps. Claim
locks the PR and admits the exact stored graph at the oldest nonterminal
sequence; begin performs current-owner freshness revalidation but makes no Git
or provider call. The concrete GitHub executor commits an attempt before its
exact-lease push and uses immutable probes/receipts for settlement.
An applied receipt atomically installs one receipt-owned read-only CI watch.
The bounded GitHub poller proves two identical exhaustive check-suite/run
passes for the exact proposed head and feeds one private source-bound batch into
the existing CI round/red-fixer loop. Green and collecting only rearm the watch.
Configurable/multi-use consent, consent UI, webhooks, generic observation
routing, test-merge/legacy-status readiness, downstream ready/merge green
consumers, and timeline UI/details remain deferred. The sole implemented green
consumer is the optional isolated receipt-owned learner described below. The
implemented PR timeline is one read-only, schema-free projection of twelve
immutable greenfield owner facts; an event-count/version cursor forces a full
restart after any late insert.

### 6. Record a fact once

Each durable fact is stored in the component that owns it. The PR timeline is a
stable projection of those facts, not a second workflow ledger. Agent activity
and raw tool output live in execution records and do not flood the durable PR
timeline.

### 7. GitHub proves remote outcomes

A successful local command or agent claim does not prove a remote result.
Remote head, CI, comments, thread state, readiness, and merge completion are
accepted only from a fresh GitHub observation or an idempotent effect probe.

## Minimal lifecycle

The Task lifecycle is deliberately small:

```text
CREATED -> ACTIVE
ACTIVE <-> WAITING_USER
ACTIVE -> NEEDS_ATTENTION -> ACTIVE
CREATED | ACTIVE | WAITING_USER | NEEDS_ATTENTION -> CANCELED
ACTIVE | WAITING_USER | NEEDS_ATTENTION -> COMPLETED when program-proven
```

Planning, development, review, CI repair, and feedback repair are kinds of
`AgentRun` or pending work, not Task stages. A Task state describes who can make
progress now, not a scripted semantic pipeline. Remote/CI/gate waiting is
projected from its owner records while the Task remains `ACTIVE`.

## End-to-end flow

This is the normative product flow. The current production subset composes a
programmatic `TaskCommands` entry, ordinary INITIAL Task/reviewer work, manual
KEEP_DRAFT initial publication, CI update publication/observation, and the
CI-autofix agent portions described above. A production-composition acceptance
trace proves INITIAL publication, exact-head RED repair/review, manual CI update,
GREEN observation, and candidate learning without consulting legacy state.
Trunk/controller admission, Task questions, local-comment commands, ready
effects, feedback, merge, frontend exposure, and cutover remain deferred.

1. A background Project Intelligence job learns source-cited project knowledge.
2. The Trunk Agent clarifies the request and may read that knowledge.
3. The Trunk Agent calls `start_task(goal)`.
4. The Flow Runtime durably creates the Task and one provisioning
   operation/ticket. Only after isolated branch/worktree provisioning succeeds
   does that operation create the persistent Task Agent session, persist the
   initial work cause, and ensure reconciliation. The selector creates the first
   run; a best-effort wake reduces latency and ticket polling is correctness.
5. The Task Agent reads the repository, plans internally, edits, tests, commits,
   and saves the PR title/body draft.
6. The Task Agent calls `spawn_agent(role="adversarial_reviewer")` for the exact
   committed head.
7. The runtime parks the Task Agent, runs a fresh read-only reviewer, stores its
   opaque result, records a result-ready fact, and lets the one Task selector
   resume the Task Agent with that reference.
8. The Task Agent fixes actionable findings and repeats review as needed, then
   calls `ready_for_review()`.
9. The program opens the exact initial-publish gate. The user can add local
   comments, return them to the same Task Agent, or approve the exact candidate.
10. After approval, the program pushes the exact branch head and opens the
    GitHub PR. The same PR record gains remote identity; local activity stays
    private. The approval also freezes `KEEP_DRAFT` or
    `MARK_READY_ON_EXACT_GREEN`.
11. The GitHub observer records CI and review events without waking a monitor
    agent.
12. Final red CI is program-dispatched through the Task selector to the
    persistent CI Fixer, with no Task-Agent relay. It may edit, test, and commit
    under the sole writer lease, but cannot push.
13. The Task Agent inspects and adversarially reviews a CI fix. The program
    pushes after an exact user gate or Task-scoped CI-push standing consent.
14. Remote reviewer feedback is persisted and selected into the next eligible
    Task-Agent turn with frozen item revisions. It fixes code and stores
    reply/resolution drafts locally.
15. The user approves the exact feedback batch. The program pushes, confirms
    the remote head and accepted CI, refetches the threads, posts replies, then
    resolves still-current eligible threads.
16. The loops repeat without concurrent writers until the exact remote head is
    merge-ready.
17. When the user selected `MARK_READY_ON_EXACT_GREEN`, the program marks only
    the freshly observed exact green head ready. `KEEP_DRAFT` requires a later
    explicit user action. The user then authorizes merge, or explicit standing
    auto-merge consent applies. A fresh GitHub observation must still prove
    every objective condition.
18. GitHub-confirmed merge completes the Task. Sessions and worktree are
    released; durable facts and the timeline remain.

Optional Upstream Sync has a separate structured entry: the user requests a
durably dispatched range preview, confirms its exact digest, and only then does
the program create a Task based on the resolved target commit. That Task joins
the upstream component's deterministic clean-pick loop first; its persistent
Task Agent stays idle until conflict or final semantic review. It then joins the
common checks/review/initial-gate path and uses the same GitHub, CI, feedback,
and merge contracts.

## Cross-component contracts

| Producer | Consumer | Durable contract | Rule |
|---|---|---|---|
| Project Intelligence | Trunk/Task Agent | `KnowledgeRecordRef` plus statement, provenance and source digest | Guidance only; never a program verdict or hidden gate. |
| Trunk Agent | Flow Runtime | `start_task(goal)` tool call | `goal` is self-contained. Program binds the current repository and retains the Trunk transcript only as audit provenance. |
| Trunk/UI | Upstream Sync | `request_upstream_sync_preview(...)` | Optional structured refs create only a durable preview operation; no Task or Git work runs in the handler. |
| Upstream Sync | Flow Runtime / User Gate | user-confirmed preview plus `UpstreamVerification` | Confirmation creates one Task at the resolved base; exact final verification is mandatory evidence for its initial gate. |
| Flow Runtime | Task Agent | immutable `TaskLaunch` | Contains exact goal plus program-owned repository, worktree, base/head, and policy facts. Task reads repository instructions and queries Project Intelligence itself. |
| Agent | Flow Runtime | tool calls plus opaque final response | Only tool calls create domain commands. Final prose is stored, not decoded. |
| Task Agent | Adversarial Reviewer | `spawn_agent` command bound by the runtime | Program supplies exact head/diff/check evidence; parent does not compose a hidden protocol payload. |
| Adversarial Reviewer | Task Agent | `AgentResultRef` | Result is persisted before parent resume. Program proves completion, not semantic approval. |
| GitHub Observer | Flow Runtime | immutable `RemoteObservation` revisions | Provider identity and head bind every event; delivery is deduplicated. |
| Flow Runtime | CI Fixer | immutable `CiFixLaunch` | One finalized CI round, exact head, bounded logs and candidate lessons. |
| CI Autofix | User Gates / GitHub Executor | `RequiredCiPolicyRevision` plus exact-head `AcceptedCiEvidence` | Requiredness is program-owned and revisioned; callers never supply or infer it. |
| Flow Runtime | Task Agent | immutable `FeedbackLaunch` | Full current item/thread revisions and exact remote/local heads. |
| Task Agent | User Gate | locally stored PR draft, feedback drafts, check evidence and `ready_for_review()` | Program builds the gate from owned records; no aggregate agent JSON. |
| User Gate | GitHub Effect Executor | immutable `Authorization`, ordered effect payload, and one runtime operation/ticket | Authorization atomically queues execution; GitHub owns attempts, probes, and receipts tied to that runtime operation. |
| Every owner | PR Timeline | owner record and timestamp | Projector creates a deterministic event ID; no agent timeline tool. |

## Data ownership

| Fact | Sole owner |
|---|---|
| Task lifecycle, worktree and writer lease | Flow Runtime |
| Agent session, run, launch, result and produced-file manifest | Flow Runtime |
| Source-cited repository knowledge | Project Intelligence |
| Confirmed launch choice | Immutable Task goal; later choices live in Task conversation or User Gate records |
| PR draft revision and local/remote identity | PR Timeline component's PR aggregate |
| Private local-review threads and revisions | User Gates |
| GitHub feedback item revisions and reply/resolution drafts | Remote Feedback |
| Local check policy, run and captured output | Flow Runtime local-check runner |
| Required remote-CI policy, CI observation, fix attempt and lesson | CI Autofix |
| Optional upstream request, range, construction and verification | Upstream Sync |
| User gate, authorization and standing consent | User Gates |
| Remote PR snapshot and GitHub effect plan, attempt, probe and receipt | GitHub integration |
| User-facing chronological view | PR Timeline projection; it owns no duplicate workflow fact |

No component may update another component's owned record directly. A
cross-component change is a synchronous command to the owner or a durable
result/event consumed by the owner.

## User authority

| Effect | Default | Optional standing consent |
|---|---|---|
| Initial push and PR creation | Explicit exact user gate, including `KEEP_DRAFT` or `MARK_READY_ON_EXACT_GREEN` | None |
| CI-only repair push | Explicit exact user gate | Task-scoped CI-push consent, default off |
| Remote feedback push/replies/resolutions | Explicit exact user gate | Never |
| Mark ready | Explicit user action, or the ready policy frozen at initial publish and carried only through authorized CI updates | No independent standing consent |
| Merge | Explicit exact user gate | Task-scoped auto-merge consent, default off |

Standing consent is authority to create a fresh exact authorization after all
facts are re-proved. It is not a reusable authorization for an old head.

## Simultaneous CI and feedback

GitHub events are persisted independently, but worktree mutation is serialized.

1. If a writer is active, both events wait.
2. After any exact in-progress continuation or recovery finishes, a finalized
   red-CI round gets the next writer before unrelated feedback so the branch
   becomes a valid base for later work.
3. The Task Agent then receives every still-current feedback revision against
   the latest local head.
4. A new revision of a thread included in an open gate makes that gate stale.
5. A new unrelated thread becomes the next batch and blocks merge, but does not
   interrupt the active batch.

No event or queued job is lost merely because its original remote head became
old. The owner carries still-open feedback forward by identity and revision,
then lets the Task Agent judge its applicability to the new code.

## Failure and recovery contract

- An objective-effect command commits its owner transition, operation, and
  dispatch ticket together or commits none. Competing Task-writer causes commit
  as pending owner facts and ensure one reconciliation ticket; the selector
  creates at most one eligible writer under the Task lock.
- A worker claims a ticket with a lease and fencing token. A stale worker cannot
  mutate the worktree or accept a result.
- Lease expiry never proves an old process stopped. The program quarantines the
  Task, terminates and proves the full old runner/process group dead, revokes its
  tools, and only then inspects state or admits a successor.
- A terminal upstream history command that intentionally leaves sealed
  sequencer/dirty state reserves the exact successor operation. The old lease
  is released, but no unrelated writer may acquire before that successor
  succeeds, restores cleanly, or quarantines the Task.
- The normative deferred `request_user_input` design uses the same safety idea:
  a future Task-question owner would store a question plus sealed
  worktree/sequencer state and admit no writer until an exact answer-bound
  successor resumes it. No current manifest exposes that tool.
- The runtime stores raw execution evidence before releasing a writer lease.
- A child result is immutable. A retry creates another run linked to the failed
  run; it never overwrites history.
- External effects are idempotent operations with preflight, attempt, probe and
  receipt records. Recovery probes before retrying.
- An uncertain external outcome remains pending/needs-attention; it is never
  guessed from an agent response.
- Dirty or unprovable worktree state quarantines that Task from new writers
  until the program restores or the user resolves it.
- Cleanup failure cannot change a merged Task back to active; it creates
  retryable cleanup work.

## First-principles challenge

The final design was challenged by asking what irreducible fact each proposed
component adds.

| Question | Decision |
|---|---|
| Does planning need an independent agent or durable stage? | No. It adds no authority or isolation boundary. The Task Agent plans internally and asks the user only for material choices. |
| Is development itself a subagent? | No. The Task Agent is the work owner. Making development a child would preserve a parent that contributes no work. |
| Does remote feedback need a specialist agent? | No. It needs the original user intent and implementation history, so the persistent Task Agent handles it. |
| Does CI observation need an agent? | No. A finalized check and its log references are objective provider facts. The program dispatches the CI Fixer directly. |
| Does CI repair justify a persistent specialist? | Yes. Logs are noisy, rounds repeat, and confirmed lessons are useful across waits. This benefit exceeds the context-transfer cost. |
| Does adversarial review justify a fresh child? | Yes. Independence and read-only isolation are the purpose of the role. |
| Should every agent get a worktree? | No. Only the Task owns the change. One worktree plus one writer lease removes merge races. |
| Should the program parse a reviewer verdict? | No. Models cannot reliably satisfy a workflow parser through ordinary prose. The Task Agent judges prose; the program checks exact identity and completion. |
| Should the timeline be another append-only truth store? | No. Duplicating facts creates divergence. Retained owner records already provide durable history; the projector supplies the view. |
| Should every local change require user approval? | No. Local edits are reversible. Gates exist at external publication and merge boundaries. |
| Can remote feedback be posted before its fix is remotely proven? | No by default. Push, remote-head confirmation and required exact-head CI precede reply/resolution. |
| Is a generic programmable workflow engine needed? | No. The product needs a small Task lifecycle, durable runs, leases, four gate kinds and event-driven loops. |

### Accepted tradeoffs

- Serial writers are slower than parallel writers, but remove stale-patch and
  merge-conflict classes of failure.
- Exact-revision gates may require the user to approve again after a small
  change, but an approval otherwise would not describe what gets published.
- A persistent Task Agent can accumulate context; bounded launch references and
  durable artifacts keep resumptions focused.
- Waiting for accepted CI before feedback replies delays conversation, but
  avoids publicly claiming a stale or red fix.
- Projecting the timeline requires stable owner retention and projector tests,
  but avoids dual writes and JSON reconstruction.
- The program can prove that review completed on head H, not that it was
  semantically correct. Manual gates, Task Agent judgment, tests and remote CI
  are intentionally separate protections.

## Required system acceptance traces

An implementation is not complete until these traces pass end to end:

1. **Normal delivery:** start Task -> commit -> checks -> adversarial review ->
   user local review -> initial publish -> green CI -> merge -> cleanup.
2. **Local review loop:** a user comment stales the publish gate; the same Task
   Agent fixes it; a new exact-head gate opens.
3. **Later-gate local review:** a local comment added to a CI-update or
   code-changing feedback gate stales that exact gate; no push occurs until the
   same Task Agent addresses it and a new exact gate is authorized.
4. **Opaque reviewer result:** free-form reviewer prose is stored and shown;
   no parser or normalization run is invoked.
5. **Child crash:** a pre-result process crash reuses the same run only after
   old-process proof; a terminal failed run remains readable and any deliberate
   semantic retry is a linked new operation/run. The parent resumes only from a
   stored terminal result.
6. **Concurrent CI and feedback:** both observations persist; only one writer
   runs; still-open feedback reaches the latest head.
7. **Stale approval:** a head, draft or thread revision changes after approval;
   the executor performs no stale effect and the UI shows a new gate.
8. **Partial remote effect:** push succeeds and the process dies before reply;
   recovery proves the push, does not repeat it, and resumes at the reply.
9. **CI lesson:** a candidate lesson can guide a fix but never auto-applies;
   an exact nonempty receipt-sourced remote GREEN may reserve one isolated
   read-only learner. A durable terminal save seal is a prerequisite; only the
   exact stopped finalizer or STOPPED recovery creates a `CANDIDATE`, and only
   while the bound GREEN remains current. It never completes the Task or
   authorizes ready/merge.
10. **External head change:** a manual GitHub push invalidates pending local
   authority and no old-head push/reply/merge proceeds.
11. **Terminal truth:** an agent says work is complete while GitHub is not
    merged; the Task remains active. A later observed merge completes it.
12. **Privacy:** local reviewer results, local comments and agent activity never
    appear in GitHub PR content or comments.
13. **No legacy dependency:** new-flow tests start with no old-flow tables,
    services, statuses or adapters available.

## Design references

Patterns adopted from Codex:

- dedicated read-only review against a selected diff/commit;
- narrow child roles and warnings about parallel writers;
- runtime-owned spawn, status, result delivery and typed approval events;
- separate live activity from durable product state.

Sources: [Codex code review](https://learn.chatgpt.com/docs/code-review),
[Codex subagents](https://learn.chatgpt.com/docs/agent-configuration/subagents),
[Codex App Server](https://learn.chatgpt.com/docs/app-server), and the pinned
[spawn implementation](https://github.com/openai/codex/blob/3aae5d885bac39c1262491aa3fd100dfd8b3919f/codex-rs/core/src/tools/handlers/multi_agents/spawn.rs#L124-L224).

Patterns adopted from Grok Build:

- independent/resumable child contexts and bounded capabilities;
- background work represented as observable tasks;
- explicit user approval UI;
- avoiding subagents when shared context or user interaction dominates.

Sources: [Grok subagents](https://github.com/xai-org/grok-build/blob/8a14c91d88875a831a38b3a066b1683116bcb31c/crates/codegen/xai-grok-pager/docs/user-guide/16-subagents.md#L106-L184),
[background tasks](https://github.com/xai-org/grok-build/blob/8a14c91d88875a831a38b3a066b1683116bcb31c/crates/codegen/xai-grok-pager/docs/user-guide/20-background-tasks.md#L7-L37),
and [Plan approval](https://github.com/xai-org/grok-build/blob/8a14c91d88875a831a38b3a066b1683116bcb31c/crates/codegen/xai-grok-pager/docs/user-guide/19-plan-mode.md#L65-L99).

Explicitly rejected: Grok's example of piping model output through `jq` and
searching for `OK`. That is useful for a disposable shell script, not a durable
workflow contract. [Headless example](https://github.com/xai-org/grok-build/blob/8a14c91d88875a831a38b3a066b1683116bcb31c/crates/codegen/xai-grok-pager/docs/user-guide/14-headless-mode.md#L411-L425)
