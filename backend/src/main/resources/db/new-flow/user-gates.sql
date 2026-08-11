CREATE TABLE flow_user_gate_local_review_binding (
    binding_id TEXT PRIMARY KEY,
    pr_id TEXT NOT NULL,
    candidate_change_set_revision_id TEXT NOT NULL,
    binding_digest TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    UNIQUE (pr_id, candidate_change_set_revision_id),
    UNIQUE (
        binding_id, pr_id, candidate_change_set_revision_id, binding_digest
    ),
    FOREIGN KEY (pr_id) REFERENCES flow_runtime_pr (pr_id),
    FOREIGN KEY (candidate_change_set_revision_id)
        REFERENCES flow_runtime_change_set_revision (change_set_revision_id)
);

CREATE TABLE flow_user_gate_subject_manifest (
    subject_id TEXT PRIMARY KEY,
    kind TEXT NOT NULL CHECK (kind IN ('INITIAL_PUBLISH', 'CI_UPDATE')),
    subject_digest TEXT NOT NULL UNIQUE,
    UNIQUE (subject_id, subject_digest),
    UNIQUE (subject_id, kind, subject_digest)
);

CREATE TABLE flow_user_gate_action_manifest (
    action_ref TEXT PRIMARY KEY,
    kind TEXT NOT NULL CHECK (kind IN ('INITIAL_PUBLISH', 'CI_UPDATE')),
    action_digest TEXT NOT NULL UNIQUE,
    UNIQUE (action_ref, action_digest),
    UNIQUE (action_ref, kind, action_digest)
);

