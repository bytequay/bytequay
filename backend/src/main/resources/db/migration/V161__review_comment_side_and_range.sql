-- Same gap as V160, for task/AI review comments: no side, no range. Mirrors
-- the side/start_line/start_side columns V28 added to pr_review_comment.
-- Existing rows are new-side, single-line — the column defaults match.
ALTER TABLE review_comment ADD COLUMN side VARCHAR(8) NOT NULL DEFAULT 'RIGHT';
ALTER TABLE review_comment ADD COLUMN start_line INTEGER;
ALTER TABLE review_comment ADD COLUMN start_side VARCHAR(8);
