# Tasks detail page — deferred from the 2026-05-17 layout refactor

The detail-page refactor (sidebar-left + bottom tabs + side-by-side
diff per `docs/mockups/design/tasks/task-detail-tabs*.png`) shipped
with two pieces stubbed because they need real backend work first.

## 1. Context window — make the number exact

**State today**

`TaskWindowSidebar` displays a CONTEXT WINDOW bar computed from the
latest `turn_done` message's `tokensIn` divided by a hard-coded
model→limit map (`MODEL_CONTEXT_LIMITS` in `TaskDetailPage.tsx`).

`StreamJsonParser.parseResult()` currently extracts only:
- `usage.input_tokens`
- `usage.output_tokens`
- `total_cost_usd`
- `duration_ms`

and persists them onto the `task_messages` row for each `turn_done`.

**Problem**

Claude Code's TUI shows context-window usage that matches the
Anthropic API's billing definition, which also counts:
- `usage.cache_read_input_tokens`
- `usage.cache_creation_input_tokens`

We drop both. The bar therefore reads low — sometimes substantially,
since cached prompt prefixes are often the bulk of a long session.

**Plan**

1. `StreamJsonParser.parseResult` — pull the two cache fields out of
   the `usage` object. Default to `0L` when absent.
2. `StreamEvent.TurnDone` — add `cacheReadInputTokens` /
   `cacheCreationInputTokens` Long fields.
3. `TaskMessage` — same two fields (nullable Long).
4. Flyway migration — add `cache_read_input_tokens` and
   `cache_creation_input_tokens` columns to `task_messages`.
5. Frontend — flip `computeContextUsage` in `TaskDetailPage.tsx`
   from `(latest.tokensIn / limit)` to
   `((latest.tokensIn + cacheRead + cacheCreation + tokensOut) / limit)`
   and drop the "approximate (cache excluded)" hint.

Effort: ~30 min backend, ~15 min frontend. No UI churn needed
because the sidebar already reads through `computeContextUsage`.

## 2. Checkpoints — auto-summarisation feature

**State today**

`TaskWindowSidebar`'s CHECKPOINTS section renders a placeholder
("Auto-summary checkpoints land in a follow-up…") with no data.

**Goal**

When a task's conversation history grows past a threshold, the model
generates a one-paragraph summary of what happened since the prior
checkpoint. The summary is persisted alongside the message it
covers, the sidebar lists checkpoints chronologically, and clicking
a checkpoint scrolls the conversation pane to that anchor. This
gives users a quick way to navigate a multi-hour task without
scrolling through thousands of messages.

**Plan**

Backend:
1. New domain `Checkpoint(id, taskId, anchorSeq, summary, createdAt)`.
2. New Flyway migration + JPA entity + `TaskCheckpointStore`
   interface + sqlite impl. Mirror the shape used by
   `task_messages` so reads stay one query per task.
3. New `TaskCheckpointService` with one trigger: after each
   `turn_done` is persisted, if
   `(messages since last checkpoint) >= CHECKPOINT_THRESHOLD` or
   `(tokens since last checkpoint) >= CHECKPOINT_TOKEN_THRESHOLD`,
   enqueue a summary job.
4. Summary job: spawn a one-shot model call (same provider as the
   task; small/cheap model variant where available) with the
   slice of `task_messages` since the last anchor as input and a
   "summarise what happened in 1-2 sentences" prompt. Persist the
   result as a `Checkpoint` and emit a `StreamEvent.CheckpointCreated`
   so the live SSE/poll stream surfaces it without a refresh.
5. New `GET /api/tasks/{id}/checkpoints` endpoint + frontend bridge.

Frontend:
6. Replace `CheckpointsStub` with a list that fetches via the new
   bridge call and renders one row per checkpoint: relative
   timestamp + truncated summary.
7. Clicking a row scrolls `StructuredConversation` to the anchored
   message — needs the renderer to expose an imperative
   `scrollToMessage(seq)` handle, similar to the existing
   `stickToBottom` plumbing.
8. Add a small "✓ summarised" badge to the conversation card that
   sits at the anchor, so scrolling there feels intentional.

**Open questions** to discuss before starting:
- Thresholds — turn-count vs token-count vs both?
- Which model variant for the summary call? Sonnet-haiku tier is
  enough; the user pays for the task's own model — do we charge
  the same one or default to a cheaper variant?
- Manual "Save checkpoint" button? The old sidebar had a disabled
  one — we may want to wire it once auto-summary lands so users
  can mark a moment without waiting for the threshold.

Effort: ~1-2 days backend (domain + storage + summary job +
event), ~half-day frontend (bridge + list + scroll-to-anchor).

## 3. Smaller bits the refactor left as TODOs

- **PR / issue linking** — the new task-window header has room for
  a "+ Link PR" chip. Needs a small picker UI (search PRs by repo)
  plus a backend column on `tasks` to store the linked pr_id, then
  the header chip resolves to that row. Punt until the user asks.
- **Group picker** — the old right sidebar had a per-task group
  picker; the new sidebar dropped it. If users want to re-group
  from inside a task without going back to the list, add a small
  chip in the task-window header.
- **Pause** — `TaskWindowHeader` accepts an `onPause` prop but
  `TaskDetailPage` always passes `undefined`. Wire once MCP grows
  a pause/resume verb.
