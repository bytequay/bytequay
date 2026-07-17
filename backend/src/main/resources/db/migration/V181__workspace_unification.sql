-- Public workspace-unification data plane. Existing workspace/repo/thread/run
-- rows remain the source of truth; these columns and tables add the fields
-- needed by the workspace-facing API without duplicating those aggregates.

-- Detach is reversible and never deletes the clone or workspace history.
ALTER TABLE workspaces ADD COLUMN detached_at_ms INTEGER;

-- A review trunk carries its PR identity. Keep the newest historic owner when
-- old data contains more than one completed review session for the same PR.
ALTER TABLE threads ADD COLUMN pr_ref TEXT;

UPDATE threads
SET pr_ref = (
    SELECT COALESCE(p.repo, rs.repo_id) || '#' || p.remote_pr_number
    FROM review_session rs
    JOIN pr p ON p.id = rs.pr_id
    WHERE rs.owner_thread_id = threads.id
      AND p.remote_pr_number IS NOT NULL
    ORDER BY rs.updated_at_ms DESC, rs.id DESC
    LIMIT 1)
WHERE flow = 'review';

UPDATE threads
SET pr_ref = NULL
WHERE pr_ref IS NOT NULL
  AND EXISTS (
      SELECT 1
      FROM threads newer
      WHERE newer.workspace_id = threads.workspace_id
        AND newer.pr_ref = threads.pr_ref
        AND newer.flow = 'review'
        AND (newer.updated_at_ms > threads.updated_at_ms
             OR (newer.updated_at_ms = threads.updated_at_ms AND newer.id > threads.id)));

CREATE UNIQUE INDEX idx_threads_one_review_trunk_per_pr
    ON threads(workspace_id, pr_ref)
    WHERE flow = 'review' AND pr_ref IS NOT NULL;

-- AgentRun is the sole Session backing record. Legacy run kinds/statuses stay
-- readable and are mapped by the public DTO; every new scheduler episode can
-- now carry its workspace/trunk ownership and accounting directly.
ALTER TABLE agent_run ADD COLUMN workspace_id TEXT REFERENCES workspaces(id);
ALTER TABLE agent_run ADD COLUMN thread_id TEXT REFERENCES threads(id) ON DELETE CASCADE;
ALTER TABLE agent_run ADD COLUMN provider TEXT;
ALTER TABLE agent_run ADD COLUMN model TEXT;
ALTER TABLE agent_run ADD COLUMN cost_usd_milli INTEGER NOT NULL DEFAULT 0;
ALTER TABLE agent_run ADD COLUMN tokens_in INTEGER NOT NULL DEFAULT 0;
ALTER TABLE agent_run ADD COLUMN tokens_out INTEGER NOT NULL DEFAULT 0;
ALTER TABLE agent_run ADD COLUMN step_cursor INTEGER NOT NULL DEFAULT 0;
ALTER TABLE agent_run ADD COLUMN launch_input TEXT;
ALTER TABLE agent_run ADD COLUMN pause_reason TEXT;
ALTER TABLE agent_run ADD COLUMN outcome TEXT;

UPDATE agent_run
SET thread_id = (
        SELECT task.thread_id FROM tasks task WHERE task.id = agent_run.task_id),
    workspace_id = (
        SELECT thread.workspace_id
        FROM tasks task
        JOIN threads thread ON thread.id = task.thread_id
        WHERE task.id = agent_run.task_id),
    provider = COALESCE(
        json_extract(agent_run.metrics_json, '$.provider'),
        (SELECT thread.provider
         FROM tasks task
         JOIN threads thread ON thread.id = task.thread_id
         WHERE task.id = agent_run.task_id)),
    model = COALESCE(
        json_extract(agent_run.metrics_json, '$.model'),
        (SELECT thread.model
         FROM tasks task
         JOIN threads thread ON thread.id = task.thread_id
         WHERE task.id = agent_run.task_id))
WHERE task_id IS NOT NULL;

