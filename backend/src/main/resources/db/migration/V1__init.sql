-- Pull requests synced from GitHub.
-- `synced_at`           = last time the sync job wrote this row (GitHub-side clock is `updated_at`).
-- `internal_created_at` = first time this row was inserted into *this* database.
-- `internal_updated_at` = last time this row was modified in *this* database.
CREATE TABLE pull_requests (
    id                  INTEGER NOT NULL PRIMARY KEY,
    repo                TEXT    NOT NULL,
    number              INTEGER NOT NULL,
    title               TEXT    NOT NULL,
    author              TEXT,
    html_url            TEXT,
    updated_at          TEXT,
    origin              TEXT    NOT NULL,
    labels              TEXT    NOT NULL DEFAULT '[]',
    draft               INTEGER NOT NULL DEFAULT 0,
    synced_at           TEXT    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    internal_created_at TEXT    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    internal_updated_at TEXT    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Application configuration (key-value pairs).
CREATE TABLE app_settings (
    key                 TEXT NOT NULL PRIMARY KEY,
    value               TEXT NOT NULL,
    internal_created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    internal_updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Seed default configuration rows.
INSERT INTO app_settings (key, value) VALUES
    ('sync.interval.seconds', '60'),
    ('github.pat',             '');
