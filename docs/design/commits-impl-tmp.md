# Commit History and Refresh Implementation Checklist

Status: planned; awaiting checklist review before implementation begins.

Canonical behavior is defined in
[Commit History and Refresh Design](commits.md). A box is checked only when the
code and focused tests exist. Every checkpoint is a buildable, reviewable
commit boundary; do not combine later checkpoints into an earlier one merely
to make the UI appear complete sooner.

## Fixed implementation rules

- [ ] Opening or switching Commit History performs no GitHub, `gh`, Git fetch,
  or refresh-enqueue operation.
- [ ] Managed Git remains the source of truth for commit facts and order; do
  not add a duplicate commit table.
- [ ] SQLite stores only durable enrichment and refresh state.
- [ ] Full PR data continues to use the existing `PullRequestStore`,
  `PrDetailStore`, and `PullRequestService`.
- [ ] Commit-to-PR lookup results, including checked negative results, survive
  application restart.
- [ ] Avatar bytes shown on commit cards come from ByteQuay's local cache;
  renderer image failure falls back to an initial without a remote URL.
- [ ] Scheduled, startup, and manual refreshes share one queue and worker.
- [ ] Existing rows remain visible while refresh is queued, running, or failed.
- [ ] Refresh may fetch/prune Git refs but never checks out, resets, cleans,
  rebases, merges, pushes, or modifies working-tree files.
- [ ] The upstream source is resolved only from the explicitly linked watched
  workspace; GitHub topology never chooses it.
- [ ] API and storage contracts return named response types, not raw maps.
- [ ] Documentation, fixtures, and examples use synthetic identities.
- [ ] Existing PR chip/title, avatar, selection, diff, rewrite, cherry-pick,
  Tasks, Progress, and “No upstream linked” behavior do not regress.
- [ ] Add no dependency, executor, generic cache framework, or second PR
  service for this feature.

## Checkpoint 0 — Lock the current commit-card behavior

Goal: make the reported regressions fail tests before changing the data path.

### Tests

- [x] Extend `HistoryView` coverage so one commit with persisted association
  data renders its PR chip, PR title, and click action.
- [x] Assert a commit with an avatar URL renders an `<img>` in the reserved
  avatar slot.
- [x] Assert a commit without an avatar renders the fixed-size initial
  fallback without changing card layout.
- [x] Keep a focused `CommitsTab` assertion for the “No upstream linked” chip
  and Settings action.
- [x] Keep selection, viewed-row, and short-SHA assertions unchanged.
- [x] Run the focused frontend tests before production changes.

Checkpoint gate:

- [x] A removal of the PR row, avatar slot, or unlinked-upstream control fails
  a focused test.

Suggested commit: `Protect commit history card behavior`

## Checkpoint 1 — Persist enrichment and refresh state

Goal: establish the durable local projection without changing runtime display.

### Core contracts

- [x] Add `CommitEnrichment`, `CommitEnrichmentSnapshot`, and
  `CommitRefreshStatus` as framework-free `core` domain values.
- [x] Add the narrow `CommitHistoryStore` port with the documented named
  request/response types from `commits.md`.
- [x] Validate repository ID, SHA, optional PR fields, timestamps, and state
  transitions at domain boundaries.

### SQLite adapter

- [x] Add one enrichment entity keyed by stable repository ID plus commit SHA.
- [x] Add one refresh-state entity keyed by workspace ID.
- [x] Implement batched enrichment reads and non-destructive upserts.
- [x] Preserve `lastSucceededAt` and previous enrichment when a later attempt
  fails.
- [x] Use the existing Hibernate schema-update convention; do not add a
  migration framework.

### Tests and gate

- [x] Persistence round-trip covers positive PR association, checked negative
  association, avatar key, and timestamps.
- [x] Upserting one SHA does not replace unrelated SHAs.
- [x] Stable repository ID continues to find enrichment after repository
  coordinates change.
- [x] Restart-style SQLite test reloads association and refresh state from a
  new store instance.
- [x] Failed-state update retains the previous success time.

Checkpoint gate:

- [x] Durable enrichment and refresh state work independently of controllers,
  GitHub clients, and the renderer.

Suggested commit: `Persist commit history enrichment`

## Checkpoint 2 — Cache and serve avatar files locally

Goal: make a stored avatar a real offline file rather than a persisted remote
URL.

### Cache and endpoint

- [ ] Add one concrete `AvatarCache` below ByteQuay's application-data root.
- [ ] Derive fixed-format keys from a digest of the source URL.
- [ ] Write a downloaded image to a temporary file and atomically move it into
  place only after a successful bounded response.
- [ ] Reuse an existing file for repeated authors/URLs.
- [ ] Add `GET /api/avatars/{key}` that serves only an existing local file with
  a stable content type and `ETag`.
- [ ] Reject malformed keys and prove path traversal cannot escape the cache
  root.
