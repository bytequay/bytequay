# GitHub observation and effects

Status: **normative greenfield replacement specification**

This component is the only path between a Task and GitHub. It observes remote
facts, normalizes them into immutable revisions, routes new work, and executes
already-authorized effects. It contains no agent and makes no semantic judgment.

Read the system contract in [README.md](./README.md), especially the rules that
GitHub proves remote outcomes and that external effects require exact authority.
CI behavior is specified in [ci-autofix.md](./ci-autofix.md), review feedback in
[remote-feedback.md](./remote-feedback.md), gates in
[user-gates.md](./user-gates.md), and user-facing history in
[pr-timeline.md](./pr-timeline.md).

## Replacement boundary

Implement this as a new vertical slice. It must not call, wrap, translate,
dual-write, or share lifecycle state with an old GitHub/PR workflow service.
Existing low-level HTTP authentication and Git command adapters may be reused.
Old stage names, verdict fields, marker-line parsing, and compatibility branches
must not enter the new model.

## Responsibility

The component owns five things:

1. proving one GitHub PR locator and asking the PR owner to bind it to the
   existing Local PR;
2. obtaining current provider state by webhook and reconciliation reads;
3. deduplicating and routing CI, review, lifecycle, and head changes;
4. executing a frozen effect plan only after exact authorization; and
5. proving the outcome by reading GitHub again.

It does **not** decide what code to write, whether a comment is correct, whether
review prose passed, or whether the user should approve an effect.

## Hard invariants

- One ByteQuay `prId` survives local drafting and remote publication. Opening a
  GitHub PR adds the PR owner's set-once remote identity; this component never
  creates a second product PR or identity record.
- A provider tuple `(installation, repository, pullNumber)` maps to at most one
  `prId`.
- Scheduled/on-demand reconciliation is the correctness path for the local
  desktop app. An optional webhook/connector signal may accelerate it, but the
  signal is never treated as complete provider state.
- If webhook delivery is enabled later, it is acknowledged only after its
  immutable envelope and a reconciliation ticket are committed together.
- Webhook and poll triggers feed the same idempotent refetch/normalizer.
- Every remote fact is bound to the provider repository and, where applicable,
  the exact remote head SHA.
- Agents have no direct GitHub write tool. Push, reply, resolve, ready-state,
  and merge are program effects.
- A successful HTTP/Git command is an attempt, not proof. A fresh provider read
  or an idempotent probe produces the receipt.
- A timeout has an unknown outcome. Recovery probes before retrying.
- Gate authorization, the frozen effect plan, the runtime `Operation`, its
  `DispatchTicket` are committed atomically. The executor revalidates
  exact authority and provider preconditions after the runtime claims the work.
- GitHub payload text is untrusted display data. It is never treated as an agent
  instruction.

The currently implemented `CI_UPDATE` boundary accepts an exact authenticated
manual decision for the current OPEN revision and atomically creates one
immutable plan, exactly one ordinal-1 `PUSH_EXACT` step, and the linked runtime
`PUBLISH` operation/ticket. Claim admits only the oldest nonterminal
`prSequence`; typed begin revalidates freshness and records `EXECUTING`. This
component now executes that one step through an exact GitHub-only transport.
It commits a distinct immutable attempt before each possible mutation call
(maximum two), pushes the
literal proposed commit to the literal full ref with an exact-old-SHA lease,
and accepts only a later exact remote probe as proof. Stable pre-attempt target
failure can stale the gate; any uncertainty after an attempt keeps the gate and
publication barrier in probe-only attention. Never-started expiry redrives the
same operation, while activated expiry can only schedule another probe.
An `APPLIED` receipt also atomically creates the one receipt-owned
`OBSERVE_CI` operation/ticket for the new head and cancels older watches for
that PR. The implemented observer is scheduled polling only: it reads the
exact open PR before and after two identical, exhaustively paged check-suite
and `filter=all` check-run passes for the proposed head. It accepts only
explicit `GITHUB_CHECK:<appId>:<exact check name>` policy selectors and stores
one source-bound round in the existing CI owner. Webhooks remain deferred.

