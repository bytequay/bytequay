# Agent Review Implementation Checklist

Canonical behavior is locked in [Agent Review](agent-review.md). This is a
temporary delivery tracker, not an alternative design. These checkpoints are
specific to Agent Review and do not reopen the completed agent-service CPs.
Unchecked means not yet verified, even if similar infrastructure exists.

## Working and verification rules

- [x] Read `ByteQuay.md`, `CONTRIBUTING.md`, the design index, Agent Review,
  and the relevant agent/worktree/CodeGraph/UI contracts before implementation.
- [x] Recheck the current tree; preserve unrelated and in-progress changes.
- [x] Keep product behavior in the canonical design. Record checkpoint test
  commands, results, evidence paths, and any blocker here without dated logs.
- [x] Check a box only after its behavior exists and its stated test passes.
  A mock, schema, compiling stub, or test skipped for missing credentials is
  not production/provider evidence.
- [x] Keep every checkpoint scoped and runnable. Do not disable existing
  isolation, Checkstyle, HTTP-contract, or regression tests to pass a gate.
- [x] Do not call GitHub write APIs during development verification without
  a separate direct user action selecting the exact test target/payload.
- [ ] Use fixture providers and disposable real Git repositories for default
  tests. Live provider tests are opt-in and use the existing safe auth setup.
- [x] Any change to the locked boundary requires user approval and a design
  update before implementation continues in that area.

## Review quality process

The user approved the simple investigation/validation process in the canonical
design. This section records its current verification; the original delivery
checkpoint evidence below is not a claim that unrelated gaps were rechecked.

- [x] Reuse one task, worktree and provider conversation for investigation and
  mandatory validation, with one shared reporting-repair opportunity.
- [x] Persist the change map, assessed changed files, context references,
  numbered candidate hypotheses and final supported/rejected conclusions.
- [x] Require validation before new findings; require every commit, required
  changed path, candidate and new finding to be accounted for before accepting
  complete coverage. Explicit partial reports remain incomplete and cannot
  advance the successful cursor.
- [x] Recover accepted validation/repair turns by deterministic request keys
  without duplicating them. Respect pause and runtime quiescence.
- [x] Compare frozen previous/head Git snapshots for incremental file scope,
  including reverted-to-base paths and patchless changes. Expose frozen head,
  merge-base and previous-head source evidence.
- [x] Guide behavioral controls for fixes/features, failure reproduction for
  remaining defects, and source/contract/runtime counterevidence. A compiler or
  setup failure does not prove a behavioral regression. Preserve command exit
  status and restore only the reviewer's own experiment edits.
- [x] Show Investigating/Validating from actual run facts and collapsed Review
  details from retained evidence; keep draft hypotheses out of Findings.
- [x] Backend `mvn verify`: 2,093 tests, zero failures/errors/skips, all modules
  and Checkstyle passed. Evidence: `/tmp/bytequay-review-verify.log` and module
  Surefire XML. The process-supervision tests require normal process visibility;
  the successful full run used sandbox escalation rather than skipped tests.
  The later evidence-display correction passed 59 focused tests and rebuilt the
  application with `mvn verify -pl app -am
  -Dtest=TestDefaultAgentReviewService,TestReviewTools
  -Dsurefire.failIfNoSpecifiedTests=false`; evidence:
  `/tmp/bytequay-review-evidence-verify.log`.
- [x] Frontend full suite: 895 tests across 80 files, recorded in
  `/private/tmp/bytequay-review-ui-tests.log`. The final status/scope
  refinements passed 75 focused tests plus lint and TypeScript. Final
  `npm run package` passed with the newly built backend JAR; evidence:
  `/tmp/bytequay-review-package.log`.
  The evidence-display regression also passed those 75 tests, lint and
  TypeScript; logs are `/private/tmp/bytequay-review-evidence-ui-{tests,lint,tsc}.log`.
- [x] Normal/narrow component visual checks: 900px and 420px snapshots under
  `/private/tmp/bytequay-review-details-P69xQk/`.
- [x] Complete a fresh live review after the update and compare its evidence
  with the same PR's original review.

`TestReviewTools` (22 tests) covers the seven fixed tools, pass ordering,
candidate accounting, exact summary/file coverage, frozen comparison evidence
and reverted/patchless incremental paths. `TestDefaultAgentReviewService`
(37 tests) covers orchestration, one repair budget, partial results,
recovery and validation evidence omitted from the initial context selection.
It does not require the model to repeat that selection to expose later proof.
`TestSqliteReviewStores` round-trips investigation, validations and
the validation-start fact. The incremental file-list regression mocks Git;
it verifies command/result handling rather than a real-repository experiment.

