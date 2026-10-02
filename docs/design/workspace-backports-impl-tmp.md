# Workspace Relationships Implementation Checklist

Status: checkpoints 1 and 2 implemented and checked.
Checkpoints 3–5 are not started.
The [design](workspace-backports.md) is canonical.
This file tracks implementation, not another runtime workflow.
Checkpoint order replaces the earlier chain-first plan.

## Working rules

Preserve concurrent work. Re-read the current memory and agent contracts before
implementation; the old knowledge notebooks and their APIs were removed.
Use existing services and modules. Mark a box only after its check passes.
Use disposable Git repositories and provider fixtures; live GitHub writes need
an explicitly selected test target. Record evidence without authoring dates.

## Checkpoint 1 — Relationships and reading

UI: workspace settings and links to related workspaces.
Starting points: `AppSettingsStore`, `MemoryBridgeResolverImpl`, `MemoryService`,
`MemoryBridge`, `RepositoryGuidelineReader`, and workspace repository resolution.

- [x] Save one direct relationship list: workspace ID, Fork/Dependency, guidance.
  Validate self-links/duplicates and logical repository identities; serialize
  settings writes. Preserve contribution routing and explain routing mismatches.
- [x] Supply the list, guidance, paths, and captured commits to development
  turns through current provider input. Use ordinary Git reads for code and
  old/new version comparisons; unavailable or unmapped revisions stay explicit.
- [x] Add optional workspace selection to memory SEARCH/READ on both providers.
  Offer the tool when only a related workspace has memory; expose all its saved
  memory through existing pagination. Label workspace/ID/version/sources.
- [x] Validate direct membership on every call. Keep REPORT and future writes
  local, respect memory's own controls, and leave source guidelines scoped.
  Do not rebuild deleted notebooks, note receipts, or version-check machinery.
- [x] Add the small settings list and existing-view links. Add shared dependency
  rejection to manual/API cherry-pick admission and delivery, checking actual
  repository identities rather than remote names.

Gate:

- [x] P2 can read P3/P4 code and memory; P3/P4 receive no reverse access. A Trino
  task can compare two Iceberg commits and read Iceberg memory even when Trino
  has none. Tests reject reverse/transitive reads, foreign REPORT, dependency
  cherry-picks, and stale relationship use; unknown versions are not guessed.

## Checkpoint 2 — Work in a related workspace

UI: conversation investigation/fix cards, destination request bubble, and the
Tasks → Linked work sub-tab from the approved prototype.
Starting points: `InteractiveAgentTaskCreator`, `WorktreeService`, existing
provider tool transport, accepted turns, task logs, and development settings.

- [x] Add one root-bound handoff with start and status/result reads, restricted
  to direct related destinations and the accepted user request. Save only the
  source/request -> target task/first-turn link; reuse normal task lifecycle.
- [x] Extend base selection for an exact investigation commit. Bind destination,
  purpose, revision, and settings in the replay fingerprint; repeated requests
  reuse one task, while changed requests on the same key fail.
- [x] Start investigation with ordinary local commits and draft/CI/review-summary
  automation off in saved defaults and accepted/queued turns. Keep fix requests
  on existing authorized development delivery. Reuse destination model settings.
- [x] Pass a focused question and selected evidence. Keep source history/memory
  and the original issue association in the source. Read the requested work's
  completed-turn answer and status without requiring task completion.
- [x] Show tested revisions, commands, outcomes, and limitations through existing
  conversation results. Keep tests in destination-owned worktrees, one per
  requested revision; use existing Stop and preservation rules.

Gate:

- [x] Both providers pass P1 -> P2 investigation/result-return fixtures at old
  and new revisions, including retry, restart, wrong root/turn, missing result,
  and destination defaults that normally publish. An investigation starts no automatic PR or backport. A separately requested
  fix enters normal development with its existing delivery settings. PR creation
  itself remains covered by the existing delivery fixtures.

## Checkpoint 3 — Resolve merged changes

UI: reuse existing commit selection for an unresolved source.
Starting points: `GitHubPullRequestReadClient`, Git history, and
`CherryPickPreviewService`.

- [ ] Add the narrow merged-PR resolver. Save verified repository/PR identity,
  merge evidence, and exact landed range; use fresh provider facts and local Git.
- [ ] Feed proven non-merge ranges into existing preview. Unsupported or
  ambiguous history pauses for explicit selection; add no merge-commit replay.
- [ ] Test linear, squash, and rebase outcomes with real Git/provider fixtures,
  including unrelated base changes and conflict-resolution fixup commits.
- [ ] Verify a P3 PR supplies P3's landed changes for P2, never the original P4
  range or pre-merge head. Missing history cannot silently widen the selection.

Gate:

- [ ] A merged PR resolves to the intended existing-executor input or a clear
  hold, with evidence for every supported merge shape.

## Checkpoint 4 — Saved backport chain

UI: compact route, current result/block, and Stop/Resume in the conversation.
Starting points: cherry-pick execution, workspace PR delivery, CI Autofix,
Branch Rebase, `MonitorLoop`, and current core/SQLite storage patterns.

- [ ] Add one chain record/store/coordinator and root-bound save/append operation.
  Save source conversation/PR, ordered frozen destinations, accepted authority,
  stable keys, landed sources, child IDs, stop/block facts, and record version.
- [ ] Validate destination-to-source Fork relationships per hop. Bind a source
  conversation only to its verified PR. Reuse remote-source registration,
  preview, execution, and delivery without replacing origin/upstream or adding
  another development conversation around each cherry-pick.
