-- Soft-delete state for AI review comments. The user clicks "Dismiss"
-- on an inline finding to keep the row around (so it can be restored
-- without re-running the AI) but exclude it from the publish payload
-- and dim it in the UI. Defaults to 0 so existing rows act as before.
ALTER TABLE pr_review_comment ADD COLUMN dismissed INTEGER NOT NULL DEFAULT 0;
