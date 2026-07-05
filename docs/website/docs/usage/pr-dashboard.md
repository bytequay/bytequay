---
id: pr-dashboard
title: PR dashboard
sidebar_label: PR dashboard
sidebar_position: 2
---

# PR dashboard

The PR dashboard is your morning view — the review inbox ByteQuay is built
around. It shows two sections, **Awaiting my review** and **My PRs**, grouped
by repo, with a live preview pane on the right so you can triage without
opening each PR.

## The list, grouped by repo

Each row carries what you need to decide at a glance: title and number, author,
CI status, and why it needs you. Selecting a row previews the PR — description,
checks, and metadata — in the pane beside the list. From there, open the diff
to review, or act on it directly.

For heavier triage, optional **Kanban** and **Teams** views give you filtered,
lane-based cuts of the same PRs (for example, *My PRs* vs *To review*).

## The home inbox

Alongside the dashboard, the **home inbox** merges everything that needs you
into one feed:

- **PRs awaiting review** and PRs where you were mentioned or CI is failing;
- **approval gates** parked by dev-agent tasks — approve & merge, approve &
  push, mark ready — each showing the PR title and a jump-to-PR button;
- **agent handoffs** and completion notices.

Toggle **Unread only** to focus on what's new, and open any row to act on it
inline. A **Today** summary rounds up what you worked on, reviewed, and merged
today — copyable as standup-ready Markdown.

## Merge, draft toggle, and CI

The PR detail page keeps the controls next to the change: a **merge** button
(gated by CI status and your push permission, with a live *Rebasing… /
Squashing… / Merging…* state while it runs), a **draft ↔ ready-for-review**
toggle, merge-queue enqueue / dequeue, and inline CI diagnostics with a
one-click **Ask AI to fix** on a failing log.
