# Repository Context implementation checklist

Approved behavior is defined in [Repository Context](repository-context.md). Start the
design review with its [Overall flow](repository-context.md#2-overall-flow).
This temporary file tracks delivery; it does not define a second architecture.

Implementation includes the notebook, editing API, discussion-source binding,
and immutable per-run snapshots selected from local notes and history, including
bounded baseline input inventories and fingerprints. Selected PR/issue and
commit-association reads reuse existing caches and refresh paths, retaining
freshness and failure evidence. The default-on preference now controls local
snapshot capture; Off bypasses selection.
Related patch pages pin cached contents by fingerprint and retain exact excerpts.
Selected registered documentation reads capture bounded text, keep continuation
pages consistent and retain exact excerpts through the existing review evidence.
Completed task summaries now join local history through a scoped task-service
query and explicit references, sharing the three-excerpt cap with prior reviews.
Baseline source reads and selected document pages retain typed read receipts,
including successful empty observations, alongside exact optional evidence.
Snapshots are prepared before agent admission and exposed in the existing review
projection. The first settings/display UI now exposes local notebook editing,
default-on review selection, source/scope configuration, Save lesson from retained
private messages, and truthful prepared-packet/receipt inspection. Review-owned
BEGIN/SUBMIT now admits shared preparation for existing scopes, retains
READY/EMPTY/FAILED outcomes and checked source receipts, reuses equal inputs,
and protects user edits. Standalone generation, direct context delivery and
provider acceptance remain pending. Unchecked items remain pending even when
related infrastructure already exists.

## Delivery summary

| Entry | Status |
| --- | --- |
| Agreed concepts, overall flow and contracts | Finished |
| Notebook persistence and editing/lesson APIs | Finished, including preparation-aware Reset/protection |
| Local selection, frozen snapshots and input fingerprints | Finished; input reuse implemented, source applicability remains pending |
| Selected PR, issue, commit association and related diff reads | Finished |
| Selected documentation capture and retained excerpts | Finished; committed as d42c105f0 |
| Scoped task-summary discovery | Finished; committed as 333902583 |
| Frozen-baseline file reads and typed source receipts | Finished; committed as 3ba1ef0d2 |
| Review-owned shared preparation and source/input reuse | Implemented; protected READY/EMPTY/FAILED with atomic replay |
| Scope/source discovery and standalone Build/Refresh | Pending |
| Automatic context delivery and complete report integration | Pending |
| Notebook/settings UI, Save lesson and prepared-context display | Implemented, including retained preparation outcomes; build activity/delivery/citations remain pending |
| On/Off quality comparison, performance and provider acceptance | Pending; required before enablement |

An implemented source reader is not completion of automatic reusable knowledge.
CP1/CP2 are partly complete; CP3 through CP6 retain their unchecked gates.

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
- [x] Clear missing-note preparation on explicit Reset, including EMPTY and
  removed READY; preserve existing user notes and prune unreferenced evidence.
- [x] Make source/scope edits and all entry mutations use one workspace lock
  and expected content revision. No model/network/Git work holds the lock or
  a database connection.
- [x] Apply notebook byte/count bounds and prune only unreferenced notebook
  evidence; reject invalid and stale local edits without replacing saved content.
- [x] Preserve user content on failed or stale generated updates.
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
- [x] Verify frozen-baseline regular-file reads and typed source receipts for
  repository/document observations, including empty reads, failures, immutable
  source identity and receipt/evidence retention in one run update.
- [x] Use complete identities in READY/EMPTY shared preparation reuse, including
  retained blank observations and post-save source inventory.
- [ ] Compare source/dependency versions with reviewed code. Manifest hashes do
  not resolve effective versions or establish remote freshness.
- [x] Add the narrow path-filtered Git history overload and reuse persisted
  commit-to-PR associations. Treat parsed PR-number suffixes as unverified hints.
- [x] Reuse existing PR detail/diff, issue detail, and review services;
  fix the current issue-tool cache bypass within the same source-read boundary.
- [x] Add scoped completed-task summary discovery through existing task services.
  Open-task learning reports and general task changed-path discovery remain deferred.
- [x] Hydrate selected missing/stale PR/issue details and related diff artifacts through existing refresh paths.
  Expose missing sections, cache age, lookup bounds, and unavailable history.
- [x] Verify selected PR metadata/activity/check reads, issue cache reuse and
  exact preview fallback. Keep related PR identity separate from frozen review
  code; retain source freshness in returned evidence.
- [x] Add selected missing commit-association reads through existing enrichment
  and lookup services, with shared negative retry rules and explicit failures.
- [x] Add related default-diff hydration with content fingerprints, consistent
  pagination and exact retained patch excerpts; current review diffs already
  come from the retained run. Do not infer verified/final revision ranges.
- [x] Register validated user documentation roots/pages with provenance through
  the existing notebook configuration API.
- [ ] Register repository-proposed roots from captured README/module evidence
  together with preparation admission; do not accept model-selected URLs alone.
- [x] Capture selected registered pages through the bounded reader and retain
  exact returned excerpts in existing review evidence.
- [x] Check every redirect and actual resolved destination, unsupported formats,
  truncation, response limits, and credential/proxy isolation.
- [ ] Verify documentation versions against reviewed code during preparation;
  current captures explicitly report unknown version/freshness/applicability.
- [ ] Label native-web/model-reported excerpts accurately when ByteQuay did
  not capture the original page itself.

Gate: a fully cached selection makes zero remote requests; a selected miss
uses the existing hydration service; fork/upstream identity cannot cross-join;
unchanged file/version inputs reuse notes; changed relevant inputs and added
source inventory invalidate them. Include local-first issue and document
redirect/size/failure regressions. No whole-site or whole-history fetch.

## CP3 — Shared preparation and explicit builder

- [x] Admit one review-owned generation owner per workspace and return immediate
  JOINED for matching active inputs; DEFERRED/gaps for competing missing inputs.
- [x] Resolve identical review admission replay before expected-revision comparison.
  Scope/root proposal registration and its revision behavior remain pending.
- [x] Persist accepted task/turn ownership and input fencing before generation.
  Derive execution status from existing tasks instead of a parallel job engine.
- [x] Implement READY, EMPTY, FAILED and negative-result reuse. A source failure
  or essential truncation cannot be accepted as a successful empty extraction.
- [x] Reject stale/cancelled owner callbacks and stale content revisions without
  losing user edits. Save notebook mutations with their bounded replay receipt.
- [x] Keep review-local accepted evidence valid if shared notebook saving fails;
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
- [x] Preserve prior notes on review-owned failure; settle ownership only after
  the accepted turn is terminal and its runtime claim is cleared.
- [ ] Exercise standalone-builder dirty-work cleanup under existing task rules.
  Do not add a background refresh queue.

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

- [x] Add Repository context to the existing workspace settings page with
  Knowledge, Saved lessons and configured sources/scopes.
- [x] Display retained READY/EMPTY/FAILED outcomes distinctly from no completed
  result, plus active owner metadata without claiming live execution.
- [x] Add the default-on Use repository context in reviews switch and show its
  captured state on each run. Explain new-run scope and retained saved notes.
- [x] Add editing, disabling/removal and explicit removal reset, with revision
  conflicts, preserved drafts and UTF-8 limits.
- [ ] Add Build/Refresh using the
  existing task transcript for progress, Stop, failure and recovery navigation.
- [x] Add Save lesson to the existing private discussion/response surface;
  use the same editor for user acceptance of text, evidence and scope.
- [x] Extend Review details with prepared context, exact captured text and bytes,
  reasons, versions, gaps, fingerprints and retained source receipts.
- [ ] Add confirmed provided state and links to cited findings/hypotheses.
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
- The notebook now retains last scope outcomes and one active review-owned
  preparation. Standalone builder/request fields remain absent until implemented;
  no placeholder builder endpoint is exposed.
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
- Committed the verified selected PR increment as `07d4a5c3c`
  (`Refresh selected pull request context`).
- Selected commit-to-PR reads now accept at most ten distinct full commit IDs
  through the existing review context tool. They resolve the monitored stable
  repository identity from the bound workspace, reuse stored positive mappings,
  and apply the shared daily/seven-day negative retry rule. Exact local metadata
  reads accept at most 256 bytes and use a two-second command timeout; annotated
  tag IDs and incomplete reads cannot trigger a selected lookup.
- The existing provider lookup now caches only positive associations, keyed by
  repository, commit and source branch. A checked miss can be retried when due;
  a missing response or a full 100-candidate page stays unavailable. Successful
  observations use one association-only upsert, preserve avatar fields and are
  reread for concurrent positives. Save/reread failures and older confirmation
  times remain explicit unpersisted observations.
- Each result retains exact identity, check/read time, lookup and persistence
  facts in individual evidence references saved with one run update. Existing
  context and review code stay frozen. Independent review corrected attribution
  of cached results: durable rows do not retain the original lookup branch.
- Frontend association checks passed: `npm run lint`,
  `npx --no-install tsc --noEmit`, and `npm test` (80 files, 901 tests).
  Logs: `/private/tmp/bytequay-context-association-lint.log`,
  `/private/tmp/bytequay-context-association-types.log`, and
  `/private/tmp/bytequay-context-association-frontend-tests.log`.
- Full backend association gate: `mvn -B verify` passed in one invocation.
  Reports contain 217 suites and 2,404 tests with zero failures, errors or skips.
  This includes 34 selected-commit reader cases, 55 review-tool cases, 12 shared
  lookup cases and the shared retry-policy boundary regression. Tests use
  provider fixtures and disposable Git repositories; no live GitHub or provider
  validation is claimed. Log:
  `/private/tmp/bytequay-context-association-verify.log`.
- The final cached-branch provenance wording correction passed
  `mvn -B -pl app -am verify -Dtest=TestSelectedCommitPullRequestReader
  -Dsurefire.failIfNoSpecifiedTests=false`, including all 34 reader cases and
  rebuilding the backend package. Log:
  `/private/tmp/bytequay-context-association-final-verify.log`.
- Desktop association packaging passed and produced
  `frontend/out/ByteQuay-darwin-arm64/ByteQuay.app`. The packaged backend JAR
  matches the final verified JAR by SHA-256 and contains the corrected provenance
  text. Log: `/private/tmp/bytequay-context-association-package.log`.
  Source whitespace, Java line-length, conflict-marker and local Markdown-link
  checks pass; all seven design JSON examples parse. Exact related-diff hydration,
  documentation capture, shared preparation, delivery and UI remain pending.
- Committed the verified commit-association increment as `22756a834`
  (`Resolve selected commit pull request context`).
- Related patch reads use the existing default PR diff store and selected
  refresh service. A content fingerprint binds the complete ordered artifact
  to its repository and PR; continuation pages use local cache reads and reject
  changed or missing artifacts. Separately observed PR metadata cannot certify
  the diff's revisions, freshness, completeness or final merged contents.
- The shared GitHub list reader now rejects missing bodies and unsolicited
  not-modified responses instead of producing false empty data. Explicit empty
  arrays and conditional first-page reuse retain their meanings. Existing
  historical artifacts still have no recorded completeness or fetch timestamp.
- Frontend related-diff checks passed: `npm run lint`,
  `npx --no-install tsc --noEmit`, and `npm test` (80 files, 901 tests).
  Logs: `/private/tmp/bytequay-context-related-diff-lint.log`,
  `/private/tmp/bytequay-context-related-diff-types.log`, and
  `/private/tmp/bytequay-context-related-diff-frontend-tests.log`.
- Full backend related-diff gate: `mvn -B verify` passed in one invocation,
  including license, Checkstyle, compilation, tests, Spring boot and packaging.
  Reports contain 219 suites and 2,461 tests with zero failures, errors or skips.
  Focused coverage includes 42 selected-diff reader cases, 60 review-tool cases
  and 10 shared pagination cases. Tests exercise cache-only reuse, empty versus
  unavailable artifacts, selected hydration/refresh and original-content
  fallback, exact digest framing, changed-cache conflicts, Unicode fragments,
  extreme page numbers and preservation of frozen review coordinates/anchors.
  Log: `/private/tmp/bytequay-context-related-diff-verify.log`.
- Related file indexes return at most 20 metadata entries without evidence
  writes. Each patch page retains exactly its at-most-2,000-code-point fragment
  with one run update; missing patches stay unavailable. No full diff copy,
  schema change, provider transport change or new background job was added.
  Hashing scans the cached artifact on each read; live latency/memory and
  provider/judgment acceptance remain pending. All tests used existing isolated
  fixtures, not live GitHub, provider credentials or the user-data database.
- Related-diff desktop packaging passed and produced
  `frontend/out/ByteQuay-darwin-arm64/ByteQuay.app`; its backend JAR matches the
  verified JAR by SHA-256. Log:
  `/private/tmp/bytequay-context-related-diff-package.log`.
  Changed source whitespace, Java line lengths, conflict markers and local
  document links pass; all seven design JSON examples parse. Selected
  documentation capture, task-summary discovery, shared preparation/generation,
  direct delivery, UI and provider/judgment acceptance remain pending.
- Committed the verified related-diff increment as `ba00420cf`
  (`Read selected pull request diff context`).
- Selected documentation reads use existing notebook configuration and retained
  review evidence. Source listing is local and paginated; actual reads require
  the run's captured enabled state. Configured roots/pages, relative selectors,
  manual redirects and connection-time public DNS checks bound each read.
  The dedicated Apache HTTP client uses direct sockets, no credentials/cookies,
  and no automatic retries or content decompression. Existing production HTTP
  clients retain their explicit JDK transport.
- Two active workers, a fifteen-second caller deadline, 1 MiB response cap,
  128 KiB extracted-text cap and sixteen temporary captures bound source work.
  Captures expire after ten minutes; continuations never refetch and fail on a
  lost/replaced/reconfigured receipt. Exact 16 KiB Unicode-safe text pages are
  retained only when nonblank. Failed refresh preserves an available earlier
  observation with its original time and a failure flag; failures cannot become
  successful empty extraction. Remote freshness and applicability stay unknown.
- Independent review corrected ambient SOCKS inheritance, overly broad public
  IPv4 rejection, source-selection revision naming, blank evidence handling and
  global proxy-test isolation. No notebook generation, extra database, full-site
  mirror, new provider process or automatic agent-input delivery was added.
- Frontend documentation checks passed: `npm run lint`,
  `npx --no-install tsc --noEmit`, and `npm test` (80 files, 901 tests).
  Logs: `/private/tmp/bytequay-context-document-lint.log`,
  `/private/tmp/bytequay-context-document-types.log`, and
  `/private/tmp/bytequay-context-document-frontend-tests.log`.
- Backend verification first stopped at six style violations, which were fixed.
  The subsequent full `mvn -B verify` completed all tests and exposed four new
  mock-fixture errors and three agent HTTP assertions caused by automatic
  transport detection selecting the added Apache dependency and retrying 503s.
  Corrected the mock fixtures and explicitly preserved that HTTP test client's
  prior non-retrying JDK transport. No assertion or product error was weakened.
- Affected-suite `verify` reruns passed the 73 transport, 17 selected-document
  and 39 agent HTTP cases and rebuilt the backend package. The full run's 70
  review-tool cases also pass. Combined full-run/rerun reports contain 221
  suites and 2,561 tests with zero remaining failures, errors or skips; this
  is combined evidence, not a claim that the first full invocation passed.
  Logs: `/private/tmp/bytequay-context-document-verify.log`,
  `/private/tmp/bytequay-context-document-verify-final.log`,
  `/private/tmp/bytequay-context-document-verify-rerun.log`, and
  `/private/tmp/bytequay-context-document-http-verify-final.log`.
- All document/source fixtures are offline and use mocked data or disposable
  local resources. Live public-document/provider use, per-run evidence growth,
  lock-wait/latency and peak resource measurements remain acceptance work.
  Next: scoped existing-task summary discovery, followed by shared preparation,
  source applicability/reuse, cold generation and the explicit builder.
- Desktop packaging passed and produced
  `frontend/out/ByteQuay-darwin-arm64/ByteQuay.app`. The packaged backend JAR
  matches the final verified JAR by SHA-256. Log:
  `/private/tmp/bytequay-context-document-package.log`.
  All nine repository-context JSON examples parse, local document links resolve,
  and changed/new source whitespace, Java line lengths and conflict checks pass.
- Committed selected documentation capture as `d42c105f0`
  (`Capture selected documentation for reviews`).
- Completed task-summary selection extends the existing task service/store with
  a read-only query. SQLite applies workspace, selected physical repository,
  interactive ownership, completed state and summary availability before the
  ten-row limit; stable ties use task ID. Archived/cleaned task summaries remain
  eligible. No schema, index, endpoint or provider change was needed.
- Local selection matches configured whole terms in bounded title/conclusion/
  notes text, or resolves at most ten explicit `task:<UUID>` references through
  the existing workspace-owned getter. It excludes current/review-owned,
  foreign, internal and incomplete tasks. Task and review excerpts share the
  existing three-item selector, ordered by direct reference, review-path match,
  then term match, with recency and stable-ID ties.
- Each task excerpt records source identity/version, workspace/repository,
  recorded head when available and unverified-conclusion provenance. Empty
  conclusions and bounded discovery remain visible gaps. Open-task learning
  reports and task changed-path discovery remain deferred. Full summary JSON
  decoding and SQLite scan work are not bounded by excerpt bytes; production
  resource/latency measurements remain acceptance work.
- Independent review found no correctness blocker and requested the source task
  version in provenance, which is included. Added service, SQLite and local
  selector coverage for filtering before limit/decoding, ordering, retained
  summaries, direct references, combined limits, ownership and UTF-8 bounds.
- Task-history frontend checks passed: lint, TypeScript and 80 test files with
  901 passing tests. Logs: `/private/tmp/bytequay-context-task-history-lint.log`,
  `/private/tmp/bytequay-context-task-history-types.log`, and
  `/private/tmp/bytequay-context-task-history-frontend-tests.log`.
- Full backend `mvn -B verify` passed all seven modules: 221 suites, 2,577 tests,
  zero failures, errors or skips. This includes 28 context-reader, 26 task-service
  and 33 SQLite agent-store cases. Log:
  `/private/tmp/bytequay-context-task-history-verify.log`.
- Desktop packaging passed; the backend JAR inside
  `frontend/out/ByteQuay-darwin-arm64/ByteQuay.app` matches the verified JAR by
  SHA-256. Log: `/private/tmp/bytequay-context-task-history-package.log`.
  Changed source whitespace/line-length checks and local document links pass;
  all nine repository-context JSON examples parse.
- Committed scoped task-summary selection as `333902583`
  (`Select relevant completed task summaries`).
- Shared preparation needs typed observations from the frozen monitored
  baseline, not the PR's source or descriptive evidence labels. This increment
  adds that source prerequisite before introducing preparation owners or states.
  The fixed source tool accepts revision=baseline and resolves regular blobs
  from the run's monitored commit, independently of HEAD/index/working files,
  replacement objects and lazy fetching. Full files are capped at 128 KiB,
  returned numbered windows at 200 lines/16 KiB, with two bounded local commands.
- Typed repository/document receipts retain backend-observed source identities
  and optional evidence references in the existing run JSON. Nonblank excerpts
  are stored once in run evidence, in the same update; successful empty files
  or blank document pages retain receipts without invented evidence. Failures
  create neither a receipt nor an EMPTY knowledge result. Original bootstrap,
  selected notes and code coordinates remain unchanged. Reads share a cap of
  128 receipts, enforced before external work; other run evidence is unaffected.
- Source work occurs before the accepted-turn recheck and optimistic run write.
  A cancellation already observed by that recheck prevents retention. This
  does not claim a transaction spanning task cancellation and shared notebook
  mutation; atomic preparation ownership/save and its race tests remain CP3.
- Independent review caught the missing baseline value in the provider tool
  schema; the schema and its regression assertion now include it. Initial
  verification also exposed record-mocking test fixtures and receipt JSON
  serialization; fixtures now use real values and responses use the configured
  application mapper. The focused review-tool rerun passed all 76 cases:
  `/private/tmp/bytequay-context-baseline-tools-verify.log`.
- Frontend lint, TypeScript and all 80 test files/901 tests passed. Logs:
  `/private/tmp/bytequay-context-baseline-lint.log`,
  `/private/tmp/bytequay-context-baseline-types.log`, and
  `/private/tmp/bytequay-context-baseline-frontend-tests.log`.
- Final full backend `mvn -B verify` passed all seven modules: 223 suites,
  2,616 tests, zero failures, errors or skips. Coverage includes 25 baseline
  reader, 76 review-tool and 14 SQLite review-store cases. Log:
  `/private/tmp/bytequay-context-baseline-verify-final.log`.
- Desktop packaging passed, and the packaged backend JAR matches the verified
  JAR by SHA-256. Log: `/private/tmp/bytequay-context-baseline-package.log`.
  All ten repository-context JSON examples parse; local document links,
  changed/new source whitespace, Java line lengths and conflict checks pass.
  Baseline reads/receipts are complete. The reviewed patch, including new
  source files, is `/private/tmp/bytequay-context-baseline-review.patch`.
- Next implementation boundary remains shared preparation admission and
  source/input reuse decisions, followed by protected generated updates, cold
  generation and the explicit builder. READY/EMPTY/FAILED lifecycle, automatic
  delivery, Context used/settings UI and provider/judgment/performance
  acceptance remain separate unfinished gates.

### First notebook and review UI

- Added typed Electron contracts, guarded IPC and preload methods for all seven
  existing notebook endpoints. Review parsing preserves nullable historical
  snapshots and rejects malformed present context instead of displaying Off.
- Added Workspace settings → Repository context with the persisted default-on
  switch, knowledge and saved-lesson groups, manual knowledge creation, entry
  editing/disable/remove, removal reset, and source/scope configuration. Opening
  the page performs only local reads. Every mutation uses the displayed revision.
- Shared native dialogs keep focus inside the editor, expose UTF-8 limits and
  preserve failed drafts. Explicit reload precedes retry; the current disabled
  scope state survives a configuration conflict. Changed source/claim structure
  cannot be silently overwritten by an older note editor.
- Review details now renders Prepared, Off for this run or Not recorded, exact
  saved packet/bytes, note versions and reasons, history, input fingerprints,
  retained source reads and gaps. It makes no delivery or comprehension claim.
- Save lesson uses the retained private message's original run, supports selected
  evidence, and asks the user to edit its title, summary, conditions and scope.
  Duplicate clicks are fenced; reload recognizes a saved lesson after a lost
  response while preserving edits made after that attempt. No provider turn or
  GitHub write is started.
- Existing backend storage/source bounds remain enforced. In particular a source
  excerpt over 16 KiB or a full notebook can reject a lesson save; the editor
  retains the draft and shows the backend error. Generation, Build/Refresh,
  preparation activity, automatic delivery and cited-finding navigation are not
  implemented by this UI increment.
- Backend `mvn -B verify` passed all seven modules, 223 suites and 2,616 tests,
  with zero failures, errors or skips. Log:
  `/private/tmp/bytequay-context-ui-backend.log`.
- Offline Chromium checks cover nine settings scenarios plus review lesson
  conflicts, old-run binding, lost responses and newer draft preservation.
  Inspected normal/narrow settings and editor layouts:
  `/private/tmp/bytequay-context-settings-ui/wide.png`,
  `/private/tmp/bytequay-context-settings-ui/narrow.png`, and
  `/private/tmp/bytequay-context-settings-ui/editor.png`.
  Review and lesson captures:
  `/private/tmp/bytequay-review-context-wide.png`,
  `/private/tmp/bytequay-review-context-narrow.png`,
  `/private/tmp/bytequay-save-lesson.png`, and
  `/private/tmp/bytequay-save-lesson-narrow.png`.
  These use disposable local fixtures, not live provider or quality acceptance.
- Frontend lint, TypeScript and the full suite passed: 84 files, 916 tests.
  Logs: `/private/tmp/bytequay-context-ui-lint.log`,
  `/private/tmp/bytequay-context-ui-types-final.log`, and
  `/private/tmp/bytequay-context-ui-tests.log`. The full run exposed a fixture
  timing assumption; replacing frame delays with bounded waits for actual dialog,
  save and reload states resolved it without changing production behavior.
- Final desktop packaging passed; its backend JAR matches the verified JAR by
  SHA-256. Log: `/private/tmp/bytequay-context-ui-package.log`. Whitespace checks
  include new files, and changed design-document local links resolve. The full
  review patch, including new UI, contracts and tests, is
  `/private/tmp/bytequay-context-ui-review.patch`.
- Next: shared preparation admission and source/input reuse, then protected
  generated updates, cold generation and the explicit builder; automatic delivery
  and small-PR On/Off/provider/performance acceptance remain later gates.


### Review-owned shared knowledge preparation

- Committed the reviewed notebook/settings and review-display UI as `5c90b8152`
  (`Expose repository context and saved lessons`). This increment remains separate.
- Added BEGIN/SUBMIT on the enabled initial investigation bridge, using existing
  configured scope IDs and typed baseline/document read IDs. Assignment returns
  a paged current scope catalog/revision so the agent does not guess selectors.
- One workspace owner is persisted before generation. Matching consumers receive
  immediate JOINED; unrelated inputs defer. READY/EMPTY reuse compares complete
  frozen-baseline/source inventories. The accepted/reused response supplies actual
  note text within 32 KiB, with explicit whole-section omissions; tool metadata
  omits full inventories and the complete serialized result is capped at 64 KiB.
- Checked blank receipts remain durable negative-result proof without invented
  evidence. Nonblank excerpts are copied under globally unique read IDs. Completed
  fingerprints use prospective saved sources, preventing immediate self-invalidation.
- User-owned/disabled/removed content remains protected. Scope configuration cannot
  forge or erase server-owned outcomes. Explicit Reset clears missing-note results.
- Notebook ownership/save and the exact accepted run receipt use the existing
  AgentUnitOfWork transaction; a real SQLite failing run-write test proves rollback.
  Git capture/report validation occur outside locks and transactions. Existing
  terminal-turn/runtime-claim facts control cleanup; no background agent is added.
- Settings now displays recorded READY/EMPTY/FAILED and active ownership. Browser
  fixtures: `/private/tmp/bytequay-context-settings-preparation-ui/{wide,narrow,editor}.png`.
- Next: connect the frozen bootstrap and preparation results to actual investigation
  input and completed brief/report, then carry that frozen brief to validation and
  evidence collection. Scope/root proposals and standalone Build/Refresh remain
  separate pending work. No live provider, GitHub write or user's database was used.
- Verification completed:
  - Full `mvn -B verify` passed: 226 suites, 2651 tests, zero failures/errors/skips.
    Log: `/private/tmp/bytequay-preparation-backend-verify.log`.
  - Frontend `npm run lint`, `npx tsc --noEmit`, all 84 files / 917 tests in
    `npm test`, and `npm run package` passed. Logs:
    `/private/tmp/bytequay-preparation-{lint,types,frontend-tests}.log` and
    `/private/tmp/bytequay-context-preparation-package.log`.
  - Sandbox process inspection/Electron launch restrictions required rerunning
    those gates with normal local process access. Packaging required network
    access for its existing Codegraph asset hook. No source workaround was added.
  - Settings wide/narrow/editor fixture images were visually inspected for
    wrapping, control visibility and overflow; ten browser scenarios include
    no-completed-result versus EMPTY/FAILED and retained ownership metadata.
  - Independent service/input review caught an oversized-packet edge case;
    fixed with complete-section deferral, compact result metadata and a 64 KiB
    serialized response limit. Largest legal note and JSON escape-expansion
    regressions pass. `git diff --check` passes.
  - Live-provider use, end-to-end automatic delivery, judgment quality and cold/warm
    production performance remain unmeasured; their acceptance boxes stay open.
