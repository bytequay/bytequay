# Project Intelligence

Status: normative greenfield replacement specification
Parent architecture: [Development-flow architecture](./README.md)

## 1. Purpose

Project Intelligence is a repository-scoped, source-cited memory of project rules,
architecture, conventions, and vocabulary. A background learner refreshes it from
repository documentation and code. The [Trunk Agent](./trunk-agent.md) and
[Task Agent](./task-agent.md) query it when they need project context.

Its boundary is deliberately narrow:

- it reports what the repository appears to say, with exact sources;
- an agent, not the program, explains whether that context matters to a request;
- it never approves, rejects, starts, pauses, or completes work;
- it is useful even when no Task exists.

This is a new component. It has no compatibility reader, legacy adapter, dual write,
or data import. At cutover, only the records and APIs defined here are used.

## 2. First-principles result

A useful project memory needs only three properties:

1. **Retrievable:** an agent can find a relevant note using normal language and
   optional path hints.
2. **Verifiable:** every note points to repository content at a specific revision.
3. **Advisory:** only an agent and user can decide how a note applies to a new goal.

It does **not** need a workflow verdict, a project-wide ontology, a graph database,
or a program that tries to understand whether a request conflicts with a vision.
Those additions create semantic parsers and hidden policy without improving the
irreversible safety boundaries.

The first implementation should use the application's normal durable store and its
native full-text search. Add embeddings only after measured retrieval failures show
that full-text search plus path/topic filters are insufficient.

## 3. Non-goals

- Deciding whether a user's goal is aligned, conflicting, allowed, or forbidden.
- Acting as a Task-creation gate.
- Replacing repository instruction files or normal source inspection.
- Producing implementation plans.
- Writing to a Task worktree.
- Recording Task or PR timeline events.
- Supplying authorization for push, reply, resolution, merge, or any other external
  effect.
- Treating generated prose as executable policy.

## 4. Owned durable data

Project Intelligence owns only the following new records. Names are logical contract
names; an implementation may map them directly to tables.

### `ProjectScan`

| Field | Meaning |
|---|---|
| `scanId` | Program-generated stable identifier |
| `repositoryId` | Repository being learned |
| `sourceHead` | Commit inspected by this scan |
| `createdAt` | Program schedule time |
| `startedAt`, `completedAt` | Program timestamps |
| `status` | `QUEUED`, `RUNNING`, `RETRYABLE`, `COMPLETED`, `FAILED`, or `CANCELED` |
| `claimOwner`, `claimExpiresAt` | Nullable expiring worker claim; not semantic ownership |
| `claimToken` | Monotonic fencing token issued on every claim; all learner writes must match the current live token |
| `attempt` | Monotonic transport/run attempt count |
| `changedPaths` | Program-computed source scope |
| `errorRef` | Program-owned diagnostic reference when the scan fails |

`UNIQUE(repositoryId, sourceHead)` coalesces repeated triggers. `ProjectScan` is
the small background queue itself; Project Intelligence does not create a
second generic workflow operation/ticket for repository learning.

### `ProjectKnowledge`

| Field | Meaning |
|---|---|
| `knowledgeId` | Program-generated stable identifier |
| `repositoryId` | Owning repository |
| `note` | Agent-authored natural-language knowledge; never parsed as a command |
| `topic` | Short search aid such as `persistence`, `testing`, or `release` |
| `scopePaths` | Optional repository paths to which the note applies |
| `sourceRefs` | One or more exact `path@commit` references, with line spans when available |
| `scanId` | Scan that recorded the note |
| `producerRunId` | Exact learner attempt that called the record tool |
| `state` | `PENDING`, `CURRENT`, `SUPERSEDED`, or `ABANDONED` |
| `supersedesId` | Earlier record replaced by this one, when known |
| `recordedAt` | Program timestamp |

No agent supplies identifiers, timestamps, repository identity, scan status, or
source content hashes. The program owns those facts.

