# Watched Repository / Workspace Implementation Checklist

Status: Checkpoint 4 complete; workspace-scoped Git and Commits integration are next in
Checkpoint 5. The existing commit browser and local cherry-pick execution are reusable; this
checklist tracks the
missing watch-to-workspace integration and its delivery checkpoints.

Canonical behavior: [Watched Repository / Workspace Design](watched-repo.md).

## Existing reusable work

- [x] Home has a watched-repository grid and repository search/watch modal.
- [x] The Workspaces tab and workspace shell routes exist.
- [x] Commits History, Changes, Flow, Tasks, and Progress views exist.
- [x] Local Git browse/history/diff/status/fetch operations exist.
- [x] Cherry-pick preview, durable execution, worktree creation, progress, and conflict-agent flow
  exist.
- [x] Home watch records and Workspaces use one durable backend workspace model.
- [x] Watched repositories are cloned and opened without the user selecting a local directory.
- [ ] Git browse and cherry-pick requests resolve by workspace ID instead of renderer paths.

## Fixed implementation rules

- [x] A watched repository is a workspace; do not maintain two authoritative models.
- [x] A stable GitHub repository ID can be watched only once; enforce this with a database unique
  constraint and idempotent watch behavior.
- [x] A repository has zero or one upstream; one upstream may be referenced by many watched repos.
- [x] Home and Workspaces list the same backend workspace rows.
- [x] Save and commit the workspace before starting clone or metadata synchronization.
- [x] Code readiness and metadata readiness are independent states.
- [ ] The renderer never submits managed repository or worktree paths.
- [x] Direct repositories use `origin` for monitor, base, push, and PR base.
- [x] Forks use the selected fork as `origin`, canonical source as `upstream`, `upstream` for
  monitor/development/PR base, and writable `origin` for push.
- [x] GitHub permissions are checked; they are not inferred only from the fork flag.
- [ ] The workspace Pull Requests tab reuses the global PR UI, service, clients, stores, and detail
  behavior; only its repository list scope differs.
- [ ] Contributors are cached locally for seven days; labels and repository topics/tags are cached
  locally for thirty days, keyed by monitored repository ID.
- [ ] Push and pull-request creation remain explicit user-confirmed writes.
- [ ] Existing commits/cherry-pick behavior is reused rather than reimplemented.
- [x] No watch/sync path restores the deleted task-agent or legacy development-flow systems.

## UI design checkpoint — Before frontend implementation

### Existing UI to reuse

- [x] Home watched-repository grid and Add Repository modal exist.
- [x] Workspaces list, workspace shell/navigation, Workspace Home, Tasks, and Settings exist.
- [x] Commits and cherry-pick views exist.
- [x] The workspace PR tab already mounts the global `PullsScreen`.
- [x] Global PR list/detail/overview/changes/actions are implemented.

### Focused mockups

- [x] Watch preview/confirmation shows direct/fork routing and duplicate-watch behavior.
  (mockup: `watched-repo-ui/Watch Repository.dc.html`, frames 4–7)
- [x] Watch card states cover preparing, ready, syncing, and failed/retryable.
  (mockup: `watched-repo-ui/Watch Repository.dc.html` frame 8 + `watched-repo-ui/Workspaces.dc.html`)
- [x] Workspaces cards cover pending, ready, syncing, and failed/retryable.
  (mockup: `watched-repo-ui/Workspaces.dc.html`, 8-card grid)
- [x] Workspace Home covers direct and fork topology plus independent code/metadata states.
  (mockup: `watched-repo-ui/Workspace Home.dc.html`, frames A–D)
- [x] Workspace Pull Requests shows its monitored-repository scope and loading/empty/stale/error
  states while reusing the existing list/detail layout.
  (mockup: `watched-repo-ui/Workspace Pull Requests.dc.html`, frames A–E)
- [x] Confirm that no replacement mockup is needed for Commits, Tasks, PR detail, or the shell.
  (confirmed in `watched-repo-ui.md` — component reuse map)
- [x] UI design gate: the four changed surfaces are approved before implementation begins.

## Checkpoint 1 — Durable workspace identity

### Backend model and persistence

- [x] Define the durable Workspace domain record and direct/fork topology enum.
- [x] Persist stable selected and monitored GitHub repository IDs.
- [x] Persist zero or one canonical upstream repository ID; do not use a multi-upstream join table.
- [x] Persist selected/monitored coordinates, default branch, clone routing, and managed path.
- [x] Persist independent code and metadata states, last-success times, and sanitized errors.
- [x] Add store/entity/repository mappings using existing backend conventions.
- [x] Make watch idempotent by stable selected repository ID.
- [x] Add a database unique constraint on selected GitHub repository ID.
- [x] Keep upstream repository ID non-unique so many watched forks can share it.
- [x] Decide and document migration of existing `WatchedRepo` rows.
- [x] Migrate or remove the renderer `bytequay.workspaces` localStorage authority.
- [x] Preserve user display ordering if the current watched-repository order is retained.

