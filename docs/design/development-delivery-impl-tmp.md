# Development Delivery Implementation Checklist

Approved behavior is defined in [Development task delivery](development-delivery.md).
This is a temporary implementation tracker, not a second design. Checkpoints
organize engineering work; they are not runtime phases or persisted states.
Implementation is in progress. An unchecked box means its behavior and
verification are still pending, even where reusable infrastructure exists.

## Scope and working rules

- [x] Confirm the approved MVP: default-on Auto commit, Auto draft PR, and
  Auto CI fix; the agent commits/pushes/creates the draft; ByteQuay advances
  follow-ups, verifies results, and coordinates existing CI Autofix.
- [x] Confirm question-first gating, one in-process coordinator, and failure
  instead of whole-flow recovery after backend restart.
- [x] Before implementation, reread the repository instructions, canonical
  delivery design, and relevant agent, UI, settings, CI, and rebase contracts.
  Recheck the working tree and preserve unrelated changes.
- [x] Reuse existing turn execution, managed instructions, fixed local-tool
  transport, task locks, settings storage, Git/GitHub clients, and CI service.
  Keep the coordinator in `app`; keep GitHub dependencies out of agent core.
- [x] Add no workflow framework, delivery table, phase enum, durable delivery
  claim, step journal, recovery loop, migration system, or new dependency.
- [x] Treat visible settings on explicit user submission as scoped authority.
  Keep provider approvals and authentication checks; do not add another routine
  publication confirmation or let generated text expand authority.
- [x] Mark an implementation item complete only after its behavior exists and
  its relevant check passes. Record commands, outcomes, and evidence below;
  do not add dated logs or claim skipped tests passed.
- [x] Keep each change focused and buildable. Checkpoint names do not belong
  in commit messages. Do not release the default-on chain partially implemented.
- [x] Use disposable Git repositories and fixture providers/GitHub responses
  for automated tests. Any live GitHub smoke write needs a separately selected,
  user-authorized test target; this planning request authorizes no publication.

## Checkpoint overview

- [x] CP1 — Settings, root readiness reporting, and question presentation.
- [x] CP2 — Live coordinator, admission, and failure foundation.
- [x] CP3 — Verified commit and draft-PR follow-ups.
- [ ] CP4 — Existing CI integration, child ownership, and deletion.
- [ ] CP5 — Green gate, source synchronization, promotion, and final checks.

Complete these in order. Focused tests and UI accompany each checkpoint.
CP5 is the release gate for the complete default-on behavior.

## CP1 — Settings and root readiness

Starting points: `AppSettingsStore`, `AppSettingsService`, task/turn settings,
`AgentSkillService`, both CLI drivers, `SettingsWindow`, and `AgentTaskScreen`.

- [x] Add the three default-on settings through existing application settings
  storage and HTTP/IPC contracts. Copy defaults to new tasks, support task
  overrides, and snapshot the selection when each user turn is accepted.
- [x] Add Settings → Agents → Workflow and the task Automation control.
  Distinguish Automation from permission mode Auto. Support Local only for
  one submission; label active-run setting edits For future turns.
- [x] Add the fixed `report_development_result` local tool for the current
  root turn in both Codex and Claude. Store typed readiness, question text,
  and optional `stopAfter` on existing turn/event data.
- [x] Reject child, wrong-task, stale-turn, and invalid reports. A report
  cannot enable a setting, choose a destination, or perform a remote write.
  Reuse existing provider tool transports without building a general framework.
- [x] Add managed instructions to report readiness before ending, report false
  when a user decision remains, honor narrower user limits, and leave unfinished
  work alone. Completed work may be committed according to accepted settings.
- [x] Show the blocking question in the existing transcript/composer flow.
  Do not infer readiness from final-message wording or add native questionnaire
  cards for this MVP. Existing approvals/native input remain execution gates.
- [x] When all three settings are off, retain ordinary task behavior: no
  mandatory report, automatic continuation, or delivery fail-and-delete policy.

Acceptance checks:

- [x] Verify defaults, task overrides, accepted snapshots, future-only edits,
  and the independent toggle combinations. Generated-turn inheritance is
  checked in CP2, where the dispatcher is introduced.
- [x] Verify both provider bindings and root identity validation. Missing,
  malformed, or unsupported reporting never becomes implicit readiness.
- [x] Verify the durable question displays before the composer even with
  unloaded history, and clears for an active answer or fresh ready result.
  Blocking publication with dirty files, commits, or an existing PR is checked
  in CP2/CP3, where follow-ups begin.
