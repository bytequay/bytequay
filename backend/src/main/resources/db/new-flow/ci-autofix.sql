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

CREATE TABLE flow_ci_policy_current (
    repository_id TEXT NOT NULL,
    scope_key TEXT NOT NULL,
    policy_revision_id TEXT NOT NULL UNIQUE,
    PRIMARY KEY (repository_id, scope_key),
    FOREIGN KEY (policy_revision_id)
        REFERENCES flow_ci_policy_revision (policy_revision_id)
);

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
            'PENDING', 'ACTIVE', 'NON_CLEAN_HANDOFF',
            'FIX_PREPARED', 'NO_HEAD_CHANGE',
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
        OR (state = 'NON_CLEAN_HANDOFF'
            AND output_local_head IS NULL
            AND output_change_set_revision_id IS NULL
            AND result_ref IS NOT NULL)
        OR (state NOT IN (
                'FIX_PREPARED', 'NO_HEAD_CHANGE', 'NON_CLEAN_HANDOFF')
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

CREATE TABLE flow_ci_cleanup_seal (
    cleanup_id TEXT PRIMARY KEY,
    repair_attempt_id TEXT NOT NULL UNIQUE,
    successor_operation_id TEXT NOT NULL UNIQUE,
    actual_head TEXT NOT NULL,
    branch_head TEXT NOT NULL,
    attachment_state TEXT NOT NULL CHECK (
        attachment_state IN ('ATTACHED', 'DETACHED')
    ),
    kind TEXT NOT NULL CHECK (
        kind IN ('DIRTY', 'GIT_OPERATION_IN_PROGRESS')
    ),
    operations_json TEXT NOT NULL,
    state_digest TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    FOREIGN KEY (repair_attempt_id)
        REFERENCES flow_ci_repair_attempt (attempt_id),
    FOREIGN KEY (successor_operation_id)
        REFERENCES flow_runtime_operation (operation_id)
);

CREATE TABLE flow_ci_cleanup_completion (
    cleanup_id TEXT PRIMARY KEY,
    run_id TEXT UNIQUE,
    result_ref TEXT UNIQUE,
    outcome TEXT NOT NULL CHECK (
        outcome IN (
            'FIX_PREPARED', 'NO_HEAD_CHANGE',
            'NEEDS_ATTENTION', 'ADMISSION_BLOCKED'
        )
    ),
    output_head TEXT,
    output_change_set_revision_id TEXT,
    final_actual_head TEXT,
    final_branch_head TEXT,
    final_attachment_state TEXT CHECK (
        final_attachment_state IS NULL
        OR final_attachment_state IN ('ATTACHED', 'DETACHED')
    ),
    final_kind TEXT CHECK (
        final_kind IS NULL
        OR final_kind IN ('DIRTY', 'GIT_OPERATION_IN_PROGRESS')
    ),
    final_operations_json TEXT,
    final_state_digest TEXT,
    attention_reason TEXT CHECK (
        attention_reason IS NULL
        OR attention_reason IN (
            'SECOND_DIRTY', 'SECOND_GIT_OPERATION_IN_PROGRESS',
            'FINAL_INSPECTION_BLOCKED', 'ADMISSION_SEAL_MISMATCH',
            'ADMISSION_INSPECTION_BLOCKED')
    ),
    inspection_failure_code TEXT,
    completed_at INTEGER NOT NULL,
    CHECK (
        (outcome IN ('FIX_PREPARED', 'NO_HEAD_CHANGE')
            AND run_id IS NOT NULL AND result_ref IS NOT NULL
            AND output_head IS NOT NULL
            AND output_change_set_revision_id IS NOT NULL
            AND final_actual_head IS NULL
            AND final_branch_head IS NULL
            AND final_attachment_state IS NULL
            AND final_kind IS NULL
            AND final_operations_json IS NULL
            AND final_state_digest IS NULL
            AND attention_reason IS NULL
            AND inspection_failure_code IS NULL)
        OR (outcome = 'NEEDS_ATTENTION'
            AND run_id IS NOT NULL AND result_ref IS NOT NULL
            AND output_head IS NULL
            AND output_change_set_revision_id IS NULL
            AND ((final_kind = 'DIRTY'
                    AND attention_reason = 'SECOND_DIRTY'
                    AND final_actual_head IS NOT NULL
                    AND final_branch_head IS NOT NULL
                    AND final_attachment_state IS NOT NULL
                    AND final_operations_json IS NOT NULL
                    AND final_state_digest IS NOT NULL
                    AND inspection_failure_code IS NULL)
                OR (final_kind = 'GIT_OPERATION_IN_PROGRESS'
                    AND attention_reason = 'SECOND_GIT_OPERATION_IN_PROGRESS'
                    AND final_actual_head IS NOT NULL
                    AND final_branch_head IS NOT NULL
                    AND final_attachment_state IS NOT NULL
                    AND final_operations_json IS NOT NULL
                    AND final_state_digest IS NOT NULL
                    AND inspection_failure_code IS NULL)
                OR (attention_reason = 'FINAL_INSPECTION_BLOCKED'
                    AND final_actual_head IS NULL
                    AND final_branch_head IS NULL
                    AND final_attachment_state IS NULL
                    AND final_kind IS NULL
                    AND final_operations_json IS NULL
                    AND final_state_digest IS NULL
                    AND inspection_failure_code IS NOT NULL)))
        OR (outcome = 'ADMISSION_BLOCKED'
            AND ((run_id IS NULL AND result_ref IS NULL)
                OR (run_id IS NOT NULL AND result_ref IS NOT NULL))
            AND output_head IS NULL
            AND output_change_set_revision_id IS NULL
            AND ((attention_reason = 'ADMISSION_SEAL_MISMATCH'
                    AND final_actual_head IS NOT NULL
                    AND final_branch_head IS NOT NULL
                    AND final_attachment_state IS NOT NULL
                    AND final_kind IS NOT NULL
                    AND final_operations_json IS NOT NULL
                    AND final_state_digest IS NOT NULL
                    AND inspection_failure_code IS NULL)
                OR (attention_reason = 'ADMISSION_INSPECTION_BLOCKED'
                    AND final_actual_head IS NULL
                    AND final_branch_head IS NULL
                    AND final_attachment_state IS NULL
                    AND final_kind IS NULL
                    AND final_operations_json IS NULL
                    AND final_state_digest IS NULL
                    AND inspection_failure_code IS NOT NULL)))
    ),
    FOREIGN KEY (cleanup_id)
        REFERENCES flow_ci_cleanup_seal (cleanup_id),
    FOREIGN KEY (run_id)
        REFERENCES flow_runtime_agent_run (run_id),
    FOREIGN KEY (result_ref)
        REFERENCES flow_runtime_agent_result (result_id),
    FOREIGN KEY (output_change_set_revision_id)
        REFERENCES flow_runtime_change_set_revision (change_set_revision_id)
);