### Workspace API

- [x] Implement `WorkspaceRepositoryService.watch`.
- [x] Implement `WorkspaceRepositoryService.listWorkspaces`.
- [x] Implement `WorkspaceRepositoryService.getWorkspace`.
- [x] Implement `WorkspaceRepositoryService.requireReadyRepository`.
- [x] Implement retry/sync and guarded unwatch operations.
- [x] Add workspace create/list/detail/sync/delete controller endpoints.
- [x] Return stable error codes and sanitized messages.

### Tests and gate

- [x] Persistence round-trip covers direct and fork routing.
- [x] Repeating watch for the same stable repository returns the existing workspace.
- [x] Concurrent duplicate-watch attempts still create only one workspace and managed clone.
- [x] One fork cannot persist multiple upstream repositories.
- [x] Multiple fork workspaces can persist the same upstream repository.
- [x] Missing and not-ready workspace errors are mapped consistently.
- [x] Existing watched-repository migration test passes.
- [x] Checkpoint 1 gate: Home and Workspaces can be backed by one persisted identity.

## Checkpoint 2 — Search, topology, and watch confirmation

### GitHub repository data

- [x] Enrich repository search results with stable repository ID.
- [x] Include default branch, visibility, fork flag, clone URL, and permission summary.
- [x] Resolve canonical source/parent identity for a selected fork.
- [x] Re-read repository data on confirm so stale search results cannot persist wrong routing.
- [x] Never persist clone URLs containing credentials.

### Watch preview

- [x] Implement `previewWatch` without mutating local or GitHub state.
- [x] Show selected repository, topology, monitored repository, development base, push remote,
  write permission, and PR base.
- [x] Carry a preview version or topology fingerprint into the watch command.
- [x] Reject confirmation when material routing changed after preview.
- [x] Explain read-only behavior when no push destination is writable.

### Tests and gate

- [x] Direct preview routes all purposes to the selected repository.
- [x] Fork preview routes monitor/base/PR to upstream and push to the selected fork.
- [x] Nested fork/source selection follows the documented canonical rule.
- [x] Nested fork preview resolves and displays one canonical upstream, never a list of upstreams.
- [x] Permission changes are reflected or rejected at confirm time.
- [x] Checkpoint 2 gate: the user sees the exact direct/fork behavior before saving.

## Checkpoint 3 — Managed clone, sync, and recovery

### Provisioning

- [x] Define the ByteQuay-owned repository and worktree roots.
- [x] Validate every resolved path remains below the managed workspace root.
- [x] Save `PENDING`, commit, then schedule provisioning after commit.
- [x] Clone the selected repository as `origin`.
- [x] For a fork, add and fetch the persisted canonical source as `upstream`.
- [x] Record `CLONING`, `READY`, `SYNCING`, and `FAILED` transitions.
- [x] Store sanitized errors and an internal correlation ID where appropriate.
- [x] Never delete or overwrite an unknown/mismatched existing directory.

### Synchronization and restart

- [x] Implement fetch/prune without reset, clean, rebase, or checkout side effects.
- [x] Serialize provisioning/code sync per workspace.
- [x] Recover `PENDING`, `CLONING`, and `SYNCING` workspaces on application startup.
- [x] Validate repository identity and remote URLs before reusing an existing managed checkout.
- [x] Reconcile a provider-side repository rename by stable ID through validated sync.
- [x] Keep inaccessible/deleted repositories visible with actionable state.

### Tests and gate

- [x] Direct provisioning produces the expected `origin` and no `upstream`.
- [x] Fork provisioning produces the expected `origin` and `upstream`.
- [x] Retry resumes an interrupted clone without creating a second workspace.
- [x] Unknown directory and remote mismatch tests fail safely without deletion.
- [x] Fetch leaves local branches, changes, and worktrees intact.
- [x] Checkpoint 3 gate: a watched repository reaches a trustworthy managed `READY` checkout.

## Checkpoint 4 — Home, Workspaces, and Workspace Home

### Home watch flow

- [x] Reuse the existing Add Repository modal with enriched search results.
- [x] Load and render the watch preview after repository selection.
- [x] Require explicit Watch confirmation.
- [x] Insert the saved workspace card immediately after the create response.
- [x] Show preparation, ready, syncing, and needs-attention states.
- [x] Add retry for retryable code or metadata failures.
- [x] Opening an already-watched search result opens/identifies the existing workspace instead of
  offering a second clone.

### Workspaces page

- [x] Replace renderer localStorage workspace loading with `GET /api/workspaces`.
- [x] Remove the local-folder picker from the watched-repository flow.
- [x] Render the same workspace state shown on Home.
- [x] Keep every workspace row clickable, including pending and failed workspaces.
- [x] Route clicks to `/workspaces/:workspaceId`.

