-- Durable CI Autofix Harness. The harness observes and rewrites only the local
-- worktree; intentionally no table models a push request or remote mutation.

CREATE TABLE ci_harness_watch (
    id                    TEXT    NOT NULL PRIMARY KEY,
    workspace_id          TEXT    NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    owner                 TEXT    NOT NULL,
    repo                  TEXT    NOT NULL,
    pr_number             INTEGER NOT NULL,
    local_pr_id           TEXT,
    local_path            TEXT,
    branch                TEXT,
    title                 TEXT,
    status                TEXT    NOT NULL,
    head_sha              TEXT,
    bootstrap_status      TEXT    NOT NULL DEFAULT 'pending',
    bootstrap_profile_json TEXT   NOT NULL DEFAULT '{}',
    budget_milli_usd      INTEGER NOT NULL,
    spent_milli_usd       INTEGER NOT NULL DEFAULT 0,
    handoff_json          TEXT,
    created_at_ms         INTEGER NOT NULL,
    updated_at_ms         INTEGER NOT NULL,
    last_polled_at_ms     INTEGER,
    stopped_at_ms         INTEGER
);

CREATE UNIQUE INDEX idx_ci_harness_watch_live_pr
    ON ci_harness_watch(workspace_id, owner, repo, pr_number)
    WHERE status != 'stopped';
CREATE INDEX idx_ci_harness_watch_poll
    ON ci_harness_watch(status, last_polled_at_ms);

CREATE TABLE ci_harness_cycle (
    id                    TEXT    NOT NULL PRIMARY KEY,
    watch_id              TEXT    NOT NULL REFERENCES ci_harness_watch(id) ON DELETE CASCADE,
    ordinal               INTEGER NOT NULL,
    trigger_kind          TEXT    NOT NULL,
    steering_text         TEXT CHECK (steering_text IS NULL OR length(steering_text) <= 4000),
    status                TEXT    NOT NULL,
    phase                 TEXT    NOT NULL,
    head_sha              TEXT,
    run_ref               TEXT,
    cost_milli_usd        INTEGER NOT NULL DEFAULT 0,
    backup_ref            TEXT,
    original_head         TEXT,
    net_neutral_proof_json TEXT,
    run_status_tail       TEXT,
    started_at_ms         INTEGER NOT NULL,
    updated_at_ms         INTEGER NOT NULL,
    finished_at_ms        INTEGER,
    error_message         TEXT,
    UNIQUE(watch_id, ordinal)
);

CREATE UNIQUE INDEX idx_ci_harness_cycle_live
    ON ci_harness_cycle(watch_id)
    WHERE status IN ('queued', 'running');
CREATE INDEX idx_ci_harness_cycle_watch_started
    ON ci_harness_cycle(watch_id, started_at_ms DESC);

CREATE TABLE ci_harness_failure (
    id                    TEXT    NOT NULL PRIMARY KEY,
    cycle_id              TEXT    NOT NULL REFERENCES ci_harness_cycle(id) ON DELETE CASCADE,
    run_id                TEXT,
    check_run_id          INTEGER,
    job_name              TEXT    NOT NULL,
    module                TEXT    NOT NULL,
    test_class            TEXT,
    test_method           TEXT,
    signature             TEXT    NOT NULL,
    log_excerpt           TEXT    NOT NULL,
    bucket                TEXT    NOT NULL,
    rule_id               TEXT,
    status                TEXT    NOT NULL,
    target_subject        TEXT,
    diagnosis_json        TEXT,
    fix_json              TEXT,
    verification_json     TEXT,
    created_at_ms         INTEGER NOT NULL,
    updated_at_ms         INTEGER NOT NULL,
    UNIQUE(cycle_id, job_name, signature)
);

CREATE INDEX idx_ci_harness_failure_cycle
    ON ci_harness_failure(cycle_id, created_at_ms);
CREATE INDEX idx_ci_harness_failure_signature
    ON ci_harness_failure(signature);

CREATE TABLE ci_harness_rule (
    id                    TEXT    NOT NULL PRIMARY KEY,
    workspace_id          TEXT    NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    owner                 TEXT    NOT NULL,
    repo                  TEXT    NOT NULL,
    matcher_pattern       TEXT    NOT NULL,
    scope                 TEXT,
    bucket                TEXT    NOT NULL,
    binding               TEXT    NOT NULL,
    recipe_json           TEXT,
    status                TEXT    NOT NULL,
    origin                TEXT    NOT NULL,
    priority              INTEGER NOT NULL,
    evidence_json         TEXT    NOT NULL DEFAULT '[]',
    hits                  INTEGER NOT NULL DEFAULT 0,
    created_at_ms         INTEGER NOT NULL,
    updated_at_ms         INTEGER NOT NULL,
    approved_at_ms        INTEGER,
    UNIQUE(workspace_id, owner, repo, matcher_pattern, scope)
);

CREATE INDEX idx_ci_harness_rule_match
    ON ci_harness_rule(workspace_id, owner, repo, status, priority DESC);
CREATE UNIQUE INDEX idx_ci_harness_rule_identity
    ON ci_harness_rule(workspace_id, owner, repo, matcher_pattern, COALESCE(scope, ''));

CREATE TABLE ci_harness_event (
    id                    INTEGER PRIMARY KEY,
    watch_id              TEXT    NOT NULL REFERENCES ci_harness_watch(id) ON DELETE CASCADE,
    cycle_id              TEXT    REFERENCES ci_harness_cycle(id) ON DELETE CASCADE,
    phase                 TEXT    NOT NULL,
    kind                  TEXT    NOT NULL,
    message               TEXT    NOT NULL,
    detail_json           TEXT    NOT NULL DEFAULT '{}',
    created_at_ms         INTEGER NOT NULL
);

CREATE INDEX idx_ci_harness_event_watch
    ON ci_harness_event(watch_id, created_at_ms DESC);

CREATE TABLE ci_harness_log_cache (
    watch_id              TEXT    NOT NULL REFERENCES ci_harness_watch(id) ON DELETE CASCADE,
    head_sha              TEXT    NOT NULL,
    check_run_id          INTEGER NOT NULL,
    log_text              TEXT    NOT NULL,
    fetched_at_ms         INTEGER NOT NULL,
    PRIMARY KEY(watch_id, head_sha, check_run_id)
);
