-- Which surface a skill belongs to: 'review' rows are selectable as
-- reviewer roles in the assign-review dialog (and only there);
-- 'build' rows are what the build/task agents see via list_skills /
-- load_skill (and only they do). Existing rows default to 'build' —
-- except rubrics, which were designed as review-time rules and
-- backfill to 'review'.
ALTER TABLE skill ADD COLUMN usage TEXT NOT NULL DEFAULT 'build';
UPDATE skill SET usage = 'review' WHERE kind = 'rubric';
