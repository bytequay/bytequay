-- Wipe and reseed the workspace / thread / task tier.
--
-- A long-standing bug stamped every new thread with workspace_id =
-- 'ws-default' regardless of which workspace the user was inside, so
-- threads created from a non-default workspace ended up orphaned in
-- the default's bucket. The code fix lands in the same change as
-- this migration (NewTaskRequest now carries workspaceId; the
-- service rejects the create when it's null/blank). This migration
-- clears the dirty data so the user starts from a clean slate, then
-- re-seeds ws-default + pins every watched repo back to it.
--
-- Scope: only workspace-tier and thread-tier rows. Watched repos,
-- credentials, settings, app_settings, PR cache, etc. are left
-- alone — they're unrelated to the bug.

-- 1. Drop everything downstream of threads. SQLite needs the children
--    cleared before the parent because foreign keys aren't always
--    enforced retroactively on SQLite (PRAGMA foreign_keys may be off
--    in some contexts, so we are explicit).

-- thread_messages / thread_files / thread_turns / thread_turn_events /
-- thread_checkpoints / thread_group_members / thread_settings all
-- key on thread_id and cascade in their migrations; truncate them
-- explicitly so a non-FK driver still leaves them empty.
DELETE FROM thread_messages;
DELETE FROM thread_turns;
DELETE FROM thread_turn_events;
DELETE FROM thread_checkpoints;
DELETE FROM thread_group_members;
DELETE FROM thread_settings;
-- thread_files was dropped in V72 — the file ledger now lives on
-- task_files, which is cleared below.

-- Review-panel rows hang off review_passes which hang off threads —
-- clear in dependency order.
DELETE FROM review_messages;
DELETE FROM review_findings;
DELETE FROM review_participants;
DELETE FROM review_passes;

-- Task children + tasks themselves.
DELETE FROM task_files;
DELETE FROM tasks;

-- Threads, thread groups (a group with no members is meaningless
-- after a wipe so drop those too).
DELETE FROM threads;
DELETE FROM thread_groups;

-- 2. Drop workspace-tier rows so the seed below starts from zero.
DELETE FROM workspace_memory_proposals;
DELETE FROM workspace_repos;
DELETE FROM workspaces;

-- 3. Reseed the ambient default workspace. Same id V73 used so any
--    code path that still references "ws-default" keeps working
--    until it's plumbed to the active workspace.
INSERT INTO workspaces (id, name, memory_md, is_scratch, created_at_ms, updated_at_ms)
VALUES ('ws-default', 'ByteQuay', '', 0,
        strftime('%s','now') * 1000,
        strftime('%s','now') * 1000);

-- 4. Pin every watched repo back into the default workspace, mirroring
--    the V73 seed so the user's repo list survives the wipe.
INSERT INTO workspace_repos (workspace_id, repo_full_name, default_base_branch, auto_fix_enabled, added_at_ms)
SELECT
    'ws-default',
    owner || '/' || repo,
    NULL,
    0,
    strftime('%s','now') * 1000
FROM watched_repos;