## Logical data model

These are logical records, not a prescribed ORM or migration scheme.

### PR-owned `RemoteIdentity`

The authoritative record is owned by the PR aggregate in
[pr-timeline.md](./pr-timeline.md), not by this component. The effect executor
requires the set-once program-observed GitHub identity bound there. The current
record freezes both base and head repository locators, so forks do not inherit
or guess the base repository target:

| Field | Meaning |
|---|---|
| `provider` | `GITHUB` in this implementation |
| base `repositoryExternalId`, canonical owner/name, `pullNumber` | Unique base repository/PR locator |
| head `repositoryExternalId`, canonical owner/name | Exact repository that owns the authorized branch ref |
| `prNodeId` | Stable GitHub node identity |
| `htmlUrl` | Display link |
| `publicationReceiptId` | Proven effect that opened/found this PR |

The locator comes from a program-owned provider observation, never caller text
or worktree remote naming. Execution requires exactly one credential-free
GitHub push URL whose canonical owner/name matches the frozen head locator; zero,
multiple, fetch-only, credential-bearing, rewritten, or non-GitHub targets fail
closed. It stores no shadow identity.

### `RemotePullRequestSnapshot`

An immutable normalized provider observation:

```text
snapshotId, prId, observedAt, providerUpdatedAt,
headSha, baseSha, lifecycle, draft, mergeable,
reviewDecision, rawEvidenceRef
```

`lifecycle` is an objective provider state such as `OPEN`, `MERGED`, or
`CLOSED`. Readiness policy is not stored here.

### Optional `ProviderDelivery`

```text
deliveryId, installationId, repositoryId, eventType, action,
receivedAt, signatureVerified, payloadDigest, rawEvidenceRef,
processingState
```

`deliveryId` is the GitHub `X-GitHub-Delivery` value. Its uniqueness constraint
makes redelivery harmless. The first local-desktop implementation does not need
a public webhook endpoint or this record; scheduled reconciliation remains
mandatory even when a connector is later installed.

### `ExternalEffectPlan`

```text
planId, operationId, prId, prSequence, authorizationId, kind,
expectedRemoteHead, expectedBaseRef?, expectedBaseSha?,
actionDigest, readyPolicy?, automationPolicyRevision,
requiredCiPolicyRevisionId?, createdAt
```

Kinds are intentionally finite: `INITIAL_PUBLISH`, `CI_UPDATE`,
`REMOTE_FEEDBACK`, `MARK_READY`, and `MERGE`. Only kinds exposed by
[user-gates.md](./user-gates.md), including the program-derived ready
transition, may be created.

`prSequence` is strictly increasing and never reused within one `prId`. Plan
creation locks that `prId`, assigns the next value, and commits the plan with its
authorization, runtime `Operation`, and `DispatchTicket`. A unique
`(prId, prSequence)` constraint rejects concurrent duplicate allocation.

The enclosing `Operation`, claim state, retry time, fence, and unique
`DispatchTicket` belong to [workflow-runtime.md](./workflow-runtime.md). This
implemented component owns only the immutable one-step `CI_UPDATE` plan tied to
that `operationId`, its bounded attempts and remote probes, and its exact
receipt. Other effect kinds remain deferred.

### Effect steps and evidence

Each plan contains ordered, immutable `ExternalEffectStep` records. The current
`CI_UPDATE` plan has exactly one canonical ordinal-1 `PUSH_EXACT` step binding
branch ref, expected remote head, proposed head, `force=false`, action digest,
and precondition digest. A step generally has a stable idempotency key, frozen
payload reference, and precondition digest.
`ExternalEffectAttempt` records authority for one possible provider mutation
call before that call can start, `ExternalEffectProbe`
records read-after-unknown checks, and `ExternalEffectReceipt` records the
provider object/current value plus the fresh observation that proved success.
Every record carries `operationId` and `stepId`; none duplicates runtime claim
or operation lifecycle state. Probe order is monotonic by claim generation and
probe number, never wall-clock/hash order. `APPLIED` means the exact proposed
SHA, `ABSENT` means the exact expected SHA, a missing ref or any third SHA is
`DIVERGED`, and unavailable proof is `UNKNOWN`. CI update mutation is bounded
to two exact-identical attempts with a fixed five-second retry delay; once an
attempt exists, expiry and local uncertainty grant probe authority only.

