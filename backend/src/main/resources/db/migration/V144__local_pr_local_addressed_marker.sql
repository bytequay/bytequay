-- High-water mark for the local addressing loop: local_pr_comment rows
-- created after this timestamp are "unaddressed" (mirrors task_review_marker
-- for the remote loop, scoped to the local_pr row since it's already 1:1
-- with the task).
ALTER TABLE local_pr ADD COLUMN local_addressed_through_ms INTEGER;
