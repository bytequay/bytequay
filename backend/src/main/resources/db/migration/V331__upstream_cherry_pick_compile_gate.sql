-- A conflicted pick is now committed with its markers and repaired by an agent
-- behind a compile gate, so the job carries how to run that gate.
--
-- repair_pending distinguishes the two reasons a job pauses. A plain conflict
-- pauses *before* the commit exists, and resuming runs `cherry-pick --continue`.
-- A repair pause happens *after* the commit landed and the gate stayed red, so
-- resuming must carry on from the next commit instead of trying to continue a
-- cherry-pick that is no longer in progress. Reusing one status keeps the
-- CHECK constraint intact, which SQLite cannot alter in place.
ALTER TABLE upstream_cherry_pick_job ADD COLUMN compile_script TEXT;
ALTER TABLE upstream_cherry_pick_job ADD COLUMN ci_job_name TEXT;
ALTER TABLE upstream_cherry_pick_job
    ADD COLUMN repair_pending INTEGER NOT NULL DEFAULT 0
    CHECK (repair_pending IN (0, 1));
