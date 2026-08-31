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

Before any thread exists, that probe also calls `config/read`, `skills/list`,
and `hooks/list` for the no-child isolation baseline. It requires exactly one
active `sessionFlags` layer containing every configured feature disable,
disabled web search, host-skill-discovery disable, and the minimal
shell-environment policy. The same values must appear in the effective config,
with an empty MCP-server map and no inherited shell environment setters,
exclusions, or filters. Hook discovery must return one clean, empty result for
the requested canonical cwd.

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
activity; those runtime behaviors remain behind the live child-capable proof
and later integration checkpoints.

Run the live gate only with a dedicated API key that may make two model turns:

```text
BYTEQUAY_CODEX_COMPAT_API_KEY=<dedicated-key> \
  node scripts/codex-app-server-compat.mjs --live
```

The live gate initially starts one App Server and two version-matched loopback
`exec-server` processes, each rooted in a clean disposable Git repository. It
passes the dedicated key only to the App Server as
`CODEX_API_KEY`; no credential is copied to disk. Each executor receives a
different, empty `CODEX_HOME` and a minimal allowlisted process environment.
The App Server's shell policy passes only core command variables and omits
`CODEX_API_KEY` and `CODEX_HOME`. A sentinel present only in the App Server
process must also be absent from each command. The fixture disables apps,
plugins, host skill discovery, hooks, web search, multi-agent, and related
optional tools. Before each turn and again after restart, it repeats the
effective launch-policy, skill-origin, hook, config-source, and managed-policy
checks for every task cwd, then verifies that the thread sees no MCP servers.

This live path is intentionally a no-child isolation baseline. It cannot close
the recursive-child gate while both multi-agent features are disabled. CP1
must add a separate child-capable profile that enables only the reviewed
multi-agent feature shape and repeats every isolation check before that profile
can become the production candidate.

It initializes with `experimentalApi`, reads the model catalog, registers and
handshakes two temporary working directories, and starts one durable fixture
thread in the isolated App Server home. Turn one selects executor A with the
`untrusted` approval policy, accepts approval callbacks confined to that
command item, and requires exactly one parent approval for the intended command
before writing one bounded untracked proof file. The still-running App Server
then receives `thread/resume`, and turn two selects executor B with no approval.
Turn two writes its bounded proof file, emits an environment marker, and yields
a tracked `/bin/sleep` process while the root turn completes. The fixture lists
exactly that background terminal, correlates its process, item, command, and
working-directory identities, terminates the listed process ID, observes the
command item become failed with its pre-yield output intact, and verifies that
the background list is empty before and after an idempotent clean.

For both turns, the fixture verifies that the recorded shell action expands the
environment variables and that the proof file is the only Git mutation in the
selected repository. Before turn two, executor B's repository must not contain
executor A's proof file; after turn two, each repository contains only its own
file. This proves that the writes were not misrouted, not that either same-user
process cannot read the other repository through an absolute path. Streamed
command events and replayed history must report the executor-specific `HOME`,
canonical working directory, unset command-level `CODEX_HOME`, absent API key,
and unset App Server sentinel. This distinguishes both executors from the App
Server's ambient process and verifies the tested shell environment policy.
Every unexpected App Server request fails the fixture. A short command is not
required to emit a separate output-delta notification.

After both turns and background cleanup, the fixture stops and verifies the old
App Server and executor process groups. It starts a fresh App Server with the
same isolated App Server home and a fresh executor B, re-registers environment
B, resumes and reads the durable thread without resending user input, and
requires the same two turn IDs and command item IDs. No provider work may start
during this recovery check. When the dedicated-key gate passes, this will prove
the narrow persisted-history recovery shape; production ownership and crash
recovery remain later checkpoints.

Turn one uses `approvalPolicy: "untrusted"`; turn two uses
`approvalPolicy: "never"`. Both use a workspace-write shell sandbox restricted
to the selected temporary environment root with shell network disabled. App
Server still uses provider network traffic for the two model turns. The durable
thread exists only inside the temporary App Server `CODEX_HOME`; each executor,
including the replacement executor, has a separate empty home. After every
registered App Server and executor process group has stopped, those homes and
the two environments are removed. Signal handling terminates every registered
process group before cleanup. If group termination cannot be confirmed, the
fixture reports the cleanup failure and retains its temporary tree for
diagnosis. The fixture does not archive or delete user task history.

## Evidence and remaining gates

The current offline gate passed on 2026-08-31 with `codex-cli 0.151.0`:

```text
PASS compact Java projection matches the exact generated schema
PASS exact Codex version 0.151.0
PASS generated stable and experimental schema, including child-thread contract (codex app-server generate-json-schema --experimental --out <temporary-directory>)
PASS App Server no-child baseline and config-source isolation
```

Separately, a complete two-turn behavior slice passed on 2026-08-30 with
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
requires a dedicated API key. The final live gate still needs a run with that
credential; the subscription-auth run is protocol evidence, not acceptance of
the unsafe credential technique.

This is a **GO candidate for the tested CP1 reference slice**: the pinned
binary supplies the environment registration and task/history primitives
required to begin a client integration. It is not final product approval. A
dedicated-key execution of the newly configured yielded-terminal, targeted
termination, bounded Git-worktree filesystem action, restart-recovery checks,
one representative real approval, the full modern live approval matrix,
recursive child discovery, cross-task event/approval/command/filesystem
isolation, API-key isolation, and the written go/no-go result remain separate
CP1 evidence. Offline
approval codec coverage does not close either live gate. Production adoption
of the executable guards and a separately proven child-capable launch profile
belongs to CP4. The safe no-child live path is now also
configured to exercise one typed parent-command approval and one bounded
Git-worktree mutation per repository without adding a model turn. It uses the
second turn for the yielded-terminal proof and reuses the same durable thread
for restart recovery. It keeps its API key out of executor process
environments and records an exact command that checks that the key,
`CODEX_HOME`, and App Server sentinel are absent. The dedicated-key rerun must
prove these added behaviors. The fixture does not claim same-user filesystem read
confinement outside the executor sandbox or production crash recovery.
Product decisions about relying on an experimental unsupported-for-production
command and the same-host executor trust boundary also remain unresolved.
