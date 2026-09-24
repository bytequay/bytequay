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
Selected merged PRs and closed issues now retain typed historical receipts and
can support shared knowledge alongside captured monitored-baseline evidence.
Snapshots are prepared before agent admission and exposed in the existing review
projection. The first settings/display UI now exposes local notebook editing,
default-on review selection, source/scope configuration, manual Save lesson from retained
private messages, and truthful prepared-packet/receipt inspection. Review-owned
BEGIN/SUBMIT now admits shared preparation for existing scopes, retains
READY/EMPTY/FAILED outcomes and checked source receipts, reuses equal inputs,
and protects user edits. Accepted review turns now receive their frozen bootstrap
or completed investigation brief with immutable collection supplements. Exact
input bindings are retained before submission; the display derives delivery
status from existing provider receipts. Standalone Learn/Refresh task and UI are
implemented and pass backend/frontend automated gates, focused process-test
cleanup verification and desktop packaging. Explicit scope/document suggestions
and bounded resolved-history discovery also pass backend/frontend and package
gates. The final integration adds frozen-head source/input comparison, exact-version
note reads, separate discussion captures, source-to-conclusion navigation and
durable preparation activity. Codex Learn, empty-notebook preparation, saved-note
reuse and existing-card discussion have live integration evidence. Two small
code changes now pass full Codex investigation/validation in four independent
Off/cold-On arms. Human judgment and controlled performance remain pending.
Later review searches and exact reads now appear in Review details with
receipt-based delivery labels. Development turns with saved but unmatched notes
receive a short search reminder. Agent-drafted lesson fields are implemented,
and their focused backend/frontend checks pass. Unchecked acceptance items
remain pending even when related infrastructure already exists.

## Delivery summary

| Entry | Status |
| --- | --- |
| Agreed concepts, overall flow and contracts | Finished |
| Notebook persistence and editing/lesson APIs | Finished, including preparation-aware Reset/protection |
| Local selection, frozen snapshots and input fingerprints | Implemented; frozen-head local source/input comparison, with remote/effective versions explicitly unverified |
| Selected PR, issue, commit association and related diff reads | Finished, including selected closed-issue activity and historical preparation receipts |
| Selected documentation capture and retained excerpts | Finished; committed as d42c105f0 |
| Scoped task-summary discovery | Finished; committed as 333902583 |
| Frozen-baseline file reads and typed source receipts | Finished; committed as 3ba1ef0d2 |
| Review-owned shared preparation and source/input reuse | Implemented; protected READY/EMPTY/FAILED with atomic replay |
| Historical PR/issue sources feeding shared generated knowledge | H1 finished; source admission, retention and reuse verified |
| Standalone Learn / Refresh knowledge lifecycle and UI | H2 implemented; backend/frontend, focused process and package gates passed |
| Scope/root discovery and bounded resolved-history discovery | H3 finished; language-neutral traversal passed backend verification and desktop packaging |
| Automatic context delivery and investigation report integration | Implemented; frozen bootstrap/brief, exact-version deferred notes and separate replayable discussion capture |
| Notebook/settings UI, Save lesson and context delivery display | Implemented; citations, retained retrievals, folded preparation outcomes, bytes/timing and receipt-derived delivery state |
| Agent-drafted lesson fields | Implemented; backend, browser interaction, frontend and package gates passed; live provider acceptance remains open |
| Development/review agent note discovery | Implemented; later review searches/reads are visible and unmatched development turns receive a search reminder; Claude and Codex have confirmed development lookup receipts, and unprompted Codex opened an offered note; broader acceptance remains open |
| Repository context usage and demand view | Implemented; task-detail cards and note-version usage recorded live offer, search and read delivery; controlled quality/performance acceptance remains open |
| Reproducible small comparison fixtures | Implemented; two frozen code diffs and eight context proposals, with dependency-free behavior checks; human assessment pending |
| False lesson, correction and future exclusion | Four live Codex lifecycle reviews passed source-based rejection, exact versions and immutable old snapshots |
| On/Off quality comparison, performance and live provider acceptance | Four isolated Codex reviews and two paired Claude cases match fixture expectations; human assessment, broader cases and controlled performance remain open |

The H1–H3 cold-start work is implemented and its automated/package gates pass.
Discussion-source supplements, selected deferred-note exact-version reads and
display integration are implemented. Remaining acceptance requires the broader
stale/irrelevant/history-assisted cases, native-memory and controlled
performance checks, plus human assessment; the live Claude pairs cannot certify
review judgment. The [acceptance protocol](repository-context-acceptance.md) keeps
those procedures and unknown metrics explicit. Existing CP sections
retain the detailed foundation and verification checklist; their numbering does
not override this priority order. Earlier “Next” notes are historical evidence.

## Agent discovery and usage

The [agent discovery design](repository-context-agent-discovery.md) now has a
first implementation. It extends the notebook to ordinary development turns
and lets active reviews find notes beyond their original frozen selection. It
does not change the H1–H3 implementation or complete their live acceptance gates.

- [x] Review the new design's overall flow, settings, matching order, source
  safety and truthful Offered/Found/Opened display.
- [x] Implement one bounded local notebook search and exact-version read path;
  verify disabled, suppressed, cross-workspace and changed-version cases.
- [x] Extend active review lookup with separately retained later discoveries;
  preserve frozen bootstrap, Off and old-card discussion behavior.
- [x] Add a default-on development-use setting, accepted-turn offer, fixed
  read-only broker tool and task-owned receipts for both providers.
- [x] Show bounded offer/search/read receipt cards in development task details;
  preserve review receipt detail, exact versions and match reasons without exposing
  note bodies in the task card.
- [ ] Run live Codex/Claude dynamic lookup and On/Off quality/performance checks.
  Both lanes have confirmed live search and exact-read receipts, and fresh
  Off/On comparisons have run. Human-adjudicated quality gain and controlled
  performance remain unverified.
- [x] Add idempotent usage facts from confirmed provider receipts and the
  Repository context Usage view. Show per-note versions, task types and
  missing-scope demand; separate prepared/uncertain from confirmed delivery,
  and label the tracking start and retained-history boundary.
- [ ] Evaluate optional per-task note feedback after usage counts are verified;
  do not infer help from retrieval or require an approval for every lesson.

The discovery implementation passes backend `mvn verify` (including 1,982 app
tests and the local HTTP/process tests), frontend lint and TypeScript checks,
950 frontend tests, and desktop packaging. Electron browser fixtures and
packaging required an unsandboxed run on this host. The new Usage view measures
observed reach, not review correctness; the live provider and human comparison
checks above remain open. The separate persistence refactor removed all three production
JDBC-template users. Usage reads and task cleanup use Spring Data repositories;
the receipt upsert retains SQLite's atomic conflict rule in one repository query.
Workspace cleanup retains its ordered SQLite-specific SQL through EntityManager.
Low-level SQL in schema and store tests remains test-only.

## Cold-start priority order

### H1 — Learn shared knowledge from selected history

Implemented and verified. The evidence entry below records the completed gates.

- [x] Extend selected closed-issue reads to retain bounded body, recorded outcome
  and available activity through existing source services, with explicit pagination, missing
  sections, stale-cache and refresh-failure limitations.
- [x] Retain typed PR/issue source receipts alongside exact run evidence, including
  selected merged-PR metadata, available diff artifacts and recorded discussion.
  A merged/closed label alone establishes neither resolution rationale nor truth.
- [x] Permit eligible historical receipts in generated KNOWLEDGE preparation,
  validating repository identity, actual observed state/version and retained
  evidence. Keep unsupported, incomplete or unsuccessful reads as explicit gaps.
- [x] Extend source/input equality and reuse so changed historical evidence can
  invalidate a reused preparation. Preserve stable provenance and bounded packets.
- [x] Verify that baseline/doc/history-derived notes can be saved and reused while
  user edits, disabled/suppressed notes, stale ownership and report replay retain
  their existing protection. No automatic MEMORY entry or Save lesson substitute.

Gate: selected merged PR and resolved issue fixtures exercise current-state
validation, exact source retention, closed-issue activity, preparation admission,
READY/EMPTY/FAILED results and stale/cross-repository rejection. Selected reads
remain bounded; no full history crawl or background agent run. Keep actual receipt
schemas in the canonical design and distinguish available partial data from a
complete-resolution account. Required checks belong to the H1 evidence entry.

### H2 — Explicit Learn and Refresh knowledge

Implemented after H1, with backend/frontend automated verification, desktop
packaging and the focused process-test cleanup check passed.
Live provider, performance and On/Off judgment acceptance remain separate open gates.