UPDATE agent_run
SET thread_id = (
        SELECT rs.owner_thread_id
        FROM review_round rr
        JOIN review_session rs ON rs.id = rr.session_id
        WHERE rr.id = agent_run.review_round_id),
    workspace_id = (
        SELECT rs.workspace_id
        FROM review_round rr
        JOIN review_session rs ON rs.id = rr.session_id
        WHERE rr.id = agent_run.review_round_id),
    provider = COALESCE(provider, json_extract(metrics_json, '$.provider'), 'agent-review'),
    model = COALESCE(model, json_extract(metrics_json, '$.model'), 'agent-review')
WHERE review_round_id IS NOT NULL;

UPDATE agent_run
SET outcome = CASE status
    WHEN 'succeeded' THEN 'completed'
    WHEN 'failed' THEN 'failed'
    WHEN 'cancelled' THEN 'cancelled'
    ELSE outcome
END
WHERE outcome IS NULL;

CREATE INDEX idx_agent_run_workspace_started
    ON agent_run(workspace_id, started_at_ms DESC);
CREATE INDEX idx_agent_run_thread_started
    ON agent_run(thread_id, started_at_ms DESC);

CREATE TABLE workspace_issue_trunk (
    workspace_id   TEXT NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    issue_number   INTEGER NOT NULL,
    thread_id      TEXT NOT NULL REFERENCES threads(id) ON DELETE CASCADE,
    created_at_ms  INTEGER NOT NULL,
    PRIMARY KEY (workspace_id, issue_number, thread_id)
);

CREATE INDEX idx_workspace_issue_trunk_thread
    ON workspace_issue_trunk(thread_id);

-- Backlog fields are workspace-local and retain every historic id/body.
ALTER TABLE backlog_item ADD COLUMN item_key TEXT;
ALTER TABLE backlog_item ADD COLUMN summary TEXT NOT NULL DEFAULT '';
ALTER TABLE backlog_item ADD COLUMN detail TEXT;
ALTER TABLE backlog_item ADD COLUMN impact_risk TEXT;
ALTER TABLE backlog_item ADD COLUMN links_json TEXT NOT NULL DEFAULT '[]';

WITH ranked AS (
    SELECT id,
           'BQ-' || ROW_NUMBER() OVER (
               PARTITION BY workspace_id
               ORDER BY created_at_ms ASC, id ASC) AS generated_key
    FROM backlog_item
)
UPDATE backlog_item
SET item_key = (SELECT generated_key FROM ranked WHERE ranked.id = backlog_item.id);

UPDATE backlog_item
SET summary = CASE
        WHEN trim(body) = '' THEN title
        WHEN instr(body, char(10) || char(10)) > 0
            THEN trim(substr(body, 1, instr(body, char(10) || char(10)) - 1))
        ELSE trim(body)
    END,
    detail = NULLIF(body, ''),
    status = CASE status
        WHEN 'created' THEN 'open'
        WHEN 'not-to-proceed' THEN 'discarded'
        ELSE status
    END,
    source = CASE source
        WHEN 'trunk-split' THEN 'agent'
        ELSE source
    END;

CREATE UNIQUE INDEX idx_backlog_workspace_key
    ON backlog_item(workspace_id, item_key)
    WHERE workspace_id IS NOT NULL AND item_key IS NOT NULL;

CREATE TABLE workspace_backlog_seq (
    workspace_id TEXT NOT NULL PRIMARY KEY REFERENCES workspaces(id) ON DELETE CASCADE,
    next_value   INTEGER NOT NULL
);

INSERT INTO workspace_backlog_seq (workspace_id, next_value)
SELECT workspace_id, COUNT(*) + 1
FROM backlog_item
WHERE workspace_id IS NOT NULL
GROUP BY workspace_id;

INSERT INTO workspace_backlog_seq (workspace_id, next_value)
SELECT id, 1 FROM workspaces
WHERE id NOT IN (SELECT workspace_id FROM workspace_backlog_seq);

-- Applied MemoryItems remain the brain block store. KB entries and distill
-- runs are separate durable artifacts with reversible operation payloads.
CREATE TABLE kb_entry (
    id              TEXT NOT NULL PRIMARY KEY,
    workspace_id    TEXT NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    title           TEXT NOT NULL,
    body            TEXT NOT NULL,
    audience_json   TEXT NOT NULL DEFAULT '[]',
    provenance_json TEXT NOT NULL DEFAULT '{}',
    created_at_ms   INTEGER NOT NULL,
    updated_at_ms   INTEGER NOT NULL
);

