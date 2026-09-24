# DeepSeek API-key lane implementation checklist

The approved behavior is in [DeepSeek API-key task lane](deepseek-api-key-lane.md).
This temporary file tracks implementation and verification; it is not another
design. The approved design is committed as 2e3b3b935. No implementation
checkpoint is complete yet.

## Working rules

- Check a box only after the behavior exists and its checkpoint gate passes.
  Record commands and evidence below that gate when work is done. An offline
  fixture does not count as a live DeepSeek turn.
- Keep one task-owned worktree and one Codex App Server process per active
  turn. Reuse the current modules and task contracts; add no agent engine,
  provider framework, or migration-history system.
- Preserve unrelated work. Keep each code change buildable and follow the
  repository's commit-message rules. Checkpoint names are planning labels,
  not commit-message subjects.
- Use fake keys and disposable repositories for default tests. A live-key
  smoke is opt-in and must not write to GitHub; test the network-write gate
  with a disposable remote or a denied request.
- OpenAI and Anthropic key lanes, comment rewriting, automatic delivery,
  PR assignment, CI Autofix, web search, images, CodeGraph, and subagents are
  outside this implementation.

## CP0 — Align the canonical design

- [ ] Fold the approved DeepSeek behavior into agent-service.md and its
  relevant provider, readiness, permission, and settings sections.
- [ ] Resolve statements that defer all Codex API-key task support while
  preserving descriptions of the current shipped Codex and Claude lanes.
- [ ] Keep the design index and temporary checklist links accurate. Do not
  describe the DeepSeek lane as implemented until its release gate passes.

Gate:

- [ ] Canonical design, approved extension, and index have no conflicting
  claims; documentation links and whitespace checks pass.

## CP1 — Stable keys and selectable lanes

- [ ] Add a permanent opaque ID to each saved API key and assign one to
  existing rows using the current SQLite/Hibernate ownership. Keep aliases
  as display names and secrets encrypted.
- [ ] Resolve one DeepSeek lane from each active key at request time, without
  a backend restart. Keep the current Codex and Claude CLI lanes available.
- [ ] Persist only the lane ID on a task. Read a secret by the lane's key ID;
  never resolve an old task through an alias or a different newly added key.
- [ ] Keep duplicate-alias rejection and do not replace a secret in place;
  removing and re-adding a key creates a new lane ID.
- [ ] Let key removal block later turn admission while preserving task
  history and worktree access. Show a typed removed-key result to task reads.

Gate:

- [ ] Persistence and service tests cover existing-key ID assignment,
  add/remove/re-add without restart, concurrent lane lookup, and an old task
  that cannot rebind to a reused alias.

## CP2 — DeepSeek Codex runtime

- [ ] Parameterize the existing Codex driver for the DeepSeek lane ID,
  session references, model catalog, and per-key ByteQuay-owned Codex home.
  Keep the installed Codex version check/update process-wide.
- [ ] Launch strict App Server with DeepSeek as a Responses provider, the
  official base URL, a curated catalog for deepseek-flash and
  deepseek-v4-pro, disabled web search, and no child agents.
- [ ] Fetch the selected key at process start. Pass it through
  DEEPSEEK_API_KEY only to the App Server; exclude it from arguments,
  configuration files, the model-facing shell, events, and logs.
- [ ] Use on-request approval with the user reviewer at process, thread,
  and turn boundaries. Disable automatic review and GitHub credential
  environment injection for this lane.
- [ ] Extend the isolation guard to verify provider, endpoint, catalog,
  web/child settings, shell environment, and reviewer profile. Include
  per-key runtime retention and secret redaction.

Gate:

- [ ] An offline fixture using the supported installed Codex binary and a
  fake Responses server starts the strict DeepSeek profile, lists only the
  curated models, verifies the effective isolation settings, and proves no
  key reaches a model-facing shell or normalized event. No plaintext auth
  file is created.

## CP3 — Admission, turns, and failures

- [ ] Use authenticated DeepSeek GET /models as a key/model preflight.
  Codex login status must not decide DeepSeek readiness. Distinguish
  invalid key, missing key, and temporary API failure without leaking data.
- [ ] Recheck readiness before task resource allocation and every later
  turn. A rejected request creates no worktree or failed turn.
- [ ] Enforce DevelopmentAutomation.LOCAL_ONLY at task creation and for
  later, queued, and overridden turns. Reject automatic delivery,
  PR assignment, and CI Autofix entry points for the DeepSeek lane.
- [ ] Keep the automatic Development/Review default chooser CLI-only and
  reject DeepSeek in those setting updates.
- [ ] Classify invalid-key, balance, and model failures during a turn.
  Preserve history, require an explicit retry, and never auto-resend.
  Ignore hidden reasoning text in provider event mapping.

Gate:

- [ ] Backend flow tests prove admission fails before resource allocation,
  automation cannot be enabled through any turn path, removed keys cannot
  start another turn, and failed turns retain readable history.

## CP4 — Settings and task UI

- [ ] Show each saved DeepSeek key once in the task provider picker with
  its alias, supported model choices, and **No automatic delivery** hint.
  Keep key management in Models settings.
- [ ] Replace stale DeepSeek model names in settings and avoid presenting
  a duplicate disabled key option beside the working lane.
- [ ] Show actionable invalid-key and removed-key states. Historical
  tasks must render when their lane no longer appears in provider status.
  Do not offer a Codex sign-in remedy for a DeepSeek-key error.
- [ ] Hide unsupported web, image, child-agent, CodeGraph, and automation
  controls for this lane through its advertised capabilities and task scope.

Gate:

- [ ] Focused frontend tests cover add/select/model choice, local-only
  display, invalid-key recovery, and an old task after key removal.
  Existing direct comment rewriting still accepts the updated DeepSeek
  model names in a focused backend regression test.

## CP5 — Full verification and live gate

- [ ] Run backend mvn verify.
- [ ] Run frontend npm run lint, npx tsc --noEmit, npm test, and
  npm run package.
- [ ] Run the offline Codex profile/isolation fixture and inspect
  redacted logs and task events for secret or reasoning leakage.
- [ ] With an opt-in real DeepSeek key, complete a local edit, a user
  approval, and a second turn that resumes the same task with another
  supported model. Cancel a separate active turn with Stop. Confirm a
  network write cannot proceed without direct user approval; perform no
  GitHub mutation during this test.
- [ ] Confirm a removed or invalid key blocks a later turn and preserves
  the previous transcript. Record which checks used a fake key and which
  actually contacted DeepSeek.

Final gate:

- [ ] All earlier gates pass, the canonical design describes the delivered
  scope accurately, and no unverified capability is shown as available.