CREATE TABLE flow_user_gate_subject (
    subject_id TEXT PRIMARY KEY,
    subject_digest TEXT NOT NULL,
    task_id TEXT NOT NULL,
    pr_id TEXT NOT NULL,
    repository_id TEXT NOT NULL,
    head_repository_external_id TEXT NOT NULL,
    head_repository_owner TEXT NOT NULL,
    head_repository_name TEXT NOT NULL,
    branch_ref TEXT NOT NULL,
    expected_remote_head TEXT NOT NULL,
    change_set_revision_id TEXT NOT NULL,
    base_revision_id TEXT NOT NULL,
    base_sha TEXT NOT NULL,
    proposed_head TEXT NOT NULL,
    head_tree_digest TEXT NOT NULL,
    diff_digest TEXT NOT NULL,
    local_check_policy_revision_id TEXT NOT NULL,
    reviewer_request_id TEXT NOT NULL,
    reviewer_run_id TEXT NOT NULL,
    reviewer_result_id TEXT NOT NULL,
    origin_ci_fix_pending_id TEXT NOT NULL,
    origin_ci_fix_source_kind TEXT NOT NULL CHECK (
        origin_ci_fix_source_kind IN ('REPAIR_ATTEMPT', 'CLEANUP')
    ),
    origin_ci_fix_source_id TEXT NOT NULL,
    ci_round_id TEXT NOT NULL,
    required_ci_policy_revision_id TEXT NOT NULL,
    ci_evidence_revision INTEGER NOT NULL CHECK (ci_evidence_revision >= 0),
    repair_attempt_id TEXT NOT NULL,
    repair_result_id TEXT NOT NULL,
    cleanup_id TEXT,
    cleanup_result_id TEXT,
    local_review_owner_present INTEGER NOT NULL CHECK (
        local_review_owner_present IN (0, 1)
    ),
    local_review_binding_id TEXT,
    local_review_batch_refs_json TEXT NOT NULL,
    local_review_revision_refs_json TEXT NOT NULL,
    local_review_digest TEXT NOT NULL,
    ci_memory_refs_json TEXT NOT NULL CHECK (ci_memory_refs_json = '[]'),
    manual_only INTEGER NOT NULL CHECK (manual_only IN (0, 1)),
    created_by_run_id TEXT NOT NULL UNIQUE,
    created_at INTEGER NOT NULL,
    FOREIGN KEY (task_id) REFERENCES flow_runtime_task (task_id),
    FOREIGN KEY (pr_id) REFERENCES flow_runtime_pr (pr_id),
    FOREIGN KEY (change_set_revision_id)
        REFERENCES flow_runtime_change_set_revision (change_set_revision_id),
    FOREIGN KEY (base_revision_id)
        REFERENCES flow_runtime_task_base_revision (base_revision_id),
    FOREIGN KEY (local_check_policy_revision_id)
        REFERENCES flow_runtime_local_check_policy_revision (
            policy_revision_id
        ),
    FOREIGN KEY (reviewer_request_id)
        REFERENCES flow_runtime_reviewer_request (request_id),
    FOREIGN KEY (reviewer_run_id)
        REFERENCES flow_runtime_agent_run (run_id),
    FOREIGN KEY (reviewer_result_id)
        REFERENCES flow_runtime_agent_result (result_id),
    FOREIGN KEY (origin_ci_fix_pending_id)
        REFERENCES flow_runtime_inbox (inbox_id),
    FOREIGN KEY (ci_round_id) REFERENCES flow_ci_round (round_id),
    FOREIGN KEY (required_ci_policy_revision_id)
        REFERENCES flow_ci_policy_revision (policy_revision_id),
    FOREIGN KEY (repair_result_id)
        REFERENCES flow_runtime_agent_result (result_id),
    FOREIGN KEY (repair_attempt_id)
        REFERENCES flow_ci_repair_attempt (attempt_id),
    FOREIGN KEY (cleanup_id)
        REFERENCES flow_ci_cleanup_seal (cleanup_id),
    FOREIGN KEY (cleanup_result_id)
        REFERENCES flow_runtime_agent_result (result_id),
    CHECK ((cleanup_id IS NULL) = (cleanup_result_id IS NULL)),
    CHECK (
        (local_review_owner_present = 0
            AND local_review_binding_id IS NULL
            AND local_review_batch_refs_json = '[]'
            AND local_review_revision_refs_json = '[]')
        OR (local_review_owner_present = 1
            AND local_review_binding_id IS NOT NULL
            AND local_review_batch_refs_json = '[]'
            AND local_review_revision_refs_json = '[]')
    ),
    FOREIGN KEY (
        local_review_binding_id, pr_id, change_set_revision_id,
        local_review_digest
    ) REFERENCES flow_user_gate_local_review_binding (
        binding_id, pr_id, candidate_change_set_revision_id, binding_digest
    ),
    FOREIGN KEY (created_by_run_id)
        REFERENCES flow_runtime_agent_run (run_id),
    FOREIGN KEY (subject_id, subject_digest)
        REFERENCES flow_user_gate_subject_manifest (
            subject_id, subject_digest
        )
);

CREATE TABLE flow_user_gate_subject_local_check (
    subject_id TEXT NOT NULL,
    ordinal INTEGER NOT NULL CHECK (ordinal >= 0),
    check_run_id TEXT NOT NULL,
    profile_id TEXT NOT NULL,
    conclusion TEXT NOT NULL CHECK (
        conclusion IN ('PASSED', 'FAILED', 'UNAVAILABLE')
    ),
    PRIMARY KEY (subject_id, ordinal),
    UNIQUE (subject_id, check_run_id),
    FOREIGN KEY (subject_id)
        REFERENCES flow_user_gate_subject (subject_id),
    FOREIGN KEY (check_run_id)
        REFERENCES flow_runtime_local_check_run (check_run_id)
);

CREATE TABLE flow_user_gate_subject_ci_observation (
    subject_id TEXT NOT NULL,
    ordinal INTEGER NOT NULL CHECK (ordinal >= 0),
    observation_id TEXT NOT NULL,
    PRIMARY KEY (subject_id, ordinal),
    UNIQUE (subject_id, observation_id),
    FOREIGN KEY (subject_id)
        REFERENCES flow_user_gate_subject (subject_id),
    FOREIGN KEY (observation_id)
        REFERENCES flow_ci_check_observation (observation_id)
);

