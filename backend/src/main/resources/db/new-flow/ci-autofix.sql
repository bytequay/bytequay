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
    source_operation_id TEXT,
    source_receipt_id TEXT,
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
    CHECK (
        (source_operation_id IS NULL AND source_receipt_id IS NULL)
        OR (source_operation_id IS NOT NULL AND source_receipt_id IS NOT NULL)
    ),
    FOREIGN KEY (source_operation_id)
        REFERENCES flow_runtime_operation (operation_id),
    FOREIGN KEY (source_receipt_id)
        REFERENCES flow_github_effect_receipt_envelope (receipt_id)
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
    UNIQUE (log_ref, content_digest),
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
    source_observation_operation_id TEXT,
    source_receipt_id TEXT,
    check_observation_ids_json TEXT NOT NULL,
    failed_log_refs_json TEXT NOT NULL,
    state TEXT NOT NULL CHECK (
        state IN (
            'COLLECTING',
            -- A per-commit compile check failed while the rest of the board is
            -- still collecting. A compile failure is deterministic, so nothing
            -- finishing later can change its verdict; nothing else is judged
            -- before its selectors are terminal.
            'PARTIAL_RED_COMPILE',
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
    CHECK (
        (source_observation_operation_id IS NULL
            AND source_receipt_id IS NULL)
        OR (source_observation_operation_id IS NOT NULL
            AND source_receipt_id IS NOT NULL)
    ),
    UNIQUE (pr_id, remote_head, policy_revision_id, evidence_revision),
    FOREIGN KEY (policy_revision_id)
        REFERENCES flow_ci_policy_revision (policy_revision_id),
    FOREIGN KEY (source_observation_operation_id)
        REFERENCES flow_runtime_operation (operation_id),
    FOREIGN KEY (source_receipt_id)
        REFERENCES flow_github_effect_receipt_envelope (receipt_id),
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

CREATE TABLE flow_ci_learning_subject (
    subject_id TEXT PRIMARY KEY,
    operation_id TEXT NOT NULL UNIQUE,
    task_id TEXT NOT NULL,
    pr_id TEXT NOT NULL,
    repository_id TEXT NOT NULL,
    receipt_id TEXT NOT NULL UNIQUE,
    receipt_digest TEXT NOT NULL,
    publication_operation_id TEXT NOT NULL UNIQUE,
    head_repository_external_id TEXT NOT NULL,
    head_repository_owner TEXT NOT NULL,
    head_repository_name TEXT NOT NULL,
    branch_ref TEXT NOT NULL,
    expected_remote_head TEXT NOT NULL,
    plan_id TEXT NOT NULL UNIQUE,
    plan_digest TEXT NOT NULL,
    authorization_id TEXT NOT NULL UNIQUE,
    gate_id TEXT NOT NULL,
    gate_revision INTEGER NOT NULL CHECK (gate_revision > 0),
    gate_subject_digest TEXT NOT NULL,
    gate_action_digest TEXT NOT NULL,
    publication_policy_revision_id TEXT NOT NULL,
    published_head TEXT NOT NULL,
    green_round_id TEXT NOT NULL UNIQUE,
    green_policy_revision_id TEXT NOT NULL,
    green_evidence_revision INTEGER NOT NULL CHECK (
        green_evidence_revision >= 0
    ),
    green_observation_operation_id TEXT NOT NULL,
    red_round_id TEXT NOT NULL,
    repair_attempt_id TEXT NOT NULL,
    repair_result_id TEXT NOT NULL,
    repair_result_digest TEXT NOT NULL,
    cleanup_id TEXT,
    cleanup_result_id TEXT,
    cleanup_result_digest TEXT,
    output_change_set_revision_id TEXT NOT NULL,
    output_diff_digest TEXT NOT NULL,
    subject_digest TEXT NOT NULL UNIQUE,
    created_at INTEGER NOT NULL,
    CHECK ((cleanup_id IS NULL) = (cleanup_result_id IS NULL)),
    CHECK ((cleanup_result_id IS NULL) = (cleanup_result_digest IS NULL)),
    FOREIGN KEY (operation_id)
        REFERENCES flow_runtime_operation (operation_id),
    FOREIGN KEY (publication_operation_id)
        REFERENCES flow_runtime_operation (operation_id),
    FOREIGN KEY (task_id) REFERENCES flow_runtime_task (task_id),
    FOREIGN KEY (pr_id) REFERENCES flow_runtime_pr (pr_id),
    FOREIGN KEY (
        receipt_id, publication_operation_id, plan_id, receipt_digest,
        head_repository_external_id, head_repository_owner,
        head_repository_name, branch_ref, expected_remote_head, published_head
    ) REFERENCES flow_github_external_effect_receipt (
        receipt_id, operation_id, plan_id, receipt_digest,
        head_repository_external_id, head_repository_owner,
        head_repository_name, branch_ref, expected_remote_head, proposed_head
    ),
    FOREIGN KEY (
        plan_id, authorization_id, plan_digest,
        publication_policy_revision_id
    ) REFERENCES flow_github_external_effect_plan (
        plan_id, authorization_id, plan_digest,
        required_ci_policy_revision_id
    ),
    FOREIGN KEY (
        authorization_id, gate_id, gate_revision,
        gate_subject_digest, gate_action_digest
    ) REFERENCES flow_user_gate_authorization (
        authorization_id, gate_id, gate_revision,
        subject_digest, action_digest
    ),
    FOREIGN KEY (green_round_id)
        REFERENCES flow_ci_round (round_id),
    FOREIGN KEY (green_policy_revision_id)
        REFERENCES flow_ci_policy_revision (policy_revision_id),
    FOREIGN KEY (green_observation_operation_id)
        REFERENCES flow_runtime_operation (operation_id),
    FOREIGN KEY (red_round_id) REFERENCES flow_ci_round (round_id),
    FOREIGN KEY (repair_attempt_id)
        REFERENCES flow_ci_repair_attempt (attempt_id),
    FOREIGN KEY (repair_result_id)
        REFERENCES flow_runtime_agent_result (result_id),
    FOREIGN KEY (cleanup_id)
        REFERENCES flow_ci_cleanup_seal (cleanup_id),
    FOREIGN KEY (cleanup_result_id)
        REFERENCES flow_runtime_agent_result (result_id),
    FOREIGN KEY (output_change_set_revision_id)
        REFERENCES flow_runtime_change_set_revision (change_set_revision_id)
);

CREATE TABLE flow_ci_learning_green_observation (
    subject_id TEXT NOT NULL,
    ordinal INTEGER NOT NULL CHECK (ordinal >= 0),
    observation_id TEXT NOT NULL,
    evidence_digest TEXT NOT NULL,
    PRIMARY KEY (subject_id, ordinal),
    UNIQUE (subject_id, observation_id),
    FOREIGN KEY (subject_id)
        REFERENCES flow_ci_learning_subject (subject_id),
    FOREIGN KEY (observation_id)
        REFERENCES flow_ci_check_observation (observation_id)
);

CREATE TABLE flow_ci_learning_failed_log (
    subject_id TEXT NOT NULL,
    ordinal INTEGER NOT NULL CHECK (ordinal >= 0),
    log_ref TEXT NOT NULL,
    content_digest TEXT NOT NULL,
    PRIMARY KEY (subject_id, ordinal),
    UNIQUE (subject_id, log_ref),
    FOREIGN KEY (subject_id)
        REFERENCES flow_ci_learning_subject (subject_id),
    FOREIGN KEY (log_ref, content_digest)
        REFERENCES flow_ci_log_evidence (log_ref, content_digest)
);

CREATE TABLE flow_ci_lesson (
    lesson_id TEXT PRIMARY KEY,
    repository_id TEXT NOT NULL,
    learning_operation_id TEXT NOT NULL UNIQUE,
    run_id TEXT NOT NULL UNIQUE,
    subject_id TEXT NOT NULL UNIQUE,
    status TEXT NOT NULL CHECK (status = 'CANDIDATE'),
    title TEXT NOT NULL,
    markdown TEXT NOT NULL,
    content_digest TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    FOREIGN KEY (learning_operation_id)
        REFERENCES flow_runtime_operation (operation_id),
    FOREIGN KEY (run_id) REFERENCES flow_runtime_agent_run (run_id),
    FOREIGN KEY (subject_id)
        REFERENCES flow_ci_learning_subject (subject_id)
);

CREATE TABLE flow_ci_learning_lesson_request (
    operation_id TEXT PRIMARY KEY,
    run_id TEXT NOT NULL UNIQUE,
    subject_id TEXT NOT NULL UNIQUE,
    process_attempt_id TEXT NOT NULL UNIQUE,
    title TEXT NOT NULL,
    markdown TEXT NOT NULL,
    content_digest TEXT NOT NULL,
    sealed_at INTEGER NOT NULL,
    FOREIGN KEY (operation_id)
        REFERENCES flow_runtime_operation (operation_id),
    FOREIGN KEY (run_id) REFERENCES flow_runtime_agent_run (run_id),
    FOREIGN KEY (subject_id)
        REFERENCES flow_ci_learning_subject (subject_id),
    FOREIGN KEY (process_attempt_id)
        REFERENCES flow_runtime_agent_process_attempt (process_attempt_id)
);

CREATE TABLE flow_ci_learning_completion (
    operation_id TEXT PRIMARY KEY,
    run_id TEXT NOT NULL UNIQUE,
    result_id TEXT NOT NULL UNIQUE,
    state TEXT NOT NULL CHECK (state IN ('CANDIDATE', 'MISSED')),
    lesson_id TEXT UNIQUE,
    reason_code TEXT NOT NULL,
    completed_at INTEGER NOT NULL,
    CHECK (
        (state = 'CANDIDATE' AND lesson_id IS NOT NULL)
        OR (state = 'MISSED' AND lesson_id IS NULL)
    ),
    FOREIGN KEY (operation_id)
        REFERENCES flow_runtime_operation (operation_id),
    FOREIGN KEY (run_id) REFERENCES flow_runtime_agent_run (run_id),
    FOREIGN KEY (result_id)
        REFERENCES flow_runtime_agent_result (result_id),
    FOREIGN KEY (lesson_id) REFERENCES flow_ci_lesson (lesson_id)
);

-- Where this component's own repair commits land, resolved by the program once
-- per Task and never afterwards: the placement decides how every round of that
-- Task publishes, so changing it under a live series would leave a branch shaped
-- two different ways. A Task with no row is TIP, which is the ordinary Task
-- behaviour and reads this table only to find nothing.
CREATE TABLE flow_ci_repair_placement (
    task_id TEXT PRIMARY KEY,
    placement TEXT NOT NULL CHECK (
        placement IN ('TIP', 'ATTRIBUTED_FIXUP')
    ),
    per_commit_compile_selectors_json TEXT NOT NULL,
    -- The exact repository CI configuration a compile selector was read from.
    -- A selector without that citation cannot be stored, which is what keeps
    -- the identification out of reach of a check-name heuristic: a guessed
    -- selector would excuse red checks it has no business excusing.
    compile_source_ref TEXT,
    compile_source_digest TEXT,
    allows_history_rewrite INTEGER NOT NULL CHECK (
        allows_history_rewrite IN (0, 1)
    ),
    -- The per-commit build this program runs at each boundary of a series it
    -- rewrote. Empty means it cannot prove a rewrite, and therefore cannot
    -- publish one.
    boundary_build_command_json TEXT NOT NULL DEFAULT '[]',
    recorded_at INTEGER NOT NULL,
    CHECK ((compile_source_ref IS NULL) = (compile_source_digest IS NULL)),
    CHECK (
        per_commit_compile_selectors_json = '[]'
        OR compile_source_ref IS NOT NULL
    )
);

-- The program's own evidence that a rewritten series compiles where it matters,
-- produced by one rebase whose boundary builds it generated. A per-commit
-- compile check reports one red for the whole series, so a target whose repair
-- lives in the fixup after it is red in isolation by construction; this is the
-- only thing that may excuse that red, and it is never a reading of a remote
-- log. No proof, no exception.
CREATE TABLE flow_ci_boundary_compile_proof (
    proof_id TEXT PRIMARY KEY,
    task_id TEXT NOT NULL,
    attempt_id TEXT NOT NULL,
    head_sha TEXT NOT NULL,
    profile_revision_id TEXT NOT NULL,
    proved_at INTEGER NOT NULL,
    UNIQUE (attempt_id, head_sha),
    FOREIGN KEY (attempt_id)
        REFERENCES flow_ci_repair_attempt (attempt_id)
);

CREATE TABLE flow_ci_boundary_compile_result (
    proof_id TEXT NOT NULL,
    ordinal INTEGER NOT NULL CHECK (ordinal >= 0),
    commit_sha TEXT NOT NULL,
    -- A bare target followed by its fixup is deliberately not a boundary, which
    -- is what makes the acceptance exception provable rather than assumed.
    kind TEXT NOT NULL CHECK (
        kind IN ('TARGET_WITH_FIXUP', 'FIXUP', 'PLAIN')
    ),
    exit_state TEXT NOT NULL CHECK (exit_state IN ('PASSED', 'FAILED')),
    evidence_ref TEXT NOT NULL,
    PRIMARY KEY (proof_id, ordinal),
    UNIQUE (proof_id, commit_sha),
    FOREIGN KEY (proof_id)
        REFERENCES flow_ci_boundary_compile_proof (proof_id)
);
