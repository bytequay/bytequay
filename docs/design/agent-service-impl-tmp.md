# Agent Service Backend Implementation Plan

This temporary checklist tracks the backend-only implementation of
[Agent Service Design](agent-service.md). It does not include frontend work,
worktree creation or deletion, provider-history deletion, hidden reasoning,
an API provider driver, Flyway, or compatibility code for the deleted agent
system.

Every checkpoint must leave the backend reactor passing `mvn verify`. Provider
checkpoints must also pass their protocol-fixture suites before the next lane
starts.

## Progress

- [x] Read the repository guidance, architecture, and agent-service design.
- [x] Confirm the existing `core`, `persistence-sqlite`, and `app` mechanical
  module split is complete.
- [x] Establish a clean baseline: `mvn verify` passes, including the existing
  62-test app suite.
- [x] Checkpoint 1 — lock framework-free agent contracts.
- [x] Checkpoint 2 — add the durable runtime foundation.
- [x] Checkpoint 3 — implement lifecycle orchestration with a deterministic
  fake lane.
- [x] Checkpoint 4 — compose Spring HTTP and SSE endpoints.
- [ ] Checkpoint 5 — implement the Codex App Server lane.
- [ ] Checkpoint 6 — implement the Claude `stream-json` lane.
- [ ] Checkpoint 7 — implement completion, recovery, and retention.

## Checkpoint 1 — Framework-free contracts

- [x] Add the `agent-spi` Maven module and its dependency boundary.
- [x] Implement provider identifiers, capabilities, health, session, turn,
  usage, event, and approval values.
- [x] Implement `AgentDriverRegistry`, `AgentDriver`, `AgentSession`,
  `RunningTurn`, and `AgentEventSink`.
- [x] Add the `agent-core` Maven module depending only on `agent-spi` and test
  dependencies.
- [x] Implement task, turn, approval, workspace, resource, metrics,
  completion, and cleanup values.
- [x] Implement validated task and turn lifecycle transitions.
- [x] Implement agent commands, normalized event envelopes, and domain
  exceptions.
- [x] Implement task, turn, and JSONL/blob storage ports.
- [x] Implement the eight public agent-core service contracts.
- [x] Document every SPI, service, and storage interface method with its
  behavioral guarantees and failure semantics.
- [x] Follow compact-record constructor, Guava immutable collection,
  shared-precondition, and Maven dependency-spacing conventions.
- [x] Include the design-required recovery seams: provider approval callback,
  lease restoration, live/recoverable/cleanup queries, and last persisted
  event sequence.
- [x] Test value validation and task/turn transition invariants.
- [x] Verify `agent-spi` and `agent-core` contain no Spring, JPA, SQLite, HTTP,
  or provider wire-format dependencies.
- [x] Run the complete backend `mvn verify` gate.

Review gate: freeze the provider-neutral contracts before persistence or CLI
implementation begins.

## Checkpoint 2 — Durable runtime foundation

- [x] Implement safe task resource allocation, resolution, permissions, and
  deletion.
- [x] Implement argv-based Git worktree inspection, clean-state validation,
  repository matching, and exclusive leases.
- [x] Add task and turn JPA entities, repositories, and SQLite stores with
  optimistic locking, idempotency, and one-active-turn enforcement.
- [x] Implement flushed JSONL append/read, blob storage, tail recovery, and
  sequence recovery.
- [x] Reject unsupported event schema versions and oversized persisted records.
- [x] Recover failed appends without consuming a sequence and keep per-task
  serialization locks stable for the service lifetime.
- [x] Keep idempotent missing-resource deletion distinct from unsafe-resource
  failures.
- [x] Round-trip every normalized event type, partial/final message authority,
  and cumulative usage.
- [x] Validate the raw-provider/log boundary against real Codex App Server
  JSON-RPC and Claude `stream-json` captures.