The live baseline reviewed `trinodb/trino#31125` at head
`2d9a8e7e28ac220fee0dc05bf5c3792274518a1c` using the configured Claude lane.
It returned zero findings and ran all six `TestBitArrayBlock` tests successfully,
including `testCopyRegion`. Its report explicitly said it did not run that test
against the pre-fix behavior. Sanitized report and event evidence is retained at
`/private/tmp/bytequay-review-quality-baseline.json` and
`/private/tmp/bytequay-review-quality-baseline-events.json`. The baseline review
was ended through its normal lifecycle after only its verified generated test
artifacts were parked under `/private/tmp/bytequay-review-quality-baseline-artifacts`.
No source edits or GitHub writes were made by that baseline.

The updated review used the identical base/head, automatically completed two
turns, and was accepted with complete coverage, no omissions and zero findings.
Without additional steering it ran 35 passing tests (`TestBitmap`,
`TestBitArrayBlockBuilder`, `TestBitArrayBlock`), restored only the old condition,
and confirmed `testCopyRegion` fails with an assertion and Maven exit 1 while
the bitmap tests still pass. Its separate eight-case reproduction passed at
HEAD and failed six cases under the compiling old-condition control. It
restored the PR source and recompiled successfully. Both candidate conclusions
were recorded; the shared-array hypothesis was rejected against source contracts.

Review `5e959b9d-c70d-44c0-ab4c-a2a9c4f244f5` remains open for inspection and
discussion, with no active turn, claim or approval. Its worktree matches the
frozen head with clean tracked/untracked source; ignored build and provider
scratch outputs remain in that review's worktree. The two-turn run took 328.3
seconds versus 115.7 seconds for the baseline. This one PR verifies workflow
and evidence behavior, not general review precision or recall. The new process
has been exercised live on Claude; no new live Codex run is claimed here.

Evidence is retained under `/private/tmp/bytequay-review-quality-updated.json`,
`/private/tmp/bytequay-review-quality-updated-events.json` and
`/private/tmp/bytequay-review-quality-updated-stored-evidence.json`.
The live check found that the initial context selection hid later validation
references in the HTTP/UI projection even though all 34 were stored durably.
The projection and disclosure now expose all retained evidence with reference
IDs. E25 contains the Maven old-condition failure, E26 the 35-test HEAD success,
and E31 the focused reproduction/control comparison.
An idle-checked normal app restart confirmed that the existing accepted/open
review exposes all 34 references, including those exact outputs, while retaining
the same two turn IDs without launching another provider turn. Readback evidence:
`/private/tmp/bytequay-review-quality-updated-after-restart.json`.

These checks establish ordering, provenance and honest reporting structure;
they do not establish that the model's semantic conclusions are correct.
Validation is another turn in the same conversation, not an independent reviewer.

## CP0 — Reuse inventory and UI reference

Entry: canonical design is available; no production work is claimed.

- [x] Map current PR tabs/cards/composers/icons/CSS and existing task transcript
  components to the proposed UI. Record genuinely missing pieces.
- [x] Inventory current pending-review state, persistence, publication
  idempotency, line/range handling, and workflow-only route restrictions.
- [x] Use the canonical UI-design prompt to produce a local-only prototype
  with the required states and a component/style reuse map.
- [x] Obtain explicit visual approval and save/link the approved reference
  under `docs/design/`; never treat the conversation sketch as pixel approval.
- [x] Confirm the plan adds no new agent runtime, generic comment/tool
  framework, Maven module, UI library, or GitHub polling engine.

Exit condition: reuse inventory is concrete; production UI work waits for
the approved reference. Backend contract work may proceed independently.

Verification/evidence: reuse is real rather than claimed. The pane renders
Markdown through the existing `MarkdownBody`, and Activity reuses the agent
task screen's exported `TurnView` and `projectTranscript` rather than a second
transcript. `frontend/src/css/agent-review.css` carries the pull-request page's
own tokens; `src/pulls/agent-review/AgentReviewTab.test.tsx` asserts the card
border, header fill and the single green primary against those values.

The approved UI reference is checked in at `docs/design/agent-review-ui/`
(prototype, `support.js`, and the four approved screenshots under
`screenshots/`), so pixel approval is a file, not a conversation.

The change adds no Maven module, no npm dependency, no agent runtime and no
poller: head observation is a listener on the existing PR refresh
(`PullRequestDetailRefreshed`, `PullRequestsRefreshed`) plus the existing
settled task-event stream.

