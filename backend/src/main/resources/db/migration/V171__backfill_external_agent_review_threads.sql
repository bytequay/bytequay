-- Materialize the lightweight workspace thread that V169 made the owner of
-- every standalone AgentReview. New reviews create this row in the service;
-- this migration makes historical external-PR reviews equally discoverable
-- without waiting for the user to reopen each PR.
--
-- A repo attached to exactly one workspace follows that workspace. Ambiguous
-- or unattached repos fall back to the ambient workspace rather than being
-- silently assigned to an arbitrary project.
--
-- ws-default is user-deletable. Recreate it only when a historical orphan
-- actually needs the documented fallback; otherwise this migration would
-- either violate the threads.workspace_id FK or assign the review arbitrarily.
INSERT INTO workspaces (id, name, memory_md, is_scratch, created_at_ms, updated_at_ms)
SELECT 'ws-default', 'ByteQuay', '', 0,
       strftime('%s','now') * 1000,
       strftime('%s','now') * 1000
WHERE NOT EXISTS (SELECT 1 FROM workspaces WHERE id = 'ws-default')
  AND EXISTS (
      SELECT 1
      FROM review_session rs
      JOIN pr p ON p.id = rs.pr_id
      WHERE rs.owner_task_id IS NULL
        AND rs.owner_thread_id IS NULL
        AND (SELECT COUNT(*)
             FROM workspace_repos wr
             WHERE wr.repo_full_name = COALESCE(p.repo, rs.repo_id)) <> 1);

INSERT INTO threads (
    id, kind, provider, agent_session_id, title, status, model,
    cost_usd_milli, tokens_in, tokens_out,
    created_at_ms, updated_at_ms, ended_at_ms, error_message,
    workspace_id, flow, parallel_slots
)
SELECT
    'agent-review-' || rs.id,
    'LOGIC_LOOP',
    'agent-review',
    NULL,
    'Review ' || COALESCE(p.repo, rs.repo_id)
        || CASE WHEN p.remote_pr_number IS NULL THEN '' ELSE '#' || p.remote_pr_number END
        || CASE WHEN p.title IS NULL OR trim(p.title) = '' THEN '' ELSE ' — ' || p.title END,
    CASE
        WHEN EXISTS (
            SELECT 1 FROM review_round rr
            WHERE rr.session_id = rs.id AND rr.status = 'RUNNING') THEN 'RUNNING'
        WHEN rs.status = 'STALE' THEN 'NEEDS_ATTENTION'
        WHEN (
            SELECT rr.status FROM review_round rr
            WHERE rr.session_id = rs.id
            ORDER BY rr.created_at_ms DESC LIMIT 1) = 'COMPLETED_WITH_QUESTIONS'
            THEN 'NEEDS_ATTENTION'
        WHEN (
            SELECT rr.status FROM review_round rr
            WHERE rr.session_id = rs.id
            ORDER BY rr.created_at_ms DESC LIMIT 1) = 'ERRORED'
            THEN 'ERRORED'
        WHEN (
            SELECT rr.status FROM review_round rr
            WHERE rr.session_id = rs.id
            ORDER BY rr.created_at_ms DESC LIMIT 1) = 'COMPLETED'
            THEN 'COMPLETED'
        ELSE 'IDLE'
    END,
    'agent-review',
    COALESCE((
        SELECT SUM(rr.cost_cents) * 10
        FROM review_round rr
        WHERE rr.session_id = rs.id), 0),
    0,
    0,
    rs.created_at_ms,
    rs.updated_at_ms,
    CASE WHEN (
        SELECT rr.status FROM review_round rr
        WHERE rr.session_id = rs.id
        ORDER BY rr.created_at_ms DESC LIMIT 1) IN ('COMPLETED', 'ERRORED')
        THEN rs.updated_at_ms ELSE NULL END,
    NULL,
    CASE
        WHEN (
            SELECT COUNT(*) FROM workspace_repos wr
            WHERE wr.repo_full_name = COALESCE(p.repo, rs.repo_id)) = 1
        THEN (
            SELECT wr.workspace_id FROM workspace_repos wr
            WHERE wr.repo_full_name = COALESCE(p.repo, rs.repo_id)
            LIMIT 1)
        ELSE 'ws-default'
    END,
    'review',
    1
FROM review_session rs
JOIN pr p ON p.id = rs.pr_id
WHERE rs.owner_task_id IS NULL
  AND rs.owner_thread_id IS NULL;

UPDATE review_session
SET owner_thread_id = 'agent-review-' || id
WHERE owner_task_id IS NULL
  AND owner_thread_id IS NULL
  AND EXISTS (
      SELECT 1 FROM threads th WHERE th.id = 'agent-review-' || review_session.id);

UPDATE review_session
SET workspace_id = (
    SELECT th.workspace_id FROM threads th
    WHERE th.id = review_session.owner_thread_id)
WHERE owner_task_id IS NULL
  AND owner_thread_id IS NOT NULL
  AND workspace_id IS NULL;
