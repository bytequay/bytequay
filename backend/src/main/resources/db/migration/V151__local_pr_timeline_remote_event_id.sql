-- Lets a remote-sourced timeline event (a synced GitHub PR comment or review)
-- be de-duplicated against its GitHub id across repeated syncs — otherwise
-- every PR-bundle fetch would re-insert the same comment/review as a new row.
ALTER TABLE local_pr_timeline_event ADD COLUMN remote_event_id INTEGER;
CREATE INDEX idx_local_pr_timeline_remote_id ON local_pr_timeline_event(local_pr_id, remote_event_id);