## CP1 — Durable local review model

Entry: canonical ownership and lifecycle invariants are understood.

- [x] Add the minimal review/run/summary/finding/discussion storage contracts
  and SQLite mappings using current module/package conventions.
- [x] Associate a review with logical PR identity and one workflow-owned
  agent task; do not confuse remote branch identity with generated worktree.
- [x] Store frozen scope, successful cursor, result revisions, user intent,
  and stable question/finding identity; derive UI activity states.
- [x] Keep review product data and bounded evidence independent of disposable
  task logs; prevent PR cache eviction from losing active/unpublished work.
- [x] Add idempotent creation, optimistic updates, and an atomic
  accepted-run/successful-cursor transition.
- [x] Enforce one open review per workspace/PR. Make drafts PR/account-owned,
  with stable item IDs and optional finding links; manual-only review works
  without an agent task/review. Persist review body/event as well as items.
- [ ] Test SQLite round trips, duplicate create/update delivery, stale writes,
  same PR number in different repositories/workspaces, and retention boundaries.

Exit condition: local facts survive restart, cross-review access is rejected,
and no test needs a provider or GitHub write.

Verification/evidence: `mvn verify` (see CP8) with
`TestSqliteReviewStores` (8 tests): `roundTripsEveryReviewRecordThroughSqlite`,
`roundTripsDraftWithManualAndPromotedItems`,
`enforcesOptimisticVersionsOnReviewsRunsAndFindings`,
`rejectsDuplicateReviewIdentifierInsteadOfMerging`,
`findsOnlyOpenReviewsAndIgnoresRepositoryCase`,
`keepsReviewsOfTheSameNumberInDifferentWorkspacesApart`,
`savesDraftOnFirstWriteAndRejectsStaleVersions`,
`acceptRunCommitsTheRunAndTheReviewCursorTogether`. Idempotent creation is
`TestDefaultAgentReviewService.startIsIdempotentForTheSamePullRequest`.

Review data lives in its own tables with no foreign key to the pull-request
cache, and a draft's identifier is `repo#number`, so evicting a cached pull
request cannot take unpublished work with it.

Not verified: retention boundaries. Nothing yet deletes review rows, so there
is no retention behaviour to test; that arrives with CP8's retention work.

## CP2 — Exact snapshots, context and safe worktree ownership

Entry: CP1 passed; shared worktree/agent execution contracts remain intact.

- [ ] Resolve/fetch authoritative PR base/head including fork PRs through
  existing backend services; provision one exact managed worktree.
- [x] Bind every context/anchor request to the stored review/run. Expose
  bounded paginated PR, timeline, issue/PR, diff, source and evidence reads.
- [x] Build deterministic per-line anchors for the frozen publication diff,
  including deletion, rename, single-line and same-side multi-line ranges.
- [x] Record base tip, merge base and head separately; never use experimental
  worktree HEAD as the successful review cursor.
- [ ] Serialize review sync with core turn/queue/tool/completion execution.
  Add the narrow durable WORKTREE_SYNC claim and core acquire/settle seam
  under the existing task lock/version path, then the workspace-operation
  lock across inspect/FF. Test queued-start and cleanup races plus unknown
  mutation recovery before enabling sync; no parallel lock registry.
- [ ] Preserve dirty, staged, untracked, local-commit, open-operation,
  wrong-branch, symlinked or mismatched worktrees; never auto-reset/stash/commit.
- [x] Reuse optional CodeGraph with no precomputed evidence pack or review
  refresh hook. Off/unavailable graph must not block review creation.
- [ ] Test real-Git snapshot construction, fork routing, renamed/deleted
  lines, binary/truncated patches, path traversal, context truncation and
  unauthorized repository/URL/evidence references.

Exit condition: every returned anchor/reference has verifiable provenance;
unsafe Git state is preserved, not repaired implicitly.

Verification/evidence: anchors are covered by `TestReviewDiffAnchors`
(9 tests), including added/deleted/context sides, hunk headers with and without
counts, patchless files, the frozen per-side excerpt, the ranges publication
must refuse, and the three remap outcomes (unmoved, followed, ambiguous).
Context binding and path safety are
`TestReviewTools.bridgeIsExposedOnlyToTheTaskOfAnOpenReview` and
`getContextReadsDiffAnchorsAndRefusesPathsOutsideTheRepository`. A run records
`baseSha`, `mergeBaseSha` and `headSha` separately, and only
`AgentReviewStore.acceptRun` advances the cursor, proven by
`TestSqliteReviewStores.acceptRunCommitsTheRunAndTheReviewCursorTogether`.
CodeGraph stays optional:
`TestReviewToolBroker.codexTokensExposeReviewToolsWithoutCodeGraphAndDispatchJsonArguments`
registers the six review tools with no graph present.