- [x] Verify typed `stopAfter` validation, persistence, and managed narrowing
  instructions. Preserve existing tool-approval and authentication behavior,
  including Local only on authentication retry. Runtime narrowing and
  leave-draft enforcement remain acceptance checks in CP2/CP3/CP5.

Exit: both providers can distinguish finished work from a question, and the
accepted automation selection is visible and immutable for that submission.

Evidence:

- `mvn verify` passed across all backend modules, including Checkstyle,
  policy/readiness persistence, atomic report storage, HTTP contracts, and
  provider/broker tests. Existing opt-in tests remained skipped.
- `npm run lint`, `npx tsc --noEmit`, and `npm test` passed; 751 frontend tests
  passed in 64 files. The Chromium bridge test covers first-turn Local only
  and retaining the accepted choice through authentication retry.
- `npm run package` produced the macOS arm64 package successfully.
- The pinned Codex `0.151.0` compatibility script and generated binding fixture
  passed. Provider behavior is covered with fixtures; no live-model or GitHub
  write smoke was performed.
- Relevant tests: `TestDefaultAgentTurnService`, `TestSqliteAgentStores`,
  `TestDevelopmentResultBridgeResolverImpl`, `TestInteractiveAgentTaskCreator`,
  `TestClaudeDevelopmentResult`, `TestCodexDevelopmentResult`,
  `developmentAutomation.test.tsx`, and `developmentAutomationBrowser.test.ts`.
- Canonical design implementation claims match the foundation. `git diff
  --check` and relative documentation-link checks passed before commit.

## CP2 — Live coordinator and failure foundation

Starting points: `DefaultAgentTurnService`, existing queue/task admission,
`DefaultAgentCompletionService`, `AgentStartupRecovery`, and a small
`DevelopmentFollowupCoordinator` implementation in `app`.

- [x] Add a live terminal-outcome notification, including failure. Advance only
  after success and verified provider/descendant shutdown. Cleanup uncertainty
  must fail the continuation without releasing an unproven writer.
- [x] Reserve one in-memory task owner under the existing task lock before
  normal queue dispatch. Keep coordinator Git inspection and continuation waits
  outside that lock; reuse the existing serialized provider-launch path.
  Send, queue start, and Complete must respect admission. Manual CI attachment
  shares branch ownership in CP4, where published PR identity is available.
- [x] Run the readiness/queue/ownership gate as one asynchronous continuation.
  Internal generated turns carry trusted origin/ownership metadata and resolve
  its wait; they cannot recursively launch another coordinator. Commit and
  publication dispatch from that function is implemented in CP3.
- [x] Carry the original accepted settings and immutable task-worktree scope
  into follow-ups, including repository, branch, starting commit, and selected
  CI provider. Verify that later task-default edits cannot change a generated
  turn's model, permission, or automation selection. PR base/push routing is
  captured with the accepted Git baseline in CP3; CI readiness at attachment
  is checked in CP4 without fallback.
- [x] Exclude CI, cherry-pick, review-owned tasks, helpers, and child agents.
  Replay, page load, terminal retry, and duplicate callbacks must not dispatch
  follow-ups; reuse existing request identifiers where applicable.
- [x] Check readiness before every automatic mutation. On a question, release
  stopped execution ownership, clear the unfinished marker, and wait normally.
  Pause previously queued instructions rather than treating them as an answer.
- [x] Start answers as ordinary user turns. Their fresh report can release the
  hold without additional file edits; inspect earlier pending work again.
- [x] When preaccepted user work takes precedence, atomically hand admission
  to its FIFO turn and end this continuation. Do not retain an owner that
  prevents the queued turn being awaited from starting.
- [x] Add only the task-level unfinished-continuation marker needed to detect
  lost execution, with existing failure text. Set before dispatch; clear on
  settlement or normal input wait. Store no phase, next action, or resume point.
- [x] On backend restart, fail lost continuations before serving new work.
  On stop/failure, revoke authority and stop writers; reuse existing process
  cleanup and task failure handling. Expose no delivery resume/retry action.

Acceptance checks:

- [x] Verify completed-but-questioning turns never advance with either dirty
  files or commits, and an explicit answered turn can deliver unchanged pending work.
- [x] Verify pending approvals, missing reports, provider failure, and cleanup
  failure cannot start follow-ups. Waiting for an answer is not task failure.
- [x] Verify `stopAfter` only narrows the accepted next action; no report can
  enable a disabled setting or expand the original authorization.
- [x] Verify simultaneous Send, queue, and Complete; atomic FIFO handoff;
  duplicate terminal notifications; and generated-turn non-recursion.
- [x] Verify a renderer reload leaves the backend continuation running, while
  backend loss fails it without replay. A settled question remains answerable.