CREATE TABLE flow_user_gate_subject_failed_log (
    subject_id TEXT NOT NULL,
    ordinal INTEGER NOT NULL CHECK (ordinal >= 0),
    log_ref TEXT NOT NULL,
    PRIMARY KEY (subject_id, ordinal),
    UNIQUE (subject_id, log_ref),
    FOREIGN KEY (subject_id)
        REFERENCES flow_user_gate_subject (subject_id),
    FOREIGN KEY (log_ref) REFERENCES flow_ci_log_evidence (log_ref)
);

CREATE TABLE flow_user_gate_subject_warning (
    subject_id TEXT NOT NULL,
    ordinal INTEGER NOT NULL CHECK (ordinal >= 0),
    warning_code TEXT NOT NULL,
    PRIMARY KEY (subject_id, ordinal),
    UNIQUE (subject_id, warning_code),
    FOREIGN KEY (subject_id)
        REFERENCES flow_user_gate_subject (subject_id)
);

CREATE TABLE flow_user_gate_ci_update_action (
    action_ref TEXT PRIMARY KEY,
    head_repository_external_id TEXT NOT NULL,
    head_repository_owner TEXT NOT NULL,
    head_repository_name TEXT NOT NULL,
    branch_ref TEXT NOT NULL,
    expected_remote_head TEXT NOT NULL,
    proposed_head TEXT NOT NULL,
    force_push INTEGER NOT NULL CHECK (force_push = 0),
    action_digest TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    UNIQUE (
        action_ref, head_repository_external_id, head_repository_owner,
        head_repository_name, branch_ref, expected_remote_head,
        proposed_head, force_push, action_digest
    ),
    FOREIGN KEY (action_ref, action_digest)
        REFERENCES flow_user_gate_action_manifest (action_ref, action_digest)
);

CREATE TABLE flow_user_gate_initial_publish_subject (
    subject_id TEXT PRIMARY KEY,
    subject_digest TEXT NOT NULL,
    task_id TEXT NOT NULL,
    pr_id TEXT NOT NULL,
    repository_id TEXT NOT NULL,
    launch_digest TEXT NOT NULL,
    change_set_revision_id TEXT NOT NULL,
    base_revision_id TEXT NOT NULL,
    expected_base_sha TEXT NOT NULL,
    proposed_head TEXT NOT NULL,
    head_tree_digest TEXT NOT NULL,
    diff_digest TEXT NOT NULL,
    draft_revision_id TEXT NOT NULL,
    draft_digest TEXT NOT NULL,
    required_ci_policy_revision_id TEXT NOT NULL,
    local_check_policy_revision_id TEXT NOT NULL,
    reviewer_request_id TEXT NOT NULL,
    reviewer_run_id TEXT NOT NULL,
    reviewer_result_id TEXT NOT NULL,
    local_review_binding_id TEXT NOT NULL,
    local_review_digest TEXT NOT NULL,
    base_repository_external_id TEXT NOT NULL,
    base_repository_owner TEXT NOT NULL,
    base_repository_name TEXT NOT NULL,
    head_repository_external_id TEXT NOT NULL,
    head_repository_owner TEXT NOT NULL,
    head_repository_name TEXT NOT NULL,
    branch_ref TEXT NOT NULL,
    target_base_ref TEXT NOT NULL,
    target_snapshot_id TEXT NOT NULL,
    target_snapshot_digest TEXT NOT NULL,
    created_by_run_id TEXT NOT NULL UNIQUE,
    created_at INTEGER NOT NULL,
    FOREIGN KEY (subject_id, subject_digest)
        REFERENCES flow_user_gate_subject_manifest (
            subject_id, subject_digest
        ),
    FOREIGN KEY (task_id) REFERENCES flow_runtime_task (task_id),
    FOREIGN KEY (task_id, launch_digest)
        REFERENCES flow_runtime_task (task_id, launch_digest),
    FOREIGN KEY (pr_id) REFERENCES flow_runtime_pr (pr_id),
    FOREIGN KEY (change_set_revision_id)
        REFERENCES flow_runtime_change_set_revision (change_set_revision_id),
    FOREIGN KEY (base_revision_id)
        REFERENCES flow_runtime_task_base_revision (base_revision_id),
    FOREIGN KEY (
        draft_revision_id, pr_id, change_set_revision_id,
        proposed_head, draft_digest
    ) REFERENCES flow_runtime_pr_draft_revision (
        draft_revision_id, pr_id, change_set_revision_id,
        head_sha, draft_digest
    ),
    FOREIGN KEY (local_check_policy_revision_id)
        REFERENCES flow_runtime_local_check_policy_revision (
            policy_revision_id
        ),
    FOREIGN KEY (required_ci_policy_revision_id)
        REFERENCES flow_ci_policy_revision (policy_revision_id),
    FOREIGN KEY (reviewer_request_id)
        REFERENCES flow_runtime_reviewer_request (request_id),
    FOREIGN KEY (reviewer_run_id)
        REFERENCES flow_runtime_agent_run (run_id),
    FOREIGN KEY (reviewer_result_id)
        REFERENCES flow_runtime_agent_result (result_id),
    FOREIGN KEY (
        local_review_binding_id, pr_id, change_set_revision_id,
        local_review_digest
    ) REFERENCES flow_user_gate_local_review_binding (
        binding_id, pr_id, candidate_change_set_revision_id, binding_digest
    ),
    FOREIGN KEY (created_by_run_id)
        REFERENCES flow_runtime_agent_run (run_id),
    FOREIGN KEY (
        target_snapshot_id, pr_id, launch_digest, proposed_head,
        required_ci_policy_revision_id, target_snapshot_digest
    )
        REFERENCES flow_github_initial_publish_target_snapshot (
            target_snapshot_id, pr_id, launch_digest, proposed_head,
            required_ci_policy_revision_id, target_snapshot_digest
        )
);

