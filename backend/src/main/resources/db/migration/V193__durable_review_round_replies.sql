-- Checkpoint each remote-round draft reply after GitHub accepts it so a
-- partially failed gate can resume without posting successful replies twice.
ALTER TABLE review_comment ADD COLUMN draft_reply_posted_at_ms INTEGER;

-- Resolving an inline thread is a separate remote side effect from posting
-- its optional reply. Checkpoint it independently so a partially failed gate
-- retries only the work GitHub has not accepted yet.
ALTER TABLE review_comment ADD COLUMN remote_thread_resolved_at_ms INTEGER;
