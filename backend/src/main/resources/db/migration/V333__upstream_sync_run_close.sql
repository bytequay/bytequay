-- Closing a sync run ends it on the user's say-so: the picker stops at the next
-- commit boundary, the CI harness watch it created is stopped, and its isolated
-- worktree is removed. Nothing that was committed is touched — the result branch
-- and the run's log both survive a close.
--
-- Closed is its own timestamp rather than a status: the status column records
-- how far the run actually got, and overwriting that with CLOSED would lose it.
-- (SQLite cannot alter the status CHECK in place either, which is why V331 made
-- the same call.)
ALTER TABLE upstream_cherry_pick_job ADD COLUMN closed_at_ms INTEGER;

-- A closed run no longer holds the one-live-run slot, so the next sync can start
-- immediately instead of waiting for a run the user has already walked away from.
DROP INDEX idx_upstream_cherry_pick_job_one_live;
CREATE UNIQUE INDEX idx_upstream_cherry_pick_job_one_live
    ON upstream_cherry_pick_job(workspace_id)
    WHERE status IN ('QUEUED', 'RUNNING', 'PAUSED_CONFLICT')
      AND closed_at_ms IS NULL;