### Workspace Home

- [x] Load details by route workspace ID.
- [x] Show selected repo, upstream/monitor routing, default branch, and development base.
- [x] Show code and metadata readiness independently.
- [x] Show last successful sync and safe retryable failures.
- [x] Explain why tabs are disabled when their required state is not ready.
- [x] Preserve existing task counts, recent activity, open-PR count, and Commits shortcut.

### Tests and gate

- [x] Home and Workspaces render the same workspace ID and state.
- [x] A saved pending workspace can be opened before clone completes.
- [x] Direct and fork routing are visible on Workspace Home.
- [x] Refresh/restart does not lose workspaces because localStorage is not authoritative.
- [x] Checkpoint 4 gate: watch → save → Workspaces → click → Workspace Home works end to end.

## Checkpoint 5 — Workspace-scoped Git and Commits

### Backend integration

- [ ] Add workspace-scoped overview, branches, commits, diff, status, and fetch endpoints.
- [ ] Resolve the trusted repository path through `requireReadyRepository`.
- [ ] Reject Git operations until code state is `READY`.
- [ ] Stop accepting `repositoryPath` from the workspace renderer flow.
- [ ] Preserve the existing `GitBrowseService` implementation behind the new controller boundary.
- [ ] Audit path validation for every Git command reached from workspace routes.

### Frontend integration

- [ ] Load Commits data using the route workspace ID.
- [ ] Remove repository path from workspace Git request types.
- [ ] Enable Commits only when code is ready.
- [ ] Keep History, Changes, Flow, Tasks, and Progress navigation intact.
- [ ] Preserve branch selection, commit pagination, range selection, and diff behavior.

### Tests and gate

- [ ] Workspace A cannot browse Workspace B by changing request data.
- [ ] Pending/failed clone state returns a stable not-ready response.
- [ ] Existing Git browse service tests still pass.
- [ ] Existing Commits frontend tests pass with workspace-ID requests.
- [ ] Checkpoint 5 gate: clicking Commits displays the managed repository's commit history.

## Checkpoint 6 — Cherry-pick handoff from Workspace Commits

### API and execution routing

- [ ] Change preview/start requests to carry workspace ID rather than repository path.
- [ ] Remove renderer-provided `worktreeParent` from the workspace flow.
- [ ] Resolve repository path and managed worktree root on the backend.
- [ ] Default a direct target base to `origin/<target-branch>`.
- [ ] Default a fork target base to `upstream/<target-branch>`.
- [ ] Persist workspace ID and resolved routing on the execution for audit/recovery.
- [ ] Preserve commit order, mainline choices, validation profile, and execution semantics.
- [ ] Preserve durable task/progress and conflict-agent continuation behavior.

### UI handoff

- [ ] Start Flow from the selected History range.
- [ ] Show the resolved direct/fork target base in preview.
- [ ] Do not ask the user to choose a worktree parent directory.
- [ ] Return to Tasks/Progress using the existing execution routes.
- [ ] Persist PR draft data with the execution when delivery work begins; do not rely only on
  localStorage.

### Tests and first shipping gate

- [ ] Direct preview/execution uses the managed checkout and `origin` base.
- [ ] Fork preview/execution uses the managed checkout and `upstream` base.
- [ ] Renderer path injection cannot redirect execution outside the workspace root.
- [ ] Existing cherry-pick preview/execution/worktree tests remain green.
- [ ] Existing conflict-agent flow remains green.
- [ ] **First shipping gate:** watch → save → Workspaces → Workspace Home → Commits → select range
  → preview → execute → Progress works for direct and fork repositories.

## Checkpoint 7 — Workspace PR scope and repository metadata

### Pull-request reuse and scope

- [ ] Add a repository-filtered query to the existing `PullRequestStore` and
  `PullRequestService`; do not create a second PR store.
- [ ] Resolve workspace ID to its monitored repository before querying PRs.
- [ ] Pass an optional monitored-repository scope to the existing `PullsScreen`.
- [ ] (new, optional) Add a title/author/label filter box to `PullsScreen` — a new enhancement, not
  existing behavior; droppable for the minimal slice.
- [ ] Reuse existing PR list rows, tabs, overview, changes, details, comments, review, merge,
  checks, refresh, DTOs, clients, and tests.
- [ ] Keep the global Pull Requests page account-centric and unfiltered.
- [ ] For a direct workspace, show only the selected repository's PRs.
- [ ] For a fork workspace, show only the one persisted upstream repository's PRs.
- [ ] Display the monitored repository in the workspace PR header and empty/error states.

### Reference metadata synchronization

- [ ] Implement independent metadata state transitions and retry.
- [ ] Synchronize contributor account summaries for search, developer selection, and reviewer
  assignment.