The backend has ContextBuild/Store/Service/Tools/Coordinator and the purpose-checked
build controller, with Learn/Refresh controls and task details in the existing
notebook UI. The latest request lives in the existing
AppSettingsStore separately from the notebook; active preparation shares a
trusted execution with paired optional review/run IDs. Generic frozen-baseline
file and directory reads need no synthetic review. Project fingerprints now
include immediate root layout names/modes, while component subtree and selected
content identities retain their existing behavior. Task details display the
required actual worktreePath and preserve explicit cleanup controls for retained
FAILED tasks. The evidence entry records the passing automated/package gates;
these do not imply live-provider, real dirty-worktree or quality acceptance.

- [x] Add the explicit workspace-settings action with selected knowledge scopes,
  and optional selected merged-PR/closed-issue references. The first Learn task
  includes repository files, directory structure, repository documentation and
  registered docs by default, without optional switches for those foundations.
  Verify the accepted request schema and explicit revision/sequence fences;
  do not present remaining proposed fields as already shipped.
- [x] Default project matching scope exists before content. Show “not prepared”
  when it has no note; a generated Project overview appears under Knowledge.
  Offer that scope directly in the build action instead of implying the existing
  scope row already contains project knowledge.
- [x] Start one ordinary managed-worktree task through current readiness and
  task services, with saved builder purpose, immutable accepted selections and
  the existing policy-only review restrictions before provider launch.
- [x] Reuse shared preparation admission, typed source reads, retained evidence,
  report fencing, note protection and READY/EMPTY/FAILED outcomes from review.
  Keep generated KNOWLEDGE distinct from explicit user-owned MEMORY lessons.
- [x] Reuse exact task/request identity across repeat clicks and recovery; preserve
  uncertain accepted starts without resending. Do not add a parallel build-job
  state machine, provider runner or background refresh queue.
  Keep the latest binding until terminal task state and recorded worktree removal
  permit replacement, so cleanup/recovery controls remain reachable.
- [x] Link to the ordinary task transcript for progress, Stop, errors and recovery.
  After a settled builder report, explicitly complete the task and invoke only
  permitted cleanup; dirty or unverifiable work follows existing retention rules.
  A task already FAILED at reconciliation entry retains its worktree for explicit
  purpose-scoped cleanup, including after Mark failed discards a recovery claim.
  GET/startup must preserve it; a claim-free OPEN task abandoned in that same
  reconciliation pass may still undergo verified cleanup.
- [x] Prove learning starts only from the explicit Learn/Refresh action: opening
  the app/workspace/settings, startup recovery and source synchronization never
  start or resume a learning task. There is no background learning scheduler.

Gate: cold empty notebook -> explicit Learn with default repository and
documentation reads -> visible source-linked
Knowledge -> reuse in a later review, including Off-for-reviews with an explicit
Learn action. Verify bounded directory/source coverage and explicit gaps when
default sources cannot be read, then exercise optional selected history,
cancellation, concurrent build/review ownership, user corrections,
stale requests, failed/uncertain admission and dirty-work cleanup through real UI
controls and current task services. No GitHub publication authority.

### H3 — Bounded scope, source and resolved-history discovery

Implemented after the explicit builder; manual scopes and explicit sources
remain usable without discovery. Backend/frontend and desktop package gates
passed; the evidence entry below records the completed checks.

The approved flow is Find suggestions -> select IDs -> Use selected suggestions
-> backend rederivation -> append configuration -> Start Learn. Discovery uses an existing local repository
and one frozen monitored baseline; no clone, Git fetch, provider/task allocation,
saved proposal lifecycle or scheduler is introduced. Optional remote history
search permits only bounded history queries. Acceptance rejects moved baseline,
changed routing, stale notebook revision and active shared preparation.
Preview/apply require repositoryFingerprint over routing and lastCodeSyncAt;
capture and final comparison use the workspace lock. One final bounded local
rev-parse also runs under that lock before notebook registration, with a
two-second/no-lazy-fetch limit and no notebook transaction or task monitor.
Candidate source reads and network/model work remain outside the lock. This
fences a sync completing while Apply waits, a failed sync that moved its ref
without updating lastCodeSyncAt, and an upstream sharing the preview commit.
Bounded local history uses GitBrowseService.localLog with a two-second timeout,
256 KiB output and no lazy fetch or replacement objects. Repository source reads
share `bytequay.repository-context.discovery-timeout=30s`: one Stopwatch supplies
the remaining budget to Guava SimpleTimeLimiter on the existing ioExecutor.
Only the caller mutates Scan; timeout retains completed suggestions and an
incomplete-coverage gap, while late results cannot merge. Interruption restores
the request's flag and aborts before history or configuration saving. This adds
no scheduler. Git command limits and history budgets stay separate; thirty
seconds is neither a whole-HTTP guarantee nor measured performance.
The subsequent traversal refinement uses bounded breadth-first inspection of
eligible directories at any depth, including below manifests. Queries prioritize
children and filter scope proposals without pruning nonmatching ancestors. The
same eight manifest names, 24-directory/24-file caps, first 100 entries per
directory and hidden/generated/vendor exclusions remain; symlinks and submodules
are not followed. There is no Maven module parser, other workspace parser or build
execution. Verification for this refinement is recorded separately below.

ContextScope and RegisteredContextSource now have server-owned optional
discoveryEvidenceRef fields pointing to exact captured RepositoryFile excerpts in
the existing notebook sourceEvidence list. Configuration references retain those
excerpts before notes exist and through preparation/reset. Unchanged defining
fields preserve provenance; manual match/source changes clear it and prune only
unreferenced evidence. The package-private registerDiscovered appends new identities
without overwriting/re-enabling existing configuration. BEGIN never registers
proposals or expands its accepted source assignment.

- [x] Propose component scopes and documentation roots only from captured
  frozen repository/module evidence. Require literal links for documentation
  proposals; never broaden a page into a root. Present evidence and accept only
  selected IDs rederived by the backend before saving configuration and Start Learn.
- [x] Discover a bounded relevant selection of merged PRs and resolved issues,
  reusing repository identity, local caches, path history and explicit search
  limits. Distinguish resolved from merely closed and retain actual outcome facts.
- [x] Keep closed-issue discovery separate from the current open-issue projection;
  avoid silently dropping resolution sources or claiming exhaustive history.
- [x] Expose why each historical item/scope matched, counts/limits and unavailable
  sources. Selected hydration must not become a repository-wide or website crawl.

Gate: explicit source inputs still work when discovery is empty/unavailable;
limits, source state, fork/upstream identity and repository-derived proposal
provenance are tested. Discovery alone performs no generation or memory promotion.

### After cold start

Discussion-source supplements, selected deferred-note exact-version reads,
citation navigation and folded preparation activity are implemented. The
explicit agent-drafted lesson proposal is implemented, with focused verification
complete. CP6's live provider, performance and On/Off quality gates, and CP7's required
repository checks and enablement rules remain open. Offline fixtures or a
working builder do not prove improved review judgments.

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
- [x] Compare application-captured repository-file and READY-preparation Git/input
  identities with the frozen reviewed head. Distinguish MATCHED identities,
  concrete VERSION_MISMATCH and UNVERIFIED/incomplete evidence. Manifest hashes
  do not resolve effective versions or establish remote freshness.
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
- [x] H3: explicitly accept repository-proposed roots from captured README/module
  evidence before Start Learn; rederive selected IDs and reject model-selected URLs.
- [x] Capture selected registered pages through the bounded reader and retain
  exact returned excerpts in existing review evidence.
- [x] Check every redirect and actual resolved destination, unsupported formats,
  truncation, response limits, and credential/proxy isolation.
- [ ] Verify documentation release versions against effective dependencies.
  Deferred beyond the minimal implementation: there is no reliable cross-language
  mapping contract. Captures remain explicitly UNVERIFIED; version-looking URLs,
  model assertions and equal manifest hashes cannot promote them.
- [x] Label native-web/model-reported excerpts accurately when ByteQuay did
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
  Scope/root acceptance is a separate explicit pre-Learn operation with its own
  revision, repository-identity and frozen-baseline guards.
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
- [x] H2: start explicit Learn/Refresh through current readiness, managed worktree,
  task, turn, completion, Stop, and recovery services, with selected scopes,
  default repository files/structure/documentation and registered docs, plus
  optional selected PR/issue inputs. Opening/sync/startup cannot trigger learning.
- [x] Save builder identity/purpose before the provider starts and use a
  policy-only review bridge. AUTO alone must not grant development authority.
- [x] Reuse a matching request after double-click/retry/restart without creating
  a duplicate builder or resending an uncertain accepted turn. Preallocate its
  task ID in the binding and reject superseded request sequences, including
  when intervening EMPTY builds did not change the content revision.
- [x] Explicitly complete the settled standalone builder and invoke permitted
  cleanup; an ordinary successful turn alone leaves the task open.
