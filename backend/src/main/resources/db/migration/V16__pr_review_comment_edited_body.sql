-- Per-finding edit: lets the user revise an AI-drafted comment before
-- publishing. The original body stays untouched as the "before" reference
-- (per the V2 mockup); edited_body, when non-null, is what the publish
-- path actually sends to GitHub.
ALTER TABLE pr_review_comment ADD COLUMN edited_body TEXT;