For a reply API without a native idempotency key, the frozen payload includes a
non-rendered ByteQuay effect marker covered by the action digest. Recovery probes
for that marker before retrying. If provider policy cannot preserve such a
marker and a timeout outcome is ambiguous, the step becomes `NEEDS_ATTENTION`;
it never risks a duplicate by guessing from body similarity alone.

Provider-specific raw CI and review payloads may be retained as evidence, but
the normalized owned records belong to [ci-autofix.md](./ci-autofix.md) and
[remote-feedback.md](./remote-feedback.md). This component calls those owners;
it does not create shadow copies.

## Program APIs

### Inbound observation

```text
GitHubObserver.acceptWebhook(headers, rawBody) -> AcceptedDelivery
GitHubObserver.reconcile(prId, reason) -> RemotePullRequestSnapshot
GitHubObserver.reconcileOpenPullRequests(repositoryId) -> ReconcileSummary
```

`reconcile` is required. `acceptWebhook` is an optional future adapter: it
verifies `X-Hub-Signature-256`, validates installation/repository scope, inserts
`ProviderDelivery`, inserts one reconciliation ticket, and returns quickly. The
worker refetches current provider state through `reconcile`; it does not trust a
partial payload as the domain fact.

### Identity and routing

```text
PrRecords.bindRemoteIdentity(prId, providerLocator, publicationReceiptId)
  -> RemoteIdentity
RemoteEventRouter.route(snapshotOrEvent) -> RouteSummary
```

Routing is mechanical:

| Observed fact | Owner command |
|---|---|
| Final check state or new failed-job evidence | `CiAutofix.observeCi(...)` |
| Review, comment, thread, edit, dismissal, or resolution | `RemoteFeedback.observe(...)` |
| Head/base/lifecycle change | `WorkflowRuntime.observeRemoteState(...)` |
| Any retained owner fact | Timeline projector reads it; no timeline write call |

The router persists owner commands before waking work. It never starts a CI
monitor or review-comments agent.

### Effects

```text
AuthorizedEffectPlans.create(authorizationId, operationId, actionManifest) -> effectPlanRef
GitHubCiUpdateExecutor.execute(runtimeClaim) -> optional exact receipt
```

`AuthorizedEffectPlans.create` is not a public second submission step. It is
called inside `GateCommands.authorize(...)` (or the corresponding exact
program-derived ready authorization) in the same transaction that persists the
authorization, runtime `Operation`, and unique `DispatchTicket`. A gate
can therefore never be authorized without executable work, and a plan can
never be submitted twice.

Inside that transaction, plan creation takes the per-`prId` ordering lock and
allocates `prSequence`. The runtime later takes the same lock before claim and
may claim only the lowest-sequence eligible nonterminal plan for that `prId`. If
an earlier nonterminal plan is not claimable yet, a later plan cannot overtake
it.

After commit, the program may best-effort nudge the in-process dispatcher.
Durable ticket polling is the recovery and correctness path; a wake is not
transactional state.

The runtime dispatcher alone claims the `Operation`/ticket and passes its fenced
claim to the executor. The executor accepts only the linked immutable
authorization from [user-gates.md](./user-gates.md); agent prose, Task status,
and a boolean `override` are not authority.

The concrete provider privately mints claim- and subject-bound observation or
local-failure proofs. GitHub Effects persists only actual remote observations;
User Gates consumes the sealed proof and atomically settles or retries the gate,
runtime operation, plan eligibility, and publication barrier. A raw caller
cannot supply an `APPLIED` enum/head pair or an arbitrary runtime result ref.

### Provider adapter

The current implementation uses one direct package-private `GitHubProvider`,
not a provider framework. Its executable surface is only:

