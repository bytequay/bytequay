# Unified PR Timeline

Status: normative greenfield replacement specification.

This document defines the one PR identity and one durable timeline shown from
the first local reviewable change through GitHub merge or closure. It is
self-contained for implementation and does not depend on an older timeline
table, event writer, reconciler, or PR lifecycle service.

Read this with the [overall architecture](./README.md),
[workflow runtime](./workflow-runtime.md), and [user gates](./user-gates.md).

## 1. Purpose

The timeline gives the user one trustworthy answer to:

> What happened to this Task's proposed change, what is waiting now, and what
> evidence or user decision produced the current state?

The same application PR begins locally and later gains a GitHub identity. Local
review history remains private and visible above remote observations. No second
PR row or second screen replaces it at publication.

## 2. Non-goals

- The timeline is not a workflow engine or a source of transition truth.
- There is no generic `record_timeline_event` API.
- There is no second append-only copy of facts already stored by Tasks, checks,
  agent runs, review threads, gates, CI observations, or external effects.
- Live token streaming, shell output, file reads, and routine tool activity do
  not belong in the durable PR timeline.
- Local findings, agent messages, local review comments, and local check logs are
  never published to GitHub.
- The projector does not parse agent prose to invent a finding count, verdict,
  status, or summary.
- No migration, backfill, dual read, or compatibility projection is required.

## 3. Core invariants

1. One Task has zero or one application `PullRequest`; after materialization it
   keeps the same `pr_id` for its lifetime.
2. The PR is materialized only when a clean committed diff against the Task's
   current proven `TaskBaseRevision` exists. An empty Task does not get a fake
   PR.
3. Publication adds a one-to-one remote identity to that PR. It does not copy,
   replace, or re-parent local history.
4. Every durable fact is stored exactly once in its authoritative component's
   immutable fact/revision/receipt row.
5. `PrTimelineProjection` reads those owner rows and returns deterministic event
   DTOs. It writes nothing and cannot advance a Task.
6. Current mutable-looking state is a fold over immutable revisions or a pointer
   to the latest revision. Historical fact rows remain after sessions and
   worktrees are cleaned up.
7. Program-generated labels describe objective facts only: “review completed on
   H,” not “review approved H.”
8. A GitHub fact is accepted only with stable remote identity and revision. The
   same webhook/poll result cannot create a second visible event.
9. Timeline visibility does not grant publication authority. Only an exact
   [user gate](./user-gates.md) can authorize an outbound effect.

## 4. Stable PR identity

### `PullRequest`

| Field | Meaning |
|---|---|
| `pr_id` | Stable local application identity. |
| `task_id` | Unique, non-null Task owner. |
| `repository_id` | Repository identity. |
| `base_ref` / `base_sha` | Current proven Task base at PR materialization; retained as the PR's original comparison base. |
| `branch_name` | The Task's one branch. |
| `created_from_head_sha` | First reviewable committed head. |
| `remote_identity_id` | Nullable set-once reference to `RemoteIdentity`. |
| `current_draft_revision_id` | Pointer to current immutable title/body revision. |
| `created_at` | Local materialization time. |

Constraints:

- `UNIQUE(task_id)`;
- `UNIQUE(remote_identity_id)` when non-null;
- `remote_identity_id` may change only from null to one value;
- deleting/archiving a Task must not cascade-delete the PR or owner facts used by
  its timeline.

### `RemoteIdentity`

An immutable set-once record:

`{remote_identity_id, provider, repository_external_id, pr_number,
pr_node_id, html_url, publication_receipt_id, bound_at}`.

The remote identity is stored once here. The `PullRequest` merely points to it,
so “the same PR row gains remote identity” does not duplicate remote data.

### `PrDraftRevision`

`{draft_revision_id, pr_id, revision, title, body, author, created_at}` with
`UNIQUE(pr_id, revision)`. Publication freezes one revision. V1 does not edit PR
metadata after remote identity exists; later provider-side metadata is remote
display state, not another local draft revision or a hidden publication path.