Normal agent-tool execution records already capture queries for debugging; do not add
a second Project Intelligence query-log table.

## 5. Exact inputs

### Scheduled refresh input

The program starts a refresh with:

- `repositoryId`;
- repository read snapshot at `sourceHead`;
- paths changed since the last completed scan, or the bounded initial source set;
- repository instruction/document discovery results;
- the IDs and source references of current knowledge in the affected paths.

The learner is not given an active Task, user conversation, or desired answer. This
keeps repository learning independent from the goal that happens to be current.

### Query input

Consumers call:

```text
search_project_context(query, path_hints?)
```

The program binds the current repository and caller identity. `query` is natural
language. `path_hints` is optional and can only narrow/rank results; it cannot hide
the source citation or freshness information.

The result contains `knowledgeId`, `note`, `topic`, `scopePaths`, `sourceRefs`, and
whether each cited source still belongs to the repository's queried revision. The
consumer receives records, not a computed alignment result.

## 6. Lifecycle: start to stop

1. **Schedule.** `ProjectIntelligenceScheduler.schedule(repositoryId)` creates a
   `ProjectScan` after a repository head change or the configured periodic interval.
   Repeated triggers for the same repository/head coalesce into one queued scan.
2. **Snapshot.** `ProjectSourceReader.open(repositoryId, sourceHead)` exposes an
   immutable, read-only repository view.
3. **Claim.** `ProjectScanQueue.claim(workerId, now)` compare-and-sets one
   eligible scan from `QUEUED` or `RETRYABLE` to `RUNNING` with an expiring claim
   and new monotonic token. Only that token may start one bounded read-only
   learner session or call a write/completion tool.
4. **Inspect.** The learner reads relevant instructions, documentation, tests, and
   code. It may search existing knowledge to avoid restating an unchanged note.
5. **Record during the turn.** For each useful note, the learner calls
   `record_project_knowledge(note, source_refs, topic?, scope_paths?, supersedes_id?)`.
   The tool validates source existence immediately and either stores a `PENDING`
   record or returns a tool error the still-running learner can correct.
6. **Finish.** The runtime automatically persists the learner's final prose as an
   opaque `AgentResult`, atomically promotes only that successful learner run's
   pending notes, applies their supersession links, marks the scan complete, and
   closes the session. Pending notes from a proven failed attempt become
   `ABANDONED`, never promotable. Final prose is never parsed to discover notes or
   status.
7. **Query.** Trunk or Task Agents call `search_project_context`. They may follow a
   citation with their normal read tools before relying on it.
8. **Refresh.** A later scan writes a new record and supersedes an old one when the
   source meaning changed. History remains available for audit, while normal search
   returns current records.
9. **Stop.** Repository removal cancels queued/running scans and disables new queries.
   Stored records follow the repository-retention policy; a Task does not own them.

## 7. Agent tools and program APIs

### Learner tool surface

| Tool | Contract |
|---|---|
| `list_project_sources(path?, depth?)` | Lists paths from the immutable scan snapshot |
| `read_project_source(path, start_line?, end_line?)` | Reads a source with revision and line metadata |
| `search_project_sources(query, path_hints?)` | Searches the immutable source snapshot |
| `search_existing_knowledge(query, path_hints?)` | Finds current knowledge records for deduplication |
| `record_project_knowledge(...)` | Validates and durably stores one cited natural-language note |

The learner has no filesystem-write, shell-write, Task, gate, GitHub, publication,
subagent, or timeline tool.
The runtime binds `{scanId, runId, claimToken}` below the visible tool schema.
Every source read may continue harmlessly after expiry, but every record,
renewal, failure, and completion call compare-and-sets the current live token;
an expired/replaced learner cannot write.

### Consumer tool surface

| Tool | Users |
|---|---|
| `search_project_context(query, path_hints?)` | Trunk Agent and Task Agent |

### Program APIs

