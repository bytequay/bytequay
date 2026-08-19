# CI Autofix end-to-end test implementation checklist

This temporary file is the execution checklist for section 13 of
[CI Autofix Service Design](ci-autofix-service.md). Every E2E-test change must
belong to a checkbox below. Check an item only after its code and stated
verification pass. Do not add production extension points, a new Maven module,
or a scenario DSL to make the tests easier.

The implementation is complete only when every checkbox is checked, the final
backend verification passes, section 12 of the service design contains the new
evidence, and this temporary file is removed.

## Locked test boundary

- [ ] Keep all new code under
  `backend/app/src/test/java/com/bytequay/app/ci/e2e`, except the one
  package-access bridge
  `backend/app/src/test/java/com/bytequay/app/ci/CiAutofixMonitorTestDriver.java`.
- [ ] Use the existing `app` module and installed Spring/JUnit/AssertJ/Mockito
  dependencies; add no dependency or Maven module.
- [ ] Use real HTTP request filtering and mapping, CI Autofix/agent services,
  SQLite stores, runtime files, Git workspace inspection, Git publishing,
  timeline storage, events, metrics, and task completion.
- [ ] Replace only GitHub Actions, the external agent process, and authenticated
  GitHub identity with deterministic test values.
- [ ] Never use an agent final response to select a program branch. The driver
  supplies terminal turn state; the program must still decide from that state,
  inspected Git facts, stored authorization, journals, and GitHub snapshots.
- [ ] Use no `Thread.sleep`, scheduler wait, network call, GitHub credential,
  real Codex/Claude process, YAML scenario file, reflection into production
  state, or direct write to CI Autofix tables.

## Checkpoint 1 — Baseline and shared fixture

### 1.1 Record the existing safety net

- [ ] Run the current focused HTTP, monitor, and integration tests:

  ```text
  cd backend
  mvn -pl app -Dtest=TestCiAutofixHttpApi,TestCiAutofixMonitorJob,TestCiAutofixIntegration test
  ```

- [ ] Run `mvn verify` in `backend/` and record the elapsed time in the
  checkpoint commit or review note.
- [ ] Confirm the starting diff contains no unrelated user changes that the E2E
  work would overwrite.

### 1.2 Create the test package

- [ ] Add these package-private helpers and no more until a required journey
  proves another helper is necessary:

  ```text
  com.bytequay.app.ci.e2e
  ├── CiAutofixE2eHarness.java
  ├── E2eGitRepository.java
  ├── ScriptedAgentDriver.java
  ├── ScriptedGitHubActionsClient.java
  ├── TestCiAutofixEndToEnd.java
  └── TestCiAutofixRestartEndToEnd.java
  ```

- [ ] Add `CiAutofixMonitorTestDriver` in the mirrored production package. It
  contains only `tick()`, which delegates directly to the package-private real
  `CiAutofixMonitorJob.monitor()` method.
- [ ] Put reusable Spring test beans in a nested `@TestConfiguration` until both
  test classes need them; extract a separate configuration file only then.
- [ ] Configure the ordinary suite with `@SpringBootTest(webEnvironment = MOCK)`,
  `@AutoConfigureMockMvc`, and `@Execution(SAME_THREAD)`.
- [ ] Disable Codex, Claude, and the production deterministic lane; register
  only `ScriptedAgentDriver` with lane ID `ci-e2e` and the `CI_AUTOFIX`
  permission policy.
- [ ] Set a fixed desktop-session token, delay the scheduled monitor for one
  hour, use an isolated SQLite `create-drop` database, and use an isolated task
  runtime root.
- [ ] Keep one cached Spring context for every non-restart journey. Generate a
  unique repository name, PR number, database row ID, idempotency key,
  worktree, and bare remote per test.
- [ ] Reset only scripted queues and call ledgers in `@BeforeEach`; never clear
  another scenario's database or runtime directory.

### 1.3 Implement `E2eGitRepository`

- [ ] Create a real local worktree and a local bare `origin`; set
  `core.logAllRefUpdates=true` on the bare repository for branch-write
  assertions.
- [ ] Configure a GitHub-shaped HTTPS origin plus Git's local `insteadOf`
  mapping so production repository validation and push code remain active
  without network access.
- [ ] Provide small fixture operations for authored commits, target-branch
  commits, checkout, local/remote head reads, clean-status reads, and commit
  subject/order reads. Execute Git with argv lists through one bounded helper.
- [ ] Seed the real PR list/detail/timeline stores with the authored PR and its
  current head/base facts; do not seed CI Autofix or agent rows.
- [ ] Make fixture creation fail with the complete bounded Git output when any
  command fails.

### 1.4 Implement `ScriptedGitHubActionsClient`