## 5. Authoritative fact owners

The new components must retain the following immutable records. A component may
maintain a latest-state pointer or cache, but the timeline reads the retained
fact/revision/receipt.

| Fact | Authoritative owner row | Minimum projection fields |
|---|---|---|
| Task created/completed/canceled | `TaskLifecycleRevision` | Task, status, actor, recorded time. |
| Task comparison-base change | `TaskBaseRevision` | Task, previous/current base, reason, evidence, recorded time. |
| Reviewable code head/commit | `ChangeSetRevision` | PR, head/base, commit metadata, recorded time. |
| Local validation | `LocalCheckRun` | PR, observed start/end head, command label, status, log ref, recorded time. |
| Agent turn boundary | Agent run/result | PR, role, exact head, terminal state, opaque result ref, recorded time. |
| Adversarial review | Reviewer run/result | PR, reviewed head/diff digest, result ref, recorded time. |
| Local user review | [User Gates](./user-gates.md) local review item revision | PR, thread, anchor, author, body/state revision, recorded time. |
| User decision | Gate revision/transition | PR, kind, exact subject digest, state, user/policy authority, recorded time. |
| Publication, push, reply, resolve, ready, merge | External effect attempt/receipt | PR, action key, exact subject, outcome, remote ref, recorded time. |
| CI state | CI observation revision | PR, remote head, suite/check identity, conclusion, log ref, recorded time. |
| GitHub review/comment/thread | [Feedback](./remote-feedback.md) remote feedback revision | PR, provider identity, remote revision, author/body/state/anchor, recorded time. |
| GitHub PR status/head | Remote PR observation revision | PR, remote head/base, draft/open/merged state, merge ref, recorded time. |
| Optional upstream range and final proof | `UpstreamRangeConfirmation` / `UpstreamVerification` | Task, resolved target base, range digest, exact verified head, recorded time. |

The table above is a storage contract. Do not create an additional
`pr_timeline_event` row when one of these rows is written.

## 6. Projection DTO

`PrTimelineProjection.page(prId, after, limit)` returns:

```text
TimelineEvent {
  eventId          // deterministic: <owner-kind>:<owner-id>:<revision>
  prId
  recordedAt       // when ByteQuay durably learned the fact; ordering time
  occurredAt       // optional provider time, display metadata only
  source           // LOCAL | GITHUB
  kind             // bounded presentation kind
  actor             // USER | TASK_AGENT | ADVERSARIAL_REVIEWER |
                    // CI_FIXER | SYSTEM | github login
  headSha?          // exact code subject when applicable
  threadId?         // stable local or remote thread identity
  summaryKey        // program-owned UI label, not model-generated prose
  detailRef?        // typed link to the authoritative owner row
  status?           // objective owner status
  cursor            // encoded (recordedAt, typeRank, eventId)
}
```

The summary is intentionally small. The detail drawer follows `detailRef` to
show the check log, full opaque agent result, diff, gate subject, comment body,
or remote receipt from its owner. Do not copy those bodies into the event DTO's
storage.

### Ordering

Sort by `(recorded_at, type_rank, event_id)`. `type_rank` is a fixed tie-breaker,
not a workflow priority. A late webhook appears when ByteQuay learned it; its
provider `occurred_at` is displayed inside the event. This preserves stable
cursor pagination without introducing a global event sequence or silently
inserting rows behind an already-consumed cursor.

### Event IDs and revisions

- Immutable one-shot row: `check:<check_run_id>:1`.
- Revisioned remote comment: `remote-feedback:<item_id>:<revision>`.
- Gate transition: `gate:<gate_id>:<transition_sequence>`.
- Effect receipt: `effect:<effect_key>:success`.

The same owner row always produces the same event ID. Re-running the query,
restarting the app, or receiving the same webhook cannot duplicate it.

## 7. Presentation kinds

The projector uses a bounded mapping, not a free-form event taxonomy supplied by
agents.