CREATE TABLE flow_user_gate_initial_publish_subject_local_check (
    subject_id TEXT NOT NULL,
    ordinal INTEGER NOT NULL CHECK (ordinal >= 0),
    check_run_id TEXT NOT NULL,
    profile_id TEXT NOT NULL,
    conclusion TEXT NOT NULL CHECK (
        conclusion IN ('PASSED', 'FAILED', 'UNAVAILABLE')
    ),
    PRIMARY KEY (subject_id, ordinal),
    UNIQUE (subject_id, check_run_id),
    FOREIGN KEY (subject_id)
        REFERENCES flow_user_gate_initial_publish_subject (subject_id),
    FOREIGN KEY (check_run_id)
        REFERENCES flow_runtime_local_check_run (check_run_id)
);

CREATE TABLE flow_user_gate_initial_publish_action (
    action_ref TEXT PRIMARY KEY,
    action_digest TEXT NOT NULL,
    pr_id TEXT NOT NULL,
    change_set_revision_id TEXT NOT NULL,
    base_repository_external_id TEXT NOT NULL,
    base_repository_owner TEXT NOT NULL,
    base_repository_name TEXT NOT NULL,
    head_repository_external_id TEXT NOT NULL,
    head_repository_owner TEXT NOT NULL,
    head_repository_name TEXT NOT NULL,
    branch_ref TEXT NOT NULL,
    target_base_ref TEXT NOT NULL,
    expected_base_sha TEXT NOT NULL,
    proposed_head TEXT NOT NULL,
    draft_revision_id TEXT NOT NULL,
    draft_digest TEXT NOT NULL,
    required_ci_policy_revision_id TEXT NOT NULL,
    ready_policy TEXT NOT NULL CHECK (
        ready_policy IN ('KEEP_DRAFT', 'MARK_READY_ON_EXACT_GREEN')
    ),
    target_snapshot_id TEXT NOT NULL,
    target_snapshot_digest TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    UNIQUE (action_ref, action_digest),
    UNIQUE (
        action_ref, pr_id, change_set_revision_id, proposed_head,
        draft_revision_id, draft_digest, action_digest
    ),
    UNIQUE (
        action_ref, action_digest, pr_id, change_set_revision_id,
        base_repository_external_id, base_repository_owner,
        base_repository_name, head_repository_external_id,
        head_repository_owner, head_repository_name, branch_ref,
        target_base_ref, expected_base_sha, proposed_head,
        draft_revision_id, draft_digest, required_ci_policy_revision_id,
        ready_policy, target_snapshot_id, target_snapshot_digest
    ),
    FOREIGN KEY (action_ref, action_digest)
        REFERENCES flow_user_gate_action_manifest (action_ref, action_digest),
    FOREIGN KEY (
        draft_revision_id, pr_id, change_set_revision_id,
        proposed_head, draft_digest
    )
        REFERENCES flow_runtime_pr_draft_revision (
            draft_revision_id, pr_id, change_set_revision_id,
            head_sha, draft_digest
        ),
    FOREIGN KEY (required_ci_policy_revision_id)
        REFERENCES flow_ci_policy_revision (policy_revision_id),
    FOREIGN KEY (
        target_snapshot_id, pr_id, target_snapshot_digest
    )
        REFERENCES flow_github_initial_publish_target_snapshot (
            target_snapshot_id, pr_id, target_snapshot_digest
        )
);

