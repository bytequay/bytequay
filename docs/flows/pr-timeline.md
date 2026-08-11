# Unified PR Timeline

Status: implemented bounded greenfield projection.

The timeline is a read-only presentation of facts already retained by their
greenfield owners. It is not a workflow engine, event ledger, callback, or
authority source. The current implementation has no timeline table, writer,
cache, signal, registration interface, legacy read, or detail API.

Read this with the [overall architecture](./README.md),
[workflow runtime](./workflow-runtime.md), [user gates](./user-gates.md),
[GitHub integration](./github-integration.md), and
[CI autofix](./ci-autofix.md).

## Program API

```text
PrTimelineProjection.page(prId, afterCursor?, limit) -> TimelinePage

TimelineCursor {
  prId, schemaVersion, eventCount,
  recordedAt, typeRank, eventId
}

TimelineEvent {
  eventId, recordedAt, occurredAt?, typeRank,
  source, kind, actor, status, headSha?, ownerRef
}
```

`limit` is between 1 and 100. The projection executes one SQLite `SELECT`
statement: a PR-scoped CTE, twelve explicit owner queries combined with
`UNION ALL`, an always-present event-count row, and a `limit + 1` page. One
statement supplies the consistent read snapshot; the projector performs no
write before, during, or after it.

The cursor is bound to the PR, projection schema version, total event count,
and last ordered tuple. If any fact was inserted since the cursor was issued,
if the projection version changed, or if the last tuple is not retained, the
result is `RESTART_REQUIRED` with no events. The caller restarts from a null
cursor. This makes same-timestamp and late inserts truthful without a global
event sequence or copied timeline row.

Events sort in binary ascending order by
`(recordedAt, typeRank, eventId)`. `recordedAt` is the local owner timestamp
that controls paging. A provider time may appear only as optional
`occurredAt`; it never moves a late provider fact behind an issued cursor.

## Implemented event sources

Only these immutable, user-meaningful owner facts are events:

| Rank | Kind | Owner row | Stable event ID |
|---:|---|---|---|
| 10 | `TASK_LIFECYCLE` | `TaskLifecycleRevision` | `task-lifecycle:<id>:1` |
| 20 | `TASK_BASE_REVISION` | `TaskBaseRevision` | `task-base:<id>:1` |
| 30 | `CHANGE_SET_REVISION` | `ChangeSetRevision` | `change-set:<id>:1` |
| 40 | `PR_MATERIALIZED` | local PR | `pr:<prId>:1` |
| 50 | `REMOTE_IDENTITY_BOUND` | set-once remote identity | `remote-identity:<id>:1` |
| 60 | `LOCAL_CHECK_COMPLETED` | `LocalCheckRun` | `check:<id>:1` |
| 70 | `AGENT_RESULT_STORED` | `AgentResult` plus bounded run role | `agent-result:<id>:1` |
| 80 | `CI_CONSENT_REVISION` | one-shot consent revision | `ci-consent:<consentId>:<revision>` |
| 90 | `GATE_TRANSITION` | exact gate transition/revision/subject | `gate:<gateId>:<sequence>` |
| 100 | `EXTERNAL_EFFECT_RECEIPT` | applied GitHub receipt | `effect-receipt:<id>:1` |
| 110 | `CI_CHECK_OBSERVED` | normalized CI check observation | `ci-check:<id>:1` |
| 120 | `CI_LESSON_CANDIDATE` | retained candidate lesson | `ci-lesson:<id>:1` |

The fixed ranks are tie-breakers, not causal or workflow priority.

A Task-base event intentionally has no `headSha`: its base is not a candidate
head. A local-check event uses its immutable observed-start head, even if an
unavailable or mutating process ended elsewhere. A gate event joins its exact
`(gateId, gateRevision)` subject and never reads the gate's current revision.
Manual authorization is attributed to the local user; automatic authorization
is the separate bounded `CI_UPDATE_CONSENT` actor.

## Redaction and ownership

The DTO contains only bounded enums, owner IDs/revisions, exact applicable
heads, and timestamps. It contains no goal, worktree path, repository/check
display name, reason prose, evidence body/ref, prompt, capability manifest,
agent input/result/error prose, check output, raw CI evidence, idempotency key,
lesson title, or lesson markdown. `OwnerRef` is a typed navigation key only;
owner-detail APIs remain deferred.

Mutable or operational rows are intentionally not events: current pointers,
CI rounds and repair attempts, operations, inbox entries, tickets, leases,
sessions, process attempts, effect plans/probes/attempts, gate subjects and
authorizations, log evidence, lesson requests, and learning completions. Their
meaningful immutable outcomes are represented by the twelve rows above. The
projector never reconstructs missing history from their current state.

## Retention, replay, and isolation

- Repeating a page or constructing a new projector after restart yields the
  same event IDs and order while the owner set is unchanged.
- A changed event count invalidates an old cursor before returning any events.
- Every branch begins from the exact requested greenfield PR/Task owner. Facts
  belonging to another PR cannot enter the result.
- Task terminalization, session closure, and worktree deletion do not affect
  projection because all event rows are retained database facts.
- Unknown PRs, invalid limits, malformed/forged cursors, unknown bounded enum
  values, and inconsistent owner joins fail closed.

## Deferred scope

Draft revisions, local comment threads, remote feedback, ready/merge/close
observations, upstream-sync projection, live agent activity, owner details,
summary folds, controllers, UI, refresh signals, webhooks, caches, and any
legacy timeline or PR lifecycle integration remain deferred until their exact
greenfield owners and product surfaces exist.
