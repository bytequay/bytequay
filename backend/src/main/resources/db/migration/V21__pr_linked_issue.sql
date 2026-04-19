-- Caches the issues a PR closes via "Closes #N" / "Fixes #N" / "Resolves #N"
-- in the PR body. Resolved at PR-detail fetch time and refreshed alongside
-- the rest of the cached detail; cross-repo links not stored yet.
CREATE TABLE pr_linked_issue (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    pr_id INTEGER NOT NULL,
    issue_number INTEGER NOT NULL,
    title TEXT NOT NULL,
    state TEXT NOT NULL,
    html_url TEXT NOT NULL
);

CREATE INDEX idx_pr_linked_issue_pr_id ON pr_linked_issue(pr_id);