- [x] Preserve prior notes on review-owned failure; settle ownership only after
  the accepted turn is terminal and its runtime claim is cleared.
- [x] Exercise standalone-builder dirty-work cleanup with real managed Git and
  completion services: tracked/untracked work prevents completion; ignored
  output permits completion but blocks removal; explicit clearing permits cleanup
  while preserving the notebook. No background refresh queue is added.

Gate: concurrent cold reviews generate once; unrelated task/late callback is
rejected; user correction during generation wins; an EMPTY result is reused
until its inputs change or explicit retry; crash/stop/uncertain-launch recovery
does not leave an immortal claim or duplicate runtime. Prove the builder lacks
GitHub publication, credential-read, and development-delivery authority.

## CP4 — Review input, evidence, and fixed tools

- [x] Capture and directly deliver the bootstrap in the existing investigation
  turn. Do not add an acknowledgement turn or rewrite provider history.
- [x] Capture On/Off and exact prepared packet/evidence per run before startup
  or recovery admits its initial turn; reuse the saved choice on replay.
- [x] Off bypasses only this feature's selection,
  automatic generation, extra input and tools; existing review behavior remains.
  Validation and old-run discussions use their source run's captured setting.
- [x] Implement the closed read/prepare tools with backend-resolved task, turn,
  workspace, run, and code identity. Preserve the existing no-child profile.
- [x] Extend the existing investigation report with the completed brief,
  actual preparation references, evidence and limitations; validate source
  identity/shape without claiming to validate semantic truth.
- [x] Deliver the frozen brief to validation/evidence collection and preserve
  existing report repair, replay, coverage, incomplete, and cursor behavior.
- [x] Bind old-card discussions to their source run and capture relevant new
  local discussion evidence at queued/immediate execution. Retain separate exact
  packets/evidence with cache/coverage gaps, preserve original On/Off/head/context,
  fence cancellation before saving and replay without rereading changed comments.
- [x] Persist the Ask-agent message's source run/revision before start/enqueue,
  then attach delivery IDs. Exercise an idle question about an older card,
  rejected admission, accepted-but-error recovery, and queued execution.
- [x] Add explicit retry and restart reconciliation for a saved Ask-agent
  message whose delivery remains uncertain, without resending accepted input.
- [x] Persist exact supplied text, sizes/digests, source versions, omissions,
  delivery bindings, actual retained retrievals and citations. Display outcomes
  from provider receipts while preserving delivery uncertainty. Deferred-note
  reads bind original entry/version, retain exact successful text/source evidence
  in the run journal, and never substitute a changed or deleted version.
- [x] Keep the small trusted instruction policy separate from untrusted notes;
  bound the whole composed transport input as well as context sections.
- [x] Verify context reaches both provider transports without copying the
  notebook into a command-line argument or exposing raw provider JSON.
  Offline initial/resumed Codex and Claude transport fixtures pass; live-provider
  behavior and judgment acceptance remain CP6 gates.

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
- [x] Add Build/Refresh using the
  existing task transcript for progress, Stop, failure and recovery navigation.
- [x] Add Save lesson to the existing private discussion/response surface;
  use the same editor for user acceptance of text, evidence and scope.
- [x] Add an explicit Draft with agent action to fill editable title, summary and
  details from the retained discussion and source-run evidence. Keep scope and
  evidence selection manual and Save lesson as the sole notebook write. Support
  drafting from the open editor while preserving scope/evidence choices. Preserve
  the current draft on malformed/provider failure and permit retry or manual
  completion. Verify the response contract and editor/retry states with focused
  tests and the real review UI before marking this item complete; live provider
  acceptance remains under CP6.
- [x] Extend Review details with prepared context, exact captured text and bytes,
  reasons, versions, gaps, fingerprints and retained source receipts.
- [x] Add confirmed provided state from existing provider receipts; missing
  acceptance stays uncertain and prepared-but-unsent stays distinct.
- [x] Add links to cited findings/hypotheses, including saved rejected conclusions.
- [x] Use folded activity for reuse/build/join/failure. Keep no-match, unbuilt,
  ready-empty, failed and stale states understandable without technical JSON.
- [x] Show observed preparation elapsed time and retained bytes/read counts without
  implying that a model read or understood everything it received. Context-specific
  provider token/cache attribution is explicitly unavailable.
- [x] Preserve the display from durable run facts after reconnect, notebook corrections, source-cache
  eviction and task-runtime retention.

Gate: component tests for user actions and provenance states, then normal and
narrow-window visual inspection. Check the overall flow through real controls;
opening settings or inspecting a source list must not launch generation.

## CP6 — Judgment, performance, and provider acceptance

- [ ] Create a small human-reviewed case set with correct, stale, irrelevant,
  missing and deliberately wrong notes, using fixed PR/code identities.
  The local generator now prepares two frozen diffs and eight proposed variants
  with independently executable contract tests and a reviewer guide. Its checks
  establish fixture behavior and integrity; human assessment and actual provider
  comparisons now include four independent Codex runs; human assessment of the
  full proposed case set remains pending.
- [ ] Compare review behavior with context disabled and enabled on the same
  cases; record evidence quality, false positives, missed known issues and
  explicit uncertainty. Use the workspace switch with fresh independent tasks
  and provider conversations per arm; same-head Update is not a fresh run.
  Include small PRs covering distinct behaviors. Citation counts alone are not
  a quality metric.
  Both Off/cold-On pairs completed with the expected supported findings: one
  boundary defect in each positive arm and none in either safe optimization
  arm. Actual tests and an independent technical audit support that observation;
  a further live Claude Off/warm-On review pair found the same seeded boundary
  bug in both arms, and a live development pair correctly declined a false fix
  in both arms. The On review received the learned note but did not open it;
  the On development turn received the note but did not search it. A separate
  non-scored Claude development turn confirmed scoped search and exact read.
  An additional Claude pair on a safe resolver change had zero findings in
  both arms, with a confirmed unrelated window note in On. That note also had
  `VERSION_MISMATCH` from a changed project-wide preparation input, so the
  pair does not isolate distraction from irrelevant content.
  These small machine-assessed samples do not close human judgment acceptance.
  An obsolete wrong rule and a pure irrelevant-note case, plus human scoring,
  remain part of the broader acceptance gate.
- [x] Verify a false saved rule cannot establish a defect by itself; exercise
  original-source/code checks, counterevidence, corrections and future exclusion.
  A real saved MEMORY v2 was delivered, read and explicitly refuted; fresh reviews
  used corrected v3 and excluded disabled/removed versions. All four accepted
  lifecycle runs had zero findings and unchanged old snapshots. This is one
  bounded live case with prior review history available, not a universal guarantee.
- [x] Exercise the offline source prompt-injection boundary: hostile source text
  does not change managed policy, tool inventory or trusted caller routing.
  Review/permission tests retain no-publication and no-child authority. Live
  model resistance to misleading text remains part of the unperformed cases.
- [x] Verify Claude native-memory settings and supported Codex behavior. Disable
  autonomous cross-task memory where supported; otherwise document and test the
  limitation before making the lane available. Claude's actual generated settings
  have an explicit autoMemoryEnabled=false regression. Codex now explicitly
  disables memories in process and start/resume configuration; the pinned CLI
  accepted that profile in disposable live runs. The later Claude live fixture
  confirmed that its process used ByteQuay's authenticated profile and generated
  autoMemoryEnabled=false settings. A disposable two-task canary for each lane
  used distinct provider threads and clean worktrees; the second task did not
  receive or reproduce the first task's marker. This bounded observation is not
  proof of absent provider memory. Never claim to disable pretraining.
- [ ] Measure cold and warm overhead: model turns/tokens, elapsed preparation,
  source requests, notebook/run bytes, SQLite wait/write duration, and peak
  process memory/CPU under simultaneous review/task activity.
  An isolated Claude review and development turn overlapped for 16.082 seconds
  with an empty notebook; token, packet, serialized-size and sampled whole-process-tree
  RSS/CPU values are retained in the concurrent evaluation. This is a missing-note
  sample, not attributable saved-note overhead. Live SQLite wait/busy-handler
  time and per-process memory were not measured in that first pair.
  A second pair overlapped for 23.898 seconds and confirmed delivery/search/read
  of one saved component note, with separate sampled JVM and Claude RSS/CPU.
  The review also built missing project knowledge, so it mixes warm reuse with
  cold preparation; live SQLite waits and attributable overhead remain unknown.
- [x] Record unknown or unavailable metrics explicitly. If contention exists,
  fix transaction length/write frequency before proposing separate storage.
  Measure existing source-hydrator locks separately from notebook mutation locks.
  Local tests separate connection-pool/transaction admission from notebook
  writes and same-review hydrator waiting. Real remote delay, SQLite busy-handler
  duration and attributable concurrent-process overhead remain explicitly unknown.