```text
probe(exact authorized CI_UPDATE activation)
pushExactFastForward(exact durable attempt capability, prepared target)
```

Preparation proves both commit objects, expected-as-ancestor-of-proposed,
exact credential-free GitHub push-remote routing, safe Git configuration, and
credential availability before an attempt exists. Each pre/post probe and
pre-attempt preparation also performs a fresh authenticated, bounded,
no-proxy/no-redirect GitHub repository read. It accepts only a complete `200`
whose stable repository ID and canonical owner/name equal the frozen head
locator. For this owner, `repositoryExternalId` is the canonical decimal REST
repository database `id`, not a GraphQL node ID or `owner/name`. An
authenticated exact mismatch is invalid; `404`, authentication,
transport, malformed, oversized, timed-out reads, or a credential attested to
a different repository ID are unavailable. The
credential source must attest that the returned credential is restricted to
the requested stable repository ID; a broad classic PAT cannot satisfy this
executor boundary, and production credential-source wiring remains deferred.

The repository-identity read and Git ref update are sequential, not one
provider-atomic operation. The repository-scoped credential preserves target
authority across that interval, the exact lease protects the ref value, and
the post-call probe revalidates repository identity. The mutation transports the
literal proposed SHA to the literal full ref with
`--force-with-lease=<ref>:<expected>`. That lease is the atomic compare-and-set
transport, not force-push authority: the action remains `force=false`, ancestry
must prove a fast-forward, and there is no plain-force fallback. Repository
hooks, prompts, replace objects, URL rewrites, and local `http.*` or `push.*`
overrides are disabled or rejected; structured stdout is bounded and stderr is
never parsed. Local proof also disables lazy object fetching and rejects legacy
`info/grafts`, so neither a provider fetch nor a fabricated parent edge can
create fast-forward authority.
Tokens exist only in the child environment/in-memory prepared capability;
mutable token buffers are wiped after use. Derived Java strings cannot be
reliably wiped, so they are never persisted or logged. URLs, arguments, and
durable evidence contain no credentials.

Initial publication, feedback/reply, ready, merge, general observation,
webhook, timeline, and multi-provider adapters remain deferred.
Production secret-source and dispatcher wiring/cutover are also deferred; this
checkpoint supplies the concrete owner/executor boundary and deterministic
transport tests only. `cancelAttention` is intentionally absent: an `UNKNOWN`
attempt retains the oldest-plan barrier until an exact later probe settles it.

## Agent-facing read tools

Agents read the normalized local store, not the live GitHub API:

```text
read_remote_pr()
read_feedback_item(content_revision_id)
read_ci_log(log_ref, query?, before?, after?)
```

The runtime resolves Task scope, requires the exact immutable feedback content
revision (stable item identity is only for listing/history), bounds output, and records the evidence read.
There is no `push`, `post_reply`, `resolve_thread`, or `merge` agent tool.

## Lifecycle

### 1. Initial publication

1. The user authorizes an `INITIAL_PUBLISH` gate for exact local head `H1`,
   exact PR draft revision `D1`, and one visible ready policy:
   `KEEP_DRAFT` or `MARK_READY_ON_EXACT_GREEN`.
2. `GateCommands.authorize(...)` atomically creates the authorization, frozen
   `ExternalEffectPlan`, runtime `Operation`, and `DispatchTicket`.
3. The runtime claims the operation; the executor rechecks the linked
   authorization, local head, and resolves the frozen base ref to the frozen
   `expectedBaseSha` under that claim.
4. It pushes exactly `H1` with a lease that refuses unexpected remote history.
5. Immediately before create, it resolves the base ref again. If it differs,
   retain the proven branch-push receipt, stale the gate, cancel the operation
   with a typed partial result, and create no PR; fresh base integration/review
   is required. Otherwise it opens a draft PR with exactly `D1` and the frozen
   base ref/expected SHA.
6. If the open call times out, it searches by repository/head branch before
   retrying; it must not create a duplicate PR.
