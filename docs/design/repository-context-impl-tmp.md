# Repository Context implementation checklist

Approved behavior is defined in [Repository Context](repository-context.md). Start the
design review with its [Overall flow](repository-context.md#2-overall-flow).
This temporary file tracks delivery; it does not define a second architecture.

Implementation includes the notebook, editing API, discussion-source binding,
and immutable per-run snapshots selected from local notes and history, including
bounded baseline input inventories and fingerprints. Selected PR/issue reads
reuse existing caches and refresh paths, retaining freshness and failure evidence. The
default-on preference now controls local snapshot capture; Off bypasses selection.
Snapshots are prepared before agent admission and exposed in the existing review
projection. Generation, direct context delivery, the settings/display UI, and
provider acceptance remain pending. Unchecked items remain pending even when
related infrastructure already exists.

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
- [x] Add the review context JSON mapping to the current run entity/store;
  reuse its existing evidence map and optimistic update behavior.
- [x] Integrate explicit workspace deletion and retained-workspace behavior;
  ordinary runtime cleanup must not delete notebooks or review evidence.

Gate: persistence round-trips; missing/corrupt/empty distinction; stale editor
conflict; edited/disabled/removed generated-note protection; byte boundaries;
runtime-retention and workspace-deletion behavior. Use current schema ownership.

## CP2 — Local selection and bounded source reads

- [x] Implement deterministic normalized path/term/scope matching and visible
  reasons, with stable ordering and evidence of budget omissions.
- [x] Capture the local monitored baseline separately from frozen PR base/head.
- [x] Capture bounded baseline blob/tree, source-configuration and dependency
  manifest fingerprints, with explicit absence and incomplete-read gaps.
- [ ] Use those identities in shared preparation reuse and compare applicable
  source/dependency versions with reviewed code. Manifest hashes do not resolve
  effective versions or establish remote freshness.
- [x] Add the narrow path-filtered Git history overload and reuse persisted
  commit-to-PR associations. Treat parsed PR-number suffixes as unverified hints.
- [x] Reuse existing PR detail/diff, issue detail, and review services;
  fix the current issue-tool cache bypass within the same source-read boundary.
- [ ] Add scoped task-summary discovery through existing task services.
- [ ] Hydrate only selected missing/stale details through existing refresh paths.
  Expose missing sections, cache age, lookup bounds, and unavailable history.
- [x] Verify selected PR metadata/activity/check reads, issue cache reuse and
  exact preview fallback. Keep related PR identity separate from frozen review
  code; retain source freshness in returned evidence.
- [ ] Add selected missing commit-association and exact related-diff hydration;
  current review diffs already come from the retained run.
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
- [x] Capture On/Off and exact prepared packet/evidence per run before startup
  or recovery admits its initial turn; reuse the saved choice on replay.
- [ ] Off bypasses only this feature's selection,
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
- [x] Add explicit retry and restart reconciliation for a saved Ask-agent
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
  current increment; record failures and any affected-suite reruns below.
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
- The snapshot increment adds nullable `context_json` to the existing review-run
  entity, captured together with its retained evidence in one optimistic update.
  Historical null differs from Off. Startup/recovery capture precedes the first
  accepted turn; replay, subsequent assignments and old discussions keep the run's
  saved choice. Its packet is prepared only; provider input remains unchanged.
- Local matching uses literal paths and normalized whole terms, with stable
  reasons and order. Incremental paths include reverts and binary changes; rename
  matching includes old and new paths. Unsupported or unavailable context paths
  produce gaps without blocking repository/text matching or starting a fetch.
- Discovery uses up to 20 literal paths and 50 path-filtered local commits,
  repository-ID/SHA associations, five configured PR-title searches of 20 local
  rows, 100 cached open issues, and ten prior review runs bounded in SQL before
  loading their payloads. Direct links rank first; relevance precedes recency.
  Selected PR, issue and prior-review previews are each limited to three.
- Notebook text is deferred whole at 8 KiB; history is bounded to 16 KiB and
  the packet to 32 KiB of UTF-8. Gaps retain bounded, explicit omission notices.
  Logs report included/deferred counts, gaps, exact bytes and elapsed capture
  time without logging note bodies. Snapshot inspection is available through the
  existing review projection; the friendly product UI remains pending.
- The existing issue read now uses a matching workspace's cached row/activity.
  Only selected missing or outdated activity uses its existing refresh service;
  an uncached issue uses the exact existing preview fallback. Failed refreshes
  retain cached evidence and indicate missing/stale data. Local discovery itself
  never hydrates sources. Evidence read/citation bounds accept context references.
- Independent review identified and fixed initial-run recovery bypass, evidence
  reference limits, Unicode metadata limits, relevance/recency ordering, and
  optional path-failure handling. All notebook claims remain UNVERIFIED until
  original sources and applicability are checked. Existing evidence-reference
  validation proves identity only; CP4/CP6 must establish policy delivery and
  judgment behavior before enablement, including finding explanations that cite
  notebook evidence. Prepared evidence is readable through existing review tools.
- Remaining: input applicability/reuse, selected hydration and documentation
  capture, shared READY/EMPTY/FAILED preparation and generation fencing, automatic
  agent delivery, notebook UI, and the small-PR On/Off comparison.
- Snapshot verification: `mvn -B verify` completed all modules and exposed one
  nested Mockito setup error affecting all 66 review-service tests, plus three
  existing HTTP continuation timeouts. The fixture now constructs a real shared
  workspace context before independently stubbing its two accessors. Production
  behavior, timeout limits and test selection were not weakened to pass the gate.
- Affected-suite rerun: `mvn -B -pl app -am verify
  -Dtest=TestDefaultAgentReviewService,TestAgentHttpApi
  -Dsurefire.failIfNoSpecifiedTests=false` passed all 66 review-service and
  39 HTTP tests, including the build gates and final backend packaging.
  Combined full-run/rerun reports contain 212 suites and 2,275 tests with zero
  remaining failures, errors or skips. This is combined evidence, not a claim
  that the first full invocation passed. Logs:
  `/private/tmp/bytequay-context-selection-backend-verify.log` and
  `/private/tmp/bytequay-context-selection-backend-rerun.log`.
- Relevant snapshot suites passed: matcher 15, local reader 14, capture service
  8, frozen scope 4, context values 4, run capture 2, SQLite review storage 13,
  Git browsing 13, and review tools 39. Fixtures cover literal paths, Unicode
  byte bounds, repository identity, unknown ancestry, zero-remote selection,
  cached issue reads, missing/stale activity, and immutable capture/recovery.
- Frontend snapshot checks: lint and TypeScript passed; `npm test` passed 80
  files and 901 tests. Only the existing ignored lucide-react `use client`
  bundle directives were reported. Packaging passed after normal network access
  resolved the sandbox's Electron-artifact DNS failure. Logs:
  `/private/tmp/bytequay-context-selection-frontend-lint.log`,
  `/private/tmp/bytequay-context-selection-frontend-types.log`,
  `/private/tmp/bytequay-context-selection-frontend-test.log`, and
  `/private/tmp/bytequay-context-selection-frontend-package.log`.
- Final packaging was repeated after the verified backend rebuild and passed
  without warnings. The packaged backend JAR matches the current verified JAR
  by SHA-256. Artifact: `frontend/out/ByteQuay-darwin-arm64/ByteQuay.app`.
  Log: `/private/tmp/bytequay-context-selection-frontend-package-final.log`.
- Document verification: all seven JSON examples parse, local Markdown link
  targets resolve, and changed/new source whitespace and conflict-marker checks
  pass. No live provider, user-data database, or GitHub publication was used.
- Collection review feedback uses immutable gap/result builders, bounded term
  iteration without a temporary list, and an immutable PR map that keeps the
  highest-ranked candidate. The added regression preserves distinct-PR counting
  for component expansion and rank/recency deduplication. `mvn -B verify` passed
  in one full invocation: 212 suites, 2,277 tests, zero failures/errors/skips.
  Log: `/private/tmp/bytequay-context-collections-verify.log`. This backend-only
  refactor did not repeat frontend checks or rebuild the desktop package.
- Selection review feedback adds a shared `TopKSelector` for lazy first-K and
  comparator-ranked picks. The reader uses named selectors, source-fetch caps
  and byte budgets; displayed count limits derive from the same values. Existing
  filtering, deduplication, ranking, distinct-PR expansion and fetch bounds are
  preserved. Six focused tests cover limits, stable ties and lazy consumption.
  `mvn -B verify` passed in one full invocation: 213 suites, 2,283 tests, zero
  failures/errors/skips, including all 16 reader tests. Source whitespace and
  conflict-marker checks passed. Log:
  `/private/tmp/bytequay-context-topk-verify.log`. This backend-only refactor did
  not repeat frontend checks or rebuild the desktop package.
- Committed the verified local snapshot/selection increment as `913416d07`
  (`Capture local context for review runs`).
- The input-identity increment saves per-scope baseline inventories in the
  existing review snapshot. Root documentation/build entries, component trees,
  knowledge-file references, ancestor manifests and the configured source
  catalog participate. Evidence receipt IDs, citation commits, retrieval times,
  unrelated baseline commits and MEMORY lessons do not force a new fingerprint.
  Observed document content/version/capture changes do participate; no remote
  document read or effective dependency resolution is claimed.
- Git inspection uses one root read and at most four shared batches of 128
  nested paths. Each command has a two-second timeout; no new read is scheduled
  after the ten-second budget. Existing process termination/drain may add time.
  Parsed output is limited to 256 KiB; the existing runner's hard capture bound
  remains 5 MiB. Complete fingerprints require every selected essential input.
  Truncated, failed, unsupported or excluded inputs retain a reason and no
  digest. Inventory keys/values are bounded to 32 KiB per scope and 256 KiB per
  run. No notebook write, provider call or new storage layer is introduced.
- Independent review corrected overlapping Git path handling, deep-source
  ancestor expansion, provenance-only hash changes, MEMORY interference,
  unrelated unusual Git filenames, and absent document metadata colliding
  with literal values. Notes remain UNVERIFIED for the PR. Reuse outcomes,
  generation, source hydration, delivery and UI gates remain pending.
- The first backend gate stopped at one overlong test line. After fixing it
  and the optional-metadata regression, a full `mvn -B verify` passed all
  modules: 215 suites, 2,318 tests, zero failures/errors/skips. This includes
  25 input-reader tests, 5 fingerprint-value tests, 11 capture-service tests,
  and the existing real SQLite roundtrip with complete/incomplete inventories.
  Logs: `/private/tmp/bytequay-context-inputs-verify.log` and
  `/private/tmp/bytequay-context-inputs-verify-final.log`.
- Frontend lint and TypeScript passed; `npm test` passed 80 files and 901 tests.
  Logs: `/private/tmp/bytequay-context-inputs-lint.log`,
  `/private/tmp/bytequay-context-inputs-types.log`, and
  `/private/tmp/bytequay-context-inputs-frontend-tests.log`.
- All seven JSON examples parse, and changed source/document whitespace,
  Java line-length and conflict-marker checks pass. No live provider, user-data
  database or GitHub publication was used.
- `npm run package` passed and produced
  `frontend/out/ByteQuay-darwin-arm64/ByteQuay.app`. Its packaged backend JAR
  matches the verified backend JAR by SHA-256. Log:
  `/private/tmp/bytequay-context-inputs-package.log`.
- Committed the verified input-fingerprint increment as `ad3a772a4`
  (`Capture repository context input fingerprints`).
- Selected PR reads now share the existing row/detail stores, projection
  mapper and PR freshness rules. Missing detail, known update lag or requested
  missing sections use the existing full refresh; expired open/monitored status
  uses its conditional refresh. Unrelated missing sections do not cause a
  refresh. Failed refreshes or persisted rereads retain the original cached
  data, timestamps and head with explicit failure/staleness. No new retry cache
  or background full-detail backoff policy was introduced.
- Existing PR, timeline and checks tools accept a selected number in the bound
  repository. Current review code coordinates remain frozen; related metadata
  does not borrow them. Known PRs read through the issue tool reuse the PR cache.
  Responses and individual evidence excerpts include source/cache age, missing
  sections, observed head and failure status. Initial notebook selection stays
  local and its saved snapshot is not replaced by these later reads.
- Independent review identified and fixed repeated retained-run writes from
  per-item PR citations. Each nonempty activity/check page now saves its up to
  20 individual references in one update; an empty page performs no evidence
  write. Review serialization still covers the existing remote read, and the
  existing detail refresh can make several HTTP calls. Live lock wait, upstream
  latency and repeated-failure cost remain performance acceptance work.
- Frontend source-read checks passed: `npm run lint`,
  `npx --no-install tsc --noEmit`, and `npm test` (80 files, 901 tests).
  Logs: `/private/tmp/bytequay-context-source-lint.log`,
  `/private/tmp/bytequay-context-source-types.log`, and
  `/private/tmp/bytequay-context-source-frontend-tests.log`.
- Focused source-read coverage passes: 29 selected-reader cases and 48 review
  tool cases, including existing issue-cache tests. Fixtures cover missing and
  incomplete sources, shared status-age rules, unchanged historical PRs, failed
  refresh/partial-save fallback, unknown check heads, moved heads with frozen
  review coordinates, related-source identity, pagination and one evidence
  write per page. No live GitHub or provider was used for these fixtures.
- Full backend source-read gate: `mvn -B verify` passed in one invocation,
  including license, Checkstyle, compilation, tests, Spring boot and packaging.
  Reports contain 216 suites and 2,356 tests with zero failures, errors or skips.
  Log: `/private/tmp/bytequay-context-source-verify.log`.
- Desktop source-read packaging: `npm run package` passed and produced
  `frontend/out/ByteQuay-darwin-arm64/ByteQuay.app`. Its packaged backend JAR
  matches the verified backend JAR by SHA-256. Log:
  `/private/tmp/bytequay-context-source-package.log`.
  Changed source/document whitespace, Java line-length and conflict checks
  pass, and all seven design JSON examples parse. The source-read increment
  is verified; selected association/range-diff hydration,
  documentation capture, shared preparation, delivery and UI remain pending.