- [x] Implement metrics, approvals, and event sequencing/publishing services.
- [x] Test SQLite constraints, path escape, symlink handling, permissions,
  append-before-publish, replay/live handoff, backpressure, and corrupt-tail
  behavior.
- [x] Run `mvn verify`.

Review gate: durable state and event ordering work without a real provider.

## Checkpoint 3 — Provider-neutral lifecycle

- [x] Implement the static driver registry and deterministic test driver.
- [x] Implement the in-memory runtime/session registry.
- [x] Implement task creation and rollback on failed creation.
- [x] Implement idempotent turn start, provider session persistence, event
  routing, completion, cancellation, approval denial, and session resume.
- [x] Exercise create-to-multiple-turn behavior through the fake lane.
- [x] Run `mvn verify`.

Review gate: approve the complete core lifecycle before CLI integration.

## Checkpoint 4 — Spring HTTP and SSE

- [x] Add agent configuration properties and Spring composition.
- [x] Add task, turn, event, approval, and provider-status controllers.
- [x] Adapt `Flow.Publisher` to SSE with cancellation and backpressure.
- [x] Map agent exceptions to stable localhost HTTP responses.
- [x] Enforce the backend desktop-session/origin contract on agent endpoints.
- [x] Add `scripts/agent-e2e.sh` to start an isolated backend, create a clean
  temporary Git repository, send turns over HTTP, and assert the SSE stream
  through the deterministic lane. Reuse the same script for the Codex and
  Claude lanes in Checkpoints 5 and 6. Its `--interactive` mode keeps SSE open
  while a developer sends messages and inspects events or backend logs; quit
  or Ctrl-C stops the backend and removes its temporary state.
- [x] Test validation, conflicts, idempotency, reconnect replay, and Spring
  context startup without installed provider binaries.
- [x] Run `mvn verify`.

Review gate: approve the backend API contract and deterministic shell E2E
before provider rollout.

## Checkpoint 5 — Codex CLI lane

- [x] Recheck the supported official App Server schema and CLI version range.
- [ ] Implement the shared streaming process supervisor with separate bounded
  stdout/stderr, stdin, exit, and process-tree cancellation.
- [ ] Implement schema bindings, JSON-RPC initialization, thread start/resume,
  turn start/interrupt, approval responses, event mapping, and unsubscribe.
- [ ] Fixture-test session resume, events, final-item authority, cumulative
  usage, disconnect, interruption, redaction, and cancellation.
- [ ] Exercise repeated interactive turns through the backend HTTP/SSE API
  against the real Codex CLI, including command output, final responses, and
  Ctrl-C cleanup.
- [ ] Run `mvn verify`.

Review gate: ship no Claude behavior in the Codex change.

## Checkpoint 6 — Claude CLI lane

- [ ] Recheck supported streaming, hook, subagent, resume, and permission
  flags.
- [ ] Implement argv construction, stream parsing, event mapping, task-scoped
  permission MCP endpoint/configuration, resume, and terminal result handling.
- [ ] Fixture-test partial/final deduplication, tools, hooks, nested agents,
  approvals, usage/cost provenance, malformed lines, trailing events,
  stderr, redaction, and process-tree cancellation.
- [ ] Run `mvn verify`.

Review gate: both provider lanes satisfy the same provider-neutral core
contract without controller branching.

## Checkpoint 7 — Completion, recovery, and retention

- [ ] Implement atomic completion summary and seven-day deletion deadline.
- [ ] Implement startup interruption recovery and durable lease restoration.
- [ ] Implement due cleanup, retry-pending state, and scheduled retries.
- [ ] Add complete and cleanup HTTP operations only after their services exist.
- [ ] Test completion and cleanup idempotency, early-cleanup rejection,
  restart recovery, cleanup failure, and terminal-state preservation.
- [ ] Run the full `mvn verify` gate and packaged sidecar startup check.

Final gate: all existing GitHub and pull-request behavior remains unchanged.
