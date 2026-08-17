# CI Autofix Backend Implementation Ledger

This temporary checklist is the mandatory implementation path for the locked
[CI Autofix Service Design](ci-autofix-service.md). It covers the backend only.
It does not include frontend screens, review-comment triage, another CI
provider, fork pull requests, GitHub comments, approval, merge, or a generic
workflow framework.

## How this ledger is used

- Work in checkpoint order. Within the current checkpoint, the first unchecked
  leaf item is the next implementation item unless its stated prerequisite is
  incomplete.
- Every CI Autofix code change must name the item IDs it implements and update
  their checkboxes in the same change.
- Check an item only when the work named by that item is complete. Production
  items may be checked before the checkpoint's grouped test item, but the
  checkpoint remains incomplete until its focused-test item and `mvn verify`
  gate are checked. A partially implemented item remains unchecked.
- Finish every checkpoint with `cd backend && mvn verify`. Do not start the
  next checkpoint while that gate fails.
- A refactor may be included only when the current item needs it. Do not add a
  new Maven module, workflow engine, provider abstraction, migration framework,
  or compatibility layer.
- Agent prose is display-only and untrusted. No item may add a parser, response
  schema, or program branch based on an assistant message, claimed test result,
  claimed target commit, or JSON-looking final response.
- If implementation reveals that the locked scope, authority boundary,
  service contracts, three-status model, or control facts must change, stop.
  Obtain explicit user approval, update the design first, then update this
  ledger. Do not silently adapt the design in code.
- Keep this file until every implementation and final-gate item is complete.
  The final cleanup removes it only after the locked design records the shipped
  implementation and the complete backend gate passes.

## Fixed implementation boundaries

- Framework-free CI values and `CiAutofixStore` live in `backend/core` under
  `com.bytequay.core.ci`.
- JPA entities, repositories, and the SQLite adapter live in
  `backend/persistence-sqlite`. Hibernate continues to own schema creation; no
  Flyway or hand-written migration history is added.
- Orchestration, Git operations, CI memory, prompt construction, monitoring,
  and the service interfaces from the design live in `backend/app` under
  `com.bytequay.app.ci`.
- GitHub REST DTOs and the concrete adapter remain in
  `com.bytequay.app.repository.github`. The current check-run read behavior is
  preserved while that adapter implements the new CI Autofix contract.
- Existing `AgentTaskService`, `AgentTurnService`, `AgentEventService`,
  `AgentCompletionService`, `AgentResourceService`, and `TaskLogStore` remain
  the owners of agent lifecycle and files. CI Autofix does not write agent
  tables or provider event files directly.
- Raw Actions logs use existing bounded task blobs. `ci-memory.md` is one
  disposable file at the fixed path beneath `TaskResources.runtimeDirectory()`;
  there is no memory database and no change to the `TaskResources` record.
- Program decisions use stored authorization/counters, GitHub facts,
  `AgentTurn.state`, actual Git facts, and push/rerun journals only.

## Checkpoint 0 — Lock and baseline

- [x] **C0.1** Read `ByteQuay.md`, `CONTRIBUTING.md`, the architecture and agent
  service designs, and the complete CI Autofix design.
- [x] **C0.2** Inspect the implemented agent services, task resources/blob
  store, provider lanes, current GitHub Actions client, PR timeline storage,
  Spring composition, and SQLite conventions.
- [x] **C0.3** Establish the pre-CI-Autofix backend baseline: `mvn verify`
  passes across all seven reactor projects. The localhost HTTP tests require a
  run environment that permits binding an ephemeral loopback port.
- [x] **C0.4** Mark `ci-autofix-service.md` locked and link this ledger as the
  required implementation checklist.

Exit: the design is frozen and the existing backend is green before CI
Autofix production code begins.

## Checkpoint 1 — Durable run and attempt foundation

- [x] **C1.1** Add validated immutable core values for run/attempt IDs,
  `ACTIVE`/`PAUSED`/`FINISHED`, pause and finish reasons, authorization scope,
  failure evidence, Git inspection facts, push/rerun journals, warnings, and
  final fixup-check facts. Keep watching/running/pushing as derived activity,
  not stored states.
- [x] **C1.2** Implement `CiAutofixRun` and `CiAutofixAttempt` transition
  methods with only the locked invariants: one current turn, counters bounded
  by stored authorization, immutable repository/PR/branch/worktree/task scope,
  no transition out of `FINISHED`, and optimistic version increments. Do not
  introduce a state-machine library.
