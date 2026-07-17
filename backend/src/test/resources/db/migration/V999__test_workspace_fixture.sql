-- Legacy unit and integration fixtures predate one-repository workspaces and
-- still refer to ws-default directly. Keep a detached, repository-free
-- workspace available only in test databases so those rows satisfy foreign
-- keys without weakening the production integrity invariant.
INSERT OR IGNORE INTO workspaces (
    id, name, memory_md, is_scratch, created_at_ms, updated_at_ms,
    detached_at_ms)
SELECT
    'ws-default', 'Test workspace', '', 1,
    strftime('%s','now') * 1000,
    strftime('%s','now') * 1000,
    strftime('%s','now') * 1000
WHERE instr(
    (SELECT file FROM pragma_database_list WHERE name = 'main'),
    'bytequay-test-') > 0;