Exit: the backend owns progression exactly once, user input wins over Git
facts, and lost execution cannot restart the delivery chain.

Evidence:

- `mvn verify` passed across all backend modules: 1,627 tests, no failures,
  errors, or skipped cases, including Checkstyle and the application build.
- After adding the disabled-setting regression, `mvn -pl agent-core -am
  -Dtest=TestDefaultAgentTurnService -Dsurefire.failIfNoSpecifiedTests=false
  test` passed all 93 turn-service tests. No production code changed after
  the full verification run.
- Core and app regressions cover completion/read ordering, trusted generated
  settings, narrowing, FIFO launch failure, question/answer gating, cleanup
  failure, cancellation races, shutdown, and failure after backend loss.
  Relevant tests: `TestDefaultAgentTurnService`, `TestAgentRuntimeRegistry`,
  `TestDefaultAgentCompletionService`, `TestDevelopmentFollowupCoordinator`,
  `TestAgentStartupRecovery`, and `TestAgentHttpApi`.
- `TestSqliteAgentStores` verifies unfinished-marker and generated-origin
  persistence. The new non-null marker also has a false schema default;
  adding it to an existing populated SQLite table preserves existing tasks.
- `npm run lint`, `npx tsc --noEmit`, and `npm test` passed; 755 frontend tests
  passed in 64 files. The Chromium bridge test covers reload, blocked Send and
  Complete, Stop automation, and marker release through lifecycle refresh.
- `npm run package` produced the macOS arm64 package with the verified backend
  jar. No live-model or GitHub-write smoke was performed.
- Canonical design claims match this foundation. `git diff --check` and all
  49 relative documentation-link checks passed. Commit/publication dispatch,
  exact remote routing, and CI ownership remain explicit later checkpoints.

## CP3 — Commit and draft publication

Starting points: coordinator, existing Git/worktree and GitHub services, and
the manual draft-PR prompt and turn rendering in `AgentTaskScreen`.

- [x] Capture the accepted user turn's starting Git facts and exact target
  PR base/push routing before any delivery write. Inspect actual ownership,
  branch/HEAD, staged/unstaged/untracked files, open Git operations,
  deliverable commits/diff, remote ref, and exact matching PR.
- [x] Compare against the turn baseline and observed published revision.
  Distinguish an unchanged read-only turn from earlier work explicitly released
  by an answer. An existing PR alone must not trigger another delivery/CI run.
- [x] With Auto commit enabled and finished dirty work, request at most one
  normal commit turn. The agent chooses focused commits and repository-convention
  subjects/bodies. ByteQuay rechecks actual history and cleanliness afterward.
- [x] With Auto commit disabled and dirty work, stop locally; do not publish
  only the committed subset. With no deliverable changes, finish without a PR.
- [x] With Auto draft PR enabled, request at most one publication turn for the
  exact clean revision. This turn never commits. The agent pushes and uses
  `gh pr create --draft`, following the repository PR template.
- [x] Enforce the authorized destination and expected remote absence/SHA at
  push time. Reuse the exact open PR; stop on closed, merged, ambiguous, or
  externally changed results. Verify remote SHA and PR identity independently.
- [x] Apply the same readiness/approval/shutdown checks to generated turns.
  Questions preserve files/commits and return control for a normal user answer.
- [x] On failed or uncertain commit/push/create, stop visibly without an
  automatic retry. Keep known branch/PR references, or show Remote result unknown.
  Never roll back remote results or quietly attach a replacement task to them.
- [x] Show ByteQuay · Auto commit and ByteQuay · Create draft PR in normal
  turn history. Replace runtime Ready with Idle and add observed file/commit,
  HEAD, push, and PR facts. Keep missing local checks honest, such as missing Bun.

Acceptance checks:

- [x] Use disposable real repositories for clean, staged, unstaged, untracked,
  already-committed, no-change, open-operation, and wrong-ownership cases.
- [x] Verify one commit/publication request maximum, question/answer inside
  either follow-up, disabled-setting behavior, and actual post-turn inspection.
- [x] Verify false readiness prevents publication with dirty files, local
  commits, and an existing PR. Check `stopAfter` at both commit and publication
  boundaries, including when a follow-up itself asks a question.
- [x] Verify existing-PR reuse, fork publication routing, wrong base/head,
  remote changes between inspection and push, and uncertain PR-create outcomes.
- [x] Verify no second PR on later task revisions, no publication from replay,
  and no unexplained success when a prerequisite or validation is unavailable.

Exit: a completed development request yields truthful local/remote facts and,
when enabled, a verified draft through bounded ordinary agent turns.

