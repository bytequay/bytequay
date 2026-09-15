# Repository Context implementation checklist

Approved behavior is defined in [Repository Context](repository-context.md). Start the
design review with its [Overall flow](repository-context.md#2-overall-flow).
This temporary file tracks delivery; it does not define a second architecture.

Implementation has started with the notebook values, local settings persistence,
editing API, and discussion-source binding. Automatic preparation, review input,
the settings/display UI, and provider acceptance remain pending. The stored
default-on preference does not yet change review execution. Unchecked items are
pending, even when related infrastructure already exists.

## Working rules

- Keep the first version within the existing review/task/worktree flow and
  SQLite. Reuse cached application data before selected remote hydration.
- Recheck the current tree and relevant canonical contracts before each
  implementation checkpoint. Preserve unrelated work.
- Put behavior changes in the design; put commands, results, evidence paths,
  and blockers here, without a dated progress diary.
- Check an implementation item only when it exists and its relevant gate passes.
  A mock, compiling interface, skipped test, or available live-test script does
  not establish provider behavior or better review judgments.
- Use fixtures and disposable Git repositories for default tests. Reuse the
  existing isolated, opt-in authentication setup for actual provider exercises.
- Do not publish findings or write to GitHub during verification without the
  user's direct action selecting the target and content.
- Measure the minimal implementation before adding storage, indexes, workers,
  automatic research, or another agent pipeline.

## CP0 — Review the design

- [x] Read the project instructions and inspect the existing data, review,
  provider, worktree, settings, and deletion boundaries.
- [x] Draft the concepts, overall flow, data contracts, matching, lifecycle,
  visibility, limits, JavaDoc interfaces, risks, and enablement proposal.
- [x] Create this checkpoint plan.
- [x] Complete independent consistency review and document/link/JSON checks.
- [x] User reviews the Overall flow, especially cold preparation inside the
  existing investigation and the separate optional Build/Refresh task.
- [x] Resolve requested design changes before implementing affected behavior.

Review focus:

1. Start review automatically provides context; no separate Load click.
   One workspace switch defaults on after acceptance and can disable automatic
   use for new runs. Saved notes and explicit notebook actions remain available.
2. A cold review generates missing notes during investigation. Later reviews
   reuse them; provider choice does not own or duplicate the notebook.
3. Explicit EMPTY is distinct from unbuilt, failure, and no matching entry.
4. Background synchronization changes source data, not knowledge or memory.
5. Comments enter analysis through existing user-started discussion. Update
   review at an unchanged accepted head retains its existing behavior.
6. Saved lessons require a user action. Exact provided/retrieved/cited evidence
   is visible; those labels never claim comprehension or causation.
7. First version covers PR reviews and an explicit context builder. Ordinary
   development, subagents, automatic comment triage, and background research
   remain deferred.

Exit: agreed overall flow and any resulting contract edits are recorded in the
design. Approval here concerns the design, not evidence that a feature works.

## CP1 — Notebook values and local persistence

- [x] Add bounded core values and the concrete application service without a
  new Maven module, generic tool framework, or context database.
- [x] Store the current notebook under the workspace settings key; distinguish
  absent, empty, and corrupt JSON without silently resetting corrupt content.
- [x] Persist useInReviews with default true and guarded settings edits.
  Switching off preserves content and does not launch or cancel work.
- [x] Implement explicit sources/scopes, stable entry IDs, source evidence,
  content and entry revisions, and generated/user ownership.
- [x] Implement edit, disable, removal suppression, explicit reset, and Save
  lesson; preserve the supporting excerpt before source/runtime retention.
  Removing an edited knowledge note retains suppression.
- [ ] Extend Reset when preparation records are introduced: clear the missing
  note's preparation so an old READY result cannot block regeneration.
- [x] Make source/scope edits and all entry mutations use one workspace lock
  and expected content revision. No model/network/Git work holds the lock or
  a database connection.
- [x] Apply notebook byte/count bounds and prune only unreferenced notebook
  evidence; reject invalid and stale local edits without replacing saved content.
- [ ] Preserve user content on failed or stale generated updates when generation
  is introduced in CP3.
- [ ] Add the review context JSON mapping to the current run entity/store;
  reuse its existing evidence map and optimistic update behavior.
- [x] Integrate explicit workspace deletion and retained-workspace behavior;
  ordinary runtime cleanup must not delete notebooks or review evidence.

Gate: persistence round-trips; missing/corrupt/empty distinction; stale editor
conflict; edited/disabled/removed generated-note protection; byte boundaries;
runtime-retention and workspace-deletion behavior. Use current schema ownership.

## CP2 — Local selection and bounded source reads

- [ ] Implement deterministic normalized path/term/scope matching and visible
  reasons, with stable ordering and evidence of budget omissions.
- [ ] Capture the local monitored baseline separately from frozen PR base/head.
  Compute relevant blob/tree/source inventory and dependency fingerprints.
- [ ] Add the narrow path-filtered Git history overload and reuse persisted
  commit-to-PR associations. Treat parsed PR-number suffixes as unverified hints.
- [ ] Reuse existing PR detail/diff, issue detail, review, and task services;
  fix the current issue-tool cache bypass within the same source-read boundary.
- [ ] Hydrate only selected missing/stale details through existing refresh paths.
  Expose missing sections, cache age, lookup bounds, and unavailable history.
- [ ] Register validated user/repository documentation roots with provenance;
  capture selected pages through the bounded reader and retain cited excerpts.
- [ ] Check every redirect and resolved destination, document versions,
  unsupported formats, truncation, response limits, and credential isolation.
- [ ] Label native-web/model-reported excerpts accurately when ByteQuay did
  not capture the original page itself.

Gate: a fully cached selection makes zero remote requests; a selected miss
uses the existing hydration service; fork/upstream identity cannot cross-join;
unchanged file/version inputs reuse notes; changed relevant inputs and added
source inventory invalidate them. Include local-first issue and document
redirect/size/failure regressions. No whole-site or whole-history fetch.

## CP3 — Shared preparation and explicit builder

- [ ] Admit one generation owner per workspace and join matching active inputs.
  Bound tool waits; display gaps when another scope/attempt prevents preparation.
- [ ] Resolve identical admission replay before expected-revision comparison,
  including when initial scope/root registration changed that revision.
- [ ] Persist accepted task/turn ownership and input fencing before generation.
  Derive execution status from existing tasks instead of a parallel job engine.
- [ ] Implement READY, EMPTY, FAILED and negative-result reuse. A source failure
  or essential truncation cannot be accepted as a successful empty extraction.
- [ ] Reject stale/cancelled owner callbacks and stale content revisions without
  losing user edits. Save notebook mutations with their bounded replay receipt.
- [ ] Keep review-local accepted evidence valid if shared notebook saving fails;
  commit notebook updates and the final review journal receipt in one short
  local transaction. Replay/reconciliation must expose failure without inventing
  a successful save or regenerating blindly.
- [ ] Start explicit Build/Refresh through current readiness, managed worktree,
  task, turn, completion, Stop, and recovery services.
- [ ] Save builder identity/purpose before the provider starts and use a
  policy-only review bridge. AUTO alone must not grant development authority.
- [ ] Reuse a matching request after double-click/retry/restart without creating
  a duplicate builder or resending an uncertain accepted turn. Preallocate its
  task ID in the binding and reject superseded request sequences, including
  when intervening EMPTY builds did not change the content revision.
- [ ] Explicitly complete the settled standalone builder and invoke permitted
  cleanup; an ordinary successful turn alone leaves the task open.
- [ ] Preserve prior good notes on failure; retain dirty work under existing
  cleanup rules. Do not add a background refresh queue.

Gate: concurrent cold reviews generate once; unrelated task/late callback is
rejected; user correction during generation wins; an EMPTY result is reused
until its inputs change or explicit retry; crash/stop/uncertain-launch recovery
does not leave an immortal claim or duplicate runtime. Prove the builder lacks
GitHub publication, credential-read, and development-delivery authority.

## CP4 — Review input, evidence, and fixed tools

- [ ] Capture and directly deliver the bootstrap in the existing investigation
  turn. Do not add an acknowledgement turn or rewrite provider history.
- [ ] Capture On/Off per run. Off bypasses only this feature's selection,
  automatic generation, extra input and tools; existing review behavior remains.
  Validation and old-run discussions use their source run's captured setting.
- [ ] Implement the closed read/prepare tools with backend-resolved task, turn,
  workspace, run, and code identity. Preserve the existing no-child profile.
- [ ] Extend the existing investigation report with the completed brief,
  actual preparation references, evidence and limitations; validate source
  identity/shape without claiming to validate semantic truth.
- [ ] Deliver the frozen brief to validation/evidence collection and preserve
  existing report repair, replay, coverage, incomplete, and cursor behavior.
- [ ] Bind old-card discussions to their source run and capture relevant new
  discussion evidence at actual queued/immediate turn execution.
- [x] Persist the Ask-agent message's source run/revision before start/enqueue,
  then attach delivery IDs. Exercise an idle question about an older card,
  rejected admission, accepted-but-error recovery, and queued execution.
- [ ] Add explicit retry and restart reconciliation for a saved Ask-agent
  message whose delivery remains uncertain, without resending accepted input.
- [ ] Persist exact supplied text, sizes/digests, source versions, omissions,
  delivery outcome, actual retrievals and citations. Preserve delivery uncertainty.
- [ ] Keep the small trusted instruction policy separate from untrusted notes;
  bound the whole composed transport input as well as context sections.
- [ ] Verify context reaches both provider transports without copying the
  notebook into a command-line argument or exposing raw provider JSON.

Gate: cold/warm review fixtures; cancelled/replaced/queued turn fencing; frozen
run and old-card identity; no new run on same accepted head; comment sync causes
no agent execution; byte omission is honest; supplied/retrieved/cited labels
derive from durable facts. Exercise each supported provider independently.

## CP5 — Friendly notebook and review display

- [ ] Add Repository context to the existing workspace settings page with
  Knowledge, Saved lessons, configured sources/scopes and preparation outcomes.
- [ ] Add the default-on Use repository context in reviews switch and show its
  captured state on each run. Explain new-run scope and retained saved notes.
- [ ] Add Build/Refresh, editing, disabling/removal and explicit reset using the
  existing task transcript for progress, Stop, failure and recovery navigation.
- [ ] Add Save lesson to the existing private discussion/response surface;
  use the same editor for user acceptance of text, evidence and scope.
- [ ] Extend Review details with Context used, exact provided text, reasons,
  versions, gaps, retrieved sources and links to cited findings/hypotheses.
- [ ] Use folded activity for reuse/build/join/failure. Keep no-match, unbuilt,
  ready-empty, failed and stale states understandable without technical JSON.
- [ ] Show elapsed time and available bytes/token/caching metrics without
  implying that a model read or understood everything it received.
- [ ] Preserve the display after reconnect, notebook corrections, source-cache
  eviction and task-runtime retention.

Gate: component tests for user actions and provenance states, then normal and
narrow-window visual inspection. Check the overall flow through real controls;
opening settings or inspecting a source list must not launch generation.

## CP6 — Judgment, performance, and provider acceptance

- [ ] Create a small human-reviewed case set with correct, stale, irrelevant,
  missing and deliberately wrong notes, using fixed PR/code identities.
- [ ] Compare review behavior with context disabled and enabled on the same
  cases; record evidence quality, false positives, missed known issues and
  explicit uncertainty. Use the workspace switch with fresh independent tasks
  and provider conversations per arm; same-head Update is not a fresh run.
  Include small PRs covering distinct behaviors. Citation counts alone are not
  a quality metric.
- [ ] Verify a false saved rule cannot establish a defect by itself; exercise
  original-source/code checks, counterevidence, corrections and future exclusion.
- [ ] Exercise source prompt injection without granting new tool/publication
  authority or accepting reference text as managed instructions.
- [ ] Verify Claude native-memory settings and supported Codex behavior. Disable
  autonomous cross-task memory where supported; otherwise document and test the
  limitation before making the lane available. Never claim to disable pretraining.
- [ ] Measure cold and warm overhead: model turns/tokens, elapsed preparation,
  source requests, notebook/run bytes, SQLite wait/write duration, and peak
  process memory/CPU under simultaneous review/task activity.
- [ ] Record unknown or unavailable metrics explicitly. If contention exists,
  fix transaction length/write frequency before proposing separate storage.
  Measure existing source-hydrator locks separately from notebook mutation locks.
- [ ] Confirm bounded documentation capture and one workspace generation owner
  under cancellation, concurrency and repeated explicit refresh.
- [ ] Run live cold then warm review for each lane intended for enablement,
  plus an explicit builder and a new-comment discussion on an existing card.

Gate: no known unsupported note-only finding or cross-run evidence acceptance;
no duplicate generation or write-lock held across external work; bounded payloads
and measured overhead acceptable for the existing desktop workflow. This is a
small acceptance sample, not a guarantee that review judgments are correct.

## CP7 — Required checks and enablement

- [x] In `backend/`: `mvn verify` with the checked-in Checkstyle gate for the
  current increment; full-run failures and successful affected-suite rerun are
  recorded below. Repeat for subsequent implementation changes.
- [x] In `frontend/`: `npm run lint`, `npx tsc --noEmit`, `npm test`, and
  `npm run package` for the current increment. Repeat for material changes.
- [x] Record exact results for the current increment and any existing/unresolved
  failures; do not mask unrelated tests or treat skipped authentication checks
  as acceptance.
- [ ] Complete the provider-specific acceptance above before enabling that lane.
  Ship default on and keep the workspace Off switch for ordinary new reviews.
  Preserve saved notebook/run data; do not run comparisons for users automatically.
- [ ] Reconcile Agent Review, Agent Service, Managed Agent Skills, task UI,
  persistence, and workspace-deletion descriptions with the shipped behavior.
- [x] Review this increment and remove speculative scaffolding, duplicate source
  caches, redundant status models and unsupported completion claims. Repeat
  before final enablement.

Exit: agreed behavior is implemented, required checks and intended live lanes
pass, UI evidence is reviewable, and limitations are explicit. This temporary
file can then be removed or retained under the repository's normal design policy;
it must not become a second source of product behavior.

## Evidence and open items

Record exact commands/results and evidence paths as checkpoints are completed.
Required checks below describe the current increment, not full feature acceptance.

- User authorized implementation of the agreed flow and default-on workspace switch.
- The design was committed as `42ea674d9` (`Define repository context for agent
  reviews`). Implementation is isolated on `codex/repository-context` in
  `.worktrees/repository-context`.
- The first increment uses the existing `AppSettingsStore` and SQLite adapter;
  there is no new database or schema entity. It adds local configuration, note
  editing, enable/disable, removal/reset, explicit saved lessons, and workspace
  deletion integration. GET does not build notes or create a settings row.
- The current notebook intentionally omits preparation/build/execution fields
  until CP3 provides their behavior. No placeholder builder endpoint is exposed.
- Real-provider, performance, and review-quality comparisons have not started.
- Independent integration/storage reviews resolved preparation replay ordering,
  suppression/reset, source page selection, atomic review save, builder identity
  and completion, prelaunch discussion binding, and frozen-context supplements.
- Python document checks passed: all seven JSON examples parse; local Markdown
  targets/anchors, fence pairs, whitespace, and absence of conflict markers pass.
  External documentation entry links resolve. `git -c core.fsmonitor=false diff
  --check` passed; a separate whitespace check covered the new untracked files.
- No user-data database or live provider was used during verification. Test
  databases and repository fixtures are disposable.
- Independent implementation review covered revision locking, serialization,
  provenance, deletion, required HTTP fields, and discussion binding. Copied
  review evidence retains its original source/provenance/explanation; reviewed
  HEAD is explicitly labeled and is never substituted for a source version.
- Backend: ran `mvn -B verify` with the checked-in gates. The sandboxed attempt
  could not inspect process groups; the unrestricted full run reached all
  modules. It exposed final-record mocks in the new notebook test fixture and
  three existing `TestAgentHttpApi` continuation timeouts. The fixture now uses
  real records. No production workaround, skipped test, timeout increase, or
  mock-maker change was introduced.
- Backend rerun: `mvn -B -pl app -am verify
  -Dtest=TestRepositoryContextService,TestAgentHttpApi
  -Dsurefire.failIfNoSpecifiedTests=false` passed both affected suites and the
  build gates. Full-run plus rerun reports contain 2,205 passing tests with no
  remaining errors, failures, or skips. This is combined evidence, not a claim
  that the first full invocation passed. Evidence:
  `/private/tmp/bytequay-repository-context-backend-verify-final.log` and
  `/private/tmp/bytequay-repository-context-backend-verify-rerun.log`.
- Relevant passing suites: notebook service 13, HTTP controller 9, real SQLite
  persistence 4, workspace deletion 11, and review service 59. Persistence
  coverage includes service recreation and unwatch; it is not a manual app
  restart or provider acceptance exercise.
- Frontend `npm run lint` and `npx --no-install tsc --noEmit` passed. `npm test`
  passed all 80 files / 901 tests after rerunning Electron fixtures with normal
  process access; sandboxed Electron launches had aborted. Evidence:
  `/private/tmp/bytequay-repository-context-lint.log`,
  `/private/tmp/bytequay-repository-context-types.log`, and
  `/private/tmp/bytequay-repository-context-frontend-tests.log`.
- Frontend `npm run package` passed and produced
  `frontend/out/ByteQuay-darwin-arm64/ByteQuay.app`. The sandboxed attempt could
  not resolve GitHub for Electron's packaging artifact; the normal-access
  rerun succeeded. Evidence:
  `/private/tmp/bytequay-repository-context-package-final.log`.
  Dependencies and the pinned CodeGraph bundle are reused through local
  ignored links; package manifests and lockfiles are unchanged.
- Next: finish CP1's review-run context mapping, then local selection/source
  reads in CP2. Preparation outcomes, generation fencing, automatic review
  delivery, notebook UI, and the small-PR On/Off comparison remain pending.
