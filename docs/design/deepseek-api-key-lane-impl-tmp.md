# DeepSeek API-key lane implementation checklist

Earlier CodeGraph references below are historical implementation checkpoints.
The [IDE refactor](code-tests.md#shared-code-queries-and-retired-code) supersedes
that engine with shared language queries and bounded baseline Git excerpts.

The approved behavior is in [DeepSeek API-key task lane](deepseek-api-key-lane.md).
This temporary file tracks implementation and verification; it is not another
design. The approved design is committed as 2e3b3b935. CP1–CP4 recorded the
initial saved-key lane and expanded automation evidence. The shared Codex
permission gate has passed. New tasks inherit saved Workflow automation
settings; the remaining opt-in live-key checks remain open.

## Working rules

- Check a box only after the behavior exists and its checkpoint gate passes.
  Record commands and evidence below that gate when work is done. An offline
  fixture does not count as a live DeepSeek turn.
- Historical initial-lane evidence remains labeled separately from the new
  automation probes. Earlier restricted-profile checks do not prove the shared
  Codex permission behavior.
- Keep one task-owned worktree and one Codex App Server process per active
  turn. Reuse the current modules and task contracts; add no agent engine,
  provider framework, or migration-history system.
- Preserve unrelated work. Keep each code change buildable and follow the
  repository's commit-message rules. Checkpoint names are planning labels,
  not commit-message subjects.
- Use fake keys and disposable repositories for default tests. A live-key
  smoke is opt-in and must not write to GitHub; test the network-write gate
  with a disposable remote or a denied request.
- OpenAI and Anthropic key lanes, comment rewriting, issue starts, PR
  assignment, web search, images, CodeGraph, and subagents are outside this
  implementation. Explicitly accepted draft delivery and CI Autofix use their
  existing bounded workflows.

## CP0 — Align the canonical design

- [x] Fold the approved DeepSeek behavior into agent-service.md and its
  relevant provider, readiness, permission, and settings sections.
- [x] Resolve statements that defer all Codex API-key task support while
  preserving descriptions of the current shipped Codex and Claude lanes.
- [x] Keep the design index and temporary checklist links accurate. Distinguish
  offline implementation evidence from the open real-key gate.

Gate:

- [x] Canonical design, approved extension, and index have no conflicting
  claims; documentation links and whitespace checks pass.

Evidence: the canonical design links the saved-key lane and identifies the
separate DeepSeek fixture. The design index links this checklist and states
that real-key verification remains open.
`git diff --check` passed.

## CP1 — Stable keys and selectable lanes

- [x] Add a permanent opaque ID to each saved API key and assign one to
  existing rows using the current SQLite/Hibernate ownership. Keep aliases
  as display names and secrets encrypted.
- [x] Resolve one DeepSeek lane from each active key at request time, without
  a backend restart. Keep the current Codex and Claude CLI lanes available.
- [x] Persist only the lane ID on a task. Read a secret by the lane's key ID;
  never resolve an old task through an alias or a different newly added key.
- [x] Keep duplicate-alias rejection and do not replace a secret in place;
  removing and re-adding a key creates a new lane ID.
- [x] Let key removal block later turn admission while preserving task
  history and worktree access. Show a typed removed-key result to task reads.

Gate:

- [x] Persistence and service tests cover existing-key ID assignment,
  add/remove/re-add without restart, concurrent lane lookup, and an old task
  that cannot rebind to a reused alias.

Evidence: `TestSqliteModelApiKeySchemaUpdate` backfills and persists an ID for
an existing row. `TestDeepSeekAgentDriverRegistry` covers live lane lookup,
concurrent lookup, removal, and re-addition with the same alias. Model settings
tests retain duplicate-alias rejection and encrypted-secret behavior.

## CP2 — DeepSeek Codex runtime

- [x] Parameterize the existing Codex driver for the DeepSeek lane ID,
  session references, model catalog, and per-key ByteQuay-owned Codex home.
  Keep the installed Codex version check/update process-wide.
- [x] Launch App Server with DeepSeek as a Responses provider, a
  task-scoped loopback proxy to the official DeepSeek URL, and a curated catalog
  for deepseek-flash and deepseek-v4-pro. Do not advertise unsupported hosted
  web search or child agents.
- [x] Fetch the selected key at process start. Pass it through
  the task-scoped proxy only; exclude it from the Codex process environment,
  arguments, configuration files, the model-facing shell, events, and logs.
- [x] Derive `AgentPermissionScope` from task policy, access, and review status
  for Claude and both Codex-backed lanes. Use one `CodexLaunchProfile` for
  subscription and DeepSeek to match the attended sandbox, ByteQuay approval routing,
  Git/`gh` command permissions, model-facing shell environment policy,
  web-search launch setting, and thread/turn settings.
  Keep provider, model, and credential settings separate from permission choice.
- [x] Verify the effective common permissions alongside provider, endpoint,
  catalog, and web/child settings. Retain per-key runtime cleanup and secret
  redaction.
- [x] Expose existing fixed task tools through a task-scoped loopback MCP
  bridge. Keep its broker bearer in ByteQuay; Codex receives only the endpoint.
- [x] Use the same `GitWorktreeWriteRoots` roots and `on-request` approval for both
  Codex-backed CI lanes. Remove DeepSeek-only named profiles and Git/Xcode
  filesystem exceptions.

Gate:

- [x] An offline fixture using the supported Codex binary and fake Responses
  server compares the effective attended and CI permissions between DeepSeek
  and subscription Codex tasks. It verifies the same Git configuration and
  ordinary local commit behavior, including linked-worktree CI commits. The
  proxy must still add the key upstream while no key reaches Codex or a
  model-facing shell or normalized event. The fixed task MCP must complete a
  tool round trip without a broker bearer in Codex. Both loopback servers
  close with the session. No plaintext auth file is created.

Evidence: `TestCodexLaunchProfile` compares attended and CI launch arguments
and thread settings across both Codex-backed lanes. Full `mvn -q verify`
passed. The native fake-key probe passed in local, attended commit, and CI
modes on Codex 0.156.1. It verified the effective Codex sandbox and reviewer,
an attended Git commit through the shared workspace-write roots, a CI commit with the
shared Git roots, a task MCP round trip, proxy authentication, secret exclusion,
and thread resume. The native probe also loaded a nonempty per-key Codex user
configuration while the shared launch settings remained effective. Earlier
restricted-profile evidence is superseded.

## CP3 — Admission, automation, and failures

- [x] Use authenticated DeepSeek GET /models as a key/model preflight.
  Codex login status must not decide DeepSeek readiness. Distinguish
  invalid key, missing key, and temporary API failure without leaking data.
- [x] Recheck readiness before task resource allocation and every later
  turn. A rejected request creates no worktree or failed turn.
- [x] Carry explicit accepted development automation through task creation,
  later turns, queued turns, and generated follow-ups. Use the existing local
  commit, scoped draft-PR, and CI Autofix paths without a lane-specific
  local-only override.
- [x] Admit DeepSeek as an explicit CI lane or as **Same agent as development**.
  Inheritance keeps the saved-key lane, not the development model; a model-less
  CI task selects and preflights `deepseek-flash` before run allocation.
- [x] Keep the automatic Development/Review default chooser CLI-only and
  reject DeepSeek in those setting updates. Keep issue starts and PR assignment
  outside this change.
- [x] Inherit saved Workflow automation settings for new DeepSeek tasks when
  the user does not choose a task-specific override.
- [x] Classify invalid-key, balance, and model failures during a turn.
  Preserve history, require an explicit retry, and never auto-resend.
  Ignore hidden reasoning text in provider event mapping.

Gate:

- [x] Backend flow tests prove admission fails before resource allocation,
  accepted automation survives task and queued-turn paths, CI preflights its
  exact default model, removed keys cannot start another turn, and failed
  turns retain readable history.

Evidence: focused `agent-core` and `app` tests and the full backend gate passed.
`TestDefaultAgentTaskService`, `TestDefaultAgentTurnService`,
`TestInteractiveAgentTaskCreator`, `TestDefaultCiAutofixService`, and
`TestDeepSeekAgentDriver` cover admission ordering, accepted/queued settings,
CI model-less readiness, removed-lane rejection, and typed key/API errors
without secret echoing. The malformed saved-key regression test proves
readiness returns an actionable key status before sending a request.

## CP4 — Settings and task UI

- [x] Show each saved DeepSeek key once in the task provider picker with
  its alias, supported model choices, and the existing Automation control.
  Keep key management in Models settings.
- [x] Replace stale DeepSeek model names in settings and avoid presenting
  a duplicate disabled key option beside the working lane.
- [x] Show actionable invalid-key and removed-key states. Historical
  tasks must render when their lane no longer appears in provider status.
  Do not offer a Codex sign-in remedy for a DeepSeek-key error.
- [x] Show CI selection and `@ci` routing when the saved-key lane advertises
  `CI_AUTOFIX`. Keep unsupported web, image, child-agent, and CodeGraph
  controls hidden through advertised capabilities.

Gate:

- [x] Focused frontend tests cover add/select/model choice, Automation and CI
  controls, invalid-key recovery, and an old task after key removal.

Evidence: `npm test` passed 100 files and 1,106 tests after the Automation and
CI UI changes, including the provider picker, task screen, and task-card cases.

## CP5 — Full verification and live gate

- [x] Run backend mvn verify after the shared-permission change.
- [x] Run frontend npm run lint, npx tsc --noEmit, npm test, and
  npm run package.
- [x] Run the offline Codex attended/CI permission-parity and task MCP
  fixtures, and inspect redacted logs and task events for secret or reasoning
  leakage.
- [x] Verify CI sandbox dependency downloads with a real JVM and Gradle/Maven
  against disposable local artifacts; keep restricted turns offline.
- [x] Run the existing scoped draft-publication and CI coordinator regression
  tests alongside DeepSeek-specific admission and native commit/CI probes.
- [ ] With an opt-in real DeepSeek key, complete a local edit and commit under
  ByteQuay approval routing, then resume the same task with
  another supported model. Cancel a separate active turn with Stop. Perform
  no GitHub mutation during this test.
- [ ] If a live GitHub delivery or CI check is requested, use a disposable
  destination with explicit user authorization for its exact writes.
- [x] Confirm a removed or invalid key blocks a later turn and preserves
  the previous transcript. Record which checks used a fake key and which
  actually contacted DeepSeek.

Evidence: before permission unification, backend `mvn verify` and frontend
lint, typecheck, tests, and package passed. Native Codex and task MCP probes
used fake keys and local servers. An opt-in `ds-001` task contacted DeepSeek
Flash, created a file in its Trino worktree, and committed it locally as
`8cd01cc3c07763e0f201626abfb8707f1efc4519`. Later V4 Pro turns resumed
the task and ran ordinary Git status commands. A separate Flash turn was
stopped while `/bin/sleep 90` ran; the previous local commit remained. These
checks used the superseded restricted profile and do not prove permission
parity. No live test pushed or created a pull request. The live GitHub-write
check remains open.

After permission unification, `mvn -q verify` passed and all three native
fake-key probe modes passed. The rebuilt backend resumed the existing `ds-001`
task under the shared Codex permission path. DeepSeek Flash appended one line
to `bytequay-deepseek-smoke.md` and made local commit
`b31a4e8d85604cdb3e72f86ba1f9d39ecf263489` using the available Git author
settings. The task worktree is clean; no pull request or CI follow-up exists.
The broader live gate remains open for a post-unification model switch and
Stop check.

Sandbox-network follow-up: Codex and DeepSeek full worktree turns now share
network access for builds, while Claude CI uses its sandbox network setting.
The native Codex CI fixture downloaded a local artifact through both curl and
Java and confirmed a restricted command stayed offline. Separate disposable
Maven and Gradle CI-shaped fixtures fetched POM/JAR artifacts into the allowed
cache roots with no approval requests. Codex's optional network proxy is
disabled because its macOS socket policy blocked JVM downloads in the same
fixture. These checks used local servers and fake provider credentials. The
final backend `mvn verify` passed after the shared network and CI guidance
changes.

Permission unification follow-up: `mvn clean verify -q` passed. The native
DeepSeek fixture used a fake key and confirmed authenticated Responses calls,
secret-free model shell commands, and workspace-write edits. The native Codex
CI fixture confirmed that an out-of-root command requests approval and a
decline lets the turn finish. A new `ds-001` task in the Trino workspace used
the saved key to get a one-word `READY` reply from DeepSeek Flash; its turn
completed and the temporary task and managed worktree were deleted. Delivery
automation was disabled for that connectivity check. These checks do not
exercise a live CI repair or GitHub write.

Final gate:

- [x] Resolve the omitted-settings default. The new-task composer now uses the
  saved Workflow choices for DeepSeek, and its browser fixture verifies both
  task and first-turn settings. Frontend lint, typecheck, tests, package, and
  backend verification passed.
- [ ] Complete the real-key gate and confirm the canonical design describes
  the delivered scope without claiming unverified capabilities.
