# Codex App Server compatibility fixture

ByteQuay's compatibility candidate is exactly `codex-cli 0.151.0`. The
executable contract lives in `scripts/codex-app-server-compat.mjs`; its checked-in
metadata records the schema-generation command and the SHA-256 hashes of both
stable and experimental generated bundles. From those exact bundles, the
fixture derives one compact committed projection manifest for only the Java
records, fields, and enums ByteQuay consumes. It does not vendor the 413
generated schema files or 820 generated TypeScript files.

This is a reference-only development fixture, not a production readiness or
version gate. Production uses the user-managed Codex CLI, keeps its startup
self-update enabled, and does not interpret any reported version text as a
readiness gate. When the running protocol is incompatible,
ByteQuay reports the detected version text with the failing operation.

Run the offline gate with a deliberately selected v0.151.0 Codex binary:

```text
node scripts/codex-app-server-compat.mjs
```

It rejects any other version, regenerates both schema variants in a new system
temporary directory, checks their hashes and required fields, derives the
compact projection again, and requires it to equal the committed
`backend/agent-cli/src/test/resources/com/bytequay/agent/cli/codex/app-server-v0.151.0-bindings.json`.
It also proves that the environment and background-terminal methods remain
experimental, then removes the temporary schemas. These offline Codex
subprocesses use a minimal allowlisted environment and an empty temporary
`CODEX_HOME`; API-key variables from the caller are not inherited.