CREATE TABLE flow_user_gate (
    gate_id TEXT PRIMARY KEY,
    task_id TEXT NOT NULL,
    pr_id TEXT NOT NULL,
    kind TEXT NOT NULL CHECK (kind IN ('INITIAL_PUBLISH', 'CI_UPDATE')),
    current_revision INTEGER NOT NULL CHECK (current_revision > 0),
    created_at INTEGER NOT NULL,
    UNIQUE (pr_id, kind),
    UNIQUE (gate_id, kind),
    FOREIGN KEY (task_id) REFERENCES flow_runtime_task (task_id),
    FOREIGN KEY (pr_id) REFERENCES flow_runtime_pr (pr_id)
);

CREATE TABLE flow_user_gate_revision (
    gate_id TEXT NOT NULL,
    kind TEXT NOT NULL CHECK (kind IN ('INITIAL_PUBLISH', 'CI_UPDATE')),
    revision INTEGER NOT NULL CHECK (revision > 0),
    subject_manifest_ref TEXT NOT NULL,
    subject_digest TEXT NOT NULL,
    action_manifest_ref TEXT NOT NULL,
    action_digest TEXT NOT NULL,
    -- Only CI_UPDATE is produced by the existing ready-for-review terminal
    -- command. INITIAL_PUBLISH deliberately has no model terminal source.
    readiness_evidence_ref TEXT,
    created_by_run_id TEXT NOT NULL UNIQUE,
    created_at INTEGER NOT NULL,
    PRIMARY KEY (gate_id, revision),
    UNIQUE (gate_id, revision, subject_digest, action_digest),
    FOREIGN KEY (gate_id, kind) REFERENCES flow_user_gate (gate_id, kind),
    FOREIGN KEY (subject_manifest_ref, kind, subject_digest)
        REFERENCES flow_user_gate_subject_manifest (
            subject_id, kind, subject_digest
        ),
    FOREIGN KEY (action_manifest_ref, kind, action_digest)
        REFERENCES flow_user_gate_action_manifest (
            action_ref, kind, action_digest
        ),
    FOREIGN KEY (readiness_evidence_ref)
        REFERENCES flow_runtime_ready_for_review_request (request_id),
    FOREIGN KEY (created_by_run_id)
        REFERENCES flow_runtime_agent_run (run_id),
    CHECK (
        (kind = 'CI_UPDATE' AND readiness_evidence_ref IS NOT NULL)
        OR (kind = 'INITIAL_PUBLISH' AND readiness_evidence_ref IS NULL)
    )
);

