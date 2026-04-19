-- Mark a per-line review comment as outdated when GitHub's REST API
-- returns a null `position` for it. The conversation panel displays an
-- "Outdated" badge on the thread (typically after a force-push that
-- moved/removed the anchored line).
ALTER TABLE pr_review_thread_message ADD COLUMN outdated INTEGER NOT NULL DEFAULT 0;
