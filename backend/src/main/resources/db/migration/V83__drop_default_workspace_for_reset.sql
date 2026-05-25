-- Second-round reset for testing: wipe every thread / task row, drop
-- workspace_repos so no watched repo is auto-pinned to ws-default,
-- and reseed ws-default as an empty bare workspace. The user wants
-- to come back to a clean surface so the create-workspace flow can
-- be exercised; ws-default has to stay as a row so the test fixtures
-- that key on it keep their FK happy. The user can rename or delete
-- ws-default from Settings → Identity later.
--
-- Scope: thread + task + review tier and workspace_repos. Watched
-- repos, credentials, settings, PR cache etc. are untouched.

DELETE FROM thread_messages;
DELETE FROM thread_turns;
DELETE FROM thread_turn_events;
DELETE FROM thread_checkpoints;
DELETE FROM thread_group_members;
DELETE FROM thread_settings;

DELETE FROM review_messages;
DELETE FROM review_findings;
DELETE FROM review_participants;
DELETE FROM review_passes;

DELETE FROM task_files;
DELETE FROM tasks;

DELETE FROM threads;
DELETE FROM thread_groups;

DELETE FROM workspace_memory_proposals;
DELETE FROM workspace_repos;

-- Reseed ws-default with empty memory + no pinned repos. The user
-- pins repos explicitly via the new-workspace dialog now; auto-pin
-- on first launch was hiding the "different workspaces have
-- different repos" model.
DELETE FROM workspaces;
INSERT INTO workspaces (id, name, memory_md, is_scratch, created_at_ms, updated_at_ms)
VALUES ('ws-default', 'ByteQuay', '', 0,
        strftime('%s','now') * 1000,
        strftime('%s','now') * 1000);
