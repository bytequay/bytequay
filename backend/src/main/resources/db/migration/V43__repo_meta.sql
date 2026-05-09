-- Cached repo-level metadata (description, license, topics, languages,
-- counts, …) backing the Repository overview / About panel. Populated
-- by RepoService.getRepoMeta with a stale-while-revalidate read pattern
-- so the page paints from the local row instantly and a background
-- refresh keeps it within the 1-hour freshness window.
CREATE TABLE repo_meta (
    owner               TEXT    NOT NULL,
    repo                TEXT    NOT NULL,
    full_name           TEXT    NOT NULL,
    html_url            TEXT    NOT NULL,
    description         TEXT,
    default_branch      TEXT,
    license             TEXT,
    stargazers_count    INTEGER NOT NULL,
    forks_count         INTEGER NOT NULL,
    watchers_count      INTEGER NOT NULL,
    open_issues_count   INTEGER NOT NULL,
    size_kb             INTEGER NOT NULL,
    -- ISO-8601 instants (TEXT for SQLite parity with the rest of the
    -- schema, where InstantToTextConverter handles round-tripping).
    created_at          TEXT,
    pushed_at           TEXT,
    -- topics: JSON array of strings; languages: JSON object
    -- (language → byte count). Stored as TEXT, decoded by the JPA
    -- converters on read.
    topics              TEXT    NOT NULL DEFAULT '[]',
    languages           TEXT    NOT NULL DEFAULT '{}',
    synced_at           TEXT    NOT NULL,
    PRIMARY KEY (owner, repo)
);
