-- Per-task minimum-approvals gate (default 0). A shipped PR only counts as
-- merge-ready once this many approvals from reviewers with WRITE permission to
-- the repo have landed — mirroring GitHub's own green (write) vs. grey
-- (no-write) approval marks. The user picks 0 / 1 / 2 on the plan card.
ALTER TABLE tasks ADD COLUMN min_approvals INTEGER NOT NULL DEFAULT 0;

-- Cache of whether a collaborator has write permission on a repo, fetched from
-- GitHub's collaborator-permission API and reused across PR polls. Permission
-- rarely changes, so an entry past its TTL is simply re-fetched and upserted.
CREATE TABLE repo_write_collaborator (
    id             TEXT PRIMARY KEY,   -- repo_full_name + '#' + login
    repo_full_name TEXT NOT NULL,
    login          TEXT NOT NULL,
    can_write      INTEGER NOT NULL,
    fetched_at     TEXT NOT NULL
);
