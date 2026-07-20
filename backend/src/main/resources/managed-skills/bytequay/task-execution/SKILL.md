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
it with the repository's own checks, and leave the worktree clean when finished.

For every implementation:

- Add or update focused tests for behaviour changes and run the relevant checks.
  Every commit must build and pass its tests.
- Follow the Trino pull-request and commit guidelines. Keep one logical change
  per commit and separate mechanical changes from functional changes.
- Use a capitalized, imperative commit subject of at most 50 characters, with
  no trailing period or Conventional Commits prefix.
- Default to a subject-only commit. Add a body only when the subject cannot
  explain the change; use it for what and why, not how, and wrap it at 72
  characters.
- Never add AI or bot attribution to a commit. Do not add `Co-Authored-By` or
  similar trailers for Claude, Codex, or any other assistant.

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