Not verified, and the largest remaining gap in this checkpoint:

- Git is a Mockito mock in every review test (`GitCommandRunner`), so the
  snapshot fetch, the fork refspec `+refs/pull/N/head:refs/bytequay/review/N`,
  the dirty-worktree refusal and the guarded fast-forward are asserted only at
  the command level. No disposable real repository exercises them yet.
- The narrow durable `WORKTREE_SYNC` claim was not added.
  `WorktreeMutationClaim.Kind` still has only `TURN`, `COMPLETION` and
  `RECOVERY`. The service instead reads the task's current claim and moves the
  worktree only when none is held, which leaves a race window between that
  check and the fast-forward. This is a deliberate deviation from the design
  and must be closed before the sync path is trusted under load.

## CP3 — Managed review capability and local tool validation

Entry: CP2 passed; tools operate only on persisted review bindings.

- [x] Add only the six fixed tools listed in the design to the existing
  task bridge, scoped to the review root agent and active run/turn.
- [x] Probe root/child MCP inheritance with each actual provider. If caller
  identity cannot enforce root-only tools, select the existing no-child
  review profile and advertise that restriction; do not rely on a prompt.
- [x] Supply managed review instructions on initial and resumed sessions;
  make no repository/global instruction-file changes.
- [x] Keep review tooling independent of graph availability and deny tools
  to ordinary tasks, unrelated tokens, completed/stale turns and child agents.
- [x] Validate strict bounded schemas, required fields, anchors, evidence,
  summary commit references, revisions and discussion targets in the backend.
- [x] Require operationKey on write tools and persist key/payload digest/result
  atomically. Test same-key lost-response replay and different-payload conflict;
  do not use connection-local JSON-RPC IDs as durable identity.
- [x] Persist bounded repair budgets. Return
  specific errors rather than guessing paths or parsing numbered Markdown.
- [x] Test missing/malformed fields, omitted tool calls, invalid ranges,
  fabricated evidence, stale revisions, duplicate calls and exhausted repair.
- [ ] Verify both provider registration/resume paths using executable fixtures
  and opt-in live smoke tests before declaring that lane review-capable.
- [x] Verify the exact MCP allowlist, managed prompt, token redaction, no
  publisher capability and the review-specific native-tool guard before AUTO
  approval. Test direct remote-write/credential requests on both adapters.
  Keep unsupported lanes visibly unavailable for review, not for ordinary tasks.

Exit condition: valid local reports persist; invalid reports never create
inline comments; reviewed provider boundaries have real evidence, not only
a mock. No tool can invoke ByteQuay's GitHub publisher.

Verification/evidence: `TestReviewTools` (10 tests) covers the six tools,
their scoping to an open review's task, schema and anchor validation, evidence
that cannot be resolved, `operationKey` replay versus a different-payload
conflict, summary revision, the missing-summary report from `review_finish`,
and withdrawal that requires the current revision without undoing a user's own
resolution.

Both adapters refuse remote writes before any approval card is shown:
`TestReviewBridge` (2 tests) proves the deny list rejects `git push`,
`gh pr comment`, `gh api -X POST`, `cat ~/.config/gh/hosts.yml`,
`security find-generic-password` and `echo hi; git push` while leaving
`git status`, `gh pr view` and `./mvnw test` alone.
`TestReviewToolBroker.claudeRegistrationsCombineInstructionsAndRefuseRemoteWritesBeforeApproval`
asserts zero approval-handler invocations for a refused `Bash` command, so the
guard is not a prompt and not a card the user could accept.

Root-only access is a launch property rather than an instruction:
`TestCodexReviewProfile` (2 tests) asserts every configured child profile
collapses to `ChildProfile.NONE` for a review, that the launch arguments then
disable both multi-agent flags, and that an ordinary task keeps its own profile.

The managed instruction reaches both lanes on initial and resumed sessions
because the broker composes it (`registration.instruction()`); the Codex lane
delivers it with the tool registration and the Claude lane appends it with
`--append-system-prompt`, asserted in `TestReviewToolBroker`.

