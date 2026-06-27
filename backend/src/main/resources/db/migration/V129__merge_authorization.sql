-- Standing merge consent + auto-retry state for the merge queue.
--
-- Once the user approves an "Approve & merge" gate, that consent stands:
-- merge_authorized_at_ms records it so the lifecycle re-enqueues the PR
-- automatically if the merge queue bounces it, instead of re-prompting.
-- merge_queue_retries counts the silent auto re-enqueues so the loop can
-- escalate (to CI fixing + a notification) after a bounded number of tries.
ALTER TABLE tasks ADD COLUMN merge_authorized_at_ms INTEGER;
ALTER TABLE tasks ADD COLUMN merge_queue_retries INTEGER NOT NULL DEFAULT 0;