7. A fresh PR read proves the number, head, and observed base SHA. The executor
   calls `PrRecords.bindRemoteIdentity` so the PR owner attaches the identity to
   the existing `prId`.
8. If the base moved in the unavoidable provider race between the final preflight
   and PR creation, bind that one proven identity to prevent duplicates, retain
   the push/create receipts, stale the gate, cancel the runtime operation with a
   typed partial-publication result, and set the Task `NEEDS_ATTENTION`. No ready,
   reply, or merge effect may follow. After the user chooses base reconciliation
   and the Task gets fresh checks/review/local approval, a new
   `INITIAL_PUBLISH` revision targets the already-bound draft PR; it never creates
   a second PR.
9. The observer starts webhook/poll reconciliation for that identity.

No local comments, agent transcripts, reviewer findings, tool logs, or Project
Intelligence records are sent to GitHub.

### Partial initial-publication recovery

A partial remote publication does not let the old authorization follow a new
head or base. After the Task reconciles the intended base and repeats exact-head
checks, adversarial review, Local PR review, and PR-draft review, the user must
manually authorize a fresh `INITIAL_PUBLISH` revision. That gate freezes the
retained receipt references, remote branch, exact partial remote head, current
expected base ref/SHA, and the bound `RemoteIdentity`/draft observation when one
exists.

The recovery plan has exactly two variants:

1. **Branch only.** A prior receipt and fresh probe prove the Task branch exists
   at partial head `Hp`, while exhaustive repository/head-branch probing proves
   no PR and no `RemoteIdentity` exists. The executor uses `FAST_FORWARD` from
   exactly `Hp` to the newly reviewed head `Hn`; it never retries `CREATE_REF`.
   It rechecks the frozen base SHA, opens one draft PR, probes on uncertainty,
   and binds the one proven identity.
2. **Draft PR already created.** Prior receipts, the set-once `RemoteIdentity`,
   and a fresh PR read prove the one open draft PR, its head branch/head `Hp`,
   and its observed base ref/SHA. The executor fast-forwards from exactly `Hp`
   to `Hn`, refetches, and reuses that bound PR. It never calls
   `openDraftPullRequest`. The current remote title/body/base ref must match the
   newly frozen action manifest; otherwise the fresh gate is stale rather than
   silently mutating unreviewed metadata.

Both variants stop before mutation if the remote branch, identity, PR lifecycle,
head, or base differs from the fresh gate. They are recovery from proven facts,
not permission to force-push or select an ad hoc remote object.

### Ready transition

The initial-publish authorization freezes and establishes one narrow,
user-owned ready-policy revision for this Task PR.

- `KEEP_DRAFT` performs no automatic transition.
- `MARK_READY_ON_EXACT_GREEN` permits the program to create a fresh exact
  `MARK_READY` authorization only when a current GitHub observation proves that
  [CI Autofix](./ci-autofix.md) has accepted the current remote head under the
  exact current `RequiredCiPolicyRevision`, no blocking work is pending, and that head arrived through initial
  publication or a program-authorized `CI_UPDATE`.

At claim, the executor refetches the remote head and draft state and asks the CI
owner to revalidate the frozen required-CI policy/evidence, plus the ready-policy
revision. Any change makes the operation stale. No agent asks GitHub to mark ready, and an agent assertion or local check
can never satisfy this policy. The policy may carry across authorized CI-only
repair heads; it never follows an external, unauthorized, stale, or
feedback-driven head.

### 2. Head-bound CI observation loop

1. An `APPLIED` `CI_UPDATE` receipt atomically installs one read-only
   receipt-owned `OBSERVE_CI` operation/ticket. A newer receipt cancels older
   same-PR watches, including claimed reads; their late batches cannot commit.
2. Claim renews the read lease for the complete bounded poll. Begin binds the
   current receipt, open PR/head identity, and current required-CI policy.