Claude subagent correction: the review launch now selects a fixed
native file/shell/source tool list, disables slash commands, and denies
`Agent`, `Task`, `Skill`, `Workflow`, and `SendMessage` before approval.
Ordinary task delegation remains available. The projection advertises no
subagents for either review lane rather than copying the ordinary driver's
capability. Installed Claude Code 2.1.263 was exercised with that exact native
profile: only Bash, Edit, Glob, Grep, NotebookEdit, Read, TaskOutput, TaskStop,
WebFetch, WebSearch and Write were available, with no skills, slash commands,
delegation, child resume, remote workflow or timer tools. Separate live
foreground/grandchild and background probes verified native parent invocation
IDs and terminal events for ordinary tasks. Sanitized foreground/background
streams are retained in agent-cli's Claude test resources. This verifies the
native no-child profile; it does not establish root-versus-child identity on
business MCP callbacks. Codex live provider isolation remains unverified here.

Review tooling cannot reach ByteQuay's GitHub publisher at all: the six tools
are the whole surface, and none of them touches `ReviewPublicationService`.

## CP4 — First review and multi-turn continuation

Entry: CP1–CP3 passed for the lane being enabled.

- [x] Start a workflow-owned task with ByteQuay's initial review request and
  record its attribution without pretending the user authored that prompt.
- [x] Use attended AUTO sandbox/approvals rather than an unattended CI or
  cherry-pick policy. Verify authored/closed PR private-review eligibility
  independently of remote publish eligibility.
- [x] Keep execution running independently of PR selection/renderer lifetime;
  reuse agent events, replay/SSE, approvals and existing turn settlement.
- [ ] Subscribe backend review reconciliation to settled durable task events;
  recover it at startup and invoke it on backend PR refresh. Test duplicate
  events and crash after terminal commit/before callback with no renderer,
  including completion repair and pending-head progression. No new poller.
- [x] Accept structured summaries/findings progressively as provisional local
  results. Require valid finish/coverage and settled turns before Ready.
- [x] Allow one bounded completion-repair opportunity; no valid report means
  Incomplete, never a clean zero-finding review.
- [x] Keep task/worktree open after a run; support later investigation turns
  and experiments without requiring a commit each turn.
- [x] Verify original-snapshot evidence versus experiments; distinguish actual
  test results, tests not run and agent assertions.
- [ ] Test valid findings, explicit zero findings, mixed valid/invalid output,
  partial coverage, no tool calls, failed tests, provider failure, cancellation,
  approval waits and renderer disconnect/reconnect.

Exit condition: the first full review is useful and truthful; a completed
run leaves the same task available for questions without a new worktree.

Verification/evidence: `TestDefaultAgentReviewService` (14 tests). The task
is workflow-owned and carries ByteQuay's own request rather than pretending the
user wrote it
(`startCreatesOneTaskAndOneRunningRunDrivenByAByteQuayRequest`); a pull request
outside the monitored repository is refused
(`startRefusesAPullRequestOutsideTheMonitoredRepository`). Acceptance requires
a summary and coverage on a settled turn
(`reconcileAcceptsACompletedRunWithASummaryAndCoverage`), one repair turn is
allowed (`reconcileSpendsTheRepairBudgetOnAMissingCoverageRecord`) and a spent
budget yields Incomplete rather than a clean zero-finding review
(`reconcileMarksARunIncompleteOnceTheRepairBudgetIsSpent`). Cancellation stops
the run and pauses follow-up
(`reconcileStopsTheRunAndPausesFollowUpForACancelledTurn`). The task stays open
for later questions (`askStartsATurnWhenTheTaskIsIdle`).

Provisional results are visible as provisional: `model.test.ts` asserts
`marks results provisional until their run is accepted` and
`AgentReviewTab.test.tsx` asserts `blocks publication of a provisional finding
and says why`. Evidence carries its own provenance and the card renders it
(`saveFindingAnchorsCitedEvidenceOnTheFrozenDiff`, `shows the location,
severity, identity and evidence`).

Not verified:

- Startup recovery of the reconciliation subscription, and duplicate delivery
  of the same settled event, have no test. `ReviewTaskObserver` restores on
  `ApplicationReadyEvent`, but nothing exercises a crash after the terminal
  commit and before the callback.
- Provider failure and renderer disconnect/reconnect are untested.

## CP5 — Incremental review and head updates

Entry: CP4 passed; a fully completed review cursor exists in fixtures.

- [x] Observe new PR heads through existing backend refresh and manual Update
  review; no dependency on an open page and no second polling engine.
- [ ] Coalesce new heads and wait for the current run, queued discussion and
  worktree safety checks before a guarded fast-forward.
- [ ] Persist expected/target sync facts and verify post-Git state; reconcile
  crash-before/crash-after mutation without blind replay.
- [ ] Compare from the last fully completed head, not the latest attempt.
  Recheck prior findings and update the overall PR/commit summary.
