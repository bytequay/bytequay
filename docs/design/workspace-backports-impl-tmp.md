# Workspace Backports Implementation Checklist

Status: not started. Canonical behavior is defined in
[Workspace references and backport chains](workspace-backports.md).
This is a temporary implementation tracker, not a second design.
All implementation and verification boxes start unchecked.

## Working rules

- [ ] Read the repository instructions and canonical design before changes;
  inspect the working tree and preserve unrelated work.
- [ ] Reuse conversation creation, development delivery, cherry-pick, PR
  delivery, CI, rebase, GitHub readers, and existing monitor/tool transports.
- [ ] Keep one chain aggregate and coordinator; add no generic workflow
  engine, new module, duplicate execution lifecycle, or new dependency.
- [ ] Mark a box complete only when its behavior exists and its relevant
  check passes. Record evidence below without dates or unsupported claims.
- [ ] Use fixture providers and disposable repositories for tests. Live
  GitHub writes require a user-selected authorized test target.

## Checkpoint 1 — Saved chain and landed source

Starting points: `CherryPickPreviewService`, `CherryPickPullRequestLookup`,
`GitHubPullRequestReadClient`, and existing core/SQLite storage conventions.

- [ ] Add the chain aggregate/store and SQLite adapter with ordered
  destinations, frozen repository/branch identities, accepted authority,
  source conversation/PR, stable keys, result IDs, stop/block facts, and version.
- [ ] Add the landed-source resolver. Verify fresh merge facts and source
  history; retain exact source commits and provenance. Do not use an old PR
  head, commit-count guess, or PR grouping alone as the answer.
- [ ] Feed compatible ranges into existing preview. Show an actionable hold
  for unsupported or ambiguous history and reuse explicit commit selection.
- [ ] Cover normal linear, squash, and rebase outcomes, retained conflict
  fixups, unrelated base commits, merge-commit rejection, and missing history
  with real Git fixtures and provider response fixtures.
- [ ] Verify chain round trips, duplicate request handling, changed request
  rejection, frozen destination routing, and optimistic update conflicts.

Gate:

- [ ] A merged PR resolves to the exact intended input, or a clear hold;
  persisted chains survive reload without losing their route or result IDs.

## Checkpoint 2 — Existing cherry-pick and delivery integration

Starting points: `CherryPickExecutionService`, `WorkspacePullRequestService`,
`CiAutofixService`, and `MonitorLoop`.

- [ ] Add one coordinator and bounded monitor for the exact waiting PR.
  Confirm merge before starting the next step; do not change general PR refresh.
- [ ] Reuse destination remote-source routing, preview, execution, push,
  PR creation, and standalone CI. Keep each operation in its owning service.
- [ ] Extend delivery admission to validated saved chain authority without
  weakening manual confirmation, provider checks, or exact target validation.
- [ ] Complete existing fork/linked-upstream cherry-pick CI activation and
  status display. Reuse its stable CI key and existing scope validation.
- [ ] Persist/adopt result IDs with stable step keys. Implement Stop/Resume,
  restart while waiting, uncertain-publication holds, closed-without-merge,
  changed routing, missing workspace, and empty/already-present outcomes.

Gate:

- [ ] Starting from an existing P4 PR, fixtures complete P4 -> P3 -> P2
  with one execution/PR per necessary hop and P3's landed result as P2's source.
- [ ] Test repeated monitor ticks, concurrent dispatch, restart around start
  and result saves, uncertain push/PR responses, and Stop during dispatch.
  No duplicate publication or automatic revival of failed development/CI occurs.

## Checkpoint 3 — Workspace references and note reads

Starting points: workspace settings, `DevelopmentContextTools`,
`ContextNotebookLookup`, `ContextNoteReader`, and existing repository readers.

- [ ] Save direct reference workspace IDs and short relationship/use guidance;
  reject self-links and duplicates without changing contribution upstreams.
- [ ] Supply reference instructions, backend-resolved paths, repository
  identities, and captured revisions to development turns. Report unavailable
  references; reuse existing checkout/Git reads and keep current permissions.
- [ ] Extend list/search/exact-read with a validated reference selector.
  Scope receipts and keys by workspace; check notes against their own source
  revision and preserve context enable settings.
- [ ] Keep proposals, grades, edits, and learning requests local to the owning
  workspace. Support references to existing notes without copying notebooks.
- [ ] Add minimal reference settings/browsing using existing views. Verify
  direct-only visibility, source labels, unavailable references, version/path
  differences, and attempts to mutate another workspace's notes.

Gate:

- [ ] A P2 development conversation can follow its Iceberg guidance into P4
  code and notes. A fresh P4 conversation receives only P4's configured context;
  no claim of new filesystem isolation is made.

## Checkpoint 4 — Conversation handoff and chain commands

Starting points: `InteractiveAgentTaskCreator`, current first-turn admission,
fixed provider tool transport, issue/Jira associations, and conversation UI.

- [ ] Add the fixed root-bound handoff operation using existing creation and
  destination settings. Repeated requests adopt one fresh conversation.
- [ ] Send only the focused request and selected evidence. Retain application
  navigation links without copying source transcripts, reference lists, or
  notebooks into the destination; keep the original issue association.
- [ ] Add the fixed save/append-chain operation bound to the accepted user
  request. Accept a source conversation before its PR exists or an existing
  PR; attach only a verified published PR and freeze authorized destinations.
- [ ] Reuse existing provider bindings and approvals. Reject wrong-root,
  stale-turn, wrong-source, changed-key, and unauthorized destination requests.
  An agent's prose alone must not start work or grant permission.
- [ ] Show the route, linked execution/PR results, blocking reasons, and
  Stop/Resume in existing conversation surfaces. Add no create-task button
  or extra development conversation around a cherry-pick execution.

Gate:

- [ ] Both providers pass fixtures for P2 investigation -> P4 conversation
  -> source PR -> chain, and for adding the same chain from P4 before/after
  merge. A negative upstream finding starts no handoff or backport.

## Checkpoint 5 — End-to-end verification and documentation

- [ ] Verify direct P4 -> P2, P4 -> P3, and sequential P4 -> P3 -> P2;
  include destination-specific conflict fixes and upstream fork publication.
- [ ] Verify no-op intermediate steps cannot silently bypass the required
  landed P3 source; holds, failures, closed PRs, routing changes, and deletion
  preserve work and prevent unauthorized later actions.
- [ ] Run `mvn verify` in `backend/`. Run `npm run lint`,
  `npx tsc --noEmit`, `npm test`, and `npm run package` in `frontend/`.
- [ ] Run `git diff --check` and documentation-link checks. Reconcile the
  design index and affected workspace, agent, cherry-pick, and CI contracts
  with actual implemented behavior.
- [ ] Record commands/results and remaining limits. Keep fixture evidence
  separate from optional live-provider/live-GitHub verification.

Gate:

- [ ] Both user scenarios work through existing execution services; every
  completed checkbox has evidence and incomplete work remains visible.

## Evidence

No implementation verification has run for this feature. Add checkpoint
evidence here as work lands; do not mark existing unrelated service tests as
proof that the new chain is implemented.
