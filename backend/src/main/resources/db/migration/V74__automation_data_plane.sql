-- Phase 7 data plane: the structural pieces automation needs before
-- the runtime lands. Adds:
--
--   1. worktree_leases — at most one live agent may hold a worktree
--      at a time. The lease, not the thread, is the lock. A held
--      worktree blocks headless auto-work from barging in; a free
--      worktree lets the auto-fixer acquire, run, and park.
--
--   2. notifications — durable surface for "this task parked at
--      AWAITING_REVIEW / NEEDS_ATTENTION" so the user can jump in
--      from the thread list or the menu-bar bell without losing
--      context. Per-(thread, task), with a payload blob describing
--      what the user is being asked to look at.
--
--   3. threads.flow — build vs review discriminator (the only hard
--      thread "type"). build threads own a branch via their tasks
--      and write code; review threads reference a PR and host a
--      multi-agent panel (Phase 8). Existing rows default to build,
--      which matches their current behaviour.
--
-- See docs/mockups/workspace-thread-task-design.md "Automation and
-- system-initiated tasks" for the model.

CREATE TABLE worktree_leases (
    -- The worktree directory is the resource being leased; PK is the
    -- path so duplicate acquires are a constraint violation rather
    -- than an application-side race.
    worktree_path   TEXT NOT NULL PRIMARY KEY,
    task_id         TEXT NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    -- 'CLI_AGENT' | 'LOGIC_LOOP' — matches the existing thread kinds.
    agent_kind      TEXT NOT NULL,
    -- OS pid for CLI agents; null for LOGIC_LOOP (the loop runs in
    -- the JVM and has no separate process to identify).
    holder_pid      INTEGER,
    acquired_at_ms  INTEGER NOT NULL,
    -- Soft expiry so a crashed holder can be reaped after a timeout.
    -- The reaper sweeps rows whose expires_at_ms < now and whose
    -- holder_pid no longer corresponds to a live process.
    expires_at_ms   INTEGER
);

CREATE INDEX idx_worktree_leases_task ON worktree_leases(task_id);

-- Per-row durable record of a user-visible event the runtime parked.
-- The frontend renders the toast / bell from these rows so a missed
-- toast can still be picked up later from the notification center.
CREATE TABLE notifications (
    id              TEXT NOT NULL PRIMARY KEY,
    -- 'AWAITING_REVIEW' | 'NEEDS_ATTENTION' | 'AUTO_FIX_DONE' | ...
    -- Stored as TEXT so adding kinds later doesn't need a migration.
    kind            TEXT NOT NULL,
    -- Pointer back to the conversation that produced the event.
    thread_id       TEXT REFERENCES threads(id) ON DELETE CASCADE,
    -- Pointer at the work-unit (the one parked). Nullable so a
    -- thread-level notification (e.g. "ship-and-continue succeeded")
    -- can stand without a specific task.
    task_id         TEXT REFERENCES tasks(id) ON DELETE CASCADE,
    -- 'UNREAD' | 'READ' | 'DISMISSED' — the runtime sets UNREAD, the
    -- UI patches to READ on click and DISMISSED on swipe-away.
    status          TEXT NOT NULL DEFAULT 'UNREAD',
    -- Free-form JSON1 blob (title, body, PR number, etc.) so a kind
    -- can carry its own payload without growing the schema.
    payload_json    TEXT NOT NULL DEFAULT '{}',
    created_at_ms   INTEGER NOT NULL,
    read_at_ms      INTEGER
);

-- "Unread bell" query — newest UNREAD rows first.
CREATE INDEX idx_notifications_status_created
    ON notifications(status, created_at_ms DESC);

-- Per-thread notification feed (the auto* filter on the threads list).
CREATE INDEX idx_notifications_thread
    ON notifications(thread_id, created_at_ms DESC);

-- Per-task notification feed (a task's parked-state history).
CREATE INDEX idx_notifications_task
    ON notifications(task_id, created_at_ms DESC);

-- Structural discriminator: build vs review. Defaults to build for
-- every existing thread; review threads land in Phase 8 alongside the
-- multi-agent review panel.
ALTER TABLE threads ADD COLUMN flow TEXT NOT NULL DEFAULT 'build';