- [ ] Use full scope for no predecessor, base/merge-base change, rewritten or
  unavailable ancestry. Stop for explicit realignment if the worktree cannot
  move safely; full scope is not authority for a force reset.
- [x] Preserve IDs, discussions and user resolutions; validate any new anchor
  mapping, mark ambiguous findings outdated, and do not resolve by omission.
- [x] Pause follow-up execution after Stop/failure/recovery until explicit
  resume. Never update a worktree beneath an active turn or tool callback.
- [ ] Test H1→H2, several coalesced pushes, unchanged head, changed base,
  force push, local experiments/commits, publication-diff versus delta anchors,
  repeated findings, vanished code and user-resolved findings.

Exit condition: only fully accepted runs advance the cursor; new revisions
cannot silently republish, relocate, resurrect or erase old findings.

Verification/evidence: head observation reuses the existing refresh and
records only against the matching review
(`TestDefaultAgentReviewService.observeHeadRecordsTheNewerHeadOnlyOnTheMatchingReview`);
the frontend derives the New commits state from that fact
(`model.test.ts`, `reports New commits when the observed head moved past the
reviewed one`). Identity survives a new head: `TestReviewDiffAnchors`
`remapKeepsAnAnchorWhoseExcerptDidNotMove`,
`remapFollowsAnExcerptToItsSingleNewLocation` and
`remapGivesUpWhenTheExcerptIsAmbiguousOrGone` give the outdated marking its
rule, and `TestReviewTools.withdrawFindingLeavesAUserResolutionStanding` proves
an agent withdrawal never undoes the user's own resolution. Stop pauses
follow-up until an explicit resume
(`stopCancelsTheActiveTurnAndPausesFollowUp`), and the sync path refuses to run
under a live turn claim.

Not verified. This is the least-tested checkpoint:

- Coalesced pushes, unchanged head, changed base, force push and local
  experiment commits have no test.
- The incremental comparison base (the last accepted head, not the latest
  attempt) and the full-scope fallbacks are implemented but unasserted.
- No durable expected/target sync record exists, so crash-before versus
  crash-after a worktree mutation cannot be reconciled; see the CP2 claim gap.

## CP6 — Private challenge, draft and publication safety

Entry: stable revisions, task input delivery and anchor validation are tested.

- [ ] Add review-scoped HTTP/IPC operations delegating to existing task/core
  services; preserve interactive-only endpoint guards.
- [x] Route summary/finding questions and replies by identity/revision. Idle
  Ask starts a turn; busy Ask queues; Steer remains a distinct gated operation.
- [ ] Distinguish discussion turns from review-run turns: a targeted reply
  needs no whole-review finish and cannot advance the successful cursor.
- [x] Persist discussion targets through queue/restart and explicitly handle
  newer code or finding revisions before delivering old questions.
- [x] Support agent explanation/revision/withdrawal and user local
  resolve/reopen with retained history and stale-write conflicts.
- [x] Integrate Add to my review with a durable editable local draft; preserve
  user text when the agent revises/withdraws or the user resolves the source.
- [x] Preserve manual comments in the same draft, reuse existing range
  selection and the Submit review sheet. Default new drafts to COMMENT;
  Approve/Request changes require explicit choice. Test sheet reopen persistence.
- [x] Exclude resolved/withdrawn/outdated/unconfirmed changed findings from
  publication even when previously added. Never auto-include private content.
- [x] Revalidate remote head and exact selected payload at explicit single
  comment/final review submission; derive GitHub line/range fields in code.
- [ ] Resolve local PR ID and repository/number together; reject mixed-PR or
  mixed-head batches, including manual comments in the outgoing draft.
- [ ] Accept publication by draft/item IDs and expected versions, deriving
  GitHub fields server-side. Require current-PR mapping for manual comments
  created in selected commit ranges; test mixed snapshots and cross-hunk ranges.
- [x] Reserve publication atomically against resolve/edit/duplicate-click
  races; store remote identities and reconcile uncertain delivery without
  automatic reposting. Do not claim local resolution retracts a sent comment.
- [x] Test every private action with a write-client spy asserting zero calls;
  test explicit publishing with a fixture client and exact payload assertions.
- [ ] Test resolve-before-send, resolve-during-send, agent/user edit races,
  stale head, duplicate send, timeout-after-remote-success and restart.
- [ ] Reserve the same item across single and batch sends. Test their race,
  already-published restaging, identical comments, successful POST with failed
  readback and ambiguous per-comment mapping without another POST.
