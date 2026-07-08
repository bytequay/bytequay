-- Local PR comments only ever anchored to a bare line number, with no way to
-- tell a removed (old-side) line from a same-numbered new-side line, and no
-- way to span a range. Mirrors the side/start_line/start_side columns V28
-- already added to pr_review_comment for the same reason. Existing rows are
-- new-side, single-line — the column defaults match.
ALTER TABLE pr_comment ADD COLUMN side VARCHAR(8) NOT NULL DEFAULT 'RIGHT';
ALTER TABLE pr_comment ADD COLUMN start_line INTEGER;
ALTER TABLE pr_comment ADD COLUMN start_side VARCHAR(8);
