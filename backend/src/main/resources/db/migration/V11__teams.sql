-- Teams: a local-only grouping of GitHub logins. The Kanban for a team is
-- derived by filtering the user's watched PRs to those authored by any of
-- the listed members. See docs/design/settings-redesign.md §5 V9 (numbered
-- V11 here because V9 was reserved for the credential refactor and V10 for
-- pr enrichment).
CREATE TABLE team (
    id          INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
    name        TEXT    NOT NULL UNIQUE,
    -- 2-letter avatar prefix shown on cards / headers (e.g. "TR" for Trino).
    avatar      TEXT    NOT NULL,
    -- One of "purple" | "green" | "orange" — keyed in CSS, not free-form.
    color       TEXT    NOT NULL DEFAULT 'purple',
    created_at  TEXT    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TEXT    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE team_member (
    team_id     INTEGER NOT NULL,
    login       TEXT    NOT NULL,
    added_at    TEXT    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (team_id, login),
    FOREIGN KEY (team_id) REFERENCES team(id) ON DELETE CASCADE
);

CREATE INDEX idx_team_member_login ON team_member(login);
