-- Agent reviews must be discoverable from workspace navigation. Task-owned
-- reviews point at their existing task/thread; external reviews receive a
-- lightweight review-only thread when first opened after this migration.
ALTER TABLE review_session ADD COLUMN workspace_id TEXT REFERENCES workspaces(id);
ALTER TABLE review_session ADD COLUMN owner_thread_id TEXT REFERENCES threads(id);
ALTER TABLE review_session ADD COLUMN owner_task_id TEXT REFERENCES tasks(id);

UPDATE review_session
SET owner_task_id = (SELECT p.task_id FROM pr p WHERE p.id = review_session.pr_id)
WHERE owner_task_id IS NULL;

UPDATE review_session
SET owner_thread_id = (
        SELECT t.thread_id
        FROM pr p JOIN tasks t ON t.id = p.task_id
        WHERE p.id = review_session.pr_id)
WHERE owner_task_id IS NOT NULL AND owner_thread_id IS NULL;

UPDATE review_session
SET workspace_id = (
        SELECT th.workspace_id
        FROM threads th
        WHERE th.id = review_session.owner_thread_id)
WHERE owner_thread_id IS NOT NULL AND workspace_id IS NULL;

CREATE INDEX idx_agent_review_owner_thread ON review_session(owner_thread_id);
CREATE INDEX idx_agent_review_owner_task ON review_session(owner_task_id);