CREATE TABLE flow_user_gate_ci_consent_revision (
    consent_id TEXT NOT NULL,
    revision INTEGER NOT NULL CHECK (revision > 0),
    task_id TEXT NOT NULL,
    pr_id TEXT NOT NULL,
    repository_id TEXT NOT NULL,
    remote_identity_id TEXT NOT NULL,
    provider TEXT NOT NULL CHECK (provider = 'GITHUB'),
    head_repository_external_id TEXT NOT NULL,
    head_repository_owner TEXT NOT NULL,
    head_repository_name TEXT NOT NULL,
    branch_name TEXT NOT NULL,
    branch_ref TEXT NOT NULL,
    enabled INTEGER NOT NULL CHECK (enabled IN (0, 1)),
    expires_at INTEGER NOT NULL,
    actor_id TEXT NOT NULL CHECK (actor_id = 'LOCAL_DESKTOP_USER'),
    idempotency_key TEXT NOT NULL,
    revision_digest TEXT NOT NULL,
    recorded_at INTEGER NOT NULL,
    PRIMARY KEY (consent_id, revision),
    UNIQUE (actor_id, idempotency_key),
    UNIQUE (consent_id, revision, revision_digest),
    UNIQUE (consent_id, revision, task_id),
    FOREIGN KEY (task_id) REFERENCES flow_runtime_task (task_id),
    CHECK (branch_ref = 'refs/heads/' || branch_name),
    FOREIGN KEY (
        pr_id, task_id, repository_id, branch_name, remote_identity_id
    ) REFERENCES flow_runtime_pr (
        pr_id, task_id, repository_id, branch_name, remote_identity_id
    ),
    FOREIGN KEY (
        remote_identity_id, provider, head_repository_external_id,
        head_repository_owner, head_repository_name
    ) REFERENCES flow_runtime_remote_identity (
        remote_identity_id, provider, head_repository_external_id,
        head_repository_owner, head_repository_name
    )
);

CREATE TABLE flow_user_gate_ci_consent_current (
    consent_id TEXT PRIMARY KEY,
    task_id TEXT NOT NULL UNIQUE,
    current_revision INTEGER NOT NULL CHECK (current_revision > 0),
    FOREIGN KEY (consent_id, current_revision, task_id)
        REFERENCES flow_user_gate_ci_consent_revision (
            consent_id, revision, task_id
        )
);

CREATE TABLE flow_user_gate_authorization (
    authorization_id TEXT PRIMARY KEY,
    gate_id TEXT NOT NULL,
    gate_revision INTEGER NOT NULL,
    pr_id TEXT NOT NULL,
    subject_digest TEXT NOT NULL,
    action_digest TEXT NOT NULL,
    authority TEXT NOT NULL CHECK (
        authority IN ('USER', 'CI_UPDATE_CONSENT')
    ),
    actor_id TEXT NOT NULL,
    consent_id TEXT,
    consent_revision INTEGER,
    consent_digest TEXT,
    idempotency_key TEXT NOT NULL,
    operation_id TEXT NOT NULL UNIQUE,
    effect_plan_ref TEXT NOT NULL UNIQUE,
    authorized_at INTEGER NOT NULL,
    UNIQUE (actor_id, idempotency_key),
    UNIQUE (gate_id, gate_revision),
    UNIQUE (consent_id, consent_revision),
    UNIQUE (
        authorization_id, effect_plan_ref, operation_id, pr_id, action_digest
    ),
    UNIQUE (
        authorization_id, gate_id, gate_revision,
        subject_digest, action_digest
    ),
    CHECK (
        (authority = 'USER'
            AND actor_id = 'LOCAL_DESKTOP_USER'
            AND consent_id IS NULL
            AND consent_revision IS NULL
            AND consent_digest IS NULL)
        OR (authority = 'CI_UPDATE_CONSENT'
            AND actor_id = 'USER_GATES_CI_CONSENT'
            AND consent_id IS NOT NULL
            AND consent_revision IS NOT NULL
            AND consent_digest IS NOT NULL)
    ),
    FOREIGN KEY (gate_id, gate_revision, subject_digest, action_digest)
        REFERENCES flow_user_gate_revision (
            gate_id, revision, subject_digest, action_digest
        ),
    FOREIGN KEY (pr_id) REFERENCES flow_runtime_pr (pr_id),
    FOREIGN KEY (consent_id, consent_revision, consent_digest)
        REFERENCES flow_user_gate_ci_consent_revision (
            consent_id, revision, revision_digest
        ),
    FOREIGN KEY (operation_id)
        REFERENCES flow_runtime_operation (operation_id)
);

