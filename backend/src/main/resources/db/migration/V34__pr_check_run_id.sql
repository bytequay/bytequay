-- GitHub check-run id (per-attempt unique). Needed so the merge bar can
-- request /actions/jobs/{id}/logs for an inline log viewer without
-- forcing the user to click out to github.com.
ALTER TABLE pr_check_runs ADD COLUMN github_id INTEGER;
