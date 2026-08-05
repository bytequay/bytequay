-- The run view renders what the picker actually did: every command it ran, the
-- exit status, and enough output to judge it. None of that was kept anywhere,
-- so a range that ran for hours left no history a human could review after the
-- fact — only a status word and two counters.
CREATE TABLE upstream_cherry_pick_event (
    id            TEXT PRIMARY KEY,
    job_id        TEXT NOT NULL
                  REFERENCES upstream_cherry_pick_job(id) ON DELETE CASCADE,
    ordinal       INTEGER NOT NULL,
    -- Index into the job's commit specs, so the view can file an event under
    -- the pick that produced it. NULL for run-level events (push, park, …).
    pick_index    INTEGER,
    kind          TEXT NOT NULL,
    title         TEXT NOT NULL,
    detail        TEXT,
    exit_code     INTEGER,
    duration_ms   INTEGER,
    created_at_ms INTEGER NOT NULL
);
CREATE INDEX idx_upstream_cherry_pick_event_job
    ON upstream_cherry_pick_event(job_id, ordinal);

-- Commits whose pick conflicted and carried git's own resolution forward. The
-- applied list alone cannot tell those apart from the clean ones, and the queue
-- has to: a carried conflict is the thing a reviewer looks for.
ALTER TABLE upstream_cherry_pick_job
    ADD COLUMN conflicted_shas_json TEXT NOT NULL DEFAULT '[]';

-- "Pause after this pick" / "Park now". The worker reads the flag between
-- commits: a pick is one git command, so there is no safe point inside one.
ALTER TABLE upstream_cherry_pick_job
    ADD COLUMN pause_requested INTEGER NOT NULL DEFAULT 0
    CHECK (pause_requested IN (0, 1));