The offline path also starts App Server without credentials and calls
`config/read(includeLayers: true)` before any thread or model turn. As an
adversarial control, it creates an isolated-user `.rules` file whose `allow`
decision is first validated with the
[documented `codex execpolicy check` command](https://learn.chatgpt.com/docs/agent-configuration/rules);
the App Server layer scan must expose the active isolated user layer and reject
that source. After the poison file is removed, a clean workspace passes only
after the fixture checks the `rules/` directory beside every returned system,
user, or project policy-bearing config source and finds no `.rules` file.
`packagedDefaults` and `legacyManagedConfigTomlFromFile` layers are not
exec-policy sources. The behavioral poison control exercises the isolated user
source without claiming every source type is present. The live isolation path
calls `config/read` for every temporary task cwd. This matters because v0.151
App Server has no public `--ignore-rules` option and no effective rule-inventory
RPC. Production must reproduce this conservative readiness guard rather than
claiming a per-thread config override disables rules.

The same keyless App Server probe calls `configRequirements/read`, pins the
response shape, requires an explicit null `requirements` field, and fails for
an omitted or non-null value. Because command-rule contents are not inspectable
through the current App Server responses, the first release treats any
missing `requirements` field, non-null value, request failure, or undecodable
response as unavailable. The response must contain exactly that one top-level
key; an unknown sibling policy field also fails closed.

Before any thread exists, that probe repeats `config/read`, `skills/list`, and
`hooks/list` with three separate App Server processes: both multi-agent
features disabled, only `multi_agent` enabled, and only `multi_agent_v2`
enabled. Each process must report exactly one active `sessionFlags` layer whose
multi-agent values match that launch profile and whose other optional features
remain disabled. The V1 profile also fixes and verifies `agents.max_depth=2`,
because [v0.151 defaults that value to
one](https://github.com/openai/codex/blob/rust-v0.151.0/codex-rs/core/src/config/mod.rs#L232)
and its [V1 spawn handler rejects the next depth above that
limit](https://github.com/openai/codex/blob/rust-v0.151.0/codex-rs/core/src/tools/handlers/multi_agents/spawn.rs#L63-L70),
so the default permits a root child but not that child's required grandchild;
V2 does not use this V1 depth gate. Disabled web search,
host-skill-discovery disable, and the
minimal shell-environment policy must appear in both the session flags and the
effective config, with an empty MCP-server map and no inherited shell
environment setters, exclusions, or filters. Hook discovery must return one
clean, empty result for the requested canonical cwd. This proves the isolated
profiles can be selected; it does not prove that a model can create children.

`skip_host_skill_discovery` does not make the v0.151 skill catalog empty. App
Server materializes its bundled system skills beneath the isolated
`CODEX_HOME/skills/.system`. The probe permits only those canonical paths with
`scope: "system"` and no plugin owner; any repository, user, admin, plugin, or
out-of-root skill, or any discovery error, fails the gate. It deliberately does
not pin the bundled skill names or count.

Synthetic poison responses prove the guards reject a repository skill, a
plugin-owned system skill, a canonical path outside the isolated system-skill
root, a skill-discovery error, and any discovered lifecycle hook. These are
guard-unit controls; the real App Server response remains the positive runtime
control.

Regenerate the committed projection only when intentionally updating the exact
reference contract:

```text
node scripts/codex-app-server-compat.mjs --update-bindings
```

The normal command verifies rather than rewrites the manifest. The
`agent-cli` conformance test then uses reflection and real codec calls to
require the projected Java record components, wire names, required fields,
required non-null values, and known enum values. Optional properties
deliberately decode explicit null as absent. Decoding also remains tolerant of
additive object fields, and each projected enum maps an unknown future wire
value to `UNKNOWN`. This reference check never validates or rejects the user's
production Codex version.

The projection and codec checks cover the v0.151.0 modern approval methods,
top-level fields, requiredness, command requests with `command` and
`writeStdin` kinds and every legal decision shape, file-change requests and
their four decisions, and the opaque permission payload with top-level
response fields and turn/session scope. Legacy
`applyPatchApproval` and `execCommandApproval` requests remain unsupported and
are proven to fail closed. This is offline wire evidence only. The current
runtime bridge still maps command/file one-time allow or deny to
`accept`/`decline`. It rejects command/file scope it cannot represent and
stale or post-cancellation decisions; preserving the full choice set in
ByteQuay's provider-neutral contract and UI is deferred to CP2 and later
checkpoints.

The offline schema gate also checks the minimum child-thread contract before
ByteQuay implements child routing. Stable schema must retain the subagent
source kinds, thread parent/session/status metadata,
`thread/status/changed`, V1 `collabAgentToolCall` sender, receiver, and agent
state fields, and V2 `subAgentActivity` thread, logical path, and lifecycle
fields. The `thread/list` method and source filtering are stable, but its
direct-parent and ancestor filters must appear only in the experimental
schema. Those filters may accelerate reconciliation; the canonical recursive
history closure does not rely on them. These checks are schema evidence only:
they neither enable multi-agent features nor add production child routing.
Thread status is load/activity state, and list responses contain no turn
history, so neither can prove that a child is terminal.

The compact Java projection retains the optional model multi-agent version,
the required V1 sender/receiver/state identity and optional state message, the
required V2 thread/path lifecycle identity, and recursive `thread/read`
parent/session/turn/item history. It accepts omitted optional capability
fields, additive object fields, and future enum members through `UNKNOWN`.
This does not subscribe to child threads, build a graph, or publish V2
activity. The live child-capable proof now covers the reference behavior;
production routing remains in later integration checkpoints.

The no-flag command above remains the default, authentication-free, CI-safe
gate. The checked-in CI workflow does not invoke either live flag. Before a
developer deliberately runs a live gate, create a separate persistent profile,
restrict it to the current user, and sign that profile into ChatGPT directly
with file-backed credential storage:

```text
mkdir -m 700 /absolute/path/to/bytequay-codex-compat-home
CODEX_HOME=/absolute/path/to/bytequay-codex-compat-home \
  codex login \
    -c 'cli_auth_credentials_store="file"' \
    -c 'forced_login_method="chatgpt"'
```

This profile is only for the compatibility fixture. Never use the normal
or otherwise ambient Codex profile, never place it inside the ByteQuay
repository, and never seed it by copying `auth.json`; sign in while that exact
`CODEX_HOME` is selected. Do not add `config.toml`, rules, or user skills to
this auth-only profile, and do not add `AGENTS.md` or `AGENTS.override.md`.
Keep the directory after a run so Codex can refresh the same login safely.
API-key authentication is deferred.

Run the no-child live gate explicitly with that profile. It makes three turns
across two independent root tasks and may make multiple provider requests:

```text
BYTEQUAY_CODEX_COMPAT_HOME=/absolute/path/to/bytequay-codex-compat-home \
  node scripts/codex-app-server-compat.mjs --live
```

Run the recursive-child gate separately. It creates one root, one child, and
one grandchild turn and may make multiple provider requests:

```text
BYTEQUAY_CODEX_COMPAT_HOME=/absolute/path/to/bytequay-codex-compat-home \
  node scripts/codex-app-server-compat.mjs --live-children
```

The two live modes are intentionally mutually exclusive so their provider
usage and evidence remain attributable. A profile-local exclusive lock also
rejects concurrent live-gate processes; a stale lock requires manual inspection
before removal. Immutable path and mode checks run first, then the lock is
acquired before mutable auth, instruction, and runtime-state checks. They are
local, opt-in smoke tests, not default tests and not normal CI work. An explicit
live invocation fails before App Server starts when the profile variable is
absent; the directory is not an absolute 0700 directory outside the repository
and disposable fixture root; `auth.json` is absent, linked, matches or reuses a
current ambient credential, or is not locally marked `chatgpt`; persistent
instruction files exist; or prior fixture runtime/config state remains.
Historical provenance cannot be inferred after credentials rotate, so never
copying `auth.json` remains an operator rule. After startup, `account/read` must
still report `requiresOpenaiAuth: true` and a `chatgpt` account before provider
work.

The `--live` gate initially starts one App Server and two version-matched loopback
`exec-server` processes, each rooted in a clean disposable Git repository. It
passes the selected persistent profile only to the App Server as `CODEX_HOME`
and confirms through `account/read` that OpenAI authentication is required and
the active account type is `chatgpt` before provider work. No credential is
copied. Before launching, the fixture rejects user configuration and stale
run state; after account validation it repeats config, rule, skill, hook, and
managed-policy isolation checks before `model/list`, environment registration,
or thread creation. A temporary `CODEX_SQLITE_HOME` owns the live run's thread
database and survives the App Server restart used by the recovery proof.
App Server also writes run-scoped rollout, writer-lock, shell-snapshot, and
trust records beneath the dedicated profile; the fixture removes those exact
runtime entries after every owned process group stops. Each
executor receives a different, empty `CODEX_HOME` and a minimal allowlisted
process environment. The App Server's shell policy passes only core command
variables and omits `CODEX_API_KEY`, `OPENAI_API_KEY`, and `CODEX_HOME`. A
sentinel present only in the App Server process must also be absent from each
command. The fixture disables apps, plugins, host skill discovery, hooks, web
search, multi-agent, and related optional tools. Before each turn and again
after restart, it repeats the effective launch-policy, skill-origin, hook,
config-source, and managed-policy checks for every task cwd, then verifies that
the thread sees no MCP servers.

The `--live` path is intentionally a no-child isolation baseline. The keyless
probe proves that either child-capable launch profile can enable exactly one
reviewed multi-agent feature while repeating every pre-thread isolation check.

The `--live-children` path first starts the no-child profile, repeats the
pre-thread isolation checks, pages `model/list`, and chooses a visible
text-capable model that advertises V1 or V2 multi-agent support. It then
restarts App Server with only that matching multi-agent feature enabled,
repeats the isolation checks, registers one disposable Git environment, and
sends one bounded root turn. The V1 launch also retains the verified
`agents.max_depth=2` override required for the grandchild. Recursive
`thread/read(includeTurns: true)` is the canonical discovery source. The gate
reads every V1 `spawnAgent` or V2
`started` edge to a fixed point and requires exactly root, child, and
grandchild in one session with direct-parent identity, one completed turn
each, and exact final markers. V2 upward `interacted` activity never creates a
child edge. Only passive transcript items and the selected collaboration item
type are used to discover the graph. The other known V1/V2 collaboration item
may coexist as supplementary history and is validated against the same known
thread set, but never creates an edge for the advertised protocol. Unknown or
execution-capable items fail closed. The gate also requires no approvals, no
Git mutation, and an empty paginated
background-terminal list for all three threads. This implements the live
proof path; its passing explicit subscription-backed run closed the
recursive-child CP1 gate. The normal keyless command feeds synthetic V1 and V2
trees, including V2 upward `interacted` activity, through the same graph
assertions; that is regression coverage for the checker, not model-behavior
evidence.
The bounded prompt asks each parent to wait to reduce nondeterminism, but V2
history does not expose a distinct wait item. Acceptance therefore proves the
topology and eventual terminal fixed point, not wait-call causality.

It initializes with `experimentalApi`, reads the model catalog, registers and
handshakes two temporary working directories, and starts two durable root
threads with distinct session trees. Task A turn one selects executor A with
the `untrusted` approval policy, accepts approval callbacks confined to that
command item, and requires exactly one approval for the intended command before
writing one bounded untracked proof file. The still-running App Server then
resumes task A in the same environment. Its second turn writes the same bounded
file, emits an environment marker, and yields a tracked `/bin/sleep` process
while the turn completes.

While that command is yielded, the live item event and
`thread/backgroundTerminals/list` are authoritative. `thread/read` may omit the
still-running item; the fixture accepts that omission, but if the item is
present it must match exactly. After targeted termination, replayed history
must contain the same command item and its terminal state.

While task A's process remains listed and in progress, task B completes one
no-approval turn through executor B and writes a differently named and valued
proof file. Only then does the fixture terminate task A's listed process. The
captured event window must order task A command start, task B turn start, task B
turn completion, and task A command completion. Every thread, turn, and item
identity in that window must belong to the matching persisted task history;
task B must emit no approval and no background terminal. The fixture correlates
task A's process, item, command, and working-directory identities, observes its
command become failed, and verifies that cleanup does not change task B's
history. The yielded command's output delta and terminal `aggregatedOutput` are
both optional in the observed v0.151 runtime. When either is present, the
fixture correlates and validates it; when both are absent, ByteQuay still has
the exact structured action, lifecycle events, and background-terminal record
but must not synthesize a log body.

For both completed foreground commands, and for the yielded command whenever
its output is exposed, the fixture verifies that the recorded shell action
expands the environment variables. It always requires each proof file to be
the only Git mutation in its repository. Each repository must contain only its
task's file.
This proves that the writes were not misrouted, not that either same-user
process cannot read the other repository through an absolute path. Streamed
command events and replayed history must report the executor-specific `HOME`,
canonical working directory, unset command-level `CODEX_HOME`, absent
`CODEX_API_KEY`, absent `OPENAI_API_KEY`, and unset App Server sentinel. This
distinguishes both executors from the App Server's ambient process and verifies
the tested shell environment policy.
Every unexpected App Server request fails the fixture. A command is not
required to emit a separate output-delta notification, and a targeted
termination may report a null aggregate.

The approval handler compares the structured command action exactly before it
returns `accept`, and failure diagnostics report only bounded metadata, never
raw command text or output. This still is not an OS filesystem-read boundary:
the App Server and its tools run as the same macOS user, so an
absolute path could reach the dedicated profile if a command learned it. CP0's
same-host trust decision accepted that boundary for the planned first release
on 2026-08-31; a future OS-user/container boundary may strengthen it.

After both tasks finish and task A's background process is cleaned, the fixture
stops and verifies the old App Server and executor process groups. It starts a
fresh App Server with the same selected authentication profile, the same
temporary `CODEX_SQLITE_HOME`, the same run-scoped rollout state, and a fresh executor A,
re-registers environment A, resumes task A, and reads both durable root
histories without resending user input. Task A must retain its two turn and
command IDs; task B must retain its one disjoint turn and command ID. No provider
work may start during this recovery check. The passing subscription-backed gate
proves this narrow persisted-history recovery shape; production ownership and
crash recovery remain later checkpoints.

Task A turn one uses `approvalPolicy: "untrusted"`; task A turn two and task B's
turn use `approvalPolicy: "never"`. All three use a workspace-write shell
sandbox restricted to the selected temporary environment root with shell
network disabled. App Server still uses provider network traffic for the three
model turns. Their database state lives in the temporary `CODEX_SQLITE_HOME`,
while their rollout and writer-lock files remain run-scoped inside the
fixture-only profile until final cleanup. Each executor, including the
replacement executor, has a separate empty home. After every registered App
Server and executor process group has stopped, the fixture removes its exact
profile runtime entries, temporary SQLite state, executor homes, and two
environments, then releases the profile lock. A runtime-entry cleanup failure
is reported and the stale entry blocks the next preflight even though the lock
is released. It never deletes `auth.json` or the selected authentication
profile. Signal handling terminates every registered process group before
cleanup. If group termination cannot be confirmed, the fixture reports the
cleanup failure, retains its temporary runtime tree, and does not release the
profile lock. The fixture does not archive or delete user task history because
the selected profile is dedicated to this fixture and must be clean before
each run.

## Evidence and conditional decision

The current offline gate passed on 2026-08-31 with `codex-cli 0.151.0`:

```text
PASS compact Java projection matches the exact generated schema
PASS exact Codex version 0.151.0
PASS generated stable and experimental schema, including child-thread contract (codex app-server generate-json-schema --experimental --out <temporary-directory>)
PASS App Server no-child launch profile and config-source isolation
PASS App Server multi-agent-v1-enabled launch profile and config-source isolation
PASS App Server multi-agent-v2-enabled launch profile and config-source isolation
```

Both safe subscription-backed live gates then passed on 2026-08-31 with the
dedicated fixture profile and `gpt-5.6-sol`. The no-child evidence was:

```text
PASS live initialize and model/list (gpt-5.6-sol)
PASS ChatGPT subscription auth and credential-free executor environments
PASS empty MCP/hooks and disabled optional capabilities
PASS two root tasks route commands, events, and files without cross-talk
PASS command events, thread/read, resume, and task A's two turns
PASS yielded background-terminal list, targeted termination, and cleanup
PASS App Server restart and disk-backed thread recovery without resend
```

The separate child-capable evidence was:

```text
PASS live recursive child history (gpt-5.6-sol, v2)
PASS exactly one root child and one grandchild with completed markers
PASS child profile isolation, clean worktree, and empty background terminals
```

An earlier two-turn behavior slice passed on 2026-08-30 with
ChatGPT authentication and the advertised default model `gpt-5.6-sol`. Its
live-specific evidence was:

```text
PASS live initialize and model/list (gpt-5.6-sol)
PASS removed temporary login and auth-free executor environments
PASS empty MCP/hooks and disabled optional capabilities
PASS environment A and B command routing without ambient fallback
PASS command events, thread/read, resume, and two turns
PASS empty background-terminal list and cleanup
```

That run exposed a fixture-design risk: copying a rotating ChatGPT login can
consume a refresh token in the disposable copy and invalidate the user's
original login. The checked-in fixture therefore never copies `auth.json` and
never uses the normal `~/.codex`. Instead, a developer signs a separate
persistent 0700 profile into ChatGPT directly and explicitly selects it with
`BYTEQUAY_CODEX_COMPAT_HOME`. The current passes use that safe profile; the
historical subscription-auth run remains protocol evidence, not acceptance of
its unsafe copied-credential technique. API-key-backed smoke testing may be
added later, but is not a CP1 requirement.

The pinned binary supplies the environment registration and task/history
primitives required to begin a client integration. CP1's technical
recommendation is **GO** to CP2 provider-neutral contract work: CP0 is complete,
and the current offline, no-child live, and recursive-child gates passed. They
prove the reviewed
yielded-terminal and targeted-termination behavior, bounded Git-worktree
actions, one representative real command approval, recursive child discovery,
restart recovery, cross-task event/approval/command/filesystem isolation, and
the credential-stripped executor process-environment boundary.

This is not final product approval. Exhaustive modern wire and codec coverage
remains offline; richer product decision matrices begin in CP2, and production
adoption of the executable guards and child-capable profile belongs to CP4.
CP0 accepted the experimental risk and same-host boundary, selected the
separate managed-worktree contract, approved the development-only UI gate, and
captured the sanitized current HTTP/SSE baseline. CP1 is formally complete and
CP2 has started.
The fixture does not claim same-user filesystem read confinement outside the
executor sandbox or production crash recovery. The accepted product risks
remain explicit release constraints rather than evidence supplied by this
fixture.
