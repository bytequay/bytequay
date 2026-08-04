-- A cherry-pick job now carries the pull-request description the user typed and
-- the subject filters that decide which commits in the range are skipped. Both
-- must be durable: the range is expanded up front, but commits are applied one
-- at a time by a worker that resumes after a restart, and it re-evaluates the
-- filters for every remaining commit.
ALTER TABLE upstream_cherry_pick_job ADD COLUMN pr_description TEXT;
ALTER TABLE upstream_cherry_pick_job
    ADD COLUMN skip_filters_json TEXT NOT NULL DEFAULT '{"startsWith":[],"contains":[]}';
