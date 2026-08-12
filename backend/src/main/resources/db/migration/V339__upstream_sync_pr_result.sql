-- The finished-runs list shows how each run ended, and merged reads very
-- differently from closed. A run learns which one when its pull request is seen
-- to have ended, and that is the only moment it can be learned cheaply: the run
-- is closed and torn down immediately afterwards, so nothing later can ask.
--
-- Recorded as its own column rather than derived from the pull request table at
-- read time, because the list is one query over a workspace's runs and the
-- repository the numbers belong to is not on these rows.
ALTER TABLE upstream_cherry_pick_job
    ADD COLUMN pr_result TEXT
    CHECK (pr_result IS NULL OR pr_result IN ('merged', 'closed'));
