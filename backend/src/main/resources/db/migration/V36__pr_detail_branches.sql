-- Branch + repo refs for the head and base sides of each PR. Used by
-- the PR detail header to show a GitHub-style "base ← head" pill so
-- the user can see at a glance what's being merged into what
-- (especially helpful for fork PRs where head_repo differs from
-- base_repo). Backfilled on the next detail sync; null on rows whose
-- updatedAt hasn't moved since this migration ran.
ALTER TABLE pr_detail ADD COLUMN head_ref  TEXT;
ALTER TABLE pr_detail ADD COLUMN head_repo TEXT;
ALTER TABLE pr_detail ADD COLUMN base_ref  TEXT;
ALTER TABLE pr_detail ADD COLUMN base_repo TEXT;
