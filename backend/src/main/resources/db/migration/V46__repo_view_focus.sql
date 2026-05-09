-- Fork → upstream "view focus" for the repo detail page.
--
-- repo_meta: cache the GitHub /repos/{o}/{r} parent.{owner,name,
-- default_branch} fields so the title-area dropdown can label the
-- upstream entry without a second API call. Nullable for legacy rows
-- and for non-fork repos; the next stale-while-revalidate refresh
-- fills them in for forks.
ALTER TABLE repo_meta ADD COLUMN parent_owner          TEXT;
ALTER TABLE repo_meta ADD COLUMN parent_repo           TEXT;
ALTER TABLE repo_meta ADD COLUMN parent_default_branch TEXT;

-- watched_repos: which view the user wants for this repo's detail
-- page commits tab. Values: 'fork' (use the local clone's HEAD /
-- origin's default branch) or 'upstream' (use
-- <upstream_remote_name>/<parent_default_branch>). Null = let the
-- service resolve the effective default: 'upstream' when this row
-- has an upstream_remote_name, else 'fork'. Nullable so existing
-- rows pick up the new default automatically.
ALTER TABLE watched_repos ADD COLUMN view_focus TEXT;
