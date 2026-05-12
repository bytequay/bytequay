-- Per-review submission timestamp for the analytics page's daily-bars,
-- heatmap, and review-network surfaces. Existing rows stay null —
-- there's no honest way to backfill the timestamp from the cached
-- detail blob (it never carried it), so analytics queries treat null
-- as "unknown" and skip those rows. The mirror fills naturally as new
-- detail syncs land for each PR.

ALTER TABLE pr_reviews
    ADD COLUMN submitted_at TIMESTAMP;

CREATE INDEX idx_pr_reviews_login_submitted_at
    ON pr_reviews(login, submitted_at);