CREATE TABLE flow_user_gate_transition (
    gate_id TEXT NOT NULL,
    gate_revision INTEGER NOT NULL,
    sequence INTEGER NOT NULL CHECK (sequence > 0),
    from_state TEXT CHECK (
        from_state IN (
            'OPEN', 'AUTHORIZED', 'EXECUTING', 'NEEDS_ATTENTION',
            'CONSUMED', 'STALE'
        )
    ),
    to_state TEXT NOT NULL CHECK (
        to_state IN (
            'OPEN', 'AUTHORIZED', 'EXECUTING', 'NEEDS_ATTENTION',
            'CONSUMED', 'STALE'
        )
    ),
    actor_type TEXT NOT NULL CHECK (actor_type IN ('PROGRAM', 'USER')),
    actor_id TEXT NOT NULL,
    reason_code TEXT NOT NULL,
    detail_ref TEXT,
    recorded_at INTEGER NOT NULL,
    PRIMARY KEY (gate_id, sequence),
    FOREIGN KEY (gate_id, gate_revision)
        REFERENCES flow_user_gate_revision (gate_id, revision),
    CHECK (
        (actor_type = 'USER' AND actor_id = 'LOCAL_DESKTOP_USER')
        OR (actor_type = 'PROGRAM'
            AND actor_id <> 'LOCAL_DESKTOP_USER')
    ),
    CHECK (
        (from_state IS NULL AND to_state = 'OPEN' AND reason_code = 'READY')
        OR (from_state = 'OPEN' AND to_state = 'STALE'
            AND reason_code IN (
                'SUPERSEDED_BY_READY', 'AUTHORIZATION_STALE'
            ))
        OR (from_state = 'OPEN' AND to_state = 'AUTHORIZED'
            AND ((actor_type = 'USER'
                    AND reason_code = 'MANUAL_AUTHORIZATION')
                OR (actor_type = 'PROGRAM'
                    AND reason_code = 'CI_UPDATE_CONSENT_AUTHORIZATION'))
            AND detail_ref IS NOT NULL)
        OR (from_state = 'AUTHORIZED' AND to_state = 'EXECUTING'
            AND actor_type = 'PROGRAM'
            AND reason_code = 'EFFECT_BEGIN'
            AND detail_ref IS NOT NULL)
        OR (from_state = 'EXECUTING' AND to_state = 'AUTHORIZED'
            AND actor_type = 'PROGRAM'
            AND reason_code IN ('NEVER_STARTED_REDRIVE', 'EFFECT_RETRY')
            AND detail_ref IS NOT NULL)
        OR (from_state = 'NEEDS_ATTENTION' AND to_state = 'AUTHORIZED'
            AND actor_type = 'PROGRAM'
            AND reason_code = 'EFFECT_RETRY'
            AND detail_ref IS NOT NULL)
        OR (from_state = 'EXECUTING' AND to_state = 'NEEDS_ATTENTION'
            AND actor_type = 'PROGRAM'
            AND reason_code IN (
                'EFFECT_UNKNOWN', 'EFFECT_PREPARATION_UNAVAILABLE',
                'EFFECT_PROBE_UNAVAILABLE',
                'EFFECT_PREPARATION_INVALID', 'EFFECT_DIVERGED',
                'EFFECT_ATTEMPT_LIMIT')
            AND detail_ref IS NOT NULL)
        OR (from_state = 'AUTHORIZED' AND to_state = 'NEEDS_ATTENTION'
            AND actor_type = 'PROGRAM'
            AND reason_code = 'EFFECT_AUTHORITY_UNPROVEN'
            AND detail_ref IS NOT NULL)
        OR (from_state IN ('AUTHORIZED', 'EXECUTING', 'NEEDS_ATTENTION')
            AND to_state = 'STALE'
            AND actor_type = 'PROGRAM'
            AND reason_code IN (
                'EFFECT_STALE', 'EFFECT_PREPARATION_INVALID',
                'EFFECT_DIVERGED')
            AND detail_ref IS NOT NULL)
        OR (from_state IN ('EXECUTING', 'NEEDS_ATTENTION')
            AND to_state = 'CONSUMED'
            AND actor_type = 'PROGRAM'
            AND reason_code = 'EFFECT_APPLIED'
            AND detail_ref IS NOT NULL)
    )
);
