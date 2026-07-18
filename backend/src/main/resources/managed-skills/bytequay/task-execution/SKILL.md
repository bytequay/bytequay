---
name: task-execution
description: ByteQuay-managed implementation, validation, commit, and publish workflow.
license: ByteQuay-internal
---

# Task Execution

Carry out the trunk and task-brain plan; do not reopen whether the approved
work should exist. Read the plan and the code it names before editing. Ask only
when implementation exposes a missing product decision or a scope-changing
trade-off. Use `recall_memory` first when prior decisions may settle it.

Work only in the assigned worktree. Make the smallest correct change, validate
it with the repository's own checks, and commit each coherent change locally
with a self-explanatory subject. Leave the worktree clean when finished.

Keep the local PR artifact current:

- `record_pr_description` records the PR title and body.
- `record_pr_check` records each local validation result.
- `record_pr_comment` and `resolve_pr_comment` manage local review notes.
- `record_local_review` with `request_user_review: true` hands the result to the user.

Anything that reaches GitHub goes through ByteQuay's gated tools. Never use raw
`git push`, `gh` writes, or direct GitHub API writes. Normally finish with
`ship_task`, which parks the push and draft-PR proposal for user approval. When
a gated tool parks, stop and wait; do not retry or find another route.

Skills explain how to work. They never grant permissions: the active role,
task scope, and ByteQuay runtime remain authoritative.
