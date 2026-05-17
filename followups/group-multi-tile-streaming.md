# Group page — multi-tile live streaming (revisit if poll feels stale)

## State today

`GroupTaskGrid` polls each visible tile every 4 s via
`window.bridge.getTaskMessages(taskId)` (see `POLL_MS` in
`frontend/src/tasks/GroupTaskGrid.tsx`) and re-renders the preview
when the list changes. The same 4 s cadence will apply to the new
zoom modal, which reuses `TaskDetailPage`'s `StructuredView` and
its existing message-poll loop.

## Why this might bite later

- 4 tiles + a zoom modal = up to 5 concurrent timers ticking. With a
  RUNNING tile that streams a tool call every ~200 ms, a 4 s window
  feels noticeably laggy compared to the per-tile terminal in
  Claude Code's TUI.
- The streaming line and blinking cursor are mockup-only today —
  there's no actual word-by-word streaming surface; we just diff
  the message list and render the last chunk. So the perceived lag
  is bounded by the poll interval, not the model.

## Options to revisit, in order of how invasive they are

1. **Shorten the poll** to 1.5–2 s on the active group page only
   (keep 4 s on the list view). Trivially safe; just confirm the
   `getTaskMessages` query is cheap enough at that rate.
2. **Server-sent events per task** — re-use the existing SSE
   endpoint that the detail page subscribes to, but multiplex N
   tile subscriptions on one connection. Avoids N-timer fan-out;
   needs back-pressure handling when tiles aren't visible.
3. **WebSocket fan-in** — one socket per renderer, server pushes
   `{ taskId, eventBatch }` envelopes. Most work, best fidelity;
   probably overkill until users actually open 4 simultaneous
   live tasks regularly.

## Decision criteria

Ship with option 0 (status quo: 4 s polling). Bump to option 1 if
the user reports the tiles feel stale. Don't pre-build SSE/WS until
there's evidence the polling model is the bottleneck — the streaming
animations are most of the perceived liveness anyway.

## Owner

Open question — flagged by the user during the 2026-05-18 group-page
redesign. Re-ask after dogfooding the new group page for a few days.
