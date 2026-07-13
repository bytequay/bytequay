-- P0-P2 investigation-review aggregate. The pre-existing review_round table
-- models remote-comment addressing rounds; keep that data under an explicit
-- name so the locked investigation contract can own review_round.
ALTER TABLE review_round RENAME TO response_round;

-- External PR reviews are not task-owned, but their rounds are still
-- AgentRuns. Generalize the run row so a panel_review may be detached from a
-- task/stage while task lifecycle runs keep their existing foreign keys.
ALTER TABLE agent_run RENAME TO agent_run_legacy;
CREATE TABLE agent_run (
    id              TEXT    NOT NULL PRIMARY KEY,
    task_id         TEXT    REFERENCES tasks(id) ON DELETE CASCADE,
    kind            TEXT    NOT NULL,
    source          TEXT,
    parent_stage_id TEXT    REFERENCES task_stage(id),
    review_round_id TEXT,
    stage_id        TEXT    REFERENCES task_stage(id) ON DELETE CASCADE,
    status          TEXT    NOT NULL,
    iterations      INTEGER NOT NULL DEFAULT 0,
    budget          INTEGER,
    headline        TEXT,
    metrics_json    TEXT,
    started_at_ms   INTEGER NOT NULL,
    finished_at_ms  INTEGER
);
INSERT INTO agent_run SELECT * FROM agent_run_legacy;
DROP TABLE agent_run_legacy;
CREATE INDEX idx_agent_run_task_status ON agent_run(task_id, status);
CREATE INDEX idx_agent_run_task_kind ON agent_run(task_id, kind);
CREATE INDEX idx_agent_run_parent_stage ON agent_run(parent_stage_id);

CREATE TABLE review_session (
    id                   TEXT NOT NULL PRIMARY KEY,
    repo_id              TEXT NOT NULL,
    pr_id                TEXT NOT NULL REFERENCES pr(id) ON DELETE CASCADE,
    base_commit          TEXT NOT NULL,
    reviewed_head_commit TEXT NOT NULL,
    status               TEXT NOT NULL,
    created_at_ms        INTEGER NOT NULL,
    updated_at_ms        INTEGER NOT NULL
);
CREATE UNIQUE INDEX idx_review_session_pr_active
    ON review_session(pr_id) WHERE status IN ('ACTIVE', 'STALE');

CREATE TABLE criterion (
    id          TEXT NOT NULL PRIMARY KEY,
    repo_id     TEXT,
    kind        TEXT NOT NULL,
    statement   TEXT NOT NULL,
    source_type TEXT NOT NULL,
    source_ref  TEXT
);

CREATE TABLE review_round (
    id             TEXT NOT NULL PRIMARY KEY,
    session_id     TEXT NOT NULL REFERENCES review_session(id) ON DELETE CASCADE,
    agent_run_id   TEXT NOT NULL REFERENCES agent_run(id) ON DELETE CASCADE,
    trigger        TEXT NOT NULL,
    scope          TEXT NOT NULL,
    start_commit   TEXT NOT NULL,
    end_commit     TEXT,
    status         TEXT NOT NULL,
    budget_json    TEXT NOT NULL,
    cost_cents     INTEGER NOT NULL DEFAULT 0,
    created_at_ms  INTEGER NOT NULL,
    finished_at_ms INTEGER
);
CREATE INDEX idx_investigation_round_session ON review_round(session_id, created_at_ms);

CREATE TABLE review_objective (
    id                   TEXT NOT NULL PRIMARY KEY,
    round_id             TEXT NOT NULL REFERENCES review_round(id) ON DELETE CASCADE,
    criterion_id         TEXT NOT NULL REFERENCES criterion(id),
    statement            TEXT NOT NULL,
    source               TEXT NOT NULL,
    applicability_status TEXT NOT NULL,
    resolution_status    TEXT NOT NULL
);

CREATE TABLE reviewer_def (
    id             TEXT NOT NULL PRIMARY KEY,
    name           TEXT NOT NULL,
    description    TEXT NOT NULL,
    runner         TEXT NOT NULL,
    runner_json    TEXT NOT NULL,
    persona        TEXT,
    eligible_kinds TEXT NOT NULL,
    enabled        INTEGER NOT NULL DEFAULT 1
);

CREATE TABLE review_assignment (
    id                    TEXT NOT NULL PRIMARY KEY,
    round_id              TEXT NOT NULL REFERENCES review_round(id) ON DELETE CASCADE,
    reviewer_def_id       TEXT NOT NULL REFERENCES reviewer_def(id),
    runner                TEXT NOT NULL,
    status                TEXT NOT NULL,
    understanding_summary TEXT NOT NULL,
    assumptions_json      TEXT NOT NULL,
    unknowns_json         TEXT NOT NULL,
    budget_json           TEXT NOT NULL
);

CREATE TABLE hypothesis (
    id               TEXT NOT NULL PRIMARY KEY,
    assignment_id    TEXT NOT NULL REFERENCES review_assignment(id) ON DELETE CASCADE,
    objective_id     TEXT REFERENCES review_objective(id),
    claim            TEXT NOT NULL,
    origin           TEXT NOT NULL,
    status           TEXT NOT NULL,
    confidence_class TEXT NOT NULL
);

CREATE TABLE investigation_step (
    id             TEXT NOT NULL PRIMARY KEY,
    assignment_id  TEXT NOT NULL REFERENCES review_assignment(id) ON DELETE CASCADE,
    hypothesis_id  TEXT REFERENCES hypothesis(id),
    action_type     TEXT NOT NULL,
    arguments_json TEXT NOT NULL,
    reason          TEXT NOT NULL,
    planned         INTEGER NOT NULL,
    cost_cents      INTEGER NOT NULL DEFAULT 0,
    status          TEXT NOT NULL
);

