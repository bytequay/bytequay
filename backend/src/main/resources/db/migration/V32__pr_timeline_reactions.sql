-- Reactions tally for issue/PR comments (the `commented` timeline
-- events). REST returns the same 8 emoji counts that we already
-- persist for review-thread messages — same shape, separate columns.
-- Existing rows default to 0 so the UI starts every comment with a
-- bare smiley + button until the next sync writes the real counts.
ALTER TABLE pr_timeline ADD COLUMN reactions_plus_one  INTEGER NOT NULL DEFAULT 0;
ALTER TABLE pr_timeline ADD COLUMN reactions_minus_one INTEGER NOT NULL DEFAULT 0;
ALTER TABLE pr_timeline ADD COLUMN reactions_laugh     INTEGER NOT NULL DEFAULT 0;
ALTER TABLE pr_timeline ADD COLUMN reactions_hooray    INTEGER NOT NULL DEFAULT 0;
ALTER TABLE pr_timeline ADD COLUMN reactions_confused  INTEGER NOT NULL DEFAULT 0;
ALTER TABLE pr_timeline ADD COLUMN reactions_heart     INTEGER NOT NULL DEFAULT 0;
ALTER TABLE pr_timeline ADD COLUMN reactions_rocket    INTEGER NOT NULL DEFAULT 0;
ALTER TABLE pr_timeline ADD COLUMN reactions_eyes      INTEGER NOT NULL DEFAULT 0;
