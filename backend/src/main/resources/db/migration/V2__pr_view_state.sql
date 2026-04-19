-- Per-PR local state: tracks whether the user has viewed or reviewed each PR.
-- `viewed_at`   = timestamp of first click/selection in the app.
-- `snoozed_until` = if set, suppress urgent ordering until this time.
-- `reviewed_at` = timestamp when the user submitted a review (approve/merge) via the app.
CREATE TABLE pr_view_state (
    pr_id               INTEGER NOT NULL PRIMARY KEY,
    viewed_at           TEXT,
    snoozed_until       TEXT,
    reviewed_at         TEXT,
    internal_created_at TEXT    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    internal_updated_at TEXT    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Default sort order for the PR list.
INSERT INTO app_settings (key, value) VALUES ('pr.sort.order', 'smart');