3. The concrete GitHub reader authenticates the frozen base repository and PR,
   reads the exact open PR before and after, and requires two identical
   exhaustive suite/run enumerations. Aggregate request, page, record, byte,
   and wall-time caps fail closed without storing partial facts. `403`/`429`
   `Retry-After` and rate-reset headers set a bounded no-earlier retry; malformed
   or absent timing uses a one-hour fixed backoff.
   One poll admits at most 10 suites, 1,000 runs, and 50 HTTP requests; local
   budget exhaustion waits at least 15 minutes, bounding one watch to at most
   200 such requests per hour.
4. Required checks use exact case-sensitive app-ID/check-name selectors.
   Historical executions remain evidence, but only the unique latest execution
   determines the round. Bounded sanitized Actions logs are fetched only for
   selected unaccepted failures when the whole batch can be `FINAL_RED`.
5. One private provider-sealed batch is accepted in one transaction:
   source-bound observations/logs, the existing `CiRound`, an optional
   `FINAL_RED` inbox/reconciliation wake, and watch rearm commit together.
   Identical provider revisions across claims reuse the same facts and round.
   Collecting polls may rearm quickly; green, attention, queued, unsupported,
   and rate-limited outcomes use slower/provider-directed cadence.

This is exhaustive only for head-bound GitHub check runs on exact
`proposedHead`; it is not branch-protection or merge-readiness proof. Test-merge
checks, legacy commit statuses, webhooks, a generic event bus, timeline
projection, green learning/readiness, and cutover remain deferred.

### 3. CI update

An authorized CI-only update contains one push step. After pushing, the
executor confirms the remote head and its receipt-owned watch feeds the exact
head-bound provider batch into the existing CI round owner.
Details and standing-consent rules are in [ci-autofix.md](./ci-autofix.md).

### 4. Remote feedback batch

The ordered effect protocol is normative:

1. push the exact approved local head if it differs;
2. confirm GitHub exposes that exact head;
3. wait until required CI accepts that exact head;
4. refetch every referenced review item/thread revision;
5. stop if semantic content or thread eligibility changed; allow only the
   proven anchor-only re-observation caused by this plan's own exact authorized
   push, as defined by [Remote Feedback](./remote-feedback.md);
6. post approved replies, probing before retry;
7. refetch affected threads;
8. resolve only approved, unchanged, still-eligible threads;
9. store one receipt for every proven effect.

This component implements the ordering. [remote-feedback.md](./remote-feedback.md)
defines the batch and user-review contract.

### 5. Merge and finish

At claim, merge refetches the PR, expected head, review state, and unresolved
threads, then asks the CI owner to revalidate the frozen
`RequiredCiPolicyRevision` and observations. It merges only while authorization remains current.
Task completion occurs only after a later observation says `MERGED`; a merge API
response alone is not enough.

## Concurrency and ordering

- Observation can run concurrently with agents because it only appends facts
  and work intents.
- Runtime admission gives one `prId` one ordered external-effect queue.
- That order is the immutable plans' `prSequence` plus their runtime tickets;
  there is no second GitHub queue table or queue cursor. Under the per-`prId`
  lock, runtime claims only the oldest eligible nonterminal plan, and never runs
  two plans for the same PR concurrently.
- Effect steps for a PR never run concurrently.
- Worktree mutation is serialized separately by the sole writer lease in
  [workflow-runtime.md](./workflow-runtime.md).
- A new event never interrupts a running agent. It is queued and re-evaluated
  when that turn releases its lease.
- A red-CI intent and feedback intent can coexist. The runtime applies the
  ordering in [README.md](./README.md): current red CI first, then still-current
  feedback against the resulting local head.

## Staleness rules

When an unexpected frozen precondition changes, the gate becomes `STALE` and
the runtime operation becomes `CANCELED` with a typed stale/partial result. A
runtime `Operation` has no separate `STALE` state.

Unexpected changes include:

- local or remote head, except the exact precondition-head -> authorized-target
  transition proven from this plan's own push;
- PR draft/action digest;
- referenced semantic content or thread eligibility, except the narrowly proven
  own-push anchor-only observation above;
- authorization revision, or revocation of an automatic authorization with no
  prior committed exact `EFFECT_BEGIN` (a claimed ticket alone is not frozen
  authority; recovered post-begin execution is);
