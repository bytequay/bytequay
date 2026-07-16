-- A workspace is the durable brain for one verified local repository. The
-- former ambient workspace is deliberately not replaced: incomplete historic
-- bindings become recovery records and cannot be used as agent cwds.

CREATE TABLE workspace_recovery (
    old_workspace_id TEXT PRIMARY KEY,
    name             TEXT NOT NULL,
    memory_md        TEXT NOT NULL,
    created_at_ms    INTEGER NOT NULL
);

INSERT OR IGNORE INTO workspace_recovery (old_workspace_id, name, memory_md, created_at_ms)
SELECT w.id, w.name, w.memory_md, strftime('%s','now') * 1000
FROM workspaces w
WHERE (SELECT COUNT(*) FROM workspace_repos wr WHERE wr.workspace_id = w.id) <> 1
   OR w.id = 'ws-default'
   OR w.id NOT IN (
       SELECT MIN(wr.workspace_id)
       FROM workspace_repos wr
       GROUP BY lower(wr.repo_full_name)
   )
   OR NOT EXISTS (
       SELECT 1
       FROM workspace_repos wr
       JOIN watched_repos watched
         ON lower(watched.owner || '/' || watched.repo) = lower(wr.repo_full_name)
       WHERE wr.workspace_id = w.id
         AND watched.local_clone_path IS NOT NULL
         AND trim(watched.local_clone_path) <> ''
   );

-- Preserve historic rows while their original workspace is removed. Their
-- repository binding is recovered below whenever the task/PR identifies one
-- verified local clone.
UPDATE review_session
SET workspace_id = NULL
WHERE workspace_id IN (SELECT old_workspace_id FROM workspace_recovery);

UPDATE threads
SET workspace_id = NULL
WHERE workspace_id IN (SELECT old_workspace_id FROM workspace_recovery);

DELETE FROM workspaces
WHERE id IN (SELECT old_workspace_id FROM workspace_recovery);

-- Existing local clones become repository workspaces even when their former
-- ambient workspace had multiple repos. The watcher id makes this migration
-- id stable and avoids relying on a user-supplied display name.
INSERT INTO workspaces (id, name, memory_md, is_scratch, created_at_ms, updated_at_ms)
SELECT 'ws-local-repo-' || watched.id,
       watched.owner || '/' || watched.repo,
       '', 0, strftime('%s','now') * 1000, strftime('%s','now') * 1000
FROM watched_repos watched
WHERE watched.local_clone_path IS NOT NULL
  AND trim(watched.local_clone_path) <> ''
  AND NOT EXISTS (
      SELECT 1 FROM workspace_repos wr
      WHERE lower(wr.repo_full_name) = lower(watched.owner || '/' || watched.repo));

INSERT INTO workspace_repos (workspace_id, repo_full_name, default_base_branch, auto_fix_enabled, added_at_ms)
SELECT 'ws-local-repo-' || watched.id,
       watched.owner || '/' || watched.repo,
       NULL, 0, strftime('%s','now') * 1000
FROM watched_repos watched
WHERE watched.local_clone_path IS NOT NULL
  AND trim(watched.local_clone_path) <> ''
  AND EXISTS (SELECT 1 FROM workspaces WHERE id = 'ws-local-repo-' || watched.id);

-- A work-unit's root is an unambiguous local-repository signal.
UPDATE threads
SET workspace_id = (
    SELECT wr.workspace_id
    FROM tasks task
    JOIN watched_repos watched
      ON lower(task.working_dir) = lower(watched.local_clone_path)
    JOIN workspace_repos wr
      ON lower(wr.repo_full_name) = lower(watched.owner || '/' || watched.repo)
    WHERE task.thread_id = threads.id
    LIMIT 1)
WHERE workspace_id IS NULL
  AND (SELECT COUNT(*)
       FROM tasks task
       JOIN watched_repos watched
         ON lower(task.working_dir) = lower(watched.local_clone_path)
       WHERE task.thread_id = threads.id
         AND watched.local_clone_path IS NOT NULL
         AND trim(watched.local_clone_path) <> '') = 1;

-- Standalone review owners are unambiguous when their PR repo has one local
-- workspace. Remote-only sessions remain deliberately ownerless.
UPDATE threads
SET workspace_id = (
    SELECT wr.workspace_id
    FROM review_session rs
    JOIN pr p ON p.id = rs.pr_id
    JOIN workspace_repos wr ON lower(wr.repo_full_name) = lower(COALESCE(p.repo, rs.repo_id))
    WHERE rs.owner_thread_id = threads.id
    LIMIT 1)
WHERE workspace_id IS NULL
  AND id IN (SELECT owner_thread_id FROM review_session WHERE owner_task_id IS NULL)
  AND (SELECT COUNT(*)
       FROM review_session rs
       JOIN pr p ON p.id = rs.pr_id
       JOIN workspace_repos wr ON lower(wr.repo_full_name) = lower(COALESCE(p.repo, rs.repo_id))
       WHERE rs.owner_thread_id = threads.id) = 1;

UPDATE review_session
SET workspace_id = (SELECT workspace_id FROM threads WHERE id = review_session.owner_thread_id)
WHERE owner_thread_id IS NOT NULL
  AND workspace_id IS NULL
  AND EXISTS (SELECT 1 FROM threads WHERE id = review_session.owner_thread_id
              AND workspace_id IS NOT NULL);

UPDATE review_session
SET owner_thread_id = NULL
WHERE owner_task_id IS NULL
  AND owner_thread_id IN (SELECT id FROM threads WHERE workspace_id IS NULL);

DELETE FROM threads
WHERE id LIKE 'agent-review-%'
  AND workspace_id IS NULL;

CREATE UNIQUE INDEX idx_workspace_repos_one_repo_per_workspace
    ON workspace_repos(workspace_id);
CREATE UNIQUE INDEX idx_workspace_repos_one_workspace_per_repo
    ON workspace_repos(lower(repo_full_name));