CREATE INDEX idx_kb_entry_workspace
    ON kb_entry(workspace_id, updated_at_ms DESC);

CREATE TABLE distill_run (
    id                TEXT NOT NULL PRIMARY KEY,
    workspace_id      TEXT NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    trigger_kind      TEXT NOT NULL,
    status            TEXT NOT NULL,
    sources_json      TEXT NOT NULL DEFAULT '[]',
    operations_json   TEXT NOT NULL DEFAULT '[]',
    inverse_json      TEXT NOT NULL DEFAULT '[]',
    base_digest       TEXT,
    applied_digest    TEXT,
    created_at_ms     INTEGER NOT NULL,
    applied_at_ms     INTEGER,
    reverted_at_ms    INTEGER
);

CREATE INDEX idx_distill_run_workspace
    ON distill_run(workspace_id, created_at_ms DESC);

CREATE TABLE distill_watermark (
    workspace_id   TEXT NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    thread_id      TEXT NOT NULL REFERENCES threads(id) ON DELETE CASCADE,
    last_seq       INTEGER NOT NULL DEFAULT 0,
    updated_at_ms  INTEGER NOT NULL,
    PRIMARY KEY (workspace_id, thread_id)
);

-- One canonical notification stream. Gate payload/status semantics are left
-- untouched; public fields make ordinary rows self-rendering and deep-linkable.
ALTER TABLE notifications ADD COLUMN workspace_id TEXT REFERENCES workspaces(id);
ALTER TABLE notifications ADD COLUMN public_type TEXT;
ALTER TABLE notifications ADD COLUMN title TEXT;
ALTER TABLE notifications ADD COLUMN summary TEXT;
ALTER TABLE notifications ADD COLUMN item_path TEXT;
ALTER TABLE notifications ADD COLUMN dedup_key TEXT;

UPDATE notifications
SET workspace_id = (
        SELECT thread.workspace_id FROM threads thread
        WHERE thread.id = notifications.thread_id),
    public_type = CASE kind
        WHEN 'AWAITING_REVIEW' THEN 'approval-gate'
        WHEN 'NEEDS_ATTENTION' THEN 'agent-question'
        WHEN 'AUTO_FIX_DONE' THEN 'ci'
        WHEN 'READY_TO_MERGE' THEN 'review-request'
        ELSE lower(replace(kind, '_', '-'))
    END,
    title = COALESCE(
        NULLIF(json_extract(payload_json, '$.title'), ''),
        CASE kind
            WHEN 'AWAITING_REVIEW' THEN 'Approval needed'
            WHEN 'NEEDS_ATTENTION' THEN 'Agent needs your input'
            WHEN 'AUTO_FIX_DONE' THEN 'Automation completed'
            WHEN 'READY_TO_MERGE' THEN 'Pull request ready to merge'
            ELSE replace(kind, '_', ' ')
        END),
    summary = COALESCE(
        json_extract(payload_json, '$.summary'),
        json_extract(payload_json, '$.body'),
        json_extract(payload_json, '$.message')),
    item_path = CASE
        WHEN thread_id IS NOT NULL AND workspace_id IS NOT NULL
            THEN '#/workspace/' || workspace_id || '/trunks/' || thread_id
        ELSE NULL
    END,
    dedup_key = 'legacy:' || id;

INSERT OR IGNORE INTO notifications (
    id, kind, thread_id, task_id, status, payload_json, created_at_ms, read_at_ms,
    workspace_id, public_type, title, summary, item_path, dedup_key)
SELECT
    'signal:' || signal.id,
    'PASSIVE',
    signal.thread_id,
    signal.task_id,
    CASE WHEN signal.read_at_ms IS NULL THEN 'UNREAD' ELSE 'READ' END,
    json_object(
        'sourceKind', signal.source_kind,
        'iconKind', signal.icon_kind,
        'sourceUrl', signal.source_url),
    signal.created_at_ms,
    signal.read_at_ms,
    thread.workspace_id,
    CASE signal.source_kind
        WHEN 'github' THEN 'mention'
        WHEN 'agent' THEN 'agent-update'
        ELSE 'system'
    END,
    signal.title,
    signal.body,
    '#/workspace/' || thread.workspace_id || '/trunks/' || signal.thread_id,
    'signal:' || signal.id