| Kind | Owner | Example objective label |
|---|---|---|
| `PR_READY_LOCAL` | PullRequest | “Local review opened at H1.” |
| `DRAFT_REVISED` | PrDraftRevision | “Title and description updated.” |
| `CHANGESET_READY` | Change-set revision | “Task Agent committed H2.” |
| `CHECK_COMPLETED` | Check run | “Local checks passed on H2.” |
| `AGENT_TURN_COMPLETED` | Agent result | “CI Fixer turn completed.” |
| `ADVERSARIAL_REVIEW_COMPLETED` | Reviewer result | “Adversarial review completed on H2.” |
| `LOCAL_COMMENT` / `LOCAL_REPLY` | Local review revision | User/agent body is loaded from the owner. |
| `LOCAL_THREAD_RESOLVED` / `DISMISSED` / `REOPENED` | Local review revision | Objective thread-state change. |
| `GATE_OPENED` / `AUTHORIZED` / `STALE` / `CONSUMED` | Gate transition | Includes gate kind and exact subject link. |
| `PR_PUBLISHED` | Publication receipt | “GitHub PR #42 opened from H2.” |
| `PR_MARKED_READY` | Ready effect receipt | “Draft PR marked ready after exact-head green.” |
| `HEAD_PUSHED` | Push receipt | “H3 pushed to GitHub.” |
| `CI_UPDATED` | CI observation | Check/suite and conclusion. |
| `REMOTE_REVIEW` / `REMOTE_COMMENT` | Remote feedback revision | Provider author/body is loaded from the owner. |
| `REMOTE_REPLY_POSTED` / `REMOTE_THREAD_RESOLVED` | Effect receipt | Proven outbound result. |
| `MERGE_AUTHORIZED` / `PR_MERGED` / `PR_CLOSED` | Gate/remote observation | Exact remote head and merge ref. |
| `TASK_COMPLETED` | Task lifecycle revision | Proven terminal Task state. |
| `UPSTREAM_RANGE_CONFIRMED` / `UPSTREAM_VERIFIED` | Upstream confirmation/verification | Exact range digest, target base, and verified candidate head. |

Do not project every tool call. A user who needs operational detail follows the
agent-run link to the separate activity surface.

## 8. Program APIs

| Method | Behavior |
|---|---|
| `PrRecords.materialize(taskId, expectedHead)` | In one transaction verify a clean committed diff, insert the one PR if absent, and bind `Task.pr_id`. Repeated calls return the same `pr_id`. |
| `PrRecords.saveDraft(prId, expectedRevision, title, body, actor)` | Before remote identity exists, append an immutable draft revision and advance the latest pointer; reject after publication. |
| `PrRecords.bindRemoteIdentity(prId, remoteIdentity, publicationReceiptId)` | Set the one remote identity only after the remote PR is proven. Same identity is idempotent; any different identity fails closed. |
| `PrTimelineProjection.page(prId, afterCursor, limit)` | Read owner rows, map them to event DTOs, merge-sort, and return a stable cursor. No writes. |
| `PrTimelineProjection.detail(detailRef)` | Authorize access and return the typed authoritative record. |
| `PrTimelineProjection.summary(prId)` | Derive current local/remote label, current head, gate attention, CI, and terminal status from owner records. |
| `LiveActivityProjection.page(runId, cursor)` | Return model/tool/process activity separately from PR history. |
| `TimelineRefresh.signal(prId)` | Optional best-effort UI invalidation after owner commits. Lost signals are harmless because queries read durable rows. |

There is deliberately no registration framework for arbitrary event producers.
The first implementation should be one explicit SQL/query module over the
bounded owner tables above. Add a cache or materialized read table only after a
measured query problem; such a cache must be disposable and rebuildable.

## 9. Lifecycle

### Local era

1. Task starts without a PR.
2. Task Agent produces a clean committed diff at head `H1`.
3. `PrRecords.materialize` creates `P1`; validation, reviewer, and local review
   records reference `P1`.