- [x] Confirm bounded documentation capture and one workspace generation owner
  in automated cancellation, concurrency and explicit-refresh/replay regressions.
  Repeat under live-provider acceptance; fixtures do not establish live behavior.
- [x] Run live cold then warm review for each lane intended for enablement,
  plus an explicit builder and a new-comment discussion on an existing card.
  Codex explicit Learn, empty-notebook review preparation, saved-note reuse/delivery
  and a local-comment discussion fixture passed. Complete review validation now
  passes on the small Python fixtures; the earlier Trino build limitation remains
  recorded separately. A later Claude sample completed cold review, explicit
  Learn and saved-note reuse; cold review skipped preparation despite the prompt,
  so its enforcement repair and repeat are tracked below. The fixture had no
  discussion message for a live lesson draft. A later Claude existing-card
  question received a confirmed 1,236-byte supplement containing a new cached
  comment; the agent checked the frozen code, rebutted the false comment and
  left the accepted card unchanged. The independent quality and performance
  gates still control enablement.

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
- [x] Reconcile Agent Review, Agent Service, Managed Agent Skills, task UI,
  persistence, and workspace-deletion descriptions with the implemented behavior.
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


### Lesson relevance and explicit scope polish

- [x] Preserve enabled/suppressed scope gates and explicit selection. Keep
  canonical knowledge ordering and missing-knowledge discovery independent of
  lesson topic ranking.
- [x] Rank eligible lessons using distinct title/summary words shared with the
  bounded PR text or changed basenames. Exclude common words and scope title/path
  vocabulary; preserve alternative configured topic terms. Repetition, bodies,
  claims and retained evidence do not increase relevance.
- [x] Retain scope-only lessons at lower priority with a visible explanation.
  Matching words are shown for stronger candidates. Equal matches retain saved
  order, and relevance never promotes applicability beyond UNVERIFIED.
- [x] Keep the 8 KiB knowledge/lesson budget and whole-entry deferral. A capture
  regression checks that the stronger lesson gets the available space before an
  earlier saved scope-only lesson and that replay preserves the frozen result.
- [x] Require an explicit scope choice when saving a private review lesson;
  explain the broad applicability of Project before save. Conflict retries retain
  the chosen scope and draft.
- [x] Update the canonical matching rules and current architecture description.
- [x] Frontend lint, TypeScript, all 84 test files / 917 tests, and desktop
  packaging passed. Logs: `/private/tmp/bytequay-matching-frontend-{lint,types,tests,package}.log`.
  The existing package hook needed network access; Electron tests needed normal
  local process access. No source workaround was introduced.
- [x] Inspected disposable offline Save lesson fixtures at 1100×900 and 420×780.
  Placeholder and Project disclosure remain readable, the narrow body scrolls,
  and footer actions remain visible. Images:
  `/private/tmp/bytequay-matching-ui/{wide,narrow}-{placeholder,project}.png`;
  bounds checks: `/private/tmp/bytequay-matching-ui/checks.json`.
- [x] Full backend `mvn -B verify` passed: 226 suites / 2,658 tests, zero
  failures/errors/skips. Log: `/private/tmp/bytequay-matching-backend-verify.log`.
  The first focused run exposed an invalid disabled-scope test fixture; corrected
  it to a selected component scope. All matcher, snapshot and reader tests pass.
- [x] Final packaging passed with the newly verified backend; the packaged JAR
  matches it by SHA-256. Log: `/private/tmp/bytequay-matching-final-package.log`.
  `git diff --check` passed. Independent review caught configured OR topic terms
  being treated as generic words; the regression now preserves their ability to
  distinguish lessons. Live-provider judgment quality remains unmeasured.
- Next: the existing automatic investigation input, completed brief/report and
  downstream delivery work remains pending. This polish adds no model call,
  source fetch, schema field or automatic provider delivery.

### Automatic review context input and retained delivery evidence

- Committed the matching polish as `99e09cb9e` (`Refine saved lesson relevance
  matching`). The following increment remains separate for review.
- Enabled initial investigation receives its frozen bootstrap automatically at
  accepted turn launch. Validation and evidence collection receive the completed
  brief plus accepted additions; old-card questions retain their source run's
  original setting and context even when queued. Off and absent historical
  snapshots add no context input or policy.
- A narrow agent-core launch port runs outside the task lock, before a provider
  session opens. Existing cancellation/worktree checks run again afterward.
  Original accepted transcript messages remain intact; source bodies travel in
  existing message transport and only trusted policy enters managed instructions.
- Investigation seals its context contribution together with its report and replay
  receipt. Same-run source/preparation references are checked. At most two later
  collection contributions append; their addition cannot rewrite a bound prefix.
  Known preparation gaps are carried independently of the agent's own limitations,
  with whole-gap omission counts if necessary. This establishes provenance and
  transport, not semantic truth or applicability.
- Each rendered contribution fits 8 KiB; context packets fit 32 KiB and complete
  composed message text fits 256 KiB UTF-8. Each run retains at most 128 immutable
  turn bindings with packet/input hashes and byte counts. Existing provider facts
  derive Prepared, Waiting for confirmation, Provided to agent or Delivery
  uncertain. A missing receipt cannot be interpreted as accepted delivery.
- Review details displays delivery records and the original brief/additions with
  retained source evidence and limitations. The original bootstrap remains visible.
  Independent review also caught and fixed dropped backend gaps in later packets;
  assignment serialization now uses the configured mapper for validation timestamps.
- Focused core, tool, resolver, review-service and real SQLite regressions pass.
  Log: `/private/tmp/bytequay-context-delivery-app-focused.log`. Scripted Codex and
  Claude transport tests cover initial and resumed sessions with 256 KiB Unicode
  messages and 100,000-byte managed policy, checking exact message transport and
  exclusion of reference text from process arguments. No live provider was used.
- Full `mvn -B verify` passed all modules: 227 suites / 2,682 tests, zero
  failures, errors or skips. Log:
  `/private/tmp/bytequay-context-delivery-backend-verify.log`. Focused reruns
  corrected fixture ownership/terminal-state setup, a timestamp assertion and
  Checkstyle line lengths before this complete passing run.
- Frontend lint, TypeScript and all 84 files / 924 tests passed. Logs:
  `/private/tmp/bytequay-context-delivery-frontend-{lint,types,tests}.log`.
  Inspected normal/narrow offline display fixtures for prepared, provided and
  uncertain states: `/private/tmp/bytequay-context-delivery-ui/{wide,narrow}-{prepared,provided,uncertain}.png`.
  Layout and inert reference-text checks are retained in the same directory's
  `checks.json`. Canonical design JSON, fences and local links pass.
- Final desktop packaging passed and its backend JAR matches the verified JAR
  by SHA-256. Log: `/private/tmp/bytequay-context-delivery-package.log`.
  `git diff --check` and new-file whitespace/license checks pass. The complete
  review patch, including new files, is
  `/private/tmp/bytequay-context-delivery-review.patch`.
- The next-work order is superseded by the cold-start priority section above:
  H1 historical-source learning, H2 standalone Build context, then H3 bounded
  discovery. Fresh discussion supplements and deferred-note exact-version reads
  follow cold start. Citation navigation, folded activity, measured performance
  and small-PR On/Off/live-provider quality acceptance remain unchecked. No user
  database or GitHub write was used.


### Selected historical sources for shared knowledge

- Added the concrete ContextHistoryReader and the `review_get_context` history
  selector. Merged PRs require an actual observed merge timestamp; closed issues
  require an exact non-PR issue preview because the existing projection contains
  only open issues. The reader reuses selected PR/detail/diff and issue activity
  services. It does not start repository-wide sync or create another history store.
- Retained history records include exact UTF-8 excerpts, state, update/revision,
  content hash, provenance and bounded limitations. Up to twenty latest observed
  activity items and three selected PR diff files fit within a 16 KiB excerpt;
  unknown timestamps sort last, and omitted items remain visible. Merge/closure
  labels do not certify a resolution, correctness or current applicability.
- BEGIN accepts at most ten same-run historical read IDs before admitting an
  owner. SUBMIT checks admitted identities and requires nonblank monitored-baseline
  evidence for history-based READY. Historical receipts cannot establish EMPTY.
  The existing notebook protection, attempt ownership, report replay and explicit
  Save lesson boundary remain in place; this adds no automatic memory promotion.
- Input equality includes retained historical content while excluding receipt IDs,
  cache timestamps and successful refresh-attempt flags. Equal observations reuse
  notes; changed observations can refresh them. A FAILED refresh can retain old
  knowledge alongside newer checked observations and still retry. Over-budget
  scope inputs defer generation with a limitation instead of aborting all scopes.
