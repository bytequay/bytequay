# Codex App Server compatibility fixture

ByteQuay's compatibility candidate is exactly `codex-cli 0.151.0`. The
executable contract lives in `scripts/codex-app-server-compat.mjs`; its checked-in
metadata records the schema-generation command and the SHA-256 hashes of both
stable and experimental generated bundles. The fixture does not vendor a second
copy of the 413 generated schema files.

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
temporary directory, checks their hashes and required fields, proves that the
environment and background-terminal methods remain experimental, and removes
the temporary directory. These offline Codex subprocesses also use a minimal
allowlisted environment and an empty temporary `CODEX_HOME`; API-key variables
from the caller are not inherited.

Run the live gate only with a dedicated API key that may make two model turns:

```text
BYTEQUAY_CODEX_COMPAT_API_KEY=<dedicated-key> \
  node scripts/codex-app-server-compat.mjs --live
```

The live gate starts one App Server and two version-matched loopback
`exec-server` processes, each rooted in a clean disposable Git repository. It
passes the dedicated key only to the App Server as
`CODEX_API_KEY`; no credential is copied to disk. Each executor receives a
different, empty `CODEX_HOME` and a minimal allowlisted process environment.
The App Server's shell policy passes only core command variables and omits
`CODEX_API_KEY` and `CODEX_HOME`. A sentinel present only in the App Server
process must also be absent from each command. The fixture disables apps,
plugins, host skills, hooks, web search, multi-agent, and related optional
tools. Before the first turn it verifies that the task sees no MCP servers,
hooks, project config, or user config outside the temporary App Server home.

It initializes with `experimentalApi`, reads the model catalog, registers and
handshakes two temporary working directories, and starts one durable fixture
thread in the isolated App Server home. Turn one selects executor A with the
`untrusted` approval policy, accepts approval callbacks confined to that
command item, and requires exactly one parent approval for the intended command
before writing one bounded untracked proof file. The still-running App Server
then receives `thread/resume`, and turn two selects executor B with no approval.
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

Turn one uses `approvalPolicy: "untrusted"`; turn two uses
`approvalPolicy: "never"`. Both use a workspace-write shell sandbox restricted
to the selected temporary environment root with shell network disabled. App
Server still uses provider network traffic for the two model turns. The
durable thread exists only inside the temporary `CODEX_HOME`; after the App
Server and both executor process groups have stopped, that home and the two
environments are removed. Signal handling terminates every registered process
group before cleanup. If group termination cannot be confirmed, the fixture
reports the cleanup failure and retains its temporary tree for diagnosis. The
fixture does not archive or delete user task history.

## Evidence and remaining credential gate

The offline gate passes with `codex-cli 0.151.0`. A complete two-turn behavior
slice also passed on 2026-08-30 with ChatGPT authentication and the advertised
default model `gpt-5.6-sol`:

```text
PASS exact Codex version 0.151.0
PASS generated stable and experimental schema (codex app-server generate-json-schema --experimental --out <temporary-directory>)
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
non-empty yielded background terminal and targeted termination, every approval
shape/decision, recursive child discovery, cross-task
event/approval/filesystem isolation, and production adoption of the isolated
launch profile remain separate CP1
evidence. The safe live path is now also configured to exercise one typed
parent-command approval and one bounded Git-worktree mutation per repository
without adding a model turn. It keeps its API key out of executor process
environments and records an exact command that checks that the key,
`CODEX_HOME`, and App Server sentinel are absent. The dedicated-key rerun must
prove these added behaviors. The fixture does not claim same-user filesystem read
confinement outside the executor sandbox or restart-based thread recovery.
Product decisions about relying on an experimental unsupported-for-production
command and the same-host executor trust boundary also remain unresolved.