- [x] **C1.3** Add the exact `CiAutofixStore` port from the locked design with
  method-level Javadocs, validation, and explicit not-found/conflict/storage
  exceptions. A run projection includes its ordered attempts.
- [x] **C1.4** Add `CiAutofixRunEntity`, `CiAutofixAttemptEntity`, Spring Data
  repositories, and converters in `persistence-sqlite`. Use explicit bounded
  columns or the project's JSON converter convention for small lists; never
  store raw CI logs, prompts, diffs, credentials, or provider payloads here.
- [x] **C1.5** Enforce start idempotency and one non-finished run per repository
  and head branch at the database boundary. Use a nullable active-branch slot
  or an equivalent Hibernate-owned unique constraint so a finished run does
  not block a later run.
- [x] **C1.6** Implement `SqliteCiAutofixStore` so run plus attempt updates are
  transactional, attempt numbers are unique within a run, list order is
  deterministic, and stale optimistic versions fail without partial writes.
- [x] **C1.7** Add persistence tests for complete round trips, all three
  statuses, start idempotency, active-branch conflicts, attempt uniqueness,
  optimistic conflicts, ordered lists, journals, and restart reconstruction.
- [x] **C1.8** Run `mvn verify`.

Exit: a run and all facts needed by later reconciliation survive restart; no
agent, Git command, or GitHub write runs yet.

## Checkpoint 2 — GitHub Actions facts, logs, and rerun contract

- [ ] **C2.1** Add the `com.bytequay.app.ci.GitHubActionsClient` interface and
  command/result records exactly as documented. Make the existing
  `com.bytequay.app.repository.github.GitHubActionsClient` implement it without
  regressing `fetchPrCheckRuns(...)` used by ordinary PR detail sync.
- [ ] **C2.2** Implement `inspect(...)` using the authenticated GitHub account
  and PR/Actions APIs. Return open/closed state, author, same-repository fact,
  head ref/SHA, base ref/SHA, workflow/run/job attempt identities, status,
  conclusion, and URLs for the exact current head.
- [ ] **C2.3** Normalize multiple workflow/job pages to the latest attempt for
  each job. A completed actionable failure is returned immediately even while
  unrelated jobs are queued or running; an old attempt must never replace a
  newer attempt.
- [ ] **C2.4** Implement bounded raw job-log reads. Follow GitHub's log response
  safely, stream only up to the command's byte limit, return truncation facts,
  redact authorization material, and persist accepted bytes through
  `TaskLogStore.writeBlob(...)`. Store only the returned task-relative
  `BlobRef` and evidence metadata in the attempt.
- [ ] **C2.5** Derive the failure signature in program code from stable workflow,
  job, step, attempt, and normalized terminal-error facts. The algorithm is
  deterministic and bounded; it never uses an agent classification.
- [ ] **C2.6** Implement `rerunFailedJobs(...)` for one exact completed workflow
  run. It returns accepted/obsolete/rejected facts only; the caller journals
  intent before the call and later GitHub attempt numbers remain authoritative.
- [ ] **C2.7** Fixture-test pagination, duplicate and out-of-order attempts,
  early actionable failure, all-pending/all-green heads, missing/expired logs,
  redirect handling, log truncation, signature stability, rerun acceptance,
  permission rejection, and existing PR check-run reads.
- [ ] **C2.8** Run `mvn verify`.

Exit: the program can reconstruct exact current Actions evidence and store a
bounded task log, but it still does not start an agent or mutate GitHub.

## Checkpoint 3 — CI agent policy, memory, and task tool

- [ ] **C3.1** Add a dedicated `CI_AUTOFIX` agent permission policy and provider
  capability check. It permits worktree edits, local compile/test commands, and
  local Git history operations required by the prompt, but it cannot expose a
  PAT or approve `git push`, GitHub comments/reviews/merge, branch deletion, or
  another GitHub write path. Reject `start(...)` for a lane that cannot enforce
  this policy.
- [ ] **C3.2** Add provider-lane tests proving the CI policy is mapped to an
  enforceable workspace boundary, credentials are not added to turn context or
  attachments, prohibited remote-write requests are denied, and existing
  `MANUAL` tasks retain their behavior.
