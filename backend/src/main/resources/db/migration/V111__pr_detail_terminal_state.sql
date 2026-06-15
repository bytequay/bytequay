-- Terminal PR state on the cached detail, so the lifecycle reconciler
-- can complete a task whose PR merged or closed anywhere — not only via
-- the in-app merge action. state: "open" | "closed"; merged: 0 | 1.
-- Existing rows backfill to NULL/0 and refresh on their next detail sync.
ALTER TABLE pr_detail ADD COLUMN state TEXT;
ALTER TABLE pr_detail ADD COLUMN merged INTEGER NOT NULL DEFAULT 0;