- [x] Retain publication attempt/review/comment identities and distinguish
  known-send success from unknown mapping. Render Published/link state and
  one canonical remote thread, with no lingering pending clone.

Exit condition: no private interaction reaches GitHub. Only a selected,
currently eligible comment can pass an explicit user publication boundary.

Verification/evidence: `TestReviewPublicationService` (12 tests) is the
publication boundary's evidence. Every private action is asserted against a
write-client spy with zero calls
(`everyLocalEditBumpsTheVersionAndNeverReachesGitHub`), and only the two
explicit user actions send, with exact payloads
(`publishItemSendsOneCommentPinnedToTheCurrentHead`,
`submitSendsOnlyTheIncludedUnpublishedItems`). Publication is refused for a
finding that moved on (`addFindingRefusesAFindingThatCannotBePublished`,
`submitRefusesAResolvedFindingBeforeAnythingIsSent`,
`ineligibleExplainsEveryItemWhoseFindingMovedOn`). Reservation is atomic and
one-way: a failed send leaves the item uncertain and blocks a resend
(`aFailedSendLeavesTheItemUncertainAndBlocksAResend`), a published item is
never sent again by either route (`aPublishedItemIsNeverSentAgainByEitherRoute`),
and a successful submit resets the draft
(`aSuccessfulSubmitResetsTheReviewAndMarksTheItemsPublished`). A promoted item
is a snapshot: editing it leaves the finding alone and vice versa
(`addFindingSnapshotsTheFindingWithoutTouchingIt`,
`editingAPromotedItemLeavesTheFindingAlone`).

Question routing is `TestDefaultAgentReviewService.askStartsATurnWhenTheTaskIsIdle`,
`askEnqueuesWhenTheTaskIsBusy` and
`askRefusesAStaleRevisionAndAWithdrawnFinding`; local resolution is
version-checked (`resolveAndReopenAreVersionChecked`). New drafts default to
COMMENT (`aPullRequestWithoutADraftGetsAnEmptyCommentReview`), and the tab
counts promoted findings alongside manual comments
(`projection.test.tsx`, `counts promoted findings in Submit review alongside
manual comments`).

Not verified:

- There is no HTTP contract test for `/api/agent-review`. Every route resolves
  the review before the task, so a workflow-owned task cannot be reached by
  task id, but that is asserted by reading the controller, not by a test.
- Mixed-pull-request and mixed-head batches are refused structurally (a draft
  is scoped to one `repo#number` and each send pins the current head) but have
  no test of their own.
- Resolve-during-send, a restart mid-publication, and the two send routes
  racing for the same item are untested.

## CP7 — Consistent PR-tab UI

Entry: CP0 visual approval and the relevant CP4–CP6 backend contracts passed.

- [x] Add the sibling Agent review tab without changing navigation/list
  columns or inserting agent content into the GitHub timeline.
- [x] Reuse actual PR card/composer/icon/button/typography styles. Extract
  only the presentational pieces shared by real callers, with local handlers.
- [x] Render Summary & comments plus Activity and the required empty/running/
  approval/ready/incomplete/resolved/draft/new-head states from backend facts.
- [x] Link the same finding/discussion between Agent review and Changes,
  including line ranges and historical/outdated locations.
- [x] Add a focused navigation target to the existing diff viewer so a finding
  opens its file, unfolds it, and scrolls/highlights its validated range.
  Show linked draft state without duplicating the finding card.
- [x] Reuse transcript/queue/approval presentation and capability gates; no
  raw protocol JSON, hidden reasoning, fabricated timer or progress percentage.
- [ ] Verify private labels, publication previews, disabled/stale controls,
  preserved composer drafts, pending input and clear delivery errors.
- [x] Add component/integration tests for navigation, shared finding state,
  scoped questions, local resolution and publication gating.
- [x] Compare against current PR details and the approved reference at normal
  and narrow pane widths in the app's supported light appearance, keyboard-only navigation,
  long file paths, long Markdown, folding and loading/error states.

Exit condition: visual consistency is demonstrated with screenshots and
interaction tests; original PR/Changes behavior remains intact.

Verification/evidence: 66 frontend tests across
`src/pulls/agent-review/model.test.ts` (26),
`AgentReviewTab.test.tsx` (29) and `projection.test.tsx` (11).