- [ ] **C3.3** Implement `CiMemoryService.update(...)` as a full rebuild from
  `CiAutofixStore`: group by program failure signature, newest first, at most 50
  signatures and five attempts per signature, include verified GitHub/Git
  outcomes, escape the optional untrusted agent note, cap at 256 KiB, write a
  sibling temporary file, and atomically replace `ci-memory.md`.
- [ ] **C3.4** Treat memory update failure as a bounded warning on the run and a
  warning log only. Preserve the previous valid file when replacement fails and
  never pause, finish, or skip the next turn because memory is unavailable.
- [ ] **C3.5** Implement `CiMemoryService.read(...)`: resolve the run only from
  the authenticated `TaskId`, reject cross-task access, trim and cap a query at
  200 characters, split only at program-written `## Failure` boundaries,
  return the ten newest sections or ten newest case-insensitive substring
  matches, cap output at 32 KiB, and return a bounded no-match/unavailable view.
- [ ] **C3.6** Generalize the existing task-scoped ByteQuay MCP registration
  only as much as needed to expose `read_ci_memory` beside existing provider
  tools. The capability token injects `TaskId`; tool arguments contain only the
  optional query; the agent cannot name a run/task or call `update(...)`; no
  public memory HTTP API or separate MCP process is added.
- [ ] **C3.7** Wire the task tool through every agent lane admitted by C3.1.
  Preserve Claude's `permission_prompt` behavior and add protocol-fixture tests
  for tool list/call, bad token, malformed/oversized query, unavailable memory,
  registration cleanup, and task isolation.
- [ ] **C3.8** Test memory rebuild idempotency, chronological grouping,
  untrusted-heading escaping, file/result size caps, atomic failure recovery,
  missing files, recent reads, substring search, no match, and best-effort
  warnings.
- [ ] **C3.9** Run `mvn verify`.

Exit: a CI task can safely search disposable prior CI history on demand, and
memory availability cannot control the run.

## Checkpoint 4 — Start one task and one fix turn

- [ ] **C4.1** Implement the basic `CiAutofixService` methods `start`, `get`,
  `list`, and `cancel`. Start requires a direct request with all locked consent
  flags, `maxAttempts` from 1 through 3, an eight-hour expiry, the current user
  as PR author, an open same-repository PR, one branch slot, and a clean leased
  worktree whose repository/branch/HEAD match the GitHub head.
- [ ] **C4.2** Make start failure and idempotency recoverable across the run/task
  boundary: persist authorization before autonomous work, create exactly one
  task with the `CI_AUTOFIX` policy, store its ID, return the existing run for a
  repeated key, and finish a partially created run with a bounded error if task
  creation cannot be completed. Never leave an unowned live task after a
  database uniqueness race.
- [ ] **C4.3** Implement one prompt builder containing the locked prompt text
  plus current head/base SHAs and normalized failed-job summaries. Resolve
  current log `BlobRef`s to safe absolute task paths and attach them with their
  media type. Do not attach `ci-memory.md`.
- [ ] **C4.4** Before starting a fix turn, persist the attempt and evidence,
  update memory best-effort, then call `AgentTurnService.start(...)` with the
  attempt ID as the turn idempotency key. Store only the returned turn ID/state;
  never wait for or parse an assistant response inside `start(...)`.
- [ ] **C4.5** Reconcile turn lifecycle only through `AgentTurnService.get(...)`:
  wait for `ACCEPTED`/`RUNNING`, inspect Git after `COMPLETED`, pause after
  `FAILED`/`INTERRUPTED`, and treat `CANCELLED` according to the stored run
  cancellation request. Mechanically copy a bounded final message to an
  **Untrusted agent note** only after the state is terminal; no condition may
  read that note.
- [ ] **C4.6** Implement `sendMessage(...)` only for `PAUSED`: recheck author,
  authorization, exact remote head, task readiness, lease, message bound, and
  idempotency before starting a turn on the same task. It cannot change the
  GitHub-write scope or reset partial local work.
- [ ] **C4.7** Unit-test author/PR/consent/worktree rejection, start races,
  repeated keys, task rollback/finish behavior, exact prompt and attachments,
  every durable turn state, cancellation, paused messages, and a malicious
  final response that claims success/failure without affecting control.
- [ ] **C4.8** Run `mvn verify`.

Exit: one consented authored PR creates one durable CI task and one correctly
scoped fix turn; remote state is unchanged.

## Checkpoint 5 — Inspect and publish agent-owned Git history