CREATE TABLE observation (
    id             TEXT NOT NULL PRIMARY KEY,
    step_id        TEXT NOT NULL REFERENCES investigation_step(id) ON DELETE CASCADE,
    source_type    TEXT NOT NULL,
    commit_sha     TEXT NOT NULL,
    path           TEXT,
    start_line     INTEGER,
    end_line       INTEGER,
    symbol         TEXT,
    command        TEXT,
    exit_code      INTEGER,
    artifact_ref   TEXT,
    content_digest TEXT NOT NULL,
    preview        TEXT NOT NULL
);

CREATE TABLE finding (
    id                  TEXT NOT NULL PRIMARY KEY,
    session_id          TEXT NOT NULL REFERENCES review_session(id) ON DELETE CASCADE,
    round_id            TEXT NOT NULL REFERENCES review_round(id) ON DELETE CASCADE,
    objective_id        TEXT NOT NULL REFERENCES review_objective(id),
    hypothesis_id       TEXT REFERENCES hypothesis(id),
    criterion_kind      TEXT NOT NULL,
    claim               TEXT NOT NULL,
    severity            INTEGER NOT NULL CHECK (severity BETWEEN 1 AND 5),
    confidence_class    TEXT NOT NULL,
    verification_status TEXT NOT NULL,
    requested_action    TEXT NOT NULL,
    lifecycle_status    TEXT NOT NULL,
    last_checked_commit TEXT NOT NULL
);

CREATE TABLE finding_evidence (
    finding_id      TEXT NOT NULL REFERENCES finding(id) ON DELETE CASCADE,
    observation_id  TEXT NOT NULL REFERENCES observation(id) ON DELETE CASCADE,
    relation        TEXT NOT NULL,
    proposition     TEXT NOT NULL,
    strength_class  TEXT NOT NULL,
    strength_reason TEXT NOT NULL,
    dependency_mode TEXT NOT NULL,
    dependency_json TEXT NOT NULL,
    PRIMARY KEY (finding_id, observation_id, relation)
);

CREATE TABLE finding_verification (
    id                    TEXT NOT NULL PRIMARY KEY,
    finding_id            TEXT NOT NULL REFERENCES finding(id) ON DELETE CASCADE,
    verifier_run_id       TEXT NOT NULL,
    evidence_accurate     INTEGER NOT NULL,
    claim_scope_accurate  INTEGER NOT NULL,
    severity_accurate     INTEGER NOT NULL,
    counter_evidence_json TEXT NOT NULL,
    status                TEXT NOT NULL,
    confidence_class      TEXT NOT NULL,
    explanation           TEXT NOT NULL
);

CREATE TABLE finding_relation (
    source_finding_id TEXT NOT NULL REFERENCES finding(id) ON DELETE CASCADE,
    target_finding_id TEXT NOT NULL REFERENCES finding(id) ON DELETE CASCADE,
    relation          TEXT NOT NULL,
    PRIMARY KEY (source_finding_id, target_finding_id, relation)
);

CREATE TABLE review_outcome (
    finding_id           TEXT NOT NULL REFERENCES finding(id) ON DELETE CASCADE,
    user_disposition     TEXT NOT NULL,
    author_response      TEXT NOT NULL,
    epistemic_resolution TEXT NOT NULL,
    utility_assessment   TEXT NOT NULL,
    style_edit_magnitude INTEGER NOT NULL,
    recorded_at_ms       INTEGER NOT NULL,
    PRIMARY KEY (finding_id, user_disposition)
);

CREATE TABLE knowledge_item (
    id            TEXT NOT NULL PRIMARY KEY,
    repo_id       TEXT NOT NULL,
    subtype       TEXT NOT NULL,
    statement     TEXT NOT NULL,
    steps_json    TEXT,
    trigger_json  TEXT NOT NULL,
    state         TEXT NOT NULL,
    counters_json TEXT NOT NULL DEFAULT '{}'
);

CREATE TABLE knowledge_provenance (
    knowledge_item_id TEXT NOT NULL REFERENCES knowledge_item(id) ON DELETE CASCADE,
    source_kind       TEXT NOT NULL,
    source_ref        TEXT NOT NULL,
    PRIMARY KEY (knowledge_item_id, source_kind, source_ref)
);

CREATE TABLE repo_review_conf (
    repo_id            TEXT NOT NULL PRIMARY KEY,
    pins_bans_json     TEXT NOT NULL DEFAULT '{}',
    rule_deltas_json   TEXT NOT NULL DEFAULT '{}',
    policy_deltas_json TEXT NOT NULL DEFAULT '{}',
    auto_start         INTEGER NOT NULL DEFAULT 0,
    auto_continue      INTEGER NOT NULL DEFAULT 1
);

ALTER TABLE pr_comment ADD COLUMN finding_id TEXT REFERENCES finding(id);
CREATE INDEX idx_pr_comment_finding ON pr_comment(finding_id);

INSERT INTO reviewer_def VALUES
    ('general-api', 'Generalist', 'Single bounded correctness investigator', 'api', '{"provider":"auto"}', NULL, '["trivial","standard","high-risk"]', 1),
    ('general-cli', 'CLI generalist', 'Read-only CLI correctness investigator', 'cli', '{"provider":"auto"}', NULL, '["trivial","standard","high-risk"]', 1),
    ('independent-verifier', 'Independent verifier', 'Cross-family evidence verifier', 'api', '{"provider":"auto-cross-family"}', NULL, '["standard","high-risk"]', 1);