The third tab is additive: `adds Agent review beside the existing tabs without
changing them` keeps the existing counters and classes, and
`marks whichever tab is open, including the third one` proves exactly one tab
is pressed. The same finding appears in both projections from one state, not a
copy (`shows the same private discussion, not a copy`,
`labels the way back differently in each projection but keeps one identity`).
The diff hook is narrow and asserted: the card mounts once, at the end line of
its validated range, with the range highlighted and nothing drawn when the pull
request has no review (`mounts the finding card at the end line of its range`,
`highlights exactly the navigated range`,
`draws nothing of its own when the pull request has no review`). Outdated and
withdrawn findings stay out of the diff, since their lines no longer map.

Visual and keyboard pass: the twelve states were rendered from the real
components and compared with `docs/design/agent-review-ui/screenshots/` at
854px and 600px. At 600px the pane does not scroll horizontally
(`scrollWidth === clientWidth === 980`). Across all twelve states there are 200
focus stops; every one is labelled and every one matches a `:focus-visible`
ring rule. That ring was missing when the pass started, which is why
`agent-review.css` now carries one and
`rings every control it owns, since the app has no global focus style` guards
it. The composer takes Escape and Cmd/Ctrl+Enter.

One deliberate change to a shared file: `.pull-detail__toolbar` and
`.pull-detail__tabs` gained `flex-wrap`, because a third tab clipped at 854px.
Two tabs still never wrap, so the existing appearance is unchanged.

Not verified: composer draft preservation across a tab switch. The frontend
suite has no DOM (`renderToStaticMarkup` only), so state that only exists
between renders cannot be asserted there.

## CP8 — Recovery, retention and release gate

Entry: every enabled-lane functional checkpoint passed.

- [ ] Exercise backend loss around start, reporting, queued questions, sync,
  cursor advancement, publication, End review and worktree cleanup.
- [x] Verify Stop preserves the review and pauses new work; End review obeys
  terminal-task → durable closed-review → normal cleanup ordering.
- [ ] Verify dirty/open/uncertain worktrees survive; generated branches remain;
  graph deletion failures do not fail successful cleanup or ordinary review.
- [ ] Verify detailed log retention does not remove review discussions,
  evidence excerpts or user drafts; PR cache retention respects local work.
- [ ] Verify both supported provider lanes end to end, including review with
  CodeGraph on, off, absent and failing. Do not call an untested lane complete.
- [x] Run `mvn verify` in `backend/`.
- [x] Run `npm run lint`, `npx tsc --noEmit`, `npm test`, and
  `npm run package` in `frontend/`.
- [x] Run `git diff --check`; inspect the final diff for unrelated changes,
  secrets, dependency additions and weakened gates.
- [x] Reconcile implementation with canonical design and cross-links; record
  remaining limitations honestly. No checkbox is satisfied by a TODO/stub.

Release condition: all required gates pass, visual approval exists, no
unresolved data-loss/publication/isolation issue remains, and unsupported
capabilities are explicitly unavailable. Keep this tracker until its evidence
has a retained home; removal requires a separate requested cleanup.

Verification/evidence.

Backend, `mvn verify` in `backend/`: exit 0,
`Tests run: 1379, Failures: 0, Errors: 0, Skipped: 0`, counted from the
surefire XML rather than the console. 59 of those are review tests:
`TestReviewDiffAnchors` 9, `TestReviewTools` 10,
`TestDefaultAgentReviewService` 14, `TestReviewPublicationService` 12,
`TestSqliteReviewStores` 8, `TestReviewBridge` 2, `TestReviewToolBroker` 2,
`TestCodexReviewProfile` 2.

The gate ran in a scratch copy of the tree, for two reasons: the running dev
app owns `app/target/bytequay-backend.jar`, and a parallel session's
in-progress workspace-deletion work does not currently compile. That session's
nine workspace files were restored to `HEAD` in the copy; every file listed in
this checkpoint is the working-tree version.

Frontend, in `frontend/`: `npm run lint` clean, `npx tsc --noEmit` clean,
`npm test` green (608 tests across 48 files, 66 of them this pane's),
`npm run package` packaged for arm64 on darwin. `git diff --check` is clean.

Stop and End are covered (`stopCancelsTheActiveTurnAndPausesFollowUp`,
`endClosesTheReviewBeforeAskingForWorktreeCleanup`, which asserts the review
closes before worktree cleanup is requested).

Not verified, and each blocks calling this release-ready:

- Backend loss around start, reporting, queued questions, sync, cursor
  advancement, publication, End review and cleanup is unexercised.
- Dirty, open and uncertain worktree survival is untested, because git is
  mocked throughout (see CP2).
- Retention has no test, and nothing yet deletes review rows.
- Neither provider lane has been run end to end against a real binary, and
  CodeGraph on/off/absent/failing was not exercised for a review. Both lanes
  should be treated as fixture-proven only.