- [ ] **C5.1** Implement the concrete `CiGitService` with argv-based Git
  commands, explicit worktree, bounded output/time, `GIT_TERMINAL_PROMPT=0`,
  and no shell. Reuse small existing process/Git utilities only where their
  behavior matches; do not add a Git framework.
- [ ] **C5.2** Implement `inspectAgentResult(...)` from actual repository facts.
  Return no-change only when HEAD equals the attempt input and index/worktree are
  clean. Return ready only for a changed committed HEAD, clean index/worktree,
  no unmerged paths or open merge/rebase/cherry-pick, a linear PR range, no
  commits outside the allowed range, and a reproducible local fingerprint.
- [ ] **C5.3** Return bounded invalid diagnostics for dirty/staged/untracked
  files, conflict markers reported by Git, unresolved entries, open operations,
  detached/wrong branch, non-linear history, out-of-range commits, or an unsafe
  publish relationship. The program pauses; it does not start an automatic
  correction turn.
- [ ] **C5.4** Implement `push(...)` so the caller persists local HEAD,
  fingerprint, expected remote SHA, branch, and intent before the process
  starts. Recheck consent/expiry/repository/branch/fingerprint and exact remote
  head immediately before pushing `HEAD:refs/heads/<authorized-head-ref>`.
- [ ] **C5.5** Use a normal push only when local HEAD descends from the expected
  remote head. For agent-rewritten PR history, use only an exact
  `--force-with-lease=<ref>:<expected-sha>`. Reject plain force, multiple
  refspecs, tags, deletion, another branch, and any credential in argv/logs.
- [ ] **C5.6** Reconcile uncertain pushes: remote equals journaled local HEAD is
  success; remote equals expected old SHA permits one identical retry while
  authorization and fingerprint remain valid; any other SHA finishes
  `STALE_HEAD`. Never create, amend, squash, reorder, or choose a fixup target in
  program code.
- [ ] **C5.7** Implement read-only `checkFixupPositions(...)` for the linear PR
  range: exact subject match, fixup immediately after a non-fixup target, at
  most one fixup per target, and no unresolved Git state. Persist the checked
  head/result so the same history is checked once.
- [ ] **C5.8** Test with temporary real repositories and bare remotes: no-change,
  one valid committed fixup, dirty/staged/untracked state, unresolved conflict,
  open rebase/cherry-pick/merge, detached/wrong branch, merge commit,
  out-of-range commit, normal push, lease rewrite, stale lease, uncertain push
  reconciliation, duplicate/misplaced fixups, output bounds, and argv safety.
- [ ] **C5.9** Run `mvn verify`.

Exit: the program can accept or reject actual agent-owned local history and
publish an accepted head once with an exact lease.

## Checkpoint 6 — Complete reconciliation loop and restart recovery

- [ ] **C6.1** Implement `advance(runId)` as the locked ordered reconciliation
  and make it perform at most one side effect: expire/finish, reconcile a
  journal, observe a turn, inspect Git, push, rerun, start one turn, run the
  final check, pause, or complete. Serialize advancement per run and use
  optimistic store updates across processes/restarts.
- [ ] **C6.2** Select an actionable completed failed job for the exact head even
  when other jobs are running. Persist its latest-attempt evidence and bounded
  logs, update memory best-effort, and consume one of at most three fix attempts
  only when the idempotent turn is accepted.
- [ ] **C6.3** After a completed turn, branch only on `CiGitInspection`: ready is
  journaled and pushed; no-change journals then requests an eligible failed-job
  rerun; invalid pauses with Git diagnostics. Agent text, claimed local checks,
  and claimed stale CI cannot alter these branches.
- [ ] **C6.4** Bound no-change recovery to three reruns for one unchanged head.
  Journal before `rerunFailedJobs(...)`, reconcile acceptance from the later
  GitHub attempt number, reset the per-head count only after an observed new
  head, and pause when rerun is unavailable/rejected/exhausted.
- [ ] **C6.5** Monitor the pushed SHA: wait while there is no actionable result,
  start another fix attempt for a failure while budget remains, finish
  `MAX_ATTEMPTS` when exhausted, finish `STALE_HEAD` for an unrelated remote
  head, and on green run the one persisted final fixup-position check.
- [ ] **C6.6** On a valid green head, record `GREEN` and call
  `AgentCompletionService.complete(...)` with verified summaries/metrics. On an
  invalid final check, pause `INVALID_FIXUP_POSITION`; do not create an
  automatic organization/correction turn.
