-- The review Lead's first-class agenda artifact: a JSON array of
-- { "id": string, "title": string, "status": "open" | "in_progress"
-- | "done" } phases the Lead sets once at kickoff and ticks through
-- as the pass runs. TEXT because SQLite has no native JSON type; the
-- app (de)serialises with Jackson, same as the review_messages
-- mentions / refs columns. NULL for passes that predate the Lead
-- orchestrator (and for passes whose Lead never set an agenda).
ALTER TABLE review_passes ADD COLUMN agenda_json TEXT;

-- Per-seat budget slice. The cap defaults to an even split of the
-- pass's cost cap across the reviewer seats, stamped at kickoff; the
-- Lead can read but never mutate it. Spend accumulates as the seat's
-- turns are metered. NULL cap on legacy rows / non-reviewer seats
-- means "no per-seat bound" (the pass-level cap still applies).
ALTER TABLE review_participants ADD COLUMN budget_milli_usd_cap INTEGER;
ALTER TABLE review_participants ADD COLUMN budget_milli_usd_spent INTEGER NOT NULL DEFAULT 0;