FROM thread_signal signal
JOIN threads thread ON thread.id = signal.thread_id;

CREATE INDEX idx_notifications_workspace_created
    ON notifications(workspace_id, created_at_ms DESC);
CREATE UNIQUE INDEX idx_notifications_dedup
    ON notifications(dedup_key)
    WHERE dedup_key IS NOT NULL;

CREATE TABLE workspace_notification_mute (
    workspace_id  TEXT NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    public_type   TEXT NOT NULL,
    muted         INTEGER NOT NULL DEFAULT 1,
    updated_at_ms INTEGER NOT NULL,
    PRIMARY KEY (workspace_id, public_type)
);

-- Typed workspace settings and onboarding are one-row-per-workspace.
CREATE TABLE workspace_settings (
    workspace_id  TEXT NOT NULL PRIMARY KEY REFERENCES workspaces(id) ON DELETE CASCADE,
    settings_json TEXT NOT NULL,
    updated_at_ms INTEGER NOT NULL
);

INSERT INTO workspace_settings (workspace_id, settings_json, updated_at_ms)
SELECT id,
       '{"sessionCapUsd":1.0,"dailyCapUsd":10.0,"pauseAtCap":true,'
       || '"syncSeconds":60,"brainBudgetChars":8000,"distillMinutes":30,'
       || '"kbAudiences":["plan","dev","review","ci-fix"],'
       || '"providers":{},"notifyCi":true,"notifyCompletions":false}',
       strftime('%s','now') * 1000
FROM workspaces;

CREATE TABLE workspace_onboarding (
    workspace_id          TEXT NOT NULL PRIMARY KEY REFERENCES workspaces(id) ON DELETE CASCADE,
    clone_complete        INTEGER NOT NULL DEFAULT 1,
    sync_state            TEXT NOT NULL DEFAULT 'ready',
    sync_current          INTEGER NOT NULL DEFAULT 0,
    sync_total            INTEGER NOT NULL DEFAULT 0,
    memory_seed_complete  INTEGER NOT NULL DEFAULT 0,
    first_trunk_complete  INTEGER NOT NULL DEFAULT 0,
    memory_imported       INTEGER NOT NULL DEFAULT 0,
    dismissed_at_ms       INTEGER,
    updated_at_ms         INTEGER NOT NULL
);

INSERT INTO workspace_onboarding (
    workspace_id, clone_complete, sync_state, memory_seed_complete,
    first_trunk_complete, updated_at_ms)
SELECT id,
       1,
       'ready',
       CASE WHEN trim(memory_md) = '' THEN 0 ELSE 1 END,
       CASE WHEN EXISTS (
           SELECT 1 FROM threads thread
           WHERE thread.workspace_id = workspaces.id
             AND thread.flow = 'build') THEN 1 ELSE 0 END,
       strftime('%s','now') * 1000
FROM workspaces;

-- Persisted background clone/sync operations. A workspace created by one of
-- these operations is omitted from ready-card queries until state='ready'.
CREATE TABLE workspace_creation (
    id               TEXT NOT NULL PRIMARY KEY,
    operation_kind   TEXT NOT NULL DEFAULT 'connect',
    owner            TEXT NOT NULL,
    repo             TEXT NOT NULL,
    write_mode       TEXT NOT NULL,
    state            TEXT NOT NULL,
    stage_message    TEXT,
    progress_current INTEGER NOT NULL DEFAULT 0,
    progress_total   INTEGER NOT NULL DEFAULT 0,
    workspace_id     TEXT REFERENCES workspaces(id),
    clone_path       TEXT,
    previous_clone_path TEXT,
    error_message    TEXT,
    attempt          INTEGER NOT NULL DEFAULT 1,
    created_at_ms    INTEGER NOT NULL,
    updated_at_ms    INTEGER NOT NULL
);

CREATE INDEX idx_workspace_creation_state
    ON workspace_creation(state, updated_at_ms);
CREATE UNIQUE INDEX idx_workspace_creation_live_repo
    ON workspace_creation(lower(owner), lower(repo))
    WHERE state IN ('queued', 'forking', 'cloning', 'syncing');
