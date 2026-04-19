-- For "reviewed" timeline events, capture the GitHub review id so the
-- conversation panel can match the event to its per-line review comments
-- exactly (instead of falling back to actor + timestamp heuristics).
-- Null on every other event type.
ALTER TABLE pr_timeline ADD COLUMN review_id INTEGER;
