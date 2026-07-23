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

During initial Development, when the PR-recording tools are available, keep the
local PR artifact current:

- Before finalizing the PR, call `record_pr_progress` with `phase: starting`.
  Inspect `git status --short`, the complete base-to-head commit history, and
  the current change scope. Commit any remaining coherent changes, then re-read
  the clean status and final committed base-to-head diff so the description
  summarizes the whole branch rather than the last tool call.
- Find and read the repository pull-request template from GitHub's standard
  locations, checking both letter cases: `.github/PULL_REQUEST_TEMPLATE.md`,
  `.github/pull_request_template.md`, `.github/PULL_REQUEST_TEMPLATE/**`,
  `.github/pull_request_template/**`, and the root or `docs/` equivalents.
  If one exists, preserve its headings,
  checklists, and structure, fill its sections, and add no new sections. If
  none exists, keep the body proportional: a small change gets one clear line;
  only a substantial change warrants a short summary paragraph.
- Call `record_pr_progress` with `phase: creating-draft`, then call
  `record_pr_description` with the finished title and body.
- `record_pr_description` records the PR title and body.
- `record_pr_check` records each local validation result.
- `record_pr_comment` and `resolve_pr_comment` manage local review notes.
  When you address a review comment, call `resolve_pr_comment` with a `reply`
  summarising the fix — it posts your reply under the comment, then resolves
  it. Use `resolution: dismissed` (no reply) only to close one you are not
  acting on.
- Finish with `record_dev_report`, then call `record_local_review` with
  `request_user_review: true`. This starts the Brain adversarial review; it
  hands the private PR to the user only after that bounded review loop ends.
- Do not call `ship_task`, `push`, or `request_review` during initial
  Development. The user promotes the private Local PR through its single
  Local Review gate after all local threads are closed and checks pass.

For current GitHub state, use ByteQuay's live read tools first. In remote-
development and CI-fixing turns, call `read_remote_pr_status` for freshly
probed checks and `read_ci_log` for the current failing job. Never treat an
earlier cached PR snapshot as current CI state. If ByteQuay's read tools do not
cover a read or cannot expose a log, read-only `gh` commands are allowed.

Anything that changes GitHub goes through ByteQuay's controlled publish path.
Never use raw `git push`, `gh` writes, or direct GitHub API writes. A CI-fix
turn commits its verified fix locally; ByteQuay auto-pushes it when the turn
completes because the user's opt-in is standing authorization. Branch-guard
turns may use their pre-authorized `push` tool. A review-round turn must commit
its fixes locally, call `record_round_reply` and
`resolve_review_comment`, and never call generic publish or push tools; the
server publishes the push and replies together only after the user approves
the round. When a gated tool parks, stop and wait; do not retry or find another
route.

Skills explain how to work. They never grant permissions: the active role,
task scope, and ByteQuay runtime remain authoritative.