- Review details show historical state, source identity, exact evidence and partial
  or failed-refresh limitations. Settings distinguish the Project overview scope
  from its saved Knowledge note and explain current review-based preparation.
- Focused context/preparation/review-tool/persistence checks passed. The complete
  final `mvn -B verify` run passed 228 suites / 2709 tests with zero failures,
  errors or skips, including the long-thread activity regressions. Logs:
  `/private/tmp/bytequay-context-history-focused-tests.log` and
  `/private/tmp/bytequay-context-history-backend-verify.log`.
- Frontend lint, TypeScript and all 84 files / 928 tests passed. Logs:
  `/private/tmp/bytequay-context-history-frontend-{lint,types,tests}.log`.
  Wide/narrow history and saved/empty settings displays were visually inspected
  in `/private/tmp/bytequay-context-history-ui/`; final history copy checks are
  recorded in `history-final-checks.json`.
- Desktop packaging passed. The packaged and verified backend JAR share SHA-256
  `408f10ec57bee8b31df97589a2b69a82a0c886828aff793f0c2189cf68287fb1`.
  Log: `/private/tmp/bytequay-context-history-package.log`.
- Next: H2 standalone Build context / Refresh knowledge using the current task
  lifecycle and existing notebook page, followed by H3 bounded scope/root and
  resolved-history discovery. Live provider, performance and small-PR quality
  acceptance remain pending. No user database or GitHub write was used.

### Explicit Learn / Refresh knowledge

- Implemented the explicit settings action, scope and optional history selection,
  saved ContextBuild purpose binding, fixed source/preparation tools, and existing
  task lifecycle integration. First-run defaults include repository files,
  directory structure, repository documentation and every registered document.
  No startup, page-open, synchronization or scheduled learning is introduced.
- Shared preparation retains source receipts, replay, user-note protection and
  scope outcomes. Mixed reused/owned preparations retain both kinds of result in
  the assigned scope order. Task details display the actual managed worktree path.
  Already-FAILED tasks retain their tree until explicit purpose-scoped cleanup;
  replacing the binding requires terminal state and recorded worktree removal.
- Final frontend lint, TypeScript and all 931 tests passed, including the required
  task worktree-path contract.
- Final production-backend `mvn -B verify` passed 231 suites / 2738 tests with zero
  failures, errors or skips in 4:09. Log:
  `/private/tmp/bytequay-context-build-final-backend-retry.log`.
  An initial complete backend verification also passed. An intervening verification
  encountered an existing process-test temporary-directory cleanup race: the
  terminal receipt writer could still be finishing after stopAndVerify returned.
  The test cleanup now waits for process.exit().get(). The focused
  TestDefaultProcessSupervisor run for that test-only change passed all 28 tests:
  `/private/tmp/bytequay-context-build-process-cleanup.log`.
  The passing full retry verifies the final production code.
  The interrupted run is retained at
  `/private/tmp/bytequay-context-build-final-backend.log`.
- Desktop packaging passed for darwin arm64:
  `/private/tmp/bytequay-context-build-package.log`.
  The verified `backend/app/target/bytequay-backend.jar` and bundled
  `frontend/out/ByteQuay-darwin-arm64/ByteQuay.app/Contents/Resources/bytequay-backend.jar`
  share SHA-256 `4201a61de127044b1fa284d97ae21ccb1b8b8e5bdf562f7381228b7a7aac745d`.
- H2 implementation and automated/package gates are complete. The separate
  real dirty-worktree end-to-end exercise remains unchecked.
  H3 bounded scope/root and resolved-history discovery is next. Live provider,
  measured performance and small-PR On/Off judgment acceptance remain separate
  pending gates; automated checks do not establish those claims.

### Explicit scope, documentation and history suggestions

- Implemented Find suggestions and Use selected suggestions in the existing
  notebook UI. Candidate configuration comes from bounded frozen repository
  reads and literal documentation links. Selected history stays optional for
  Learn; preview and acceptance never start an agent or automatically save memory.
- Added server-owned discoveryEvidenceRef provenance, append-only acceptance,
  revision/routing checks and selected-ID rederivation. Exact configuration
  evidence remains in the notebook before any generated note exists and survives
  preparation/reset; manual defining-data edits clear obsolete references.
  Acceptance rechecks routing/sync metadata and the final bounded local Git ref
  under the workspace lock before notebook registration, including failed-sync
  ref movement without a recorded sync-time change. The final full backend run
  includes that regression and passed.
- Local history uses GitBrowseService.localLog with a two-second timeout,
  256 KiB bound and no lazy fetch or replacement objects. This increment's
  scheduling-only scan deadline was superseded by the configurable read budget
  recorded in the review-polish entry below. No measured latency claim is made.
- Focused backend verification passed 282 tests:
  `/private/tmp/bytequay-context-discovery-focused.log`.
- Final frontend lint, TypeScript and all 84 files / 935 tests passed. Logs:
  `/private/tmp/bytequay-context-discovery-frontend-{lint,types,tests}.log`.
  Wide and narrow suggestion/task displays were visually inspected in
  `/private/tmp/bytequay-context-discovery-ui/`.
- The initial full backend run executed 2,768 tests with two failures in
  TestReviewTools documentation listing:
  `/private/tmp/bytequay-context-discovery-backend-verify.log`.
  The new optional discoveryEvidenceRef exposed an existing putPOJO path whose
  JsonNode.toString used an unconfigured mapper. Listing now converts sources
  with the injected ObjectMapper.valueToTree; all 96 TestReviewTools tests passed:
  `/private/tmp/bytequay-context-discovery-review-tools.log`.
- Final full backend verification passed 233 suites / 2,768 tests with zero
  failures, errors or skips in 03:04:
  `/private/tmp/bytequay-context-discovery-backend-verify-final.log`.
- Desktop packaging passed for darwin arm64:
  `/private/tmp/bytequay-context-discovery-package.log`.
  The verified `backend/app/target/bytequay-backend.jar` and bundled
  `frontend/out/ByteQuay-darwin-arm64/ByteQuay.app/Contents/Resources/bytequay-backend.jar`
  share SHA-256 `d4126bbd840fbe2d8e5daa2609a1bc22f29394e51dcde96adc1322e76917a471`.
- H3 implementation and automated/package gates are complete. Fresh
  discussion-source supplements and selected deferred-note exact-version reads
  are next. Existing real
  dirty-worktree, live-provider, performance and On/Off judgment acceptance gates
  remain open.

### Discovery timeout and scan review polish

- Replaced the unmeasured eight-second scheduling deadline with the configurable
  `bytequay.repository-context.discovery-timeout=30s` source-read budget.
  One Stopwatch supplies remaining time to Guava SimpleTimeLimiter on the existing
  ioExecutor. No scheduler or durable lifecycle was added.
- Only the request mutates Scan; each worker returns source data. Timeout retains
  already completed suggestions with an explicit coverage gap, and late worker
  results cannot merge. Interruption restores the flag and aborts before history
  discovery or notebook configuration saving.
- Split the scan into source-reading, directory/file inspection, child-queue and
  limit-reporting helpers to make reads and caller-owned mutation explicit.
  Existing source/count bounds, two-second Git command limits and the
  separate history budget remain. No thirty-second whole-HTTP or measured
  performance guarantee is claimed.
- Four added regression cases cover retained partial suggestions and rejection
  of a late worker result after timeout; interrupted discovery preserving the
  interrupt flag and aborting before history; interrupted acceptance aborting
  before configuration saving; and zero/negative timeouts rejected before reads.
- Focused verification passed all 22 tests: 12 discovery, three Git and seven
  SQLite tests. Log:
  `/private/tmp/bytequay-context-discovery-polish-focused-final.log`.
  An initial Spring test startup failed because test resources replace production
  application.properties; the test properties now include the discovery timeout.
- Full backend verification passed 233 suites / 2,772 tests with zero failures,
  errors or skips in 02:55:
  `/private/tmp/bytequay-context-discovery-polish-backend-final.log`.
  The initial sandboxed full run could not inspect macOS process groups because
  sysctl access was denied. The rerun passed with the required local process and
  socket access; no test or production behavior was weakened to bypass that gate.
- Frontend code is unchanged since the preceding lint, TypeScript and 935-test
  pass. Its earlier evidence still applies; this polish adds no frontend behavior.
- Desktop packaging passed for darwin arm64:
  `/private/tmp/bytequay-context-discovery-polish-package.log`.
  The verified `backend/app/target/bytequay-backend.jar` and bundled
  `frontend/out/ByteQuay-darwin-arm64/ByteQuay.app/Contents/Resources/bytequay-backend.jar`
  share SHA-256 `53bf0bfc56c34b162e33968152208836bdd6e21af288ff5f77303a02cc693269`.
  This completes the automated/package gates for the polish. Live-provider,
  performance and review-quality acceptance remain separate open gates.