| API | Responsibility |
|---|---|
| `ProjectIntelligenceScheduler.schedule(repositoryId)` | Coalesce and persist refresh work |
| `ProjectScanQueue.claim(workerId, now)` | Atomically claim one `QUEUED`/`RETRYABLE` scan, issuing a new monotonic token and incrementing attempt; an expired `RUNNING` claim is first fenced/reclassified by compare-and-set, so the old token immediately loses write authority |
| `ProjectScanQueue.renew(scanId, workerId, claimToken)` | Extend only the same current unexpired token |
| `ProjectIntelligenceRuntime.startScan(scanId, claimOwner, claimToken)` | Verify the live token and run one bounded learner |
| `ProjectKnowledgeStore.record(scanId, runId, claimToken, command)` | Compare-and-set the live token, generate IDs, validate cited sources, and commit one note |
| `ProjectIntelligenceRuntime.completeScan(scanId, runId, claimToken, resultRef)` | Compare-and-set the live token, atomically promote only this run's valid pending notes/apply supersession, complete the scan, and close the learner |
| `ProjectIntelligenceRuntime.failAttempt(scanId, runId, claimToken, diagnosticRef)` | Only the current token may mark its pending notes `ABANDONED` and set the scan `RETRYABLE` or terminal `FAILED` by bounded policy |
| `ProjectKnowledgeSearch.search(repositoryId, query, pathHints)` | Return current, cited results with freshness |

## 8. Contracts with other components

### [Trunk Agent](./trunk-agent.md)

- Trunk may query context before discussing project alignment with the user.
- Trunk explains alignment, tension, or uncertainty in ordinary conversation.
- Project Intelligence never returns or stores that judgement.
- `start_task(goal)` carries the confirmed goal, not a hidden Project Intelligence
  decision. The Task Agent may query Project Intelligence independently.

### [Task Agent](./task-agent.md)

- Task may query context during implementation and may inspect cited repository files.
- A knowledge note is guidance, not permission and not proof that code is correct.
- When a source is stale or ambiguous, Task reads current source and asks the user if
  the choice materially changes the confirmed goal.

### [Workflow Runtime](./workflow-runtime.md)

- Runtime schedules and observes scans, validates objective source references, and
  persists opaque agent completion text.
- Runtime does not rank goals against knowledge or infer workflow transitions from a
  note.

### [PR Timeline](./pr-timeline.md) and [User Gates](./user-gates.md)

- Scan and query activity is operational telemetry, not PR timeline content.
- Project Intelligence never opens, closes, satisfies, or invalidates a user gate.
- A user-approved exception belongs in the confirmed Task goal or decision history;
  it does not mutate repository knowledge.

## 9. Invariants

1. Every current knowledge note has at least one source reference that existed at the
   scan's immutable `sourceHead`.
2. An agent cannot choose `repositoryId`, `scanId`, timestamps, or record IDs.
3. No Project Intelligence result has lifecycle or authorization semantics.
4. Search returns only `CURRENT` notes from completed scans; a failed partial scan can
   never leak into consumer context.
5. Final agent prose is opaque and cannot create knowledge records.
6. A malformed `record_project_knowledge` call fails during the live turn; it cannot
   become a malformed record after completion.
7. Queries always expose citations and freshness.
8. Scans are read-only with respect to repository content and Task worktrees.
9. One failed scan cannot block Task creation, development, review, publication, or
   merge.
10. A stale `claimToken` cannot create/promote/abandon knowledge or complete/fail
    the current scan, even if its learner process is still alive.

## 10. Failure and recovery

