-- PromotionManifest freezes publish requirements. The dispatcher produces
-- the proof; approval must not claim that synchronous preflight already did.
ALTER TABLE promotion_manifest
    RENAME COLUMN worktree_clean TO require_clean_worktree;
ALTER TABLE promotion_manifest
    RENAME COLUMN commits_ahead TO minimum_commits_ahead;
ALTER TABLE promotion_manifest
    RENAME COLUMN branch_verified TO require_branch_match;
ALTER TABLE promotion_manifest
    RENAME COLUMN base_verified TO require_base_match;
ALTER TABLE promotion_manifest
    RENAME COLUMN permission_clear TO require_publish_permission;