- [ ] Extend existing delivery admission to specific saved chain authority.
  Complete linked-upstream CI activation/status with the existing stable CI
  key and fork checks. Keep CI, rebase, and publication in their owning services.
- [ ] Persist before dispatch and serialize each chain. Reuse child IDs/keys on
  repeated ticks and restart. Stop/recheck before new actions; adopt uncertain
  publication only with exact evidence. Preserve work on missing workspaces,
  changed relationships/routing, failed children, or closed-unmerged PRs.
- [ ] Wait for each PR's merge, including the final PR, without automatic merge.
  Show existing child progress and Stop/Resume. Handle no-op final hops without
  empty PRs; intermediate hops require a proven destination source to continue.

Gate:

- [ ] P4 -> P3 -> P2 creates one necessary backport PR per destination using
  P3's actual landed changes. Tests cover concurrent dispatch, lost responses,
  restart around child creation, relation changes, Stop races, and no-op hops.
  No duplicate publication or revival of failed development/CI occurs.

## Checkpoint 5 — End-to-end checks and documentation

UI: verification only.

- [ ] Verify starting from P2 investigation -> P4 fix -> P3 -> P2, and starting
  from a P4 conversation with the route added before/after its PR merges.
  Also cover direct P4 -> P2/P3 and a negative upstream finding.
- [ ] Verify Trino -> Iceberg investigation independently of chains: version
  mapping, moving tags, memory source limitations, result return, local-only
  publication settings, and dependency transfer rejection through every entry.
- [ ] Run `mvn verify` in `backend/`; run frontend lint, `npx tsc --noEmit`,
  tests, and package. Run `git diff --check` and changed-document link checks.
- [ ] Reconcile workspace, memory, agent, cherry-pick, and CI contracts with the
  actual implementation. Keep unrelated Memory UI/harvest/capture work separate
  and report fixture versus live-provider evidence honestly.

Gate:

- [ ] All requested fork and dependency flows work through the existing services;
  every completed box has evidence and remaining work is explicit.

## Evidence

Checkpoint 1 adds settings/IPC/HTTP storage and navigation, development discovery,
related memory reads and shared dependency transfer rejection. It reuses current
services and introduces no workflow engine or new module.

Focused checks cover direct/reverse/transitive membership, unavailable and removed
links, duplicate logical projects, concurrent settings writes, related-only memory,
all-page reads, memory provenance/switches, foreign REPORT rejection, concrete Git
version differences, dependency rejection at preview/start/retry/delivery, and
source-repository persistence. The shared provider tool schema carries workspace
selection without a fixed pagination ceiling. No live provider or GitHub writes
were used.

The Chromium fixture verifies add/edit/remove, failed-save preservation, unavailable
links, same-project rejection and navigation to the existing Commits/Memory views.
The rendered form and saved row were also checked manually against the approved
prototype. The picker, explicit Fork/Dependency segments, guidance, row actions
and compact sidebar now follow it. Missing-checkout responses keep guidance
and disable both view links; HTTP and browser fixtures verify this state.

Verification:

- Backend `mvn verify` passed: 3,279 tests, no failures, errors or skips.
  After the UI availability change, targeted `mvn verify` passed the controller
  checks, including the new missing-checkout test.
- Frontend lint, TypeScript and Electron packaging passed.
- The full frontend test run and targeted reruns initially passed 137 of 138 test files,
  including the new related-workspace browser fixture. The remaining unchanged
  `usePullRequestDetailBrowser.test.ts` then passed in an isolated run alongside
  the updated relationship fixture: all 138 files have passed across these runs.
  No timeout was relaxed.
- `git diff --check` passed. Changed-document links resolved apart from the
  pre-existing `design.md` link to the missing `agent-review-impl-tmp.md`.

This evidence covers checkpoint 1 fixtures and the browser preview, not live
Claude/Codex sessions or the planned cross-workspace handoffs and chains.


Checkpoint 2 saves one request link, freezes destination settings, and uses the
ordinary creator and turn service. Tests use real local Git revisions and the
real conversation creator with fixture task storage/provider readiness. They
cover old/new commits, local-only investigation settings, normal fix defaults,
concurrent replay, service restart, lost acceptance responses, changed requests,
removed relationships, stale turns, generated-request recursion, deleted targets,
and the requested root turn's final answer versus missing history. Workspace
purge removes owned request settings.

Provider fixtures cover Claude's child-identity hook and Codex's dynamic root
channel, including initial/resumed threads, wrong roots and stale turns. Older
Codex threads without dynamic tools cannot gain this operation on resume; use a
new conversation. This is recorded in the canonical design. No live model calls,
GitHub writes, PRs or merges were used for this checkpoint.

The Chromium fixture verifies Linked work groups, source/destination navigation,
exact revision display, Markdown results, the ByteQuay destination request bubble,
and Stop with the destination runtime generation. Manual browser review checked
card width and the commands/limitations disclosure against the approved prototype.
The existing CI fixture now checks actual CI reads rather than counting every
five-second timer, because Linked work has its own independent polling.

Checkpoint 2 verification:

- Full backend `mvn verify`: 3,292 tests, zero failures, errors or skips. Existing
  socket/process fixtures required an unsandboxed rerun; that full run passed.
- Frontend full suite: 138 files passed, with one CI timer-assumption failure.
  After correcting that fixture, it and the Linked work browser fixture passed:
  all 139 files and 1,386 tests have passed across the full run and focused rerun.
- TypeScript, lint and Electron packaging passed with the final UI layout.
- Changed design links and `git diff --check` passed.