### Language-neutral bounded directory discovery

- Replaced the Maven module regex/priority queue and shallow traversal stopping at
  manifest boundaries with generic bounded breadth-first traversal. Eligible
  directories can be inspected at any depth, including beneath an existing
  manifest, within the same total bounds.
- Queries prioritize child traversal and filter component scope proposals;
  nonmatching ancestors remain eligible so they can lead to matching descendants.
  The same eight manifest names remain recognized. No workspace-format parser,
  build execution or new dependency is added.
- Retained the 24-directory/24-file caps, first 100 entries per directory and
  configurable thirty-second shared source-read budget. Hidden/generated/vendor
  exclusions and safe-path checks remain; symlinks and submodules are not followed.
  Partial coverage stays explicit. All proposals require captured evidence and
  user acceptance before configuration changes or Learn.
- Three added regression cases cover mixed-language/deep manifests and
  documentation, query matches beneath nonmatching ancestors, and bounded deep
  and wide traversal. Existing cases also exercise the retained exclusions and
  Maven directories without module declarations.
- Focused verification passed all 48 tests: 15 discovery and 33 baseline-reader
  tests. Log: `/private/tmp/bytequay-context-discovery-language-focused.log`.
- Full backend verification passed 233 suites / 2,775 tests with zero failures,
  errors or skips in 03:02:
  `/private/tmp/bytequay-context-discovery-language-backend.log`.
- Frontend code is unchanged since the preceding lint, TypeScript and 935-test
  pass. This refinement adds no frontend behavior.
- Desktop packaging passed for darwin arm64:
  `/private/tmp/bytequay-context-discovery-language-package.log`.
  The verified `backend/app/target/bytequay-backend.jar` and bundled
  `frontend/out/ByteQuay-darwin-arm64/ByteQuay.app/Contents/Resources/bytequay-backend.jar`
  share SHA-256 `22116506b3bf4222c108f71ba257f5b2e5c6dba2f6e2896657c5e724c198148e`.
  This completes the automated/package gates for the traversal refinement.
  Live-provider, performance and review-quality acceptance remain separate open
  gates.

## Final context integration verification

- Committed discovery as `96236bdb1` (`Discover repository context sources`).
- Implemented frozen-head source/input identity comparison, exact selected-note
  retrieval and retention, per-turn local discussion supplements, durable
  preparation timing/activity, citation navigation and exact retrieved-note UI.
  Original source-run settings, packet prefixes and source versions remain fixed.
- Discussion selection uses the original finding revision when available. A
  changed finding cannot silently supply a newer path filter; the missing old
  anchor is an explicit gap. New comment capture never refreshes GitHub.
- Added real managed-worktree completion/cleanup coverage and offline hostile
  source/authority checks. Claude's generated auto-memory setting is asserted.
- Full `mvn -B verify`: 237 suites / 2,809 tests, zero assertion failures,
  one timeout in the existing
  `TestAgentHttpApi.enabledQuestionKeepsTaskOpenAndPausesPreviouslyQueuedWork`.
  Log: `/private/tmp/bytequay-context-final-verify.log`. The timeout is retained
  here; a successful rerun does not prove its underlying cause.
- After the final reader/discussion correction, `mvn -B verify
  -Dtest=TestAgentHttpApi,TestRepositoryContextReader,TestContextDiscussionReader,TestRepositoryContextTurnInput,TestAgentReviewProjection
  -Dsurefire.failIfNoSpecifiedTests=false` passed all five suites / 85 tests with
  zero failures, errors or skips, including the whole 39-case HTTP suite. It
  rebuilt the final backend JAR through verify. Log:
  `/private/tmp/bytequay-context-final-affected-verify.log`.
- Frontend `npm run lint`, `npx tsc --noEmit` and `npm test`: passed, 84 files /
  942 tests. A final citation/browser rerun passed 16 tests after ensuring resolved
  findings remain reachable. Logs: `/private/tmp/bytequay-context-observability-lint.log`,
  `-types.log`, `-tests.log` and `-citation-tests.log` with the same prefix.
- Normal and narrow browser checks exercised source-to-hypothesis-to-source and
  source-to-finding navigation, folded ancestors, focus and unchanged app routing.
  Eight screenshots were visually inspected at 1,280 and 640 CSS pixels without
  overflow: `/private/tmp/bytequay-context-observability-ui/`.
- `npm run package` passed. Source and bundled backend JAR SHA-256 both equal
  `70d1e6ae1f5bf1a1bb45042417455cf16078cc13cb27f4fe763f37f7e6aa21e1`.
  Log: `/private/tmp/bytequay-context-observability-package.log`.
- Design contracts, managed guidance, task/review display, persistence and
  workspace/runtime-retention descriptions now match the implemented boundaries.
- Remaining acceptance is explicit in [the protocol](repository-context-acceptance.md):
  human-approved On/Off judgments, live cold/warm runs and discussions/builders
  for intended lanes, native-memory enforcement and measured contention/CPU/memory
  overhead. These were not run and are not claimed complete. Automatic mapping
  from external document releases to effective dependencies stays outside the
  minimal implementation; those claims remain UNVERIFIED.

## Disposable live acceptance and integration repairs

- Ran the selected cached Trino PR #31022 against fixed local Git identities in
  disposable databases/workspaces. Off and cold On used fresh provider tasks and
  separate databases; no GitHub writes or personal notebook mutations occurred.
  Codex 0.153.4 was authenticated; Claude was not, so its live checks remain open.
- Fixed omitted review HTTP context fields, scoped native MCP approval to the
  exact broker inventory, corrected resumed Codex invocation usage accounting,
  and explicitly disabled Codex native memories at process and thread start/resume.
  Initial protocol-failure attempts are excluded from comparisons.
- Live cold review and standalone Learn exposed missing canonical-note guidance
  and contradictory checked-source limits. Both tool contracts now expose
  expectedNoteId, one canonical READY note, actionable constructor validation,
  and actual UTF-8 bounds. Checked-read inventories use the 128-capture budget;
  note and claim citations retain their eight-source bound. Regressions cover
  correction after rejection, 13 checked observations, and rejection of 129.
- Off/cold reviews completed provider turns but remained INCOMPLETE because build
  validation was unavailable. The first standalone Learn captured repository
  structure/documentation but settled FAILED and saved no knowledge; its clean
  worktree was removed. These failures drove the fixes and are not successful
  quality or warm-reuse evidence. Exact observations and limitations are retained
  in [the acceptance record](repository-context-acceptance.md).
- Initial focused integration verification passed seven suites / 162 tests:
  `/private/tmp/bytequay-context-live-integration-verify.log`.
- Full `mvn -B verify` ran 237 suites / 2,818 tests, with zero assertion failures
  and two existing HTTP-suite settlement timeouts:
  `createsTaskRunsIdempotentTurnsAndReplaysSse` and
  `missingReadinessReportStopsAutomationAndPreservesTheTaskForAnotherRequest`.
  Log: `/private/tmp/bytequay-context-live-full-verify.log`. These paths use the
  deterministic driver, not Codex; the log does not prove the latency cause.
- Final affected verification passed seven suites / 193 tests, including the
  entire 39-test HTTP suite and final submission-contract changes:
  `/private/tmp/bytequay-context-live-contract-verify-final.log`.
  The first contract attempt stopped at a missing test import, which was fixed.
  Its log remains `/private/tmp/bytequay-context-live-contract-verify.log`.
- Frontend lint, TypeScript and all 84 files / 943 tests passed. Electron tests
  required the usual local process access; the initial sandbox attempt could not
  launch Electron. Logs: `/private/tmp/bytequay-context-live-{lint,types,tests}.log`.
- Desktop packaging passed: `/private/tmp/bytequay-context-live-package.log`.
  Source and bundled backend JAR SHA-256 both equal
  `65579514c84ac0d1f05d5c668db139ec886c4e4a2cee6a7b82b9ed2493c7356d`.
- CP6/CP7 acceptance remains open: human-approved judgments, deliberately false
  notes, independent cold/warm overhead and contention measurements, new-comment
  discussion, and both intended provider lanes. Partial integration samples do
  not establish those broader claims.
- A further live retry exposed ancestor-manifest fingerprint growth: nine normal
  documentation paths expanded beyond 120 individual inputs. Compact complete
  `git-manifests:<directory>` identities now preserve addition/removal/content
  detection within the unchanged Git read budgets. Applicability compares these
  groups, and legacy fingerprints conservatively refresh or become UNVERIFIED.
