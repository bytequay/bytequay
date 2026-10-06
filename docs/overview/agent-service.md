# Agent service

ByteQuay's replacement agent service is implemented and shared by interactive
work and approved feature workflows. It must not restore the removed task-agent
or staged development-flow systems. This document defines the durable design;
provider wire formats and exact contracts belong in source and tests.

## Ownership

A task belongs to one workspace, generated branch, managed worktree, and selected
provider lane. Its turns resume the associated provider conversation. Changing
workspace defaults does not silently move an existing task to another account
or provider.

ByteQuay owns task lifecycle, authorization, durable facts, event projection,
input queues, and the UI. The provider harness owns its model/tool loop, native
tools, sandbox, and approval mechanisms. Capability discovery determines which
interactions are available; unsupported features are not simulated.

The shared UI presents messages, plans, folded activity, approvals, supported
attachments, child-agent inspection, steering, queued inputs, recovery, and
completion. It does not expose raw provider payloads or hidden reasoning.

## Turn execution

1. Check provider executable and authentication readiness before allocating
   task resources. Resolve the task's workspace and exact starting revision.
2. Provision and verify a separate managed worktree. Accept input with a stable
   identity so repeated requests do not create duplicate work.
3. Claim the task's mutable worktree for one active turn. Start the matching
   provider process and pass applicable instructions and capability scope.
4. Normalize visible provider events and commit state/event facts durably before
   streaming them through Electron to the renderer.
5. Record the provider result separately from process cleanup. Verify root,
   child, tool, and background-process quiescence before releasing the claim.
6. Start an explicitly queued request only when the preceding turn settled under
   the queue rules. Later turns resume the same task conversation.

Codex uses a task-owned App Server per active turn; Claude uses a separate CLI
process per turn. Each process is rooted at the task worktree. Child agents stay
within that task's ownership and selected provider capabilities.

A read-only side discussion may have a separately supervised process alongside
an active task. It is not another worktree-writing turn and does not enter the
main conversation, event history, or input queue.

## Persistence and recovery

SQLite retains durable task, turn, input, queue, approval, process-ownership,
metric, and event-delivery facts. Task runtime files retain detailed replay and
bounded attachment/output data. Durable task facts survive detailed-history
retention and normal worktree cleanup.

Electron main reconnects by event sequence. The renderer derives presentation
from normalized events rather than interpreting provider protocols. Provider
history is not assumed to reconstruct every lost native event.

Uncertain input delivery is never automatically resent. Startup reconciles owned
processes before exposing tasks as ready. Cleanup failure preserves a proven
provider result, process identity, and worktree claim until safe cleanup can be
verified. Terminal provider status alone is not proof that tools stopped.

Normal completion removes only a verified clean worktree without force and
retains its generated branch. Explicit destructive task deletion is a separate
user-authorized operation.

## Review and private conversations

Review uses an isolated reviewer task and worktree, with investigation,
validation, and bounded evidence follow-up in the same conversation. Findings,
commit explanations, and local discussions stay private. Sequential validation
is self-review, not an independent reviewer guarantee.

Quoted-answer discussions and GitHub-comment triage use separate read-only
conversations with no repository writes, network access, approval requests, or
product tools. Optional automatic summaries do not authorize fixes or remote
replies. Drafted replies reach GitHub only through the user's posting action.

Guarded review children can search source, read permitted context and repository
memory, and message the root. They cannot save reports, write memory, edit via
exposed edit tools, or delegate further. Root reporting and mutation tools check
root-turn ownership. Prompt-level read-only scope does not imply an independent
filesystem sandbox.

## Instructions and context

Each new turn receives the applicable repository guidelines and current managed
skills. User skills have Global, Development, or Review scope; internal product
policy remains separate. Editing a skill does not change an active turn or grant
new execution permissions.

Repository memory supplies sourced rules, recipes, and specifications with user
control. Optional CodeGraph offers bounded structural exploration using an
isolated task index. Its output helps locate source, is not correctness evidence,
and its failure cannot block tasks or cleanup.

Feature tools remain fixed and task-bound. Ordinary review, discussion,
cherry-pick, CI, and development sessions retain their own authority. GitHub
writes require direct user authority or an accepted automation scope even when
native tools expose `git` or `gh`.

## Provider limits

Codex and Claude share the product model while retaining provider-specific
readiness, sandbox, and protocol behavior. Model catalogs and capabilities are
discovered rather than hard-coded into the UI.

The saved-key DeepSeek lane reuses the Codex adapter through a local key-holding
proxy and the same permission policy. Its narrower capabilities and incomplete
full live-key verification must remain visible; it does not establish generic
API-key provider support. See [remaining work](roadmap.md).
