# CI Autofix Harness — ByteQuay integration design

**Status:** engine shipped 2026-07-25/29; the program/agent split inverted 2026-08-05 —
see "The upstream sync run" below, which is the current design and supersedes anything
above it that disagrees.

The **engine design** is in [`ci-autofix-harness.md`](./ci-autofix-harness.md) (authored
in a separate design session, copied here 2026-07-24). It remains canonical for the generic
engine; where ByteQuay's integration overrides it, this note says so and wins. A
component-specs HTML and a UI design prompt sat alongside these two notes until 2026-08-05,
when the inversion made every component they specified — confidence rubrics, verify verbs,
recipe bindings, the promotion gate — obsolete; both were deleted rather than rewritten.
Recover them from git history if you need the archaeology.

## What it is

A hybrid Program + Agent that keeps OSS version-bump PRs green on an internal fork while
preserving one-fixup-per-cherry-pick history. The program does the deterministic work —
run the picks, fetch logs, parse them, annotate, persist, notice when CI finalizes, wake
the agent — and the **agent owns the fix**: it decides scope, edits the worktree, commits
the fixup, validates it, pushes, and writes what it learned into the repo's knowledge
base. Distilled from one large upstream version-bump PR on an internal fork.
~~The harness **never pushes** — it hands off to the human.~~ ~~An LLM agent is
advisory-only; a deterministic harness owns every git-history mutation.~~ *Both superseded
— see "The upstream sync run" below.*

## Decisions (2026-07-24)

- **Home: ByteQuay feature.** Java service in the Spring Boot sidecar + a new workspace
  surface. Not a standalone tool — ~80% of the primitives already exist in the app.
- **Target: the watched repo maps to a ByteQuay workspace.** The harness runs in an
  **app-owned detached worktree**, not the workspace's registered checkout — see
  "Superseded decisions" below. Path-scoped commits + tip backups still apply; they are
  now belt-and-braces rather than the only thing making a shared checkout safe. The user
  reviews in their editor and pushes themselves.