- PR lifecycle or repository identity; or
- required-check policy.

Already-proven effect receipts are never erased. Unexecuted steps stop. The
owner creates a new gate/batch if work remains.

## Recovery

- **Duplicate webhook:** the delivery insert conflicts; return the existing
  accepted result without re-routing.
- **Missed webhook:** scheduled reconciliation produces the missing immutable
  owner revisions.
- **Crash before effect call:** runtime claim expiry makes the same operation
  ticket reclaimable; the executor owns no independent claim.
- **Crash or timeout during effect:** mark outcome unknown and probe GitHub.
  Typed proof of success/absence/staleness goes to `GateAttention.applyProbe`;
  only proof of absence re-enables the same frozen operation. Explicit user
  attention cancellation terminally settles it so later PR effects cannot be
  blocked forever.
- **Push succeeded, PR open outcome uncertain with unchanged subject/base:**
  probe the existing branch/PR and resume the same operation when its original
  authorization is still exact.
- **Branch exists but a base/subject race staled the old gate:** retain its push
  receipt; after fresh integration/review and manual approval, use the
  branch-only partial recovery plan and `FAST_FORWARD` from the frozen partial
  head.
- **Draft PR exists after the base-creation race:** retain push/create receipts
  and the bound identity; after fresh integration/review and manual approval,
  fast-forward and reuse/probe that exact draft PR without another create call.
- **Reply succeeded, receipt missing:** search/refetch the target conversation
  using the operation's stable marker/digest before retrying.
- **Authorization goes stale while waiting for CI:** retain push receipt, stop
  all conversation effects, and return remaining work to a new exact gate.
- **Rate limit/provider outage:** keep durable retry state with provider delay;
  do not wake an agent to interpret transport failure.
- **Remote branch changed externally:** refuse force, reconcile, and mark the
  current authorization stale; persist a semantic-reconciliation cause for the
  Task Agent. Do not create an unowned generic attention state.

## Timeline projection

This component does not call `record_timeline_event`. The timeline projects:

- remote identity binding;
- normalized head/lifecycle observations;
- effect authorization references;
- effect attempt/receipt facts; and
- confirmed merge/close.

Raw webhook payloads, retries, and API calls remain execution evidence. See
[pr-timeline.md](./pr-timeline.md).

## Required acceptance traces

1. **Publish once:** retry after an open-PR timeout finds and binds the existing
   PR; only one remote PR and one local identity exist.
2. **Duplicate observation:** repeated reconciliation reads produce one owner
   revision/work intent; if the optional webhook adapter exists, redelivery of
   one GUID has the same result.
3. **Missed delivery:** reconciliation discovers a review comment and dispatches
   the same work as a webhook would.
4. **Stale push:** an unexpected remote head refuses the push and no PR
   conversation effect follows.
5. **Uncertain reply:** a timeout is probed; an already-posted reply is receipted
   without duplication.
6. **Feedback order:** replies and resolutions cannot execute before the pushed
   exact head has accepted required CI.
7. **Edited thread:** an item edited after approval makes remaining steps stale;
   no stale resolution is posted.
8. **Merge proof:** a successful merge call does not complete the Task until a
   provider observation reports `MERGED` on the authorized head.
9. **Privacy:** initial publication transmits branch commits and approved PR
   title/body only; local timeline content is absent remotely.
10. **Ready policy:** `KEEP_DRAFT` remains draft after green; the alternate
    policy marks ready only after a fresh observation proves accepted CI on the
    exact current authorized publication head with no pending blocker. An
    authorized CI-only repair may inherit the policy; an external or
    feedback-driven head cannot.
11. **Per-PR effect order:** concurrent authorizations receive unique increasing
    `prSequence` values; runtime cannot claim the later plan while the earlier
    nonterminal plan is blocked or executing, and no second queue exists.
12. **Branch-only partial:** base movement after branch push stales the old gate;
    fresh base integration/review and manual approval fast-forward the proven
    branch from its exact partial head, then create and bind exactly one PR.