- [ ] Implement the existing `com.bytequay.app.ci.GitHubActionsClient`
  interface as a primary test bean; do not add a production interface.
- [ ] Key scripts by the complete `PullRequestRef` (`owner/repository#number`),
  never by PR number alone.
- [ ] Queue explicit `CiHeadSnapshot` values. `inspect` returns the current
  value until the scenario advances it, so repeated production polling is
  deterministic rather than consuming snapshots accidentally.
- [ ] Store scripted raw job-log bytes with the real `TaskLogStore.writeBlob`
  and return the resulting bounded `BlobRef`, terminal error lines, byte count,
  and truncation facts.
- [ ] Record every `RerunFailedJobsCommand` before returning its scripted
  `CiRerunResult`.
- [ ] Fail immediately on an unknown PR, missing job log, missing rerun result,
  or write whose command does not match the scripted workflow/head/attempt.
- [ ] Expose read-only copies of inspect/log/rerun call ledgers for assertions.

### 1.5 Implement `ScriptedAgentDriver`

- [ ] Queue actions by full PR identity and bind the selected queue to the
  session's canonical worktree; fail on an unknown worktree or an unscripted
  turn.
- [ ] Support only actions required by section 13: complete without change,
  create/fold/reorder fixup, rebase/resolve, leave invalid Git, fail,
  interrupt, and block until cancelled.
- [ ] Let each action operate on the real worktree and emit provider-neutral
  message, command/file, lifecycle, and usage events through the real event
  sink.
- [ ] Return `AgentTurnResult.State` through the real `RunningTurn.completion`
  future. Use an already-completed future for normal turns and one
  test-controlled incomplete future for cancellation; start no polling thread.
- [ ] Allow an action to call `CiMemoryService.read` with its own task ID and
  record the query/result as the E2E representation of `read_ci_memory`.
- [ ] Make final response prose freely scriptable and label it untrusted in the
  test data; never parse it in the driver or harness.
- [ ] Expose read-only turn, memory-read, cancellation, and Git-action ledgers.

### 1.6 Implement `CiAutofixE2eHarness`

- [ ] Implement only the contract documented in service-design section 13.3:
  fixture creation, provider scripting, HTTP start/message/cancel, one tick,
  bounded drive, and snapshot.
- [ ] Send the real `X-ByteQuay-Session` and `Idempotency-Key` headers through
  `MockMvc`. Parse only public response DTO fields when asserting API results.
- [ ] Have `tick()` call `CiAutofixMonitorTestDriver.tick()` exactly once so the
  real monitor selects non-finished runs, enforces its batch boundary, isolates
  per-run failures, and calls the service. Do not call
  `CiAutofixService.advance()` directly from the harness.
- [ ] Have `driveUntil` stop on its predicate or fail after `maximumTicks`.
  Never sleep or wait for the real scheduler.
- [ ] On a tick-bound failure, print a bounded diagnostic containing run and
  attempt status, current turn state, fake call ledgers, local/remote SHA,
  concise Git status, recent event kinds, and runtime-file names. Exclude
  credentials and raw log bodies.
- [ ] Build `snapshot` from public services/stores plus filesystem and Git
  observations. It may read durable rows but must never mutate them.

### Checkpoint 1 exit

- [ ] Add a smoke journey that starts an authored failing PR through HTTP,
  creates an agent task/turn, and stops before publishing.
- [ ] Prove the session filter rejects a missing token in the same Spring
  context.
- [ ] Prove an unscripted GitHub or agent call fails immediately with the full
  PR/worktree identity.
- [ ] Prove one manual tick passes through the real monitor and reaches CI
  inspection/log preparation for the active run while the scheduled timer is
  disabled.
- [ ] Run the new smoke test and the three baseline test classes successfully.
- [ ] Commit the shared fixture only after these checks pass.

## Checkpoint 2 — Primary green journey

### 2.1 Build the authored history and CI script

- [ ] Create a PR with at least three authored commits so fixup placement is
  observable rather than trivially at the tip.
- [ ] Script a failed compile job plus an unrelated pending slow job for the
  same head. Assert the service starts a fix turn without waiting for the slow
  job.
- [ ] Supply a real raw compile log and assert it exists as a bounded task blob,
  is attached or path-referenced according to lane capability, and is absent
  from SQLite summaries/API responses.
- [ ] Script a later, different test failure for the pushed SHA and then a green
  snapshot for the second pushed SHA.

### 2.2 Drive two fix attempts

- [ ] First agent action fixes the compile failure, runs a represented local
  check, creates `fixup! <target subject>`, and places it immediately after the
  middle authored commit.
- [ ] Assert the program accepts the result from inspected Git facts, journals
  it, exact-lease pushes it, and records one published attempt/timeline event.