4. The user reviews the diff and timeline. Local comments and replies remain in
   their authoritative local review rows.
5. The initial publication gate freezes `P1`, exact head, draft revision, checks,
   reviewer run, and local thread revisions.

### Publication membrane

1. The user authorizes the exact initial gate.
2. The executor pushes commits and creates the GitHub PR.
3. Only code plus the frozen title/body cross the membrane.
4. After the provider effect is proven, `RemoteIdentity R1` is created and `P1`
   points to it.
5. The next timeline query contains both the existing local facts and
   `PR_PUBLISHED`; no record is moved.

The initial gate also freezes `readyPolicy = KEEP_DRAFT` or
`MARK_READY_ON_EXACT_GREEN`. The latter permits a later program-owned exact-head
ready authorization only after a fresh GitHub observation proves the published
head green with no pending blocker. It may carry to a later head only when that
head was produced by an authorized `CI_UPDATE`; it never follows an external or
`REMOTE_FEEDBACK` head. The effect receipt projects `PR_MARKED_READY`; no fifth
gate is created.

### Remote era

1. GitHub observer writes deduplicated CI, review, comment, head, and PR-status
   revisions referring to `P1` through `R1`.
2. CI repairs and feedback preparation create local change/check/review facts on
   `P1`.
3. User-gated pushes, replies, resolutions, ready changes, and merge create
   receipts on `P1`.
4. A proven GitHub merge/close revision ends remote activity. Task sessions and
   the worktree may be removed, while `P1` and all owner facts remain queryable.

## 10. Review threads

A thread is one identity with immutable revisions, rendered in three views:

- **Diff:** spatial anchor and discussion.
- **Timeline:** chronological history.
- **Gate:** exact unresolved/replied/resolution state included in a publication
  decision.

Local and GitHub thread identities are different namespaces. Local anchors are
never translated to GitHub positions because local comments never cross the
publication membrane. GitHub comments retain provider node/revision IDs.

Resolving, dismissing, reopening, editing, or receiving a provider update creates
a new owner revision. It does not update an older timeline fact and does not
write a second timeline event row.

## 11. Concurrency, idempotency, and recovery

### Concurrency

- Owner writes commit before optional refresh signals.
- The projector reads under a consistent database snapshot per page.
- It may show a newly committed fact on the next refresh; workflow correctness
  never waits for UI projection.
- Same-timestamp facts remain ordered by the stable type rank and event ID.

### Idempotency

- One PR per Task by database constraint.
- One remote identity per PR and one PR per provider repository/number.
- One owner fact per component-specific idempotency key.
- GitHub facts by provider node/key plus revision.
- Projection event ID deterministically derived from that fact.

### Recovery

- Crash after owner commit but before refresh: query reveals the fact after
  restart; nothing is repaired or copied.
- Crash during publication before remote binding: the effect executor probes
  GitHub, records the receipt, and then binds the discovered identity.
- Orphan remote PR observed before binding: match only by the publication
  operation's stable marker; ambiguous matches require attention. Never create a
  second application PR.
- Archived Task: retain PR, drafts, checks, results, revisions, gates,
  observations, and receipts needed by timeline details. Large logs may use
  content-addressed storage with retained references.
- Projection bug: fix and redeploy the pure mapping. No data migration is needed
  because owner facts remain authoritative.

## 12. Acceptance traces

### A. Local materialization is idempotent

1. Call `materialize` concurrently twice for Task `T1`, head `H1`.
2. Observe one `PullRequest P1` and `T1.pr_id = P1`.
3. Timeline begins with one `PR_READY_LOCAL` event.

### B. One timeline survives publication

1. Create local checks, reviewer result, and user thread for `P1`.
2. Publish and bind GitHub PR `#42`.
3. Assert the same `P1` returns all local events followed by publication and
   remote events.
4. Assert no local comment or reviewer body appears in the GitHub requests.

### C. Facts are not dual-written