- **v1 cuts** (all have seams to add back later):
  - GitHub Actions ForgeAdapter + Maven EcosystemPack only (adapter interfaces stay, per
    the engine doc's five-layer portability model — one impl each).
  - fixup-per-pick `HistoryPolicy` only (no squash mode). *Refined 2026-08-05: the fixup
    sits directly after its target pick and is never absorbed into it; repeated failures
    on one pick squash into that pick's single fixup.*
  - No `move_hunk` (reviewer-driven hunk relocation deferred).
  - Flakes: defer + note only, no auto-retry.
  - One active harness run per repo.
- ~~**Diagnosis agent runs on the AgentScheduler API lane** with a purpose-built
  **read-only** `AgentTool` set. Not a CLI subprocess — a CLI agent can write files and run
  arbitrary shell, which the agent contract forbids ("proposes only, never touches the
  worktree").~~ *Superseded 2026-08-05: writing files and running shell is exactly what
  both agents now do. Both lanes are write-capable CLI sessions; budgeted via `AgentRun`
  episodes.*
- **Separate from `CiFixRunExecutor`** (task-PR remote CI fixing): that loop's CLI agent
  fetches logs and pushes force-with-lease itself — the opposite philosophy. No
  convergence in v1.
- ~~**Never pushes** — handoff summary + push hint only. Consistent with the app-wide
  "nothing published to GitHub without explicit user action" rule.~~ *Superseded
  2026-08-05.* The user's explicit action is starting the run; within it the harness
  pushes to its own cherry-pick branch so CI can re-run. It still never merges, never
  touches a branch it did not create, and still parks for review before anything is
  proposed to a human reviewer.

## Superseded decisions — what actually shipped

The engine landed 2026-07-25; graduation/escalation surfaces 2026-07-29. **Where this
section and the decisions above disagree, this section wins** — the code matches it.
Recorded because both items read as current and a later session would otherwise "fix"
them back into bugs.

- **Runs in an app-owned worktree, not the registered checkout.** `HarnessWorktreeProvisioner`
  creates a detached worktree under `<clone>.bytequay-worktrees/ci-harness/`; the
  orchestrator refuses to edit anything else (`isAppOwnedWorktree`). Handoff is
  `git -C <worktree> push --force-with-lease origin HEAD:<branch>`; the local branch ref is
  never moved. This is strictly safer than the original shared-checkout plan — a parallel
  agent or the user's own WIP can no longer collide with a history rewrite. **Do not
  "restore" the harness to the registered checkout.**
- **A "recipe" is a replayed `Diagnosis`, not composable primitives.** The KB stores the
  first verified diagnosis verbatim in `ci_harness_rule.recipe_json` and replays it on the
  next match, revalidated against the rule (`validatedRecipe`). The run-generator /
  rename-by-pattern / apply-edits primitives in M3 below were never built, and there is no
  `harness_env_delta` table. Known consequence: a replayed recipe carries occurrence #1's
  `target_subject` and literal edit anchors, so it is only correct when the recurrence is
  genuinely identical — see "Open items".
- **No adapter interfaces.** The v1 cut said "adapter interfaces stay — one impl each";
  in practice there is no `ForgeAdapter`/`EcosystemPack`/`AuthProvider` seam. The GitHub
  Actions probe and the Maven-only command whitelist (`^(?:\./mvnw|mvn)…`) live directly in
  the generic core. Adding a second forge or ecosystem means extracting the seam first.
- **`binding: "defer"` is unreachable.** The engine doc's data model allows
  `recipe_id | "agent" | "defer"`, but `validateCandidate` accepts only `agent` or
  `recipe:<id>`, so no rule can ever be created with a defer binding — INFRA/FLAKE deferral
  happens in the orchestrator instead. The defer branch in the classifier's precedence
  ordering is dead code. Either wire it up or drop it from the data model.
- **"One active harness run per repo" is enforced per *PR*, not per repo.** The partial
  unique indexes are `(workspace, owner, repo, pr_number) WHERE status != 'stopped'` and
  one live cycle per watch. Two watched PRs on the same repo run concurrently — safe today
  only because each gets its own worktree.

## Reuse map

| Harness component (engine doc §4) | ByteQuay piece |
|---|---|
| CI Probe (① ) | `GitHubClient` (`fetchPrCheckRuns` paginated, `fetchCheckRunLog` → `/actions/jobs/{id}/logs`); **add** `/actions/runs/{id}/jobs` listing; `PatResolver` for auth |
| Verifier exec (⑦) | `ShellRunner.runArgv` (bounded output + real timeouts); `TestRunnerDetector` precedent |
| Git Safety Layer (⑧) substrate | `GitRunner` (commit/branch/rebase/fetch/reflog/reset-hard/worktrees); **add** `commit --fixup -- <paths>`, fixup-positioning rebase (*not* absorbing autosquash — see "The upstream sync run") |
| Watched-repo checkout | `LocalRepoService` (workspace repos, managed clones, recovery destinations) |
| Diagnosis Service (⑤) runtime | `AgentScheduler` API lane, `TurnRunner`, `AgentTool`/`LogicLoopToolRegistry`, `AgentRunService` budgets |
| State Store / KB (⑩/④) pattern | `ProjectLearningStore` (raw `JdbcTemplate`, workspace+repo scoped, JSON blob columns) |
| Escalation surfacing | `NEEDS_ATTENTION` notification pattern (`AutomationCoordinator`) |
| Loop trigger | `@Scheduled` poller pattern (`AutomationCoordinator.scanForFailingCi`) — new entry point keyed on `harness_watch`, not tasks |
| Live UI updates | polling first; SSE broker pattern (`threadStreamBridge.ts`) when the run view needs streaming |

**Genuinely net-new:** structured Maven/Surefire log parser + signature normalization;
rule store (KB) + classifier; Bootstrapper (workflow/pom readers); guarded-mutation git
safety semantics; read-only diagnosis tool set + `parse_and_validate` gate.

## Data model (SQLite, Flyway; M1 migration = V197, later milestones add their own)

- `harness_watch` — a watched PR: id (TEXT), workspace_id, repo_full_name, pr_number,
  status, created_at_ms. One active per repo (v1 cut).
- `harness_run` — the run ledger: id, watch_id, ci_run_id, head_sha, loop phase,
  started/finished_at_ms, summary_json. Idempotency + audit.
- `harness_failure` — typed failures: id, run_id, signature (dedupe key), module,
  test_class/method, bucket, matched_rule_id, status (new/fixing/verifying/fixed/
  escalated/deferred), log_excerpt, fix_json.
- `harness_rule` — the KB: id, repo scope, matcher (pattern + fields), bucket, binding
  (recipe_id | agent | defer), status (candidate/active/retired), origin
  (bootstrap/agent/human), priority, hits, evidence_json.
- M3 adds `harness_recipe` + `harness_env_delta`; M4 adds diagnosis/escalation columns or
  a `harness_escalation` table (decide at M4).

## Milestones

Engine doc §10 order, mapped to concrete pieces. Package: `service/harness/` +
`web/HarnessController` + `beans/harness/`, mirroring the `AgentRunController` trio.

- **M1 — Read-only triage** (highest ROI; replaces manual log spelunking).
  `Bootstrapper` (parse `.github/workflows/*.yml`: job topology, `needs:` graph →
  aggregator detection, secret/cloud-gated jobs, VerifyProfile command extraction,
  `setup-java` runtime; pom module map; cherry-pick link convention). `CiProbe` (new
  workflow-jobs endpoint + log download). `MavenLogParser` (Surefire/compiler/checkstyle
  extractors, `Caused by:` first-cause walk, normalization scrub, ~40-line windows,
  dedupe). `Classifier` (specificity-ordered match over active rules; bias to UNKNOWN).
  `HarnessStore` (JdbcTemplate). Poller + read-only triage UI (new workspace nav entry:
  `App.tsx` Nav union, `workspaceRoutes.ts`, `WorkspaceNavShell`).
- **M2 — Git Safety Layer** (highest risk-reduction). `GitSafetyService` over `GitRunner`:
  `guarded()` (backup branch → mutate → diff-assert exactly-intended → hard restore),
  path-scoped `commitFixup` with exact-subject resolution, `internalBase` tight range
  (never rebase onto master), `normalizeFixups` (autosquash + net-neutral assert —
  *reshaped 2026-08-05: position the fixup after its target instead of absorbing it*),
  `divergence` (fetch first, always), stale-lock clear with the full safety checks.
  Scratch-repo JUnit tests for every invariant.
  *(Shipped except the stale-lock clear — `_clear_stale_locks` was never built, so a
  crashed cycle that leaves an `index.lock` fails every subsequent `git add` and the
  90s poller retries forever.)*
- **M3 — Verifier + first recipes** (covers the deterministic ~70%). `Verifier` executes
  VerifyProfile steps scoped to changed modules (`-pl`), env = bootstrap-derived + learned
  env deltas, regen-idempotence 0-diff proof. Recipe primitives (run-generator,
  rename-by-pattern, apply-edits) + KB bindings — no recipes hardcoded; they're learned.
  *(Shipped with three gaps: no learned env deltas, no recipe primitives (see "Superseded
  decisions"), and the regen proof is run-twice-same-result rather than the spec's
  `diff_stat(exclude=fix.files_changed)` — it proves determinism but not scope.)*
- **M4 — Diagnosis agent** (the ~30% tail). `DiagnosisService` on the API lane: read-only
  tools (`read_file`, `grep`, `git_show`, `git_log`, `oss_diff`, `candidate_targets` —
  bounded output), cached system prompt (invariants + strict JSON schema + KB hints),
  `parse_and_validate` (schema, real target subject, unique anchors, signature-pattern
  self-test vs unrelated samples), confidence gate θ=0.75, escalation records +
  notification. Learns candidate rules. `oss_diff` resolves the upstream commit via the
  detected link convention — prefer the checkout's upstream remote, GitHub API fallback.
- **M5 — Graduation** (compounding). Candidate→active promotion (K hits or human approval
  in UI), retire, fix→recipe capture. KB browser + escalation queue + handoff surfaces.

Each milestone lands as its own reviewable slice through the normal gates
(`mvn verify`, `tsc`, `npm test`).

## UI

Shipped surfaces: harness dashboard (watch card, failure table, loop timeline, handoff
banner), failure detail with diagnosis card, KB browser, escalation queue, bootstrap
view — all built against the pre-inversion design. **The diagnosis card, KB browser,
escalation queue and promotion gate render components the new design deletes**, so the
surface needs rethinking alongside the backend work, not after it. Mockup PNGs →
`docs/mockups/design/ci-autofix/`.

## The upstream sync run — agent-driven, three phases (decided 2026-08-05)

The cherry-picker and the harness are one continuous run, not two features that hand off.
**Conflicts and red CI are the expected case** — a range off an upstream the fork has
drifted from produces them by the dozen — and absorbing them is why this exists.

**The split.** The program does the dirty deterministic work and nothing more: run the
picks, fetch and parse CI logs, annotate them with classifier hints, persist them, notice
when a CI run finalizes, and wake the agent. The **agent owns the fix** — what to fix, how
much of it, how to edit, how to validate, when to push. There is no confidence gate, no
attempt counter, and no rule-matching engine deciding on the agent's behalf. When the
agent judges that it cannot resolve something it calls a **`park` tool** and the run stops
for a human.

### One session for the whole run

Not one per phase and not one per failure: **a single agent session spans phase 1's first
conflict through phase 3's retrospective**, resumed at every wake-up — each conflicted
pick, each CI round, the resume after a budget raise, and the final merge retrospective.

The reason is phase 2's most common failure: a compile error in the PR is usually caused by
a conflict resolution made back in phase 1, and only the session that made it knows why it
was chosen. Splitting the run into separate sessions would hand phase 2 the symptom with
the reasoning thrown away.

**The agent compacts its own session** when it grows — the program does not manage that
window. Compaction losing detail is acceptable here precisely because the knowledge base is
the durable store: anything worth keeping was already written to memory after CI confirmed
it. The session carries working context; the KB carries what was learned.

### Parking is a question, not an ending

Both ways a run stops short — the agent calling `park`, and the budget running out — put
the same choice in front of the user: **stop here, or raise the budget and carry on.**
Raising it resumes the same session, so nothing the agent worked out is thrown away. A
park therefore has to carry enough for that decision to be an informed one: what it was
trying to fix, what it tried, and why it stopped.

This is what makes an agent-set bound safe. The program no longer caps attempts or pushes,
so the budget is the only hard stop — and a hard stop the user can lift on the spot is a
checkpoint rather than a wall.

### How the agent validates — learned, not configured

The agent looks for the project's own validation command in the repo's documentation and
its CI config. If it finds one it can run, it validates locally first and pushes after.
If it cannot — no toolchain, unreachable internal repo, credentials — it pushes and takes
the verdict from the remote check logs, judging for itself whether what came back is a
compile error in its own change or something else. The same rule holds in both phases.
Local-first is a cost optimisation, not a correctness requirement: CI is always the
authority.

### Phase 1 — pick the range

Per commit: `git cherry-pick -x`. A clean pick moves straight on — upstream already
compiled it. A conflicted pick is committed as-is by the program, **conflict markers and
all** (`git add -A` then `--continue`; the `-x` line survives because git keeps the
prepared message in the sequencer). That is deliberate: the repair is then an ordinary
commit on top rather than an edit to a half-finished pick, the pick keeps matching
upstream verbatim, and a crash leaves a normal repo instead of a stuck sequencer.

The agent then resolves the conflict, commits it as that pick's `fixup!`, and validates by
the rule above. It is the run's one session, resumed, so a conflict at commit 200 still
knows what the fork decided at commit 3 — and phase 2 will still know it too.

After the last pick: push, open the draft PR, create the harness watch.

### Phase 2 — drive CI green, one round per CI run

A round is **program prepares → agent acts → agent's turn ends → program waits.**

1. **Program.** A CI run finalizes. Download the failed-job logs, parse them into typed
   failures, annotate with classifier hints, persist them locally.
2. **Program.** Wake the agent — the run's session, resumed, the same one that made the
   picks — with *every* failure from this round plus the knowledge-base projection for
   them.
3. **Agent.** First question: did last round's fix work?
   - **Yes** → write its knowledge-base entry (below), then choose what to take on next.
   - **No** → making that failure green takes priority over starting anything new.
4. **Agent.** Fix as much or as little as it judges right — **batching is the agent's
   call, not the program's** — subject to the one ordering rule below. Commit each fix as a
   `fixup!` positioned directly after the cherry-pick that owns it, squashing into that
   pick's existing fixup rather than adding a second, so every pick stays independently
   reviewable against upstream — or, when no single pick owns the fix, as a standalone
   commit at the tip of the branch (see Phase 3). Validate, push, **end the turn**.

**Compile failures come first, always.** If CI reports a compile or build failure, it is
fixed before any other failure type is looked at. Everything else in that run is noise
until it is gone: tests that never ran, style gates on a tree that does not build,
downstream jobs failing on a missing artifact. This overrides the agent's own batching
judgment and it overrides "finish last round's failure first".

The rule holds **even though phase 1 already gated on compilation, and even when phase 1's
local validation passed.** Phase 1 compiles the module a commit touched, on this machine,
with whatever toolchain is here; CI builds the whole project in a clean environment on the
pinned one. A compile error surviving into the PR is an ordinary outcome, not a sign that
phase 1 malfunctioned.

### When the base moves — rebase and repair (phases 2 and 3)

A range of hundreds of picks takes days, so the fork's own target branch **will** move
underneath it. When it moves and conflicts, the agent **rebases the branch onto the updated
target and resolves the conflicts**, and each resolution lands as a `fixup!` on the pick
that owns it — squashing into that pick's existing fixup, same shape rule as everything
else, or a standalone commit at the tip when no pick owns it.

**A base conflict is its own round.** A rebase rewrites every sha on the branch, so the CI
verdict the agent was just handed no longer describes what is there. It rebases, repairs,
pushes, and ends the turn; CI re-runs against the new tree and the next round reads that.
It takes priority over the compile-first rule for the same reason — that ordering exists to
stop the agent chasing noise, and a stale tree is the same kind of noise.

**The program detects, the agent fixes** — the usual split. The check rides on the CI poll
the program is already making; it does not add a fetch of its own.

**This is not the rebase §2 prohibits.** The engine doc's "never `rebase -i master`" bars
using a moved branch as the base for a *fixup-positioning* rebase, where it would drag
unrelated commits into the range. Deliberately integrating a moved base is the opposite
intent, and it is the only way a long-running branch stays mergeable.

The push after a rebase is a force-push — which is what the guarded push
(`--force-with-lease`, refuse any branch the harness did not create) is for. Non-optional
here rather than belt-and-braces.

### Phase 3 — merge, remember, clean up

CI is green and the run parks. What the human reviews has a fixed shape:

```
  <standalone fix commit>      ← only for fixes no single pick owns; at the tip
  fixup! Pick N                ← at most ONE fixup per pick, ever
  Pick N                       ← upstream verbatim, (cherry picked from …)
  …
  Pick 2                       ← a pick that needed nothing carries no fixup
  fixup! Pick 1
  Pick 1
```

**One fixup per target.** Every later fix owned by the same pick squashes into that pick's
existing fixup rather than appending a second, across both phases — a pick and its repair
read as one reviewable unit against upstream.

**Standalone commits are the exception, at the tip.** When a phase-2 fix belongs to no
single cherry-pick — a fork-wide adjustment, a new fork-only file, a change the whole range
implies rather than any one commit of it — forcing it into a `fixup!` would attribute it to
a pick that does not own it. It becomes its own commit at the end of the branch instead.
The agent makes that call; §2's "fixup targets the semantic owner" is the reason, not an
exception to it: where there is no owner, do not invent one.

**The human merges.** Reviewed and merged manually in the app — the harness has never
merged and does not start here.

**The park is not necessarily terminal.** The target branch can move between green and
merge — the classic green-PR-that-will-not-merge. That re-opens the loop: rebase, repair,
push, CI re-runs, park again on green. Only the merge itself ends the run.

**Then the agent writes the run's memory.** The app detects the merge and wakes the agent
one last time for the retrospective: what this range taught the fork. This is the run-level
entry, distinct from phase 2's per-failure ones — and it is the only moment anything a
*reviewer* changed or pushed before merging is still visible, which is the highest-value
memory in the run: what the agent got wrong that a human corrected.

**Then teardown, in that order.** Worktree removed, sync run closed, harness watch stopped,
agent sessions closed. The ordering is a hard constraint, not a preference: the
retrospective needs the worktree to read the merged history and the session to remember
what it tried, so teardown is gated on the KB write finishing. It must also survive that
write failing — a failed retrospective loses a memory, it must not leak a worktree.

A PR **closed without merging** cleans up the same way but writes no retrospective.
5. **Program.** Wait for the next CI run to finalize. Back to 1.

Green ends the run and it parks for human review. The harness never merges and never
touches a branch it did not create.

### The knowledge base is memory, not a rule table

Written by the agent, and only after **CI confirms green** — never off a local pass. An
entry reads like a memory: the failure as it appeared in the log, how it was resolved, why
that worked, and what was tried that did not and why. Prose, retrieved by relevance to the
next failure, not a matcher table of buckets and bindings. It informs the agent's
judgment; it never routes around it.

Two write points: **per failure** during phase 2, once CI confirms that fix, and **once per
run** at merge (Phase 3), for what the range taught the fork as a whole.

Learning only after a CI-confirmed green is the point. The previous design recorded a
candidate rule as soon as a fix passed *local* verification, so it learned from fixes CI
never confirmed. It also could not record *why the other approach failed*, because the
program threw that context away between attempts — only a session that lives across the CI
wait knows it, which is why the agent's session resumes rather than restarting each round.

### History of this decision

- *2026-08-04* — agent repair behind a per-commit compile gate; park rather than push when
  repairs are exhausted.
- *2026-08-05, morning* — that gate was deleted. The repair agent it needed had been
  specified but never implemented, so a red gate had no repair path and could only ever
  park. Conflicts were carried into the PR unjudged.
- *2026-08-05, midday* — the gate returned with a repair agent, but an advisory one: the
  program ran the compile, asked the agent for find/replace edits, validated the anchors,
  applied them, committed, and retried a fixed number of times.
- *2026-08-05, this decision* — that split is inverted. The program's judgment was in the
  agent's way: a fixed attempt counter, a fixed confidence threshold, program-chosen
  batching and a program-run compile gate all decided things the agent is better placed to
  decide, and the "compile could not run" escape hatch was a program guess about a
  toolchain the agent can simply go and look at. The program keeps what is genuinely
  mechanical.

### What this costs in code

**Survives:** worktree provisioning, the pick loop, `GitHubActionsProbe`,
`HarnessLogParser`, the run/cycle/event ledger, the poller.

**Deleted:** the θ=0.75 confidence gate, `MAX_REPAIR_ATTEMPTS`, `HarnessFixApplier`,
`HarnessVerifier` and VerifyProfile execution, `HarnessGitSafety`'s fixup batch and
net-neutral proof, recipe replay, candidate→active promotion, and `ci_harness_rule`'s
matcher/binding/status columns. `HarnessClassifier` survives only as an *annotator* — its
output is a hint in the agent's prompt, not a route.

**New:** a write-capable CLI lane for both agents (`CliReviewRunner` launches Codex
`--sandbox read-only` today; `CodexCliThreadAgent` already runs `workspace-write`), a
`park` tool, knowledge-base read/write tools over `knowledge_item` +
`SessionKnowledgeProvider`, a program-side guarded push (`--force-with-lease`, refuse any
branch the harness did not create) — the one irreversible step in the loop — and a
raise-the-budget-and-resume action on a parked run, which needs `budget_milli_usd` to be
writable mid-run and the parked session id to be resumable.

**Resequenced:** `closeRunsWhosePullRequestEnded` today detects the merge and immediately
tears down — `closeRun` → `removeWorktree` + `stopWatch`. Phase 3's retrospective has to be
inserted ahead of all of it, with teardown gated on that write completing *or failing*.

**Accepted risk.** A conflict resolution that compiles can still be semantically wrong, and
an agent that sets its own bounds can spend a long time being wrong. The budget is the hard
stop — deliberately one the user can lift at the park rather than one that ends the run —
CI is the correctness net, and human review after the last park is the final one.

## Open items

- Surface naming in nav ("CI Harness"? "Autofix"?) — resolved: "CI Harness".
- M4 detail: where escalations live — resolved: columns on `ci_harness_failure`, no
  separate table.
- **The budget is now the only hard stop, and it is raisable.** With no attempt counter and
  no push limit, cost is bounded by the agent's judgment plus `budget_milli_usd`, checked
  program-side before each wake-up. Exhausting it parks rather than fails, and a park asks
  the user to stop or raise — so `budget_milli_usd` needs to be writable after the run
  starts, and resuming has to reuse the parked session id rather than starting a fresh one.
- **Phase 1 in remote-validation mode pushes per conflicted commit** — one CI run per
  commit, on a range that may hold hundreds. Batching is the agent's call now, so the
  prompt has to tell it what a remote round costs for it to decide well.
- **The shipped UI renders components the new design deletes** — diagnosis cards with
  confidence, the KB browser's rule table, the candidate-promotion gate, the escalation
  queue. It needs rethinking with the backend work; the two docs that specified it were
  deleted 2026-08-05 rather than rewritten.
- Retired by the agent-driven flow: recipe-replay targets, `verify_hint` verbs, the
  confidence rubric, and diagnosis-validation retry — none of those components survive.
- Post-v1 seams intentionally left: GitLab/Buildkite forges, Gradle/pytest/npm packs,
  squash HistoryPolicy, `move_hunk`, flake auto-retry, multi-run concurrency.