Evidence:

- `mvn verify` passed across all seven backend reactor modules, including
  Checkstyle and the repackaged sidecar: 1,687 tests, zero failures, errors,
  or skips. Core acceptance/shutdown coverage includes all 97 turn-service tests.
- `TestDevelopmentFollowupCoordinator` covers 37 cases: bounded generated
  requests, prior-question and FIFO pending-work inheritance, source/generated
  stop limits, exact existing-PR reuse, wrong-base/closed/ambiguous refusal,
  changed remote/local revisions, missing evidence, cancellation, and uncertain
  publication without retries.
- `TestDevelopmentGitInspector` covers 12 disposable real-Git scenarios,
  including dirty-file fingerprints, ignored output, local commits, merge-base
  diffs, verified ownership/routing, fork destination, and absent/stale remote
  leases against a bare fixture remote. The exact object is pushed even when
  local HEAD subsequently advances. GitHub identity fixtures cover exact head
  validation, incomplete responses, and pagination bounds.
- `TestDevelopmentFactsResponse`, `TestAgentHttpErrors`, and `TestAgentHttpApi`
  cover factual unknown-versus-absent results, historical PRs with a deleted
  remote ref, exact workspace scope, and repeated GET without delivery writes.
  The HTTP fixture retains real local Git inspection and stubs remote reads.
- `npm run lint`, `npx tsc --noEmit`, and `npm test` passed: 762 frontend tests
  in 65 files. The Chromium bridge regression covers generated-turn labels,
  question precedence, settled facts refresh, and unavailable remote evidence.
  A merged PR with no current remote branch retains its link and never claims
  that it was never pushed.
- The full build exposed a reproducible pre-existing process-test teardown
  race: the final receipt could arrive during temporary-directory deletion.
  One test-only await now waits for `process.exit()` before cleanup, as its
  neighboring test already does. All 26 supervisor tests and the subsequent
  full build passed; production process handling is unchanged.
- Native publication uses authenticated `gh`, branch-only heads for direct
  repositories, and owner-qualified heads for forks. The installed CLI's
  organization-owned-fork limitation is explicit in the canonical design;
  failures stop without an alternate publisher or retry. No live-model or
  GitHub-write smoke was performed.
- Canonical delivery and agent-service contracts describe the implemented
  accepted baseline, generated turns, and read-only HTTP/IPC facts surface.
  CI attachment, child ownership, green validation, synchronization, and
  promotion remain CP4–CP5; this checkpoint is not the complete-chain release.
- `npm run package` produced the macOS arm64 app with the freshly verified
  backend jar. Build outputs stayed ignored and the temporary dependency
  symlink was removed without changing the original dependencies.
- `git diff --check` passed, and all 18 relative links in the changed design
  documents resolved. Final review found no new runtime phase/state service,
  delivery recovery journal, dependency, or live remote mutation.


## CP4 — CI integration, ownership, and deletion

Starting points: `DefaultCiAutofixService`, CI/rebase monitor jobs,
`BranchRebaseMonitorJob`, `AgentStartupRecovery`, and task Delete handling.

- [ ] Start or reuse existing CI Autofix for the verified PR/head. Preserve
  current eligibility: open authored same-repository PR and a ready CI-capable
  provider. Recheck the accepted CI provider's readiness without fallback at
  attachment. Show the draft and reason when CI is unsupported or unavailable.
- [ ] Preserve three repair attempts, three reruns per unchanged head, and
  the eight-hour window beginning at continuation activation. Follow-ups never
  renew it; an unchanged request cannot restart an exhausted run.
- [ ] Store the optional parent task ID before a delivery-owned CI run can
  start. Require the matching live coordinator for all its advancement/write
  paths: repair turns, rebase, rerun, push, and explicit resume/message actions.
- [ ] Drive these children through the existing CI service from the coordinator.
  Exclude owned CI and linked rebase work from independent schedulers/recovery.
  Keep standalone CI behavior and recovery unchanged.
- [ ] Keep source branch ownership while CI may write. Disable new source
  Send/queue start and Complete; retain composer drafts. Existing CI question
  controls can answer within that run without competing source development.
- [ ] Add derived CI activity, run navigation, attempt counts, and Stop controls.
  New source work waits; stopped or unrecoverable automation fails the MVP.
- [ ] Revoke parent authority before stopping children. During startup, stop
  known child/provider processes and fail lost parents before serving or
  scheduler writes. Never replay an orphaned push journal.
- [ ] Extend explicit Delete to stop and remove linked CI local tasks/worktrees
  after writers are proven stopped. Preserve remote branches/PRs and show their
  references. Failure alone must not delete local work; unknown writers block Delete.