- After that final correction, all 26 context/review-tool suites / 401 tests
  passed: `/private/tmp/bytequay-context-live-fingerprint-verify.log`.
  This includes the real nine-document layout, unrelated sibling stability,
  manifest addition/change/removal and missing/failed lookup coverage.
- Repackaging the final backend passed:
  `/private/tmp/bytequay-context-live-package-final.log`. Final source/bundled JAR
  SHA-256 is `92ecfd6730b292d2bfd1ab5d49df1e52f0cbd7d57c5b1428b3da39b116c3e9a2`.
- Final live Learn succeeded without an active PR: project READY,
  `knowledge:project` v1, notebook revision 1, eleven checked file receipts and
  clean learning-worktree removal. A fresh review returned REUSED in 44 ms,
  delivered the same saved note with MATCHED source identities, confirmed both
  packet deliveries, and generated no replacement knowledge. The warm review's
  build/test validation remained incomplete. This closes the basic Codex
  Learn-to-reuse integration check, not the broader CP6 quality/performance gate.
- The live existing-card discussion also passed with a clearly labeled local
  comment fixture: its new 1,986-byte supplement had confirmed delivery, while
  original packet/note/fingerprint/delivery data stayed unchanged. The agent
  rebutted the false index claim using frozen-code evidence and retained the
  validation limits. This is an integration observation, not human-adjudicated
  false-memory acceptance. Notebook and purpose revisions remained 1. The
  disposable review was closed and backend/sampler stopped; evidence retained.

## Remaining-development verification

- Corrected empty-notebook Codex review preparation passed: revision 0 became 1,
  `knowledge:project` v1 was saved READY, and both the original bootstrap and
  subsequent preparation brief had confirmed delivery. The two provider turns
  completed; the review stayed INCOMPLETE because Trino compilation/focused tests
  could not finish after Maven cache access failed and the broad retry was denied.
  This is integration evidence, not an independent quality-comparison arm.
