-- Merge-queue awareness (GraphQL-sourced, mirrors mergeable/mergeable_state
-- from V155) plus a local-only marker for whether the app has deleted the
-- head branch after a merge.
ALTER TABLE pr ADD COLUMN merge_queue_enabled INTEGER NOT NULL DEFAULT 0;
ALTER TABLE pr ADD COLUMN merge_queue_state TEXT;
ALTER TABLE pr ADD COLUMN branch_deleted_at_ms INTEGER;