- [ ] Assert the later failure creates a new attempt instead of finishing from
  the first agent's prose.
- [ ] Have the second action search CI memory for the prior signature, record
  the returned bounded memory, and create or fold the next fixup in the correct
  position.
- [ ] Assert the final PR range preserves authored commit order, contains at
  most one fixup per target, and is clean and linear.
- [ ] Assert the second exact-lease branch update occurs and GitHub green, not
  agent text or local-check text, determines completion.

### 2.3 Assert durable output

- [ ] Assert one run, two attempts, exact input/pushed SHAs, failure signatures,
  Git results, check results, summaries, and warnings through the public GET
  response and durable store.
- [ ] Assert `ci-memory.md` contains both observed failures, root-cause/attempt
  notes where available, changed paths, and observed results without raw log
  contents.
- [ ] Assert agent events and non-zero usage totals are available through the
  real services and that agent final messages are display-only notes.
- [ ] Assert timeline event keys are stable and each published fix appears once.
- [ ] Assert the final fixup-position check is `VALID`, runs once for the final
  SHA, and the agent task is completed only afterward.
- [ ] Repeat the same start idempotency key and assert the same run is returned
  with no extra task, attempt, turn, log download, push, or timeline event.

### Checkpoint 2 exit

- [ ] `TestCiAutofixEndToEnd#fixesTwoFailuresAndCompletesGreen` passes without a
  sleep or live scheduler.
- [ ] The full non-restart class remains below 15 seconds at this checkpoint.
- [ ] Run the baseline focused tests and `mvn -pl app test` successfully.
- [ ] Commit the primary journey only after these checks pass.

## Checkpoint 3 — Bounds, safety, and interaction

Add one test method per journey unless sharing setup makes two assertions
materially clearer. Do not combine unrelated failures into a single opaque
scenario.

### 3.1 Start authorization

- [ ] Reject a PR whose author differs from the authenticated test user.
- [ ] Reject a fork PR.
- [ ] Reject a request missing any required consent flag.
- [ ] For every rejection, assert zero CI Autofix rows, agent tasks, job reruns,
  Git branch writes, and timeline events.

### 3.2 Empty-result handling

- [ ] Complete a turn with clean, unchanged Git and false success prose. Assert
  one journaled failed-job rerun, no push, and completion only after a later
  GitHub green snapshot.
- [ ] Repeat empty results for one unchanged head until the stored limit of
  three reruns is reached. Assert `RERUN_EXHAUSTED`, no fourth rerun, no push,
  and no dependence on the three different final messages.
- [ ] Assert each rerun command carries the exact PR, workflow run, head SHA,
  previous attempt, and stored authorization.

### 3.3 Invalid Git and repair

- [ ] Leave unresolved conflict markers/open Git state after the first turn.
  Assert the run pauses immediately, no journal or remote write is created, and
  no automatic repair turn starts.
- [ ] In one journey, send one user message through HTTP and make that recovery
  turn produce valid positioned fixup history and continue to green.
- [ ] In another journey, leave invalid Git again and assert
  `INVALID_GIT_RESULT`, no automatic follow-up turn, no failed-job rerun, and no
  push.
- [ ] Give both invalid turns final prose claiming CI is green and requesting a
  force push. Assert the program result is unchanged.

### 3.4 Moving branches

- [ ] Move the target branch and script the agent to rebase, resolve, and create
  correctly positioned fixup history. Assert the program accepts only the
  resulting clean/linear Git facts and exact-lease pushes the PR branch.
- [ ] Move the PR head in the bare remote after local inspection but before
  publish. Assert `STALE_HEAD`, preservation of the external commit, and no
  ByteQuay branch rewrite or timeline event.

### 3.5 User interaction and cancellation

- [ ] Pause a run, send a message through the real HTTP endpoint, and assert a
  turn starts on the same task/session with the supplied message.
- [ ] Repeat the message idempotency key and assert no duplicate turn. Let the
  turn create a valid fixup and drive the run to green.
- [ ] Start a controlled blocked turn, cancel it through HTTP, and assert its
  future becomes cancelled, the run finishes `CANCELLED`, and later ticks
  perform no rerun, push, or timeline write.

### 3.6 Final position check

- [ ] Reach green GitHub checks with a clean but misplaced fixup and assert the
  final check records `INVALID`, pauses, and starts no automatic correction
  turn.
- [ ] Tick repeatedly and assert the same final SHA is not checked twice and the
  task is not marked complete.

### Checkpoint 3 exit

- [ ] Every required non-restart journey in section 13.5 has a named test and
  exact negative assertions for forbidden reruns/pushes/timeline writes.