1. Store one check run.
2. Query the timeline repeatedly and restart the app.
3. Observe one deterministic `CHECK_COMPLETED` ID and no timeline storage row.
4. Delete a disposable projection cache, if one exists; the event still renders.

### D. Remote redelivery and revision

1. Ingest the same GitHub comment revision ten times; observe one event.
2. Ingest an edited provider revision; observe one additional event for the same
   thread.
3. Verify the first body remains historically available.

### E. Late provider event and cursor

1. Read through cursor `C`.
2. Ingest an older provider event now.
3. Query after `C`; the newly recorded event appears once with its old
   `occurredAt` metadata and new `recordedAt` ordering position.

### F. Cleanup does not erase history

1. Complete and archive a Task, close sessions, and remove the worktree.
2. Reopen `P1`.
3. All timeline events and detail links still render without filesystem access.

## 13. First-principles challenge

| Question | Decision | Why | Trade-off |
|---|---|---|---|
| Why have a PR before GitHub? | Materialize only when a reviewable diff exists. | The user must review the exact branch before the first irreversible push. | A brand-new Task has no PR page until it has code. |
| Why not an append-only timeline table? | Project immutable owner facts directly. | Copying facts creates two truths, dual-write failures, and format drift. | Projection query is wider; optimize only if measured. |
| Will cleanup destroy projections? | No; retain the small owner facts and external log references. | Durability belongs to records, not a duplicate presentation log. | Retention policy must preserve referenced evidence. |
| Why not include every agent action? | Keep live activity separate. | Tool chatter hides user decisions, code heads, checks, and external facts. | Deep debugging takes one click into the run. |
| Why order by recorded time? | It is the only locally provable append boundary. | Provider timestamps can arrive late and break stable cursors. | A late remote fact appears at observation time while showing its original time. |
| Why one local and remote PR identity? | Publication adds a binding to the same aggregate. | Splitting requires migration/reparenting and breaks review continuity. | Queries handle a nullable remote identity during the local era. |
| Should the projector summarize agent findings? | No. Link the opaque result. | Semantic summarization would reintroduce model-output parsing and disagreement. | Timeline labels are less clever but remain true. |

## 14. Evidence and adopted/rejected ideas

- **Accepted from Codex:** its review surface is grounded in the actual selected
  Git diff and keeps review findings read-only until a human/agent acts. The new
  timeline likewise links to exact diffs and stored results rather than treating
  prose as state.
  [Official Codex code-review documentation](https://learn.chatgpt.com/docs/code-review)
- **Accepted from Codex/Grok:** active child work and completion are visible to a
  parent/user. **Merged:** ByteQuay separates verbose live activity from durable
  PR history and retains only the objective run boundary there.
  [Official Codex subagent documentation](https://learn.chatgpt.com/docs/agent-configuration/subagents),
  [Grok Build subagents](https://github.com/xai-org/grok-build/blob/8a14c91d88875a831a38b3a066b1683116bcb31c/crates/codegen/xai-grok-pager/docs/user-guide/16-subagents.md)
- **Accepted product idea from earlier ByteQuay research:** one page and one
  identity should span local and GitHub review, and local review activity is a
  private membrane.
  [Local PR research](../mockups/local-pr-design.md),
  [PR identity research](../mockups/pr-record-unification-design.md)
- **Rejected from that earlier storage proposal:** a separate append-only
  timeline table. The greenfield design retains authoritative owner facts, so a
  duplicate event log is unnecessary and risks the same cross-format bugs this
  replacement is meant to remove.

## 15. Definition of done

- One Task can produce at most one stable `pr_id`.
- Binding a GitHub identity does not change `pr_id` or lose local facts.
- All presentation events have deterministic IDs and typed detail links.
- No production write path calls a timeline-recording method.
- No timeline state is required to dispatch work or authorize an effect.
- Local/private content is absent from publication and GitHub effect payloads.
- Timeline and detail views work after all agent sessions and the worktree are
  removed.
- The implementation reads no old timeline or PR lifecycle storage.
