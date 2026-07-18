-- Repository-wide reviewer candidates and labels change slowly. Persist one
-- snapshot per repo so PR metadata pickers search local SQLite and refresh
-- from GitHub only after the service's weekly TTL expires.
CREATE TABLE repo_metadata_cache (
    repo_full_name  TEXT NOT NULL PRIMARY KEY,
    users_json      TEXT NOT NULL,
    labels_json     TEXT NOT NULL,
    fetched_at      TEXT NOT NULL
);