13. **PR-created base race:** a PR created during a base race is bound once;
    fresh base integration/review and manual approval fast-forward its branch,
    probe/reuse the same open draft PR, and never issue another create call.

## First-principles challenge

| Question | Decision | Tradeoff |
|---|---|---|
| Is a GitHub monitor agent needed? | No. Provider events, IDs, heads, and conclusions are objective facts. Program observation is cheaper and reliable. | Semantic anomalies must be routed to Task/CI agents later. |
| Should the first local version expose a webhook endpoint? | No. Scheduled/on-demand reconciliation is sufficient and does not require public ingress. A future authenticated connector may add webhook signals without changing owner contracts. | Polling adds API traffic and some latency; use conditional/current-PR reads. |
| Should each domain copy GitHub payloads? | No. Keep one raw evidence envelope and send normalized commands to each owner. | Normalizer versioning needs tests. |
| Can an agent push after saying it is ready? | No. Readiness is semantic; publication is an irreversible effect requiring exact authority. | More program machinery, but no accidental publication. |
| Is an HTTP 2xx proof? | No. Timeouts and partial provider operations make read-after-write/probe necessary. | Extra API calls. |
| Should effects be a generic workflow language? | No. A finite ordered set of operations is sufficient and safer. | Adding a genuinely new effect requires code. |
| Is a second per-PR queue needed? | No. Immutable `prSequence` plus runtime `Operation`/`DispatchTicket` state is the queue. | Claim admission must lock and scan the oldest nonterminal plan. |
| May an old partial-publish authorization cover a reconciled head/base? | No. Receipts prove what already happened; fresh manual review authorizes what changes next. | Recovery needs another gate, but never duplicates a branch or PR. |
| Should every provider get an adapter now? | No. Implement GitHub directly and extract a forge boundary only when a second provider is approved. | Future extraction cost is accepted instead of speculative abstraction now. |

## Evidence and adopted/rejected ideas

- **Adopt Codex's typed runtime boundary:** Codex exposes agent lifecycle and
  tool events as program protocol objects rather than asking a host to parse a
  final chat answer. ByteQuay applies that principle to observations and
  effects. [Codex App Server](https://learn.chatgpt.com/docs/app-server)
- **Adopt Grok's host-owned background work:** Grok shows long-running commands
  and agents as addressable background tasks with completion events. ByteQuay
  similarly makes observation/effect work durable and inspectable, but the
  program—not a model—chooses deterministic routing.
  [Grok background tasks](https://github.com/xai-org/grok-build/blob/8a14c91d88875a831a38b3a066b1683116bcb31c/crates/codegen/xai-grok-pager/docs/user-guide/20-background-tasks.md)
- **Adopt GitHub's delivery identity guidance:** GitHub documents
  `X-GitHub-Delivery` as the unique event identifier and redelivery reuses that
  value. It is therefore the correct webhook idempotency key.
  [GitHub webhook best practices](https://docs.github.com/en/webhooks/using-webhooks/best-practices-for-using-webhooks)
- **Adopt GitHub signature verification:** validate `X-Hub-Signature-256` before
  accepting payloads.
  [GitHub webhook validation](https://docs.github.com/en/webhooks/using-webhooks/validating-webhook-deliveries)
- **Reject agent-driven provider loops:** Codex/Grok subagents are valuable for
  bounded semantic work, not for polling IDs and replaying transport effects.
  [Codex subagents](https://learn.chatgpt.com/docs/agent-configuration/subagents),
  [Grok subagents](https://github.com/xai-org/grok-build/blob/8a14c91d88875a831a38b3a066b1683116bcb31c/crates/codegen/xai-grok-pager/docs/user-guide/16-subagents.md)

## Implementation completion rule

The component is complete only when every acceptance trace passes against a
fake provider capable of duplicate delivery, missing delivery, timeout after
success, stale head, rate limit, and partial feedback-batch failure. A happy
path backed only by mocked 2xx responses is not an implementation of this
contract.
