---
id: tasks
title: AI tasks
sidebar_label: AI tasks
sidebar_position: 4
---

# AI tasks

An **AI task** is a unit of work you hand to an agent: it writes the code and
commits it in an isolated git worktree, and you review the result. The agent
does the typing; you keep the reins. Nothing the agent produces reaches GitHub
— or even leaves your machine — until a review has cleared it.

## Plan in the trunk, then cut a task

Work starts in a **trunk**: a conversational thread where you think out loud
with an agent about what needs doing. The agent can ask you clarifying
questions (you answer them inline) and propose a **backlog** of items in one
batch. When something is ready to build, cut it into a task — from a backlog
item's *Start development* action, or directly in the trunk.

Each task runs in its own git worktree, so multiple tasks never step on each
other, and the scheduler runs them within a small resource cap.

## The task lifecycle

Every task moves through the same tracked pipeline. The current phase shows
live in the task view; each step is either a human approval gate or an
automated stage.

```
Plan       → one approved plan: the goal + architecture, the risk signals and a
             confidence level, and the ordered implementation steps
Develop    → the agent writes the change in its worktree
Validate   → tests + lint + repo rules run, with a bounded auto-fix loop
Local PR   → the change becomes a private, PR-shaped review surface (see below)
Push → PR  → you approve the push; the branch lands and a draft GitHub PR opens
CI         → remote CI is watched; red → the agent fixes and re-pushes, green → holds
Mark ready → you flip the draft PR ready for review
Review     → external reviewers comment; the agent addresses them, an AI re-review verifies
Merge      → the PR merges; a reconciler completes the task and reaps the worktree
```

**The gates are yours.** Approve the plan, approve each push, mark the PR
ready, and merge — the agent can do none of these itself. Publishing runs
through approval-gated tools, so a stray `git push` or `gh` call can't sneak
work onto GitHub behind your back.

## Review the Local PR before it's public

When the agent finishes writing, its work becomes a **Local PR** — a
PR-shaped review surface that lives entirely on your machine, before any push.
It has the same face a real PR does:

- a **changed-files + diff** view, identical to the one you use for GitHub PRs;
- **inline review comments** you can leave on any line, then **Resolve** or
  **Dismiss**;
- a **timeline** of what the agent did, and **local test checks**;
- comments grouped into review **rounds**, so a back-and-forth stays legible.

You leave comments; the dev agent addresses them in its worktree and replies
in-thread. A **promotion gate** refuses to push while any comment thread is
still open or the latest local test run is red — nothing goes public with an
unaddressed review or a failing test.

When you approve the push, the *same branch* flips into the live GitHub PR.
The promotion is non-lossy: your Local PR's timeline continues past the
divider, with GitHub's events (CI, reviewer comments, the merge) flowing in
below your private history. Your local review comments never leave your laptop.

## Watch, resume, or stop

- **Watch** — the task view streams the agent's turns and shows the live phase.
- **Resume** — a task that's waiting on you (an approval gate, an open review
  comment) picks up exactly where it left off once you act.
- **Stop** — you can halt a running task; idle or stuck sessions are also
  reaped by the scheduler so nothing runs away.
