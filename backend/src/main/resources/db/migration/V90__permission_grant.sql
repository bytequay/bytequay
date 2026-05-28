--
-- Licensed under the Apache License, Version 2.0 (the "License");
-- you may not use this file except in compliance with the License.
-- You may obtain a copy of the License at
--
--     http://www.apache.org/licenses/LICENSE-2.0
--

-- Per-scope permission grants. The PermissionResolver walks the
-- cascade global → workspace → thread → task and tightens the
-- caller's role-derived base capability set: a deny at any level
-- removes the capability for that scope and everything under it.
-- The resolution is tighten-only — a child level can subtract from
-- its parent but never re-add. allow / inherit are recorded for
-- intent + future policy but don't widen the set.
--
--   scope_kind  'global' | 'workspace' | 'thread' | 'task'
--   scope_id    null for global; workspace_id / thread_id / task_id
--               for the narrower scopes
--   capability  a SecurityType name (CODE_WRITE, GIT_PUSH, …)
--   mode        'allow' | 'deny' | 'inherit'
--   params_json optional structured policy for the capability (e.g. a
--               run_shell allowlist) — stored now, interpreted later
CREATE TABLE permission_grant (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    scope_kind TEXT NOT NULL CHECK (scope_kind IN ('global', 'workspace', 'thread', 'task')),
    scope_id TEXT,
    capability TEXT NOT NULL,
    mode TEXT NOT NULL CHECK (mode IN ('allow', 'deny', 'inherit')),
    params_json TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE INDEX permission_grant_scope_idx ON permission_grant(scope_kind, scope_id);