- Added three bounded local contention regressions: source input inspection
  leaves the notebook transaction free, two concurrent admissions produce one
  owner, and selected hydration queues only its review while the notebook lock
  remains obtainable. A 98,369-byte notebook transaction took 2.658 ms in the
  retained single sample; intentionally blocked connection and hydrator waits
  are reported separately. Full boundaries and limitations are in
  [the acceptance record](repository-context-acceptance.md#local-storage-and-contention-checks).
- Replaced the HTTP test helper's generic five-second continuation wait with a
  separate thirty-second integration bound and last-response diagnostics.
  Terminal provider state precedes real Git settlement, which performs many
  subprocess checks under four concurrent test JVMs. Production behavior,
  generic fixture waits and build concurrency are unchanged. The exact cause
  of earlier machine latency is not claimed proven.
- Full `mvn -B verify` passed all 237 suites / 2,829 tests, with no failures,
  errors or skips in 03:04, including all 39 HTTP cases:
  `/private/tmp/bytequay-context-remaining-full-verify.log`.
- A final logging-only change makes measurement maps visible through the existing
  test logger. Both affected suites passed all 117 tests through verify:
  `/private/tmp/bytequay-context-remaining-measurements.log`. Exact reports and
  bounded sample values are retained in `/private/tmp/bytequay-context-remaining-metrics/`.
- No frontend source changed in this increment; the preceding lint, TypeScript
  and 943-test pass still applies. No learning scheduler, new storage service,
  automatic memory promotion, GitHub write or personal-notebook mutation was added.
- Desktop packaging passed for darwin arm64:
  `/private/tmp/bytequay-context-remaining-package.log`. Source and bundled JARs
  share SHA-256 `92ecfd6730b292d2bfd1ab5d49df1e52f0cbd7d57c5b1428b3da39b116c3e9a2`.
- Claude authentication was unavailable during this earlier run; a later live
  Claude comparison succeeded. Human quality evaluation and
  controlled live overhead/native-memory behavior remain separate acceptance
  items, not a per-note or per-review approval requirement. All disposable live
  services and the resource sampler were stopped after evidence capture.

## Reproducible comparison-case tooling

- Added `scripts/repository-context-cases.py`: explicit `create` and `verify`
  commands prepare a disposable local Git repository, an exact-identity manifest
  and a reviewer guide. The two frozen diffs contain a half-open window boundary
  bug and a safe immutable field-index lookup optimization. Eight proposed
  context variants cover correct, false, stale, irrelevant, missing, corrected,
  disabled and removed notes.
- Verification checks frozen source bytes, checkout bytes, manifest identities
  and the generated guide before running fixed dependency-free behavior checks.
  It rejects existing output directories and Git-hidden checkout changes. Note
  proposals are not app import payloads or fabricated capture receipts; normal
  Learn/edit/Save lesson operations remain required for a live comparison.
- All five tests in `scripts/test-repository-context-cases.py` passed on Python
  3.9.6. Log: `/private/tmp/bytequay-context-cases-tests.log`.
- Both CLI commands passed independently: legacy/base/safe tests exit zero and
  the buggy head exits one with the expected `END_EXCLUSIVE` assertion. The
  generated guide is
  `/private/tmp/bytequay-context-cases-final-zho1k1o3/cases/REVIEW.md`; machine
  results are retained beside its `cases` directory as `create-result.json`
  and `verify-result.json`.
- This increment changes acceptance tooling and documentation only. The prior
  backend/frontend/package verification still applies. No provider is launched,
  no app state is seeded and no scheduler or approval gate is introduced.
- CP6/CP7 human findings assessment, independent live On/Off comparisons,
  controlled overhead/native-memory checks and the authenticated Claude lane
  remain open. The fixture tests do not establish improved review quality.

## Isolated review and saved-lesson acceptance

- Removed expected-outcome labels from generated Git commit subjects. Live
  clones expose no evaluator tags, labels or guide; the fixed neutral corpus
  passed all five Python checks (`/private/tmp/bytequay-context-neutral-tests.log`).
- Four independent Codex reviews at identical per-pair code/settings completed
  full investigation and validation: boundary Off/On each reported one supported
  defect; safe optimization Off/On each reported none. Cold On runs saved actual
  source-linked knowledge and confirmed both bootstrap and later delivery.
- Four additional fresh reviews exercised one genuinely saved lesson through
  false v2, corrected v3, disabled and removed states. The false claim was
  explicitly rejected against source/test evidence. Every run completed with no
  finding, knowledge stayed v1, and all original context snapshots stayed intact.
  These same-workspace lifecycle runs retain prior history and are reported
  separately from the independent pairs.
- Invocation-aware token folding matched persisted usage. Single-run timings,
  packet sizes, evidence, snapshot/version checks and limitations were written
  to `/private/tmp/bytequay-context-comparison-y47wqfjn/results.json` and the
  adjacent `REVIEW-RESULTS.md`, but these temporary reports are no longer on
  disk. The
  [acceptance record](repository-context-acceptance.md#executed-isolated-small-code-comparisons)
  records the exact code identities and observations. An independent technical
  audit checked all eight accepted outcomes and snapshot preservation.
- All reviews were closed; all four disposable backends stopped; temporary
  credential records were removed and zero remaining rows verified. The initial
  final-report attempt raced SQLite shutdown; its read-only rerun after shutdown
  succeeded. No production service or UI code changed in this increment; the
  previously verified backend JAR hash is unchanged. `git diff --check` passed.
- Core feature implementation is complete. Broader stale/irrelevant/history
  cases, human adjudication, controlled overhead, native-memory behavior and
  the unauthenticated Claude lane remain acceptance work. None is an approval
  requirement for each ordinary lesson or review.

## Agent-drafted lesson verification

- **Draft with agent** now requests a structured title, summary and body from the
  retained discussion through a disposable text-only provider session. The
  request is bound to the saved review/run/target revision, reads bounded local
  evidence and returns no partial proposal on insufficient or malformed output.
  Drafting writes no notebook entry; the user edits text, chooses scope and
  evidence, then explicitly uses **Save lesson**.
- Focused backend tests passed: 15 draft-service tests and 16 SQLite review-store
  tests, with no failures or errors. They cover source/target binding, bounded
  nearby discussion, strict response parsing, insufficient output, and provider
  open/start/stop failures. Log: `/private/tmp/bytequay-lesson-draft-tests-final.log`.
  The later target-binding regression is included in the final full gate below.
- Final-source backend `mvn verify` passed with 1,943 app tests, zero failures or
  errors and zero Checkstyle violations. Log:
  `/private/tmp/bytequay-backend-verify-lesson-draft-final.log`.
- Frontend lint, `npx tsc --noEmit`, all 84 test files / 943 tests and darwin
  arm64 packaging passed. The review browser fixture exercises drafting before
  and inside the editor, failure/retry, editable text, preserved scope/evidence,
  and the separate explicit Save boundary. Electron visual inspection at
  1000×900 and 430×900 confirmed the draft action and suggestion are visible
  without scrolling or horizontal clipping. Captures:
  `/private/var/folders/mj/vy763y3s0fvf33w6bz86q3980000gn/T/bytequay-lesson-visual-6eWhKS/normal-agent.png`
  and `/private/var/folders/mj/vy763y3s0fvf33w6bz86q3980000gn/T/bytequay-lesson-visual-6eWhKS/narrow-agent.png`.
  No live provider draft is claimed here.
- CP6/CP7 provider-specific acceptance remains open: this increment did not
  exercise Claude (the dedicated profile was not yet wired into ByteQuay);
  human-adjudicated stale, irrelevant and history-assisted comparisons,
  cross-task native-memory checks and controlled concurrent overhead are not
  established by these tests. The existing workspace Off switch remains
  available, and no ordinary lesson requires a separate approval queue.

## Claude profile, first-use preparation guard and local reuse

- ByteQuay now points every Claude readiness and task process at its dedicated
  profile. A sanitized profile status returned `loggedIn=true`; the disposable
  backend reported Claude CLI 2.1.280 READY, and the live Learn/review processes
  used that profile with `autoMemoryEnabled=false` in generated settings.
- A first Claude review of an empty project notebook completed with no findings
  but called no shared preparation tool. This was a real cold-start behavior
  gap: the project fingerprint was complete and the missing scope was delivered,
  yet the investigation accepted empty preparation references.
- Added frozen first-use scope IDs to the run snapshot and assignment, plus a
  first-investigation gate. A scope with complete inputs and no reusable EMPTY
  result now needs a recorded BEGIN; owned work needs a cited SUBMIT receipt.
  Reuse, joined/deferred attempts, failed reads and changed catalog routing
  retain their explicit receipt or limitation paths. Older persisted snapshots
  deserialize without a retroactive requirement.
- An explicit Claude **Learn project** then created `knowledge:project` v1 from
  five captured source reads. A fresh review selected and received that entry
  without rebuilding; it marked source applicability `VERSION_MISMATCH` because
  the reviewed change edited a learned file, and checked the code and tests.
  This reuse run is not an independent quality-comparison arm.
- Focused guard tests and elevated full backend `mvn -q verify` passed: 2,856
  tests, zero failures/errors/skips. Frontend code did not change in this repair.
  A rebuilt-JAR cold Claude repeat recorded OWNED BEGIN, APPLIED SUBMIT with an
  explicit EMPTY outcome, and both receipt references in the accepted
  investigation. It completed with confirmed packet delivery and complete
  coverage. The guard prevented silent skipping; the agent did not create a
  note because it judged the tiny fixture not worth a durable summary. Live
  lesson drafting, human judgment and controlled concurrent resource acceptance
  remain open.
- A final code review found two edge cases and both are fixed with regression
  tests: a full JOINED/DEFERRED activity journal now reserves one bounded
  decision for each still-undecided required scope, and a mixed reused/deferred
  BEGIN keeps a receipt that must be cited for the reused scope. The last full
  verify log is `/private/tmp/bytequay-context-full-verify-cap.log`.

## Live Claude On/Off comparison checkpoint — 2026-09-24

- A synthetic PR review ran with context Off and warm On in separate databases,
  checkouts and Claude Sonnet/high conversations. Both found the one seeded
  half-open-window regression with no extra finding. On delivered one learned
  project note in confirmed packets; neither arm opened a note dynamically.
  The preserved evidence and limits are in
  [the review comparison](../evaluation/repository-context-review-compare/README.md).
- A separate synthetic development investigation used the same frozen source,
  prompt and model in independent Off and warm-On arms. Both correctly declined
  a false bug fix, passed two focused tests and left clean worktrees. On had one
  confirmed 2,348-byte note offer; neither scored arm searched or read a note.
  A distinct, unscored On task confirmed SEARCH and exact READ of that note,
  after two rejected extra-parameter attempts. Evidence is in
  [the development comparison](../evaluation/repository-context-development-compare/README.md).
- The Claude CLI allowlist now includes the notebook lookup tool only when its
  task-scoped bridge is registered, with open/resume regression coverage. The
  full backend `mvn verify` passed 2,892 tests with zero failures, errors or
  skips when run with local process-group access. The sandboxed attempt could
  not run process-supervisor tests because the sandbox denied `sysctl`/process
  group inspection; it is not a product test failure.
- These two easy pairs show confirmed delivery and correct outcomes, not a
  measured quality gain. The proposed development oracle is not human-approved.
  Stale, irrelevant, misleading and history-assisted cases, sustained resource
  measurements and human judgment remain CP6 acceptance work. The Codex live
  review attempt was stopped by this host's managed-isolation guard before a
  model turn; it was excluded from the comparison.

## Codex lookup and next history case — 2026-09-24

- The earlier Codex 503 was traced to ByteQuay's own App Server launch flag
  `forced_login_method="chatgpt"`: it made `configRequirements/read` non-null.
  The launch no longer sets that flag; the strict null-requirements guard stays,
  and `account/read` now verifies a ChatGPT account before each turn. A read-only
  real App Server probe confirmed that response shape for ByteQuay's isolated
  profile. The fix and evidence are committed as `49a5b8c17`.
- A real Codex development task then received a confirmed 2,348-byte offer,
  confirmed 789-byte SEARCH and confirmed 12,252-byte exact READ of
  `knowledge:project` v1. It compared the note with source and README, reported
  no discrepancy, passed two tests and left a clean worktree. Independent test
  and worktree checks passed. This was an instructed, unscored lookup smoke, not
  spontaneous discovery or a paired quality win. See
  [the Codex acceptance record](../evaluation/repository-context-codex-lookup/README.md).
- Focused Codex tests passed (94 tests). Full `mvn verify` had one unrelated
  Claude HTTP event-order test fail under concurrent execution; that exact test
  passed on isolated rerun. A subsequent full `mvn verify` passed every module
  and all 1,978 app tests without failures, errors or skips. The package hash
  stayed the same as the live fixture. No frontend source changed.
- The next case tested a recorded decision in merged PR #7 that is absent from
  the current repository README and ordinary tests. Explicit Learn reused the
  cached merged PR and three baseline files to create `knowledge:project` v1,
  including a sourced `RECORDED_DECISION` claim. Independent Off and warm-On
  Claude reviews of PR #8 both reached ACCEPTED with complete coverage and the
  same single boundary finding. On received two confirmed context packets and
  marked the learned source `VERSION_MISMATCH`; it made no dynamic note open or
  note citation. Off found PR #7 through ByteQuay's selected-PR tool, so this
  pair shows successful learning/delivery but no attributable quality gain.
  The proposed oracle remains unapproved and unscored. The fixture, cache seed,
  Learn evidence, paired results and limits are in
  [the history comparison](../evaluation/repository-context-history-compare/README.md).
- Remaining CP6 acceptance: human adjudication of the proposed oracles,
  spontaneous Codex discovery, stale/misleading/irrelevant note cases, resolved
  issue history, and controlled concurrent CPU, memory and SQLite-wait checks.

## Unprompted Codex development checkpoint — 2026-09-24

- Fresh Codex Off/On development tasks used the same frozen inclusive-end
  commit, ordinary change request, model and effort in separate databases and
  worktrees. On received a confirmed project-note offer and independently
  opened `knowledge:project` v1 with a confirmed exact READ, without being
  asked to use notes or the lookup tool. It did not SEARCH because the offer
  already contained the note identity. Both arms declined the proposed change,
  passed the existing test and boundary probe, and left clean worktrees. This
  proves an unprompted open of an offered note, not a quality gain; see
  [the offered-note comparison](../evaluation/repository-context-spontaneous-development/README.md).
- A second pair tested dynamic discovery without preloading the answer. A
  normal selected-scope Learn from cached merged PR #7 created generated
  `knowledge:window` with a recorded-decision claim. The natural customer
  prompt matched no component term, so On's confirmed initial offer contained
  zero bytes and zero entries. On made no SEARCH or READ; both arms instead
  inspected local Git history, restored the inclusive end and added tests that
  fail before the fix and pass after it. Independent fixture and diff replays
  confirmed the source behavior. The usage view correctly leaves the built
  component note without confirmed delivery or open. See
  [the component-note comparison](../evaluation/repository-context-spontaneous-component/README.md).
- Neither pair shows a measured quality gain. Both proposed oracles remain
  unapproved by a human. The missing project-scope count in the second On run
  refers to its empty project overview, not the built component note. Keep
  dynamic lookup optional; one task that solved the problem through local Git
  history does not justify making every development turn search. Remaining CP6
  work includes human judgment, misleading/stale/irrelevant-note cases,
  resolved-issue history and sustained resource measurements.
