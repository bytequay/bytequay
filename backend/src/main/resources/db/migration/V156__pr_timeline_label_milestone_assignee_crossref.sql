-- Carries labeled/unlabeled, milestoned/demilestoned, assigned/unassigned,
-- and cross-referenced timeline events through the cache round-trip —
-- these event types are now kept (see PullRequestDetailMapper's widened
-- INTERESTING_EVENTS) instead of being dropped, so the columns backing
-- their per-event data need to persist across a cached detail re-read the
-- same way before_sha/requested_reviewer/etc already do.
ALTER TABLE pr_timeline ADD COLUMN label_name TEXT;
ALTER TABLE pr_timeline ADD COLUMN label_color TEXT;
ALTER TABLE pr_timeline ADD COLUMN milestone_title TEXT;
ALTER TABLE pr_timeline ADD COLUMN assignee_login TEXT;
ALTER TABLE pr_timeline ADD COLUMN cross_ref_number INTEGER;
ALTER TABLE pr_timeline ADD COLUMN cross_ref_title TEXT;
ALTER TABLE pr_timeline ADD COLUMN cross_ref_url TEXT;
ALTER TABLE pr_timeline ADD COLUMN cross_ref_is_pull_request INTEGER NOT NULL DEFAULT 0;
