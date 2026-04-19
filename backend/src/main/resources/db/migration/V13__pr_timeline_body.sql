-- Capture the comment text on `commented` and `reviewed` timeline events so
-- PrAttention can scan for @-mentions of the current user (the MENTIONED
-- attention reason). Existing rows backfill to NULL — the next detail sync
-- replaces the per-PR timeline rows wholesale, so the column will populate
-- naturally without a one-shot fetch.
ALTER TABLE pr_timeline ADD COLUMN body TEXT;