- [ ] Return missing for an absent file without downloading from the read
  endpoint.

### Tests and gate

- [ ] Cache tests cover first write, deduplication, failed download, and atomic
  replacement behavior.
- [ ] Controller tests cover local success, missing key, malformed key, and
  traversal attempts.
- [ ] A test proves the avatar GET path makes zero remote calls.

Checkpoint gate:

- [ ] A cached avatar is readable after cache reconstruction with networking
  disabled.

Suggested commit: `Cache commit avatars locally`

## Checkpoint 3 — Build the local history read service

Goal: assemble the final display response locally before replacing the current
controller path.

### Service

- [ ] Add `CommitHistoryRef` with `WORKSPACE` and `UPSTREAM` sources plus a
  validated branch.
- [ ] Implement `CommitHistoryService.listHistory(...)` using
  `WorkspaceRepositoryService`, `GitBrowseService.log(...)`, and
  `CommitHistoryStore` only.
- [ ] Resolve selected and upstream stable repository IDs from durable
  workspace routing.
- [ ] Reject an upstream ref when no watched upstream workspace is linked.
- [ ] Cap the limit at 300 and retain existing revision safety checks.
- [ ] Return a named `CommitHistorySnapshot` containing commit DTOs and durable
  refresh status.
- [ ] Convert avatar keys to localhost avatar URLs without storing the runtime
  port in SQLite.
- [ ] Join persisted PR number/title directly into each commit response.

### Tests and gate

- [ ] Direct-workspace and linked-upstream histories resolve the correct Git
  ref and repository ID.
- [ ] Unlinked-upstream, invalid branch, missing workspace, and not-ready
  workspace cases fail safely.
- [ ] Missing enrichment still returns the local commit with null optional
  fields.
- [ ] Inject GitHub collaborators that throw if called and prove
  `listHistory(...)` still succeeds.

Checkpoint gate:

- [ ] The local service returns the complete card model with zero network
  dependencies.

Suggested commit: `Add local commit history service`

## Checkpoint 4 — Implement one refresh queue and worker

Goal: populate the projection without blocking display or dropping concurrent
requests.

### Queue and state transitions

- [ ] Implement `CommitHistoryService.requestRefresh(...)` on the existing
  application executor.
- [ ] Persist `QUEUED` before returning and `RUNNING` before remote work starts.
- [ ] Coalesce refs per workspace in one pending set.
- [ ] Use one drain guard so a request arriving during a run remains pending
  and is eventually processed.
- [ ] Do not create a new executor or wait with `Thread.sleep` in tests.

### Synchronization

- [ ] Resolve every requested ref again inside the worker so stale renderer
  routing cannot select a repository.
- [ ] Call `WorkspaceProvisioningService.synchronizeCode(...)` for safe
  fetch/prune before reading remote refs.
- [ ] Read at most 300 local commits per requested ref.
- [ ] Load enrichment in one store query and request avatars once per ref.
- [ ] Query PR associations only for unknown, failed, or due-negative SHAs.
- [ ] Cache avatar files before persisting their keys.
- [ ] Reuse `PullRequestService.syncRepository(...)` for the existing full PR
  projection and share its repository-level overlap guard.
- [ ] Upsert successful enrichment without clearing prior values after a
  partial provider failure.
- [ ] Persist `IDLE` plus a new success time only after required work succeeds;
  otherwise persist `FAILED` with a safe correlation reference.

### Tests and gate

- [ ] Successful refresh covers Git fetch, avatar enrichment, PR association,
  existing PR sync, persistence, and state transitions in order.
- [ ] Duplicate requests coalesce and never run concurrently.
- [ ] A request received during execution runs in a later drain pass.
- [ ] Provider, Git fetch, and persistence failures retain the last usable
  snapshot and success time.
- [ ] Positive associations are not re-fetched; recent negative associations
  obey the documented daily retry window.

Checkpoint gate:

- [ ] One service request creates a restart-safe local snapshot without any UI
  participation.

Suggested commit: `Synchronize commit history snapshots`

## Checkpoint 5 — Expose manual and scheduled refresh

Goal: route every trigger through the worker built in Checkpoint 4.

### Backend API and schedule

- [ ] Add `POST /api/workspaces/{workspaceId}/git/commits/refresh` with named
  request and response classes.
- [ ] Return `202 Accepted` after queuing, not after GitHub synchronization.
- [ ] Accept only source-and-branch selectors; never accept filesystem paths,
  stable repository IDs, or remote names from the renderer.
- [ ] Add `CommitHistorySyncJob` with a one-minute due-work tick and a
  five-minute freshness threshold.
- [ ] Queue only ready workspaces and their default workspace/upstream refs.
- [ ] Invoke the same due-work path at application startup.
- [ ] Ensure a recent manual success suppresses scheduled work until stale.

### Tests and gate

- [ ] HTTP tests cover accepted manual refresh and validation failures.
- [ ] Scheduler tests use a controlled clock and direct method invocation, not
  wall-clock waiting.
