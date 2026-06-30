-- Expand the per-thread backlog stub into the full lifecycle model: a status
-- machine (created -> in-progress -> resolved; created <-> not-to-proceed),
-- priority + source + creator provenance, a workspace pointer for the
-- workspace-wide view, and the trunk-split sibling linkage. SQLite only adds
-- one column per statement, and a NOT NULL add needs a constant default.
ALTER TABLE backlog_item ADD COLUMN workspace_id TEXT;
ALTER TABLE backlog_item ADD COLUMN priority TEXT NOT NULL DEFAULT 'medium';
ALTER TABLE backlog_item ADD COLUMN source TEXT NOT NULL DEFAULT 'manual';
ALTER TABLE backlog_item ADD COLUMN status TEXT NOT NULL DEFAULT 'created';
ALTER TABLE backlog_item ADD COLUMN created_by TEXT NOT NULL DEFAULT 'user';
ALTER TABLE backlog_item ADD COLUMN in_progress_at_ms INTEGER;
ALTER TABLE backlog_item ADD COLUMN resolved_at_ms INTEGER;
ALTER TABLE backlog_item ADD COLUMN rejected_at_ms INTEGER;
ALTER TABLE backlog_item ADD COLUMN rejection_reason TEXT;
ALTER TABLE backlog_item ADD COLUMN related_backlog_ids_json TEXT NOT NULL DEFAULT '[]';

-- Backfill the workspace pointer from the owning thread.
UPDATE backlog_item
SET workspace_id = (SELECT t.workspace_id FROM threads t WHERE t.id = backlog_item.thread_id)
WHERE workspace_id IS NULL;

-- Backfill the status machine from the legacy started_at_ms: a started item
-- that materialised a task is resolved; a started item without one is in
-- progress; the rest stay 'created'. Mirror started_at into in_progress_at.
UPDATE backlog_item
SET status = 'resolved', in_progress_at_ms = started_at_ms, resolved_at_ms = started_at_ms
WHERE started_at_ms IS NOT NULL AND linked_task_id IS NOT NULL;

UPDATE backlog_item
SET status = 'in-progress', in_progress_at_ms = started_at_ms
WHERE started_at_ms IS NOT NULL AND linked_task_id IS NULL;

CREATE INDEX idx_backlog_item_workspace ON backlog_item(workspace_id, created_at_ms);
