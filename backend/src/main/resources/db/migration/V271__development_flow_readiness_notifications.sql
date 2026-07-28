-- Readiness notifications are a read-only projection of the current exact
-- Remote Stage. This marker throttles one notification per readiness edge;
-- it is never consulted by Stage, Task, or merge lifecycle code.

CREATE TABLE remote_readiness_notification_marker_v271 (
    stage_id              TEXT    NOT NULL PRIMARY KEY
        REFERENCES stage(id) ON DELETE CASCADE,
    workspace_id          TEXT    NOT NULL
        REFERENCES workspaces(id) ON DELETE CASCADE,
    trunk_id              TEXT    NOT NULL
        REFERENCES threads(id) ON DELETE CASCADE,
    task_id               TEXT    NOT NULL
        REFERENCES tasks(id) ON DELETE CASCADE,
    task_epoch            INTEGER NOT NULL CHECK (task_epoch > 0),
    stage_generation      INTEGER NOT NULL CHECK (stage_generation > 0),
    ready_stage_version   INTEGER NOT NULL CHECK (ready_stage_version >= 0),
    readiness_evidence_id TEXT    NOT NULL
        REFERENCES remote_readiness_evidence(id) ON DELETE CASCADE,
    head_sha              TEXT    NOT NULL CHECK (length(trim(head_sha)) > 0),
    state                 TEXT    NOT NULL CHECK (state IN (
        'DELIVERED', 'REGRESSED')),
    delivered_at_ms       INTEGER NOT NULL CHECK (delivered_at_ms >= 0),
    regressed_at_ms       INTEGER CHECK (regressed_at_ms >= 0),
    updated_at_ms         INTEGER NOT NULL CHECK (updated_at_ms >= 0),
    CHECK ((state = 'DELIVERED' AND regressed_at_ms IS NULL)
        OR (state = 'REGRESSED' AND regressed_at_ms IS NOT NULL))
);

CREATE INDEX idx_remote_readiness_notification_marker_state_v271
    ON remote_readiness_notification_marker_v271(state, updated_at_ms);

-- Deliberately no UPDATE/DELETE guard: this mutable projection resets on a
-- durable readiness regression and cascades during an authorized owner purge.
