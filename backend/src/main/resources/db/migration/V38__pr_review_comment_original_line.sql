-- Original-side coordinates for review comments. GitHub's diff_hunk
-- reflects the file at the time the comment was made, but `line` and
-- `start_line` shift forward as the file is edited. The frontend slices
-- the hunk to the commented range using these original coordinates so
-- multi-line review threads render the right lines, not the entire file.
ALTER TABLE pr_review_thread_message ADD COLUMN original_line       INTEGER;
ALTER TABLE pr_review_thread_message ADD COLUMN original_start_line INTEGER;