- [ ] Startup, scheduled, and manual requests coalesce in the same worker.
- [ ] Unlinked workspaces never queue an upstream ref.
- [ ] Multiple workspaces sharing a repository reuse enrichment by stable
  repository ID and SHA.

Checkpoint gate:

- [ ] Manual and scheduled triggers produce identical validated worker
  requests and cannot create overlapping refreshes.

Suggested commit: `Schedule commit history refresh`

## Checkpoint 6 — Cut the backend display path over to local data

Goal: remove remote work from the request that opens History.

### Controller and preview

- [ ] Change `GitBrowseController.commits(...)` to delegate only to
  `CommitHistoryService.listHistory(...)`.
- [ ] Return `CommitHistorySnapshot` rather than a bare commit list.
- [ ] Remove `GitHubRepoClient`, `CredentialService`, and
  `CherryPickPullRequestLookup` from the controller constructor.
- [ ] Make `CherryPickPreviewService` read the same persisted associations and
  retain the existing non-load-bearing warning when enrichment is absent.
- [ ] Keep `CherryPickPullRequestLookup` callable only by the refresh worker.
- [ ] Remove obsolete display-time and preview-time association cache paths.

### Tests and gate

- [ ] Controller tests assert zero avatar or PR-association GitHub calls.
- [ ] Opening history with no GitHub credentials still returns local commits.
- [ ] Preview uses stored PR groups and keeps every commit selectable when an
  association is missing.
- [ ] Existing diff, status, branch, rewrite, and cherry-pick endpoints remain
  unchanged.

Checkpoint gate:

- [ ] Every normal History and preview read is local-only and all focused
  backend tests pass.

Suggested commit: `Read commit history from local data`

## Checkpoint 7 — Move the renderer to durable refresh state

Goal: preserve the existing UI while replacing its data and refresh call
chains.

### Bridge and state

- [ ] Add named TypeScript DTOs for `CommitHistorySnapshot`,
  `CommitRefreshStatus`, and refresh refs.
- [ ] Update `gitLog` to consume the named snapshot response.
- [ ] Add `refreshCommitHistory(workspaceId, refs)` to main/preload/types.
- [ ] Replace the toolbar's direct `gitFetch(...)` call with the refresh
  endpoint.
- [ ] Remove `bytequay.lastSync.*` localStorage reads and writes.
- [ ] Display the backend's queued/running/failed/last-success state.
- [ ] Keep current commits rendered during refresh and poll local history only
  while a user-triggered refresh is queued or running.
- [ ] Stop the separate `fetchPrs(workspaceId)` request from `CommitsTab`; use
  the PR number/title already present on each commit.
- [ ] Change `Avatar` so commit cards use the localhost URL or initial fallback
  and never synthesize a GitHub URL after failure.

### Regression tests and gate

- [ ] PR chip/title and click action still render from the snapshot.
- [ ] Cached avatar `<img>` and fixed-size fallback both render correctly.
- [ ] “No upstream linked” remains aligned and opens Settings.
- [ ] Manual refresh sends the selected workspace branch and only sends an
  upstream branch when an explicit link exists.
- [ ] Old rows remain during queued/running/failed states.
- [ ] Polling starts only for a manual active refresh and stops on success,
  failure, unmount, or workspace change.
- [ ] History, Changes, selection, rewrite, cherry-pick, Tasks, and Progress
  focused tests pass.

Checkpoint gate:

- [ ] The visible Commit History behavior is unchanged except that opening is
  fast/local and refresh status is durable.

Suggested commit: `Use local commit history in the UI`

## Checkpoint 8 — Full verification and acceptance

Goal: prove the complete local-first behavior and leave no transitional path.

### Automated verification

- [ ] Run `mvn verify` in `backend/` and confirm Checkstyle and every module
  test pass.
- [ ] Run frontend lint, TypeScript type-check, all tests, and packaging.
- [ ] Run `git diff --check`.
- [ ] Search production display code for direct commit-avatar and
  commit-association GitHub calls.
- [ ] Reconcile every checked box with the final code and focused test.

### Manual acceptance

- [ ] Open Commit History repeatedly and confirm only local history requests
  occur.
- [ ] Restart offline and confirm commits, stored PR associations, and cached
  avatars remain visible.
- [ ] Trigger manual refresh and confirm old rows remain until the new local
  snapshot is ready.
- [ ] Simulate GitHub failure and confirm stale rows, prior avatars/PR chips,
  last-success time, and retry control remain.
- [ ] Confirm direct, explicitly linked upstream, and unlinked workspace flows.
- [ ] Confirm a refresh never changes the checked-out branch, local changes,
  or worktree files.

Final gate:

- [ ] The implementation, tests, and `commits.md` describe the same local-only
  display and shared scheduled/manual refresh behavior.

Suggested commit: `Verify local commit history refresh`
