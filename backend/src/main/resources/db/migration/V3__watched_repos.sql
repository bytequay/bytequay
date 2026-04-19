-- Repositories the user has chosen to monitor on the home page.
CREATE TABLE watched_repos (
    id            INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
    owner         TEXT    NOT NULL,
    repo          TEXT    NOT NULL,
    display_order INTEGER NOT NULL DEFAULT 0,
    added_at      TEXT    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(owner, repo)
);
