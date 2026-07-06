-- Dashboard sync-derived fields, added straight onto `pr` (plain ADD COLUMN
-- suffices here — unlike V152, nothing here relaxes a NOT NULL constraint,
-- so no CREATE+DROP+RENAME rebuild is needed). watch_reason is deliberately
-- distinct from `origin` (task | external): it answers "is this PR on my
-- dashboard, and why" (authored | review_requested | NULL = not watched),
-- orthogonal to where the PR came from.
ALTER TABLE pr ADD COLUMN watch_reason TEXT;
ALTER TABLE pr ADD COLUMN gh_updated_at_ms INTEGER;
ALTER TABLE pr ADD COLUMN labels TEXT NOT NULL DEFAULT '[]';
ALTER TABLE pr ADD COLUMN label_colors TEXT;
ALTER TABLE pr ADD COLUMN draft INTEGER NOT NULL DEFAULT 0;
ALTER TABLE pr ADD COLUMN ci_status TEXT;
ALTER TABLE pr ADD COLUMN additions INTEGER NOT NULL DEFAULT 0;
ALTER TABLE pr ADD COLUMN deletions INTEGER NOT NULL DEFAULT 0;
ALTER TABLE pr ADD COLUMN comment_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE pr ADD COLUMN attention_reason TEXT;
ALTER TABLE pr ADD COLUMN mergeable INTEGER;
ALTER TABLE pr ADD COLUMN mergeable_state TEXT;
ALTER TABLE pr ADD COLUMN head_pushed_at_ms INTEGER;
ALTER TABLE pr ADD COLUMN reviewer_verdicts TEXT;
ALTER TABLE pr ADD COLUMN requested_reviewers TEXT NOT NULL DEFAULT '[]';

-- Dashboard list query filters on this; NULL (unwatched) rows are the
-- overwhelming majority once every task-origin PR is included.
CREATE INDEX idx_pr_watch_reason ON pr(watch_reason) WHERE watch_reason IS NOT NULL;

-- Local triage state — deliberately a sibling table, not columns on `pr`.
-- A bulk syncList() overwrites every column above wholesale; keeping
-- handled/snoozed/viewed state in a separate table makes it structurally
-- impossible for a sync to clobber it, mirroring the existing (deliberate)
-- pull_requests / pr_view_state split this replaces.
CREATE TABLE pr_triage (
    pr_id              TEXT    NOT NULL PRIMARY KEY REFERENCES pr(id) ON DELETE CASCADE,
    viewed_at_ms       INTEGER,
    reviewed_at_ms     INTEGER,
    handled_action     TEXT,
    snoozed_until_ms   INTEGER,
    snoozed_at_ms      INTEGER,
    snooze_wake_reason TEXT
);