- [ ] **C6.7** Update CI memory best-effort after stored failure evidence, Git
  inspection, push reconciliation, rerun observation, and later CI result.
  Warning persistence may affect display only and never the next action.
- [ ] **C6.8** Add a small scheduled monitor in `app` that selects bounded
  non-finished batches and calls `advance(...)`; it owns no state machine and
  does not create runs. Make interval/batch configurable with conservative
  defaults and prevent overlapping advancement of one run.
- [ ] **C6.9** Implement restart cases for live-turn interruption, uncertain
  push, uncertain rerun, completed-but-uninspected turn, authorization expiry,
  final-check replay, and active/paused run reconstruction. There is no blind
  retry and no automatic recovery turn after interruption.
- [ ] **C6.10** Add orchestration tests for the complete decision matrix,
  including early failure, slow unrelated jobs, compile failure priority in the
  prompt, empty result then rerun, stale false/true claims in agent prose,
  repeated failures, all attempt/rerun limits, cancellation at each write
  boundary, expiry, concurrent advance, and all restart journals.
- [ ] **C6.11** Run `mvn verify`.

Exit: the bounded fix/push/monitor loop is deterministic, restart-safe, and
never controlled by agent output.

## Checkpoint 7 — Local timeline, HTTP API, and final proof

- [ ] **C7.1** Implement `PullRequestTimelineService.record(...)` as one
  idempotent local write after a reconciled push. Add a stable local event key
  to existing PR timeline persistence, preserve local events across full and
  incremental GitHub detail refreshes, and build event body/subject/paths/SHAs
  only from verified Git and the push journal. Do not post to GitHub and do not
  add AOP around unrelated methods.
- [ ] **C7.2** Test duplicate timeline recording, refresh preservation, PR
  deletion cleanup, ordering, and that a forged agent commit summary cannot
  enter verified event fields.
- [ ] **C7.3** Add the five locked `/api/ci-autofix/runs` endpoints under
  `com.bytequay.app.web.ci`, request/response models, validation, stable error
  mapping, and the same desktop-session/origin protection as agent endpoints.
  Require `Idempotency-Key` for start and message.
- [ ] **C7.4** Return durable status plus derived activity, expiry, exact
  head/base/check facts, ordered attempts, task/turn IDs, accumulated existing
  agent metrics, warnings, journals/push facts, final-check result, and summary.
  Mark agent messages display-only and never expose credentials, raw provider
  payloads, unrestricted filesystem paths, or unbounded logs.
- [ ] **C7.5** Compose CI services and the monitor in Spring without circular
  dependencies. Keep ordinary PR sync, existing agent HTTP/SSE, GitHub
  onboarding, and application startup working when no agent CLI is installed.
- [ ] **C7.6** HTTP-test author-only consent, same-repository enforcement,
  validation, session protection, idempotent start/message/cancel, list/get,
  conflict/not-found/provider errors, response bounds, and no accidental
  GitHub write from any read endpoint.
- [ ] **C7.7** Add one backend integration scenario with a deterministic agent,
  mocked GitHub Actions, temporary worktree/bare remote, SQLite, and restart:
  consented authored failing head -> fixup commit -> lease push -> green ->
  final position check -> local timeline event -> completed agent task.
- [ ] **C7.8** Add a second integration scenario proving hostile agent prose
  cannot control flow: a response claims green and asks for a force push while
  actual Git is invalid; the program pauses without a GitHub write.
- [ ] **C7.9** Run the final `mvn verify` gate and packaged sidecar startup smoke
  test. Record the passing command and date in the locked design's implemented
  status before removing this ledger.
- [ ] **C7.10** Audit the final diff against every non-goal and authority rule:
  no review-triage coupling, no extra durable state machine, no agent response
  parser, no GitHub comment/review/merge, no agent credential, no unleased
  push, no raw log in SQLite, no new Maven module, and no Flyway.
- [ ] **C7.11** After C7.1–C7.10 are checked, mark the locked design implemented
  with its verification evidence and remove this temporary ledger in the same
  final cleanup change.

Final exit: one explicitly consented, authored, same-repository PR can move
from an actionable failing GitHub Actions head to green, survive backend
restart, expose durable progress/metrics/summary for a future frontend, and
never let agent prose control the program.
