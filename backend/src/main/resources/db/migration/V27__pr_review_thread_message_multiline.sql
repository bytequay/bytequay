-- Multi-line review comment support: GitHub's per-line comment payload
-- carries start_line + start_side when the comment spans a range. We
-- persist them so existing threads retain the range across detail
-- syncs, and so the frontend can render "Comment on lines L455 to R467"
-- in the thread header.
--
-- Both columns are nullable — single-line comments have neither set.

ALTER TABLE pr_review_thread_message ADD COLUMN start_line INTEGER;
ALTER TABLE pr_review_thread_message ADD COLUMN start_side VARCHAR;
