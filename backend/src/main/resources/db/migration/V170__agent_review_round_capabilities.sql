-- Freeze the source/tool boundary with each review round. A result produced
-- from GitHub data alone must remain distinguishable from one that could
-- inspect a local repository at the reviewed SHA.
ALTER TABLE review_round ADD COLUMN capabilities_json TEXT NOT NULL DEFAULT
    '{"source_mode":"remote-only","available":["pr_diff","file_blobs","commits","checks"],"unavailable":["repository_callers","code_graph","local_tests","git_history"]}';
ALTER TABLE review_round ADD COLUMN trigger_stage_id TEXT REFERENCES task_stage(id);

-- Historical task-only reviews used their task worktree; external reviews
-- used the GitHub diff/blob path before this capability snapshot existed.
UPDATE review_round
SET capabilities_json =
    '{"source_mode":"local-source","available":["pr_diff","file_blobs","repository_source","repository_callers","git_history"],"unavailable":["code_graph","local_tests"]}'
WHERE session_id IN (
    SELECT rs.id
    FROM review_session rs
    JOIN pr p ON p.id = rs.pr_id
    WHERE p.task_id IS NOT NULL AND p.remote_pr_number IS NULL
);
