-- External-PR drafts publish via one explicit "Submit review" action rather
-- than migrating comment-by-comment like a task PR's push does. This marks
-- the moment a draft was batched into that GitHub review.
ALTER TABLE pr_comment ADD COLUMN published_at_ms INTEGER;
