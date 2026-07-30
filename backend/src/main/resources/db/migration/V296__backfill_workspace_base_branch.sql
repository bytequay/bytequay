-- Workspace creation historically left the base branch unset even after
-- repository metadata had learned GitHub's default branch.  Task creation is
-- database-only, so fill that durable routing value without replacing an
-- explicit workspace choice.
UPDATE workspace_repos
SET default_base_branch = (
    SELECT TRIM(repo_meta.default_branch)
    FROM repo_meta
    WHERE lower(repo_meta.full_name) = lower(workspace_repos.repo_full_name)
      AND NULLIF(TRIM(repo_meta.default_branch), '') IS NOT NULL
    LIMIT 1
)
WHERE NULLIF(TRIM(default_base_branch), '') IS NULL
  AND EXISTS (
      SELECT 1
      FROM repo_meta
      WHERE lower(repo_meta.full_name) = lower(workspace_repos.repo_full_name)
        AND NULLIF(TRIM(repo_meta.default_branch), '') IS NOT NULL
  );
