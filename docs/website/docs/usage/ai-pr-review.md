---
id: ai-pr-review
title: AI PR review
sidebar_label: AI PR review
sidebar_position: 3
---

# AI PR review

Review is the center of ByteQuay, and AI is your reviewing partner — not a
replacement for your judgment. The AI drafts; **you decide what actually gets
said.** Nothing is posted to GitHub until you explicitly publish it.

## Start a review

Open a pull request from the dashboard and enter its diff view. The diff
viewer shows a native file tree and unified diff (with click-to-expand for
collapsed context) on the left, and a resizable **AI review sidebar** on the
right.

Kick off a review from the sidebar. The AI reads the diff and drafts:

- a **top-level summary** of what the change does and what to watch for, and
- **per-line comments** anchored to specific lines, surfacing risks, bugs, and
  questions.

Everything it produces is stored **locally** — nothing is on GitHub yet.

## Work through findings

Read the drafted comments in context, right against the diff. For each one you
can:

- **edit** the wording before it ever goes out,
- **keep** it to publish, or
- **dismiss** it if it's off-base.

You can also write your own inline comments, reply on existing review threads,
react, and resolve / unresolve threads — the full review surface, native.

## Post comments back to GitHub

When you're happy with a comment (or a batch), **publish** it. Only then does
it reach GitHub, posted as you. The AI never publishes on its own; the draft
stays local until your click. That's the rule everywhere in ByteQuay: the
model can propose, but review — yours — is what crosses the line to public.

## CI diagnostics and "Ask AI to fix"

On the PR detail page, failing-check cards expand to show GitHub's actual
error message, and **Show full log** lazy-loads the Actions log inline with
`[ERROR]` / `Caused by` markers highlighted. **✨ Ask AI to fix** sends the
failing log to your configured model and renders a root-cause explanation plus
a suggested patch inline — so a red build is a starting point, not a dead end.
