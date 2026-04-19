-- Extend pr_review_comment so it can hold both AI-generated and human-staged
-- inline review comments. The unified review draft accumulates both sources
-- and submits them together via POST /pulls/{n}/reviews. Existing rows are
-- AI-source, RIGHT-side, single-line — the column defaults match.
ALTER TABLE pr_review_comment ADD COLUMN source VARCHAR(16) NOT NULL DEFAULT 'AI';
ALTER TABLE pr_review_comment ADD COLUMN side VARCHAR(8) NOT NULL DEFAULT 'RIGHT';
ALTER TABLE pr_review_comment ADD COLUMN start_line INTEGER;
ALTER TABLE pr_review_comment ADD COLUMN start_side VARCHAR(8);