- [ ] The complete `TestCiAutofixEndToEnd` class passes in at most 15 seconds.
- [ ] Run `mvn -pl app test` successfully.
- [ ] Commit the safety/interaction journeys only after these checks pass.

## Checkpoint 4 — Full restart with an uncertain push

### 4.1 Build the restart harness

- [ ] Keep restart support local to `TestCiAutofixRestartEndToEnd`; do not add
  restart methods to the ordinary harness unless another test needs them.
- [ ] Open a complete first Spring context with `SpringApplicationBuilder`
  against explicit SQLite, runtime-root, worktree, and bare-remote paths.
- [ ] Register the same scripted GitHub/agent scenario objects as primary test
  beans in each context using an application initializer. Do not use static
  global lookup from production code.
- [ ] Use `spring.jpa.hibernate.ddl-auto=create` for the first context and
  `validate` for the second so the second context reads the first context's
  durable rows without migration behavior; delete its temporary database at
  test cleanup.
- [ ] Disable real lanes and the scheduled monitor in both contexts.

### 4.2 Simulate the lost result

- [ ] Add a test-only primary `CiGitService` decorator that delegates the first
  push to `DefaultCiGitService`, verifies the remote reached the journaled local
  SHA, and returns `UNCERTAIN` once to simulate a lost response.
- [ ] Assert the first context leaves a durable push journal and does not mark
  the attempt published.
- [ ] Close the first context cleanly without changing SQLite, task runtime, or
  either Git repository.
- [ ] Open the second context with the ordinary real `DefaultCiGitService` (the
  lost-response decorator disabled) and restore the scripted GitHub green
  snapshot for the remote SHA.
- [ ] Advance the recovered run. Assert the program reconciles remote head to
  the journaled local head, records the attempt as published, clears the
  journal, performs the final check, and finishes green.

### 4.3 Prove exact-once effects

- [ ] Assert the bare remote branch has one ref update for ByteQuay's fix, the
  expected final SHA, and no rewritten external commit.
- [ ] Assert one published-attempt record, one stable timeline event, one final
  fixup check for the SHA, one completed task, and no duplicate agent turn.
- [ ] Assert raw logs, CI memory, agent events, metrics, and summary created in
  the first context remain readable in the second.
- [ ] Assert rerunning recovery ticks is a no-op for remote and timeline state.

### Checkpoint 4 exit

- [ ] `TestCiAutofixRestartEndToEnd#reconcilesAPushWhoseResponseWasLost` passes in
  at most 8 seconds.
- [ ] Run both E2E classes together successfully.
- [ ] Run `mvn -pl app test` successfully.
- [ ] Commit the restart proof only after these checks pass.

## Checkpoint 5 — Consolidate and finish

### 5.1 Remove duplicate test scaffolding

- [ ] Compare every assertion in `TestCiAutofixIntegration` with the new E2E
  suite.
- [ ] Remove `TestCiAutofixIntegration` only after its reconstructed-service,
  real fixup/push/timeline, and hostile-prose/invalid-Git proofs are all present
  in the new suite.
- [ ] Keep `TestCiAutofixHttpApi`, `TestCiAutofixMonitorJob`, Git operation,
  memory, prompt, store, and domain tests; they own focused permutations that
  E2E should not duplicate.
- [ ] Delete unused fixture code and imports. Do not keep compatibility wrappers
  for the removed integration test.

### 5.2 Performance and flake gate

- [ ] Record elapsed time for both E2E classes and `mvn verify`.
- [ ] Keep non-restart E2E at or below 15 seconds, restart E2E at or below
  8 seconds, and backend `mvn verify` at or below 35 seconds on the reference
  development machine. If a target is missed, profile context starts and Git
  fixture setup before changing the architecture.
- [ ] Execute `TestCiAutofixEndToEnd` 100 consecutive times with no intermittent
  failure and attach the command/result to the checkpoint review or commit
  note. Do not add a permanent repeat script unless the command is reused by CI.
- [ ] Search the E2E package for `sleep`, live URLs, real agent lane IDs, direct
  CI table updates, unbounded loops, and parsing of final agent text; the search
  must find none.

### 5.3 Final verification and documentation

- [ ] Run `mvn verify` from `backend/` with zero Checkstyle, test, or packaging
  failures.
- [ ] Update section 12 of `ci-autofix-service.md` with the E2E scenario count,
  restart evidence, measured timings, and final backend test count.
- [ ] Confirm section 13 still matches the implemented package, helpers,
  journeys, and performance boundary; update the design if an approved simpler
  implementation was used.
- [ ] Confirm `git status --short` contains only intended E2E tests and design
  changes.
- [ ] Remove this temporary checklist after every checkbox above is checked.
- [ ] Commit with a message describing the verified end-to-end behavior, not a
  checkpoint or internal phase name.
