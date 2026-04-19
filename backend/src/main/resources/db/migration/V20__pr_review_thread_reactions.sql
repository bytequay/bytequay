-- Persist GitHub reaction tallies on per-line review comments. Stored as
-- separate INTEGER columns so we can query/aggregate without parsing JSON.
-- All columns default to 0; a row with no reactions reads as Reactions.EMPTY.
ALTER TABLE pr_review_thread_message ADD COLUMN reactions_plus_one INTEGER NOT NULL DEFAULT 0;
ALTER TABLE pr_review_thread_message ADD COLUMN reactions_minus_one INTEGER NOT NULL DEFAULT 0;
ALTER TABLE pr_review_thread_message ADD COLUMN reactions_laugh INTEGER NOT NULL DEFAULT 0;
ALTER TABLE pr_review_thread_message ADD COLUMN reactions_hooray INTEGER NOT NULL DEFAULT 0;
ALTER TABLE pr_review_thread_message ADD COLUMN reactions_confused INTEGER NOT NULL DEFAULT 0;
ALTER TABLE pr_review_thread_message ADD COLUMN reactions_heart INTEGER NOT NULL DEFAULT 0;
ALTER TABLE pr_review_thread_message ADD COLUMN reactions_rocket INTEGER NOT NULL DEFAULT 0;
ALTER TABLE pr_review_thread_message ADD COLUMN reactions_eyes INTEGER NOT NULL DEFAULT 0;
