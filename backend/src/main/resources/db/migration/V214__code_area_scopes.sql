-- Project Intelligence may propose history-backed code areas, but a proposal
-- never influences a trunk until the user explicitly approves it.
CREATE TABLE repo_directory_scope_decision (
    workspace_id   TEXT    NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    repo           TEXT    NOT NULL,
    scope_path     TEXT    NOT NULL,
    decision_state TEXT    NOT NULL CHECK (decision_state IN ('approved', 'rejected')),
    decided_at_ms  INTEGER NOT NULL,
    PRIMARY KEY (workspace_id, repo, scope_path)
);

-- A thread may opt into one approved code area. The redundant workspace/repo
-- columns make workspace-scoped reads cheap and bind the assignment to the
-- exact approval row; deleting either owner cascades the assignment.
CREATE TABLE thread_directory_scope_assignment (
    thread_id      TEXT    NOT NULL PRIMARY KEY REFERENCES threads(id) ON DELETE CASCADE,
    workspace_id   TEXT    NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    repo           TEXT    NOT NULL,
    scope_path     TEXT    NOT NULL,
    assigned_at_ms INTEGER NOT NULL,
    FOREIGN KEY (workspace_id, repo, scope_path)
        REFERENCES repo_directory_scope_decision(workspace_id, repo, scope_path)
        ON DELETE CASCADE
);

CREATE INDEX idx_thread_directory_scope_workspace
    ON thread_directory_scope_assignment(workspace_id, repo);