- [ ] Synchronize labels and repository topics/tags from the monitored repository.
- [ ] Use selected repository for direct workspaces and upstream for fork workspaces.
- [ ] Store all reference projections locally by stable monitored repository ID, not workspace ID.
- [ ] Share one cached projection when several fork workspaces link to the same upstream.
- [ ] Upsert projections by stable provider IDs.
- [ ] Retain the last successful snapshot when refresh fails.
- [ ] Refresh contributors only when absent or at least seven days old.
- [ ] Refresh labels and repository topics/tags only when absent or at least thirty days old.
- [ ] Serve stale local data while refresh runs or fails.
- [ ] Add pagination/cursor handling only where the existing GitHub client requires it.

### Issues

- [ ] Keep issue synchronization separate from slow reference metadata.
- [ ] Scope future issue query/sync to the workspace's monitored repository.
- [ ] Design and implement the real Issues list/detail UI; the current tab is only a placeholder.

### Query/UI integration

- [ ] Scope Issues, Pull Requests, and Contributors queries by workspace ID while caching by
  monitored repository ID.
- [ ] Show freshness and retryable metadata failure independently from code state.
- [ ] Ensure a metadata failure does not disable Commits.
- [ ] Ensure a code failure does not erase available metadata.

### Tests and gate

- [ ] Direct metadata calls target the selected repository.
- [ ] Fork metadata calls target upstream and never the fork by mistake.
- [ ] Workspace PR list contains no rows from another repository.
- [ ] Global PR list remains unchanged after workspace scoping is added.
- [ ] Existing PR screen/service/detail/action tests remain green.
- [ ] Repeated sync upserts instead of duplicating issues, PRs, or users.
- [ ] Partial/provider failure keeps the last complete snapshot readable.
- [ ] Several fork workspaces sharing one upstream trigger one reference-metadata refresh/cache.
- [ ] Fresh weekly/monthly cache entries do not call GitHub again.
- [ ] Checkpoint 7 gate: workspace issues, PRs, and contributor search monitor the correct repo.

## Checkpoint 8 — Push and pull-request delivery

This checkpoint completes remote delivery. It is not required for the first local cherry-pick
shipping gate.

### Explicit writes

- [ ] Persist PR draft title/body/base with the workspace execution.
- [ ] Delegate existing PR write/detail behavior instead of copying it into a workspace-only
  implementation.
- [ ] Require an explicit user action before push.
- [ ] Direct workspace pushes the generated branch to writable `origin`.
- [ ] Fork workspace pushes the generated branch to writable fork `origin`.
- [ ] Disable push with an actionable message when the configured remote is not writable.
- [ ] Never force-push or silently choose another remote.
- [ ] Require a second explicit action before creating the pull request.
- [ ] Direct PR targets the selected repository and chosen base branch.
- [ ] Fork PR head comes from the fork and targets upstream/chosen base branch.
- [ ] Record the provider PR ID and URL on the execution/workspace task.

### Tests and gate

- [ ] Direct push/PR routing is correct.
- [ ] Fork push/PR routing is correct.
- [ ] Read-only routing produces no GitHub write.
- [ ] Duplicate confirmation is idempotent or returns the existing PR.
- [ ] No remote write occurs during watch, clone, preview, or local execution.
- [ ] Checkpoint 8 gate: the generated branch can be deliberately pushed and proposed to the
  correct repository.

## Checkpoint 9 — Verification and handoff

### Automated verification

- [ ] Backend focused workspace/provisioning/metadata tests pass.
- [ ] Backend existing Git browse and cherry-pick tests pass.
- [ ] Backend full Maven verification passes.
- [ ] Frontend lint passes.
- [ ] Frontend TypeScript check passes.
- [ ] Frontend tests pass.
- [ ] Frontend packaging/build verification passes.
- [ ] Documentation/code links and endpoint names match the implementation.
- [ ] `git diff --check` passes.

### Manual acceptance

- [ ] Direct repository: search, preview, watch, clone, open Workspace Home, browse Commits, and
  complete a local cherry-pick execution.
- [ ] Fork repository: preview clearly identifies upstream, clone configures both remotes, Commits
  uses upstream base, and local cherry-pick completes.
- [ ] Restart during clone/sync recovers without duplicate workspace or destructive cleanup.
- [ ] GitHub metadata failure leaves ready Commits usable and visibly retryable.
- [ ] Clone failure leaves the workspace openable and visibly retryable.
- [ ] Unwatch refuses active work and safely removes only ByteQuay-owned paths when allowed.
- [ ] If Checkpoint 8 ships, direct and fork push/PR manual routes are verified separately.

### Final gate

- [ ] All in-scope checkpoints are complete, deferred items are explicitly recorded, and the
  watched repository → workspace → commits → cherry-pick flow is ready to merge.
