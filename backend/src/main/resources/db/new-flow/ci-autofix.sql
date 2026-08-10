CREATE TABLE flow_ci_policy_revision (
    policy_revision_id TEXT PRIMARY KEY,
    repository_id TEXT NOT NULL,
    scope_key TEXT NOT NULL,
    target_base_ref TEXT NOT NULL,
    sequence INTEGER NOT NULL,
    resolution TEXT NOT NULL CHECK (resolution IN ('RESOLVED', 'UNAVAILABLE')),
    source_ref TEXT,
    source_digest TEXT,
    unavailable_reason_ref TEXT,
    required_check_selectors_json TEXT NOT NULL,
    accepted_conclusions_json TEXT NOT NULL,
    recorded_at INTEGER NOT NULL,
    UNIQUE (repository_id, scope_key, sequence)
);

CREATE INDEX flow_ci_policy_current_idx
    ON flow_ci_policy_revision (repository_id, scope_key, sequence DESC);

CREATE TABLE flow_ci_check_observation (
    observation_id TEXT PRIMARY KEY,
    pr_id TEXT NOT NULL,
    head_sha TEXT NOT NULL,
    selector_key TEXT NOT NULL,
    provider_check_id TEXT NOT NULL,
    provider_run_id TEXT NOT NULL,
    attempt INTEGER NOT NULL,
    provider_state_revision TEXT NOT NULL,
    name TEXT NOT NULL,
    status TEXT NOT NULL,
    conclusion TEXT,
    started_at INTEGER,
    completed_at INTEGER,
    observed_at INTEGER NOT NULL,
    raw_evidence_ref TEXT NOT NULL,
    UNIQUE (
        pr_id,
        selector_key,
        provider_check_id,
        provider_run_id,
        attempt,
        provider_state_revision
    )
);

CREATE INDEX flow_ci_observation_head_idx
    ON flow_ci_check_observation (
        pr_id,
        head_sha,
        selector_key,
        observed_at DESC
    );

CREATE TABLE flow_ci_log_evidence (
    log_ref TEXT PRIMARY KEY,
    observation_id TEXT NOT NULL UNIQUE,
    content_digest TEXT NOT NULL,
    exposed_content_digest TEXT NOT NULL,
    raw_byte_count INTEGER NOT NULL,
    stored_byte_count INTEGER NOT NULL,
    truncated INTEGER NOT NULL CHECK (truncated IN (0, 1)),
    sanitized_content BLOB NOT NULL,
    stored_at INTEGER NOT NULL,
    FOREIGN KEY (observation_id)
        REFERENCES flow_ci_check_observation (observation_id)
);

CREATE TABLE flow_ci_round (
    round_id TEXT PRIMARY KEY,
    task_id TEXT NOT NULL,
    pr_id TEXT NOT NULL,
    remote_head TEXT NOT NULL,
    policy_revision_id TEXT NOT NULL,
    evidence_revision INTEGER NOT NULL,
    check_observation_ids_json TEXT NOT NULL,
    failed_log_refs_json TEXT NOT NULL,
    state TEXT NOT NULL CHECK (
        state IN (
            'COLLECTING',
            'FINAL_RED',
            'QUEUED',
            'ACTIVE',
            'FIX_PREPARED',
            'GREEN',
            'SUPERSEDED',
            'NEEDS_ATTENTION'
        )
    ),
    created_at INTEGER NOT NULL,
    superseded_by TEXT,
    UNIQUE (pr_id, remote_head, policy_revision_id, evidence_revision),
    FOREIGN KEY (policy_revision_id)
        REFERENCES flow_ci_policy_revision (policy_revision_id),
    FOREIGN KEY (superseded_by)
        REFERENCES flow_ci_round (round_id)
);

CREATE INDEX flow_ci_round_current_idx
    ON flow_ci_round (
        pr_id, remote_head, policy_revision_id, evidence_revision DESC
    );

CREATE TABLE flow_ci_repair_attempt (
    attempt_id TEXT PRIMARY KEY,
    round_id TEXT NOT NULL,
    operation_id TEXT UNIQUE,
    agent_run_id TEXT UNIQUE,
    input_local_head TEXT NOT NULL,
    input_remote_head TEXT NOT NULL,
    input_change_set_revision_id TEXT NOT NULL,
    output_local_head TEXT,
    output_change_set_revision_id TEXT,
    local_check_run_ids_json TEXT NOT NULL,
    result_ref TEXT,
    state TEXT NOT NULL CHECK (
        state IN (
            'PENDING', 'ACTIVE', 'FIX_PREPARED', 'NO_HEAD_CHANGE',
            'NEEDS_ATTENTION'
        )
    ),
    retry_of_attempt_id TEXT,
    retry_ordinal INTEGER NOT NULL CHECK (retry_ordinal >= 0),
    created_at INTEGER NOT NULL,
    CHECK (
        (state = 'PENDING' AND operation_id IS NULL AND agent_run_id IS NULL)
        OR (state <> 'PENDING'
            AND operation_id IS NOT NULL AND agent_run_id IS NOT NULL)
    ),
    CHECK (
        (state IN ('FIX_PREPARED', 'NO_HEAD_CHANGE')
            AND output_local_head IS NOT NULL
            AND output_change_set_revision_id IS NOT NULL
            AND result_ref IS NOT NULL)
        OR (state NOT IN ('FIX_PREPARED', 'NO_HEAD_CHANGE')
            AND output_local_head IS NULL
            AND output_change_set_revision_id IS NULL
            AND result_ref IS NULL)
    ),
    CHECK (
        state NOT IN ('FIX_PREPARED', 'NO_HEAD_CHANGE')
        OR (state = 'NO_HEAD_CHANGE'
            AND output_local_head = input_local_head)
        OR (state = 'FIX_PREPARED'
            AND output_local_head <> input_local_head)
    ),
    UNIQUE (round_id, retry_ordinal),
    FOREIGN KEY (round_id) REFERENCES flow_ci_round (round_id),
    FOREIGN KEY (operation_id)
        REFERENCES flow_runtime_operation (operation_id),
    FOREIGN KEY (agent_run_id)
        REFERENCES flow_runtime_agent_run (run_id),
    FOREIGN KEY (input_change_set_revision_id)
        REFERENCES flow_runtime_change_set_revision (change_set_revision_id),
    FOREIGN KEY (output_change_set_revision_id)
        REFERENCES flow_runtime_change_set_revision (change_set_revision_id),
    FOREIGN KEY (result_ref)
        REFERENCES flow_runtime_agent_result (result_id),
    FOREIGN KEY (retry_of_attempt_id)
        REFERENCES flow_ci_repair_attempt (attempt_id)
);

CREATE UNIQUE INDEX flow_ci_one_attempt_retry
    ON flow_ci_repair_attempt (retry_of_attempt_id)
    WHERE retry_of_attempt_id IS NOT NULL;