Acceptance checks:

- [ ] Verify new and reused CI runs, original deadline/budget retention,
  unsupported providers/forks, and manual attachment exclusion on the same branch.
- [ ] Verify every scheduler/manual child entry point rejects advancement
  after parent stop, failure, or restart, including rebase and push recovery.
- [ ] Verify source-turn/CI-write races and CI question handling without a
  competing writer. Confirm standalone CI regression behavior remains intact.
- [ ] Verify cascade Delete, dirty owned worktrees, proven-stop requirements,
  and remote-result preservation. An in-flight remote write may remain unknown.

Exit: the existing CI loop can be attached without any child continuing after
its parent loses authority. Do not enable automatic CI before these checks pass.

Evidence: Pending.

## CP5 — Green gate, source synchronization, and release

Starting points: `CiHeadSnapshot`, GitHub checks/status and ready-for-review
integration, coordinator, managed Git operations, and task result rendering.

- [ ] Correct the shared green predicate: passing observed jobs cannot hide
  pending workflows with no jobs. Require completed, nonempty relevant CI evidence.
- [ ] Before promotion, verify required checks/statuses, current PR/head/base,
  and accepted scope. Missing or unknown evidence waits. Report unsupported
  external-check failures and workflows that cannot run while draft.
- [ ] Keep ready promotion owned by the development coordinator, never a
  generic CI-green callback. Honor Auto CI fix and narrowed `stopAfter` intent;
  leave-draft requests retain draft status even after CI repairs succeed.
- [ ] Add ready-for-review mutation with fresh before/after observations.
  External-head races and uncertain responses fail visibly without promotion
  retry or automatic draft-status reversal.
- [ ] Once CI writers stop, synchronize the source to the exact verified
  published head. Require expected old HEAD, a completely clean worktree, and
  no active Git operation; fetch the exact object and perform the approved
  checked clean-worktree transition. Verify the result; never hard-reset dirt.
- [ ] Fail source synchronization visibly without private checkpoint refs,
  replay machinery, or automatic repair. Preserve the published CI result.
- [ ] Finish with actual local/remote/CI facts and retain the open task/worktree
  for later development. Questions take display precedence; failures show the
  failed action and Delete task and start again, with no Resume delivery control.

Acceptance checks:

- [ ] Regression: a passing workflow plus a queued workflow with no jobs
  remains waiting. Cover stale head, pending/failed/missing required checks,
  and unknown or third-party status results.
- [ ] Verify promotion authorization, leave-draft behavior, head/base races,
  uncertain mutation outcomes, and no promotion from standalone CI success.
- [ ] Use real Git to verify source synchronization after rewritten CI history,
  dirty/moved-source refusal, and synchronization failure with remote work retained.
- [ ] Exercise the complete default-on path: edit → commit → push/draft →
  CI failure → repair/push → green → ready, with synchronized open source task.
- [ ] Exercise question → answer without new edits → pending delivery,
  questions in generated turns, all toggles off and mixed settings,
  unavailable/exhausted CI, stop, backend restart, and Delete/start-over.
- [ ] Verify settings, question precedence, generated-turn labels, result
  facts, CI busy controls, and renderer reconnect through the actual UI bridge.
- [ ] Run `mvn verify` in `backend/`.
- [ ] Run `npm run lint`, `npx tsc --noEmit`, `npm test`, and
  `npm run package` in `frontend/`.
- [ ] Record any opt-in real-provider/live-PR smoke separately from fixture
  evidence, with its exact authorized target. Missing credentials are not a pass.
- [ ] Update canonical design implementation claims and relevant module/UI
  contracts to match the final code. Remove superseded intermediate behavior
  and tests rather than retaining compatibility scaffolding.
- [ ] Run `git diff --check`; review the final diff for unrelated edits,
  accidental recovery/state machinery, and unverified completion claims.

Exit: all checkpoints and required checks pass, default-on delivery is concrete
and observable, and the agreed question/failure boundaries hold end to end.

Evidence: Pending.

## Evidence and remaining work

CP1–CP3 are implemented and verified through backend, frontend, and packaging
checks.
The live coordinator now captures accepted Git/routing facts, dispatches bounded
commit and draft-publication turns, and verifies the actual local/remote result.
CI integration, shared branch ownership, synchronization, and promotion remain
pending in CP4–CP5.
Do not release the full default-on chain before the CP5 release checks pass.
Replace each Pending evidence entry with focused commands/results and relevant
test or artifact paths when verified. Record an unresolved blocker beside its
unchecked item; do not add a runtime recovery design to this tracker.
