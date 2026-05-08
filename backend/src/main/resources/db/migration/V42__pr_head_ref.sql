-- The PR's head branch name (GitHub's `head.ref` on the list-PR
-- response). Populated by the list-page sync, so the local-repo
-- kanban can map a local branch to its open PR without waiting for
-- the per-PR detail fetch. Nullable for legacy rows that pre-date
-- this migration; the next sync fills them in.
ALTER TABLE pull_requests ADD COLUMN head_ref TEXT;
