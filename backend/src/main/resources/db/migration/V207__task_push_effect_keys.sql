-- Freeze the exact ordered first-push effects on each durable authorization.
-- The default backfills authorizations created by V205 before this column
-- existed and keeps an already-applied V205 migration checksum stable.
ALTER TABLE task_push_authorization
    ADD COLUMN effect_keys_json TEXT NOT NULL
    DEFAULT '["push_branch","ensure_pull_request"]';
