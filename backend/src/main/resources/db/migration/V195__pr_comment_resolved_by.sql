-- The actor who resolved a review thread, for the "X marked this conversation
-- as resolved" attribution on local comments (remote threads carry it from
-- GitHub). Null while the thread is open or dismissed.
ALTER TABLE pr_comment ADD COLUMN resolved_by TEXT;
