# CI Autofix Harness — ByteQuay integration design

**Status:** execution plan finalized 2026-07-24. UI design pending (prompt:
[`ci-autofix-ui-design-prompt.md`](./ci-autofix-ui-design-prompt.md); mockups will land in
`docs/mockups/design/ci-autofix/`).

The **engine design is canonical** in [`ci-autofix-harness.md`](./ci-autofix-harness.md)
(authored in a separate design session, copied here 2026-07-24; component-level detail in
`ci-autofix-harness-component-specs.html`). This note covers only what's ByteQuay-specific:
where the harness lives in the app, what it reuses, the data model, and the build order.
When this note and the engine doc disagree about engine behavior, the engine doc wins.

## What it is

A hybrid Program + Agent that keeps OSS version-bump PRs green on an internal fork while
preserving one-fixup-per-cherry-pick history. A deterministic harness owns the loop, the
toolchain, and every git-history mutation; an LLM agent is advisory-only, invoked for novel
failures; every failure met is learned into a per-repo knowledge base (first occurrence
explored, second deterministic). Distilled from one large upstream version-bump PR on an
internal fork. The harness **never pushes** — it hands off to the human.

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
  - fixup-per-pick `HistoryPolicy` only (no squash mode).
  - No `move_hunk` (reviewer-driven hunk relocation deferred).
  - Flakes: defer + note only, no auto-retry.
  - One active harness run per repo.
- **Diagnosis agent runs on the AgentScheduler API lane** (in-JVM tool loop à la
  `LogicLoopThreadAgent`), with a purpose-built **read-only** `AgentTool` set. Not a CLI
  subprocess — a CLI agent can write files and run arbitrary shell, which the agent
  contract forbids ("proposes only, never touches the worktree"). Budgeted via `AgentRun`
  episodes (new kind, e.g. `harness_diagnosis`).
- **Separate from `CiFixRunExecutor`** (task-PR remote CI fixing): that loop's CLI agent
  fetches logs and pushes force-with-lease itself — the opposite philosophy. No
  convergence in v1.
- **Never pushes** — handoff summary + push hint only. Consistent with the app-wide
  "nothing published to GitHub without explicit user action" rule.

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
| Git Safety Layer (⑧) substrate | `GitRunner` (commit/branch/rebase/fetch/reflog/reset-hard/worktrees); **add** `commit --fixup -- <paths>`, autosquash rebase |
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
  (never rebase onto master), `normalizeFixups` (autosquash + net-neutral assert),
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

Designed in the design-companion chat — see
[`ci-autofix-ui-design-prompt.md`](./ci-autofix-ui-design-prompt.md). Surfaces: harness
dashboard (watch card, live failure table, loop timeline, handoff banner), failure detail
with diagnosis card, KB browser, escalation queue, bootstrap/"what I derived" view.
Mockup PNGs → `docs/mockups/design/ci-autofix/`, HTML sources →
`docs/mockups/design/ci-autofix/_src/`.

## Cherry-pick conflicts — agent repair behind a compile gate (decided 2026-08-04)

The cherry-picker feeds the harness, and its conflict handling changed. **This
reverses the previous rule** ("conflicts are retained for a human and never enqueue
an agent turn"), which is still written on `UpstreamCherryPickService`'s class
javadoc until the flow lands.

**Two tiers of judgement, deliberately different.**

- *Per commit, while picking:* a **compile-only hard gate**. Not the CI suite — a
  range can be hundreds of commits and running the suite on each is untenable.
- *Once, on the final commit:* the real CI verdict, which is what the harness watch
  on the pull request already provides.

Compilation is the right per-commit gate for a conflicted pick specifically: a commit
still holding `<<<<<<<` markers cannot parse, so the gate cannot be satisfied until
they are gone. It is a structural check, not a taste check.

**Flow on conflict.** `git cherry-pick -x` conflicts → the app stages and commits the
conflicted state (markers included, `-x` line preserved — verified: git keeps the
prepared message in the sequencer and `--continue` uses it) → run the compile gate →
red → the agent proposes edits → the program applies them and commits a `fixup!`
targeting that pick → re-gate → bounded attempts (`LocalCiFixExecutor` sets the
precedent at 5).

**If the attempts are exhausted the job parks and does *not* push or open the PR.**
That rule is what keeps a marker-bearing commit off GitHub, and it is the whole
mitigation for committing an invalid tree in the first place. Without it a failed
repair publishes conflict markers.

**Compile command resolution**, most explicit first:

1. a run script typed on the cherry-pick request;
2. the script learned from a CI job the user names — the agent reads
   `.github/workflows/*.yml` and extracts that job's build step;
3. otherwise a plain compile: `./mvnw clean install -DskipTests`, scoped with
   `-pl <module> -am` when the last commit's module is known to the build.

Implemented in `CherryPickCompileGate`. Scripts are restricted to a bare
`mvn`/`./mvnw` invocation — no operators, redirects or substitutions — and a command
that *fails to run at all* is reported as not-reproduced rather than as a red gate,
so the agent is never sent to fix a defect that is not in the code.

**Known risk, accepted.** Auto-resolving a conflict is the highest-risk thing an
agent does here: a resolution that compiles can still be semantically wrong, and
unlike a CI failure nothing downstream catches it before the final commit. The
compile gate proves the markers are gone; it does not prove the merge was right.

## Open items

- Surface naming in nav ("CI Harness"? "Autofix"?) — resolved: "CI Harness".
- M4 detail: where escalations live — resolved: columns on `ci_harness_failure`, no
  separate table.
- **Recipe replay carries occurrence #1's target and anchors.** Because a recipe is a
  stored `Diagnosis`, replaying it reuses that diagnosis's `target_subject` and literal
  find/replace anchors. That violates §2's "fixup targets the semantic owner" whenever the
  recurrence belongs to a different cherry-pick. Either re-resolve the target per
  occurrence, or restrict recipe bindings to edit-free (regen-only) fixes until the
  primitives from M3 exist.
- **Verify verbs are taken from the agent's `verify_hint`.** Component specs ⑥/⑦ have the
  *program* infer them from the changed files (`_infer_verify(planned)`,
  `verbs_for(fix.files_changed)`); neither exists. A `.java` edit hinted `["style"]` is
  committed without ever being compiled — a safety decision currently delegated to the
  model, against §7's program/agent split.
- **No confidence rubric.** Specs ⑤ defines six booleans behind `confidence`; the model
  emits a bare float instead. Two of the six (`target_from_candidates`, `anchors_unique`)
  are already computed program-side by `validateTarget`/`validateAnchors`, so the model is
  self-reporting facts the harness independently knows. The UI's "confidence with its
  rubric breakdown" has nothing to render.
- **Validation failure has no retry.** Specs ⑤ escalates after validation "fails twice",
  carrying the partial rationale + tool transcript. Today the first failure throws and —
  because the `diagnose` call is the one per-failure path not wrapped — aborts the whole
  cycle and rolls back fixups that already verified and committed.
- Post-v1 seams intentionally left: GitLab/Buildkite forges, Gradle/pytest/npm packs,
  squash HistoryPolicy, `move_hunk`, flake auto-retry, multi-run concurrency.