| Failure | Required behavior |
|---|---|
| Program stops after queueing | Durable scan remains queued and is claimed once |
| Program stops with an expired running claim | Compare-and-set the old token out and mark the same scan `RETRYABLE`; a new claim gets a higher token. Best-effort terminate the old read-only learner, but fencing—not death detection—protects durable writes |
| Learner crashes | The current token persists failed opaque result/diagnostic and marks the same scan `RETRYABLE`. Mark it terminal `FAILED` only when bounded retry policy is exhausted; uniqueness forbids a shadow same-head scan |
| Tool call omits/uses invalid source | Reject the call immediately; learner corrects it while active |
| Source head disappears | Fail the scan; never silently learn from a different head |
| Duplicate schedule trigger | Return/coalesce to the existing repository/head scan |
| Knowledge contradicts current source | Consumer sees source revision; next scan supersedes it; consumer reads current source meanwhile |
| Search is unavailable | Trunk/Task disclose that repository memory is unavailable and inspect repository sources directly |
| Crash after note commit but before scan completion | Notes remain `PENDING` and invisible while recovery proves whether that exact run completed. It either completes that run once or marks its notes `ABANDONED` before retry; a later run cannot promote them |

## 11. Acceptance traces

### A. Request aligns with a documented direction

1. Background learner records “PostgreSQL is the approved persistence store,” citing
   the exact design file revision.
2. Trunk queries `search_project_context("replace persistence store")`.
3. The tool returns the note and citation—no verdict.
4. Trunk explains the tension and asks the user whether replacement is intended.
5. The final confirmed choice is written into `start_task(goal)`.
6. Task independently checks current project context and source before editing.

### B. Learner emits an invalid shape

1. Learner calls `record_project_knowledge` without a valid source reference.
2. Tool validation rejects it immediately.
3. Learner reads the source and retries correctly.
4. Whatever the learner says at turn completion is stored as opaque prose and cannot
   substitute for the missing tool call.

### C. Intelligence is stale

1. Query returns a useful note whose citation is older than the Task base.
2. Result is visibly marked stale.
3. Task reads the current cited path and makes its own judgement.
4. Development continues; the next scheduled scan refreshes the note.

### D. Scan crashes during active development

1. Worker claims the unique repository/head scan and the learner crashes.
2. Recovery compare-and-sets its claim to `RETRYABLE`; a new worker claims the
   same scan with a higher token.
3. Let the old learner remain alive and attempt `record` and `complete`; both
   stale-token calls fail. The new learner alone may publish knowledge.
4. No second repository/head scan exists, and no Task or gate changes state;
   Task can continue with direct repository reads.

## 12. Evidence: accept, merge, reject

| Source | Decision | Reason |
|---|---|---|
| [Codex repository instructions](https://developers.openai.com/codex/guides/agents-md/) | **Accept** source-controlled, repository-scoped instructions as primary evidence | The agent can verify current instructions in the exact checkout rather than trusting a detached summary |
| [Codex subagents](https://learn.chatgpt.com/docs/agent-configuration/subagents) | **Merge** narrow tool surfaces and isolated context into the background learner | The learner needs read/search/record capabilities, not Task or publication authority |
| [Grok subagents](https://github.com/xai-org/grok-build/blob/8a14c91d88875a831a38b3a066b1683116bcb31c/crates/codegen/xai-grok-pager/docs/user-guide/16-subagents.md) | **Accept** the warning not to delegate when context-transfer cost exceeds benefit | Project learning is independent and reusable, so it is suitable background work; request interpretation stays with Trunk/Task |
| Model-produced alignment/verdict objects | **Reject** | They recreate a fragile semantic parser and incorrectly make advisory memory a program gate |
| Custom knowledge graph/vector platform at launch | **Reject** | Native full-text retrieval and exact citations satisfy the initial contract with much less state and failure surface |

## 13. Tradeoffs

- Source citations cost storage and scanning time, but make generated knowledge
  inspectable and correctable.
- Advisory results can leave genuine ambiguity for Trunk and the user; that is safer
  than programmatically enforcing a model's interpretation.
- Full-text search may miss synonymous language. Add embeddings only after retrieval
  evaluation demonstrates the need.
- Independent Task queries repeat some work performed by Trunk, but prevent a hidden
  context handoff from becoming an unreviewed requirement.
