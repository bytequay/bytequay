CREATE TABLE flow_runtime_task (
    task_id TEXT PRIMARY KEY,
    request_key TEXT NOT NULL UNIQUE,
    repository_id TEXT NOT NULL,
    repository_owner TEXT NOT NULL,
    repository_name TEXT NOT NULL,
    goal_text TEXT NOT NULL,
    repository_root TEXT NOT NULL,
    git_common_dir TEXT NOT NULL,
    remote_name TEXT NOT NULL,
    base_ref TEXT NOT NULL,
    launch_digest TEXT NOT NULL UNIQUE,
    status TEXT NOT NULL CHECK (
        status IN (
            'CREATED',
            'ACTIVE',
            'WAITING_USER',
            'NEEDS_ATTENTION',
            'COMPLETED',
            'CANCELED'
        )
    ),
    epoch INTEGER NOT NULL DEFAULT 1,
    launch_base_sha TEXT,
    current_base_sha TEXT,
    current_base_revision_id TEXT,
    branch_name TEXT NOT NULL UNIQUE,
    worktree_path TEXT NOT NULL UNIQUE,
    current_head_sha TEXT,
    current_change_set_revision_id TEXT,
    task_session_id TEXT UNIQUE,
    ci_session_id TEXT UNIQUE,
    pr_id TEXT UNIQUE,
    current_lifecycle_revision_id TEXT,
    pending_work_watermark INTEGER NOT NULL DEFAULT 0,
    last_reconciled_work_watermark INTEGER NOT NULL DEFAULT 0,
    reconciliation_sequence INTEGER NOT NULL DEFAULT 0,
    selected_writer_operation_id TEXT,
    waiting_mutation_state_ref TEXT,
    writer_fence_sequence INTEGER NOT NULL DEFAULT 0,
    UNIQUE (task_id, launch_digest),
    FOREIGN KEY (current_base_revision_id)
        REFERENCES flow_runtime_task_base_revision (base_revision_id),
    FOREIGN KEY (current_change_set_revision_id)
        REFERENCES flow_runtime_change_set_revision (change_set_revision_id)
);

CREATE TABLE flow_runtime_task_base_revision (
    base_revision_id TEXT PRIMARY KEY,
    task_id TEXT NOT NULL,
    sequence INTEGER NOT NULL CHECK (sequence > 0),
    previous_base_sha TEXT,
    base_sha TEXT NOT NULL,
    reason_code TEXT NOT NULL CHECK (
        reason_code IN (
            'INITIAL',
            'UPSTREAM_TARGET_INTEGRATION',
            'EXPLICIT_RECONCILIATION'
        )
    ),
    evidence_ref TEXT NOT NULL,
    source_operation_id TEXT NOT NULL,
    recorded_at INTEGER NOT NULL,
    CHECK (
        (reason_code = 'INITIAL' AND sequence = 1
            AND previous_base_sha IS NULL)
        OR (reason_code <> 'INITIAL' AND sequence > 1
            AND previous_base_sha IS NOT NULL)
    ),
    UNIQUE (task_id, sequence),
    UNIQUE (task_id, source_operation_id),
    FOREIGN KEY (task_id) REFERENCES flow_runtime_task (task_id),
    FOREIGN KEY (source_operation_id)
        REFERENCES flow_runtime_operation (operation_id)
);

CREATE TABLE flow_runtime_change_set_revision (
    change_set_revision_id TEXT PRIMARY KEY,
    task_id TEXT NOT NULL,
    sequence INTEGER NOT NULL CHECK (sequence > 0),
    previous_change_set_revision_id TEXT,
    previous_head_sha TEXT NOT NULL,
    head_sha TEXT NOT NULL,
    base_revision_id TEXT NOT NULL,
    base_sha TEXT NOT NULL,
    head_tree_digest TEXT NOT NULL,
    diff_digest TEXT NOT NULL,
    differs_from_base INTEGER NOT NULL CHECK (differs_from_base IN (0, 1)),
    source TEXT NOT NULL CHECK (
        source IN ('TASK_AGENT', 'CI_FIXER', 'UPSTREAM_SYNC')
    ),
    source_run_id TEXT,
    source_operation_id TEXT NOT NULL,
    adopted_at INTEGER NOT NULL,
    UNIQUE (task_id, sequence),
    UNIQUE (task_id, source_operation_id, head_sha),
    CHECK (
        (source = 'UPSTREAM_SYNC' AND source_run_id IS NULL)
        OR (source <> 'UPSTREAM_SYNC' AND source_run_id IS NOT NULL)
    ),
    CHECK (
        (sequence = 1 AND previous_change_set_revision_id IS NULL)
        OR (sequence > 1 AND previous_change_set_revision_id IS NOT NULL)
    ),
    FOREIGN KEY (task_id) REFERENCES flow_runtime_task (task_id),
    FOREIGN KEY (previous_change_set_revision_id)
        REFERENCES flow_runtime_change_set_revision (change_set_revision_id),
    FOREIGN KEY (base_revision_id)
        REFERENCES flow_runtime_task_base_revision (base_revision_id),
    FOREIGN KEY (source_run_id) REFERENCES flow_runtime_agent_run (run_id),
    FOREIGN KEY (source_operation_id)
        REFERENCES flow_runtime_operation (operation_id)
);

CREATE TABLE flow_runtime_task_lifecycle_revision (
    lifecycle_revision_id TEXT PRIMARY KEY,
    task_id TEXT NOT NULL,
    sequence INTEGER NOT NULL,
    from_status TEXT,
    to_status TEXT NOT NULL,
    reason_code TEXT NOT NULL,
    evidence_ref TEXT,
    operation_id TEXT,
    recorded_at INTEGER NOT NULL,
    UNIQUE (task_id, sequence),
    FOREIGN KEY (task_id) REFERENCES flow_runtime_task (task_id)
);

CREATE TABLE flow_runtime_remote_identity (
    remote_identity_id TEXT PRIMARY KEY,
    provider TEXT NOT NULL,
    repository_external_id TEXT NOT NULL,
    repository_owner TEXT NOT NULL,
    repository_name TEXT NOT NULL,
    head_repository_external_id TEXT NOT NULL,
    head_repository_owner TEXT NOT NULL,
    head_repository_name TEXT NOT NULL,
    pr_number INTEGER NOT NULL,
    pr_node_id TEXT NOT NULL,
    html_url TEXT NOT NULL,
    publication_receipt_id TEXT NOT NULL,
    bound_at INTEGER NOT NULL,
    UNIQUE (provider, repository_external_id, pr_number),
    UNIQUE (
        remote_identity_id, provider, head_repository_external_id,
        head_repository_owner, head_repository_name
    ),
    CHECK (provider = 'GITHUB'),
    CHECK (
        repository_owner <> '' AND repository_name <> ''
        AND head_repository_owner <> '' AND head_repository_name <> ''
    )
);

CREATE TABLE flow_runtime_pr (
    pr_id TEXT PRIMARY KEY,
    task_id TEXT NOT NULL UNIQUE,
    repository_id TEXT NOT NULL,
    base_ref TEXT NOT NULL,
    base_sha TEXT NOT NULL,
    target_base_ref TEXT NOT NULL,
    scope_key TEXT NOT NULL,
    branch_name TEXT NOT NULL,
    created_from_change_set_revision_id TEXT NOT NULL,
    created_from_head_sha TEXT NOT NULL,
    remote_identity_id TEXT UNIQUE,
    current_remote_head TEXT,
    current_draft_revision_id TEXT UNIQUE,
    created_at INTEGER NOT NULL,
    UNIQUE (
        pr_id, task_id, repository_id, branch_name, remote_identity_id
    ),
    UNIQUE (pr_id, task_id, repository_id, branch_name),
    FOREIGN KEY (task_id) REFERENCES flow_runtime_task (task_id),
    FOREIGN KEY (created_from_change_set_revision_id)
        REFERENCES flow_runtime_change_set_revision (change_set_revision_id),
    FOREIGN KEY (remote_identity_id)
        REFERENCES flow_runtime_remote_identity (remote_identity_id),
    FOREIGN KEY (current_draft_revision_id, pr_id)
        REFERENCES flow_runtime_pr_draft_revision (
            draft_revision_id, pr_id
        )
);

CREATE TABLE flow_runtime_pr_draft_revision (
    draft_revision_id TEXT PRIMARY KEY,
    pr_id TEXT NOT NULL,
    sequence INTEGER NOT NULL CHECK (sequence > 0),
    change_set_revision_id TEXT NOT NULL,
    head_sha TEXT NOT NULL,
    title TEXT NOT NULL CHECK (length(title) BETWEEN 1 AND 256),
    body TEXT NOT NULL CHECK (length(body) <= 65536),
    draft_digest TEXT NOT NULL UNIQUE,
    created_by_run_id TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    UNIQUE (pr_id, sequence),
    UNIQUE (draft_revision_id, pr_id),
    UNIQUE (draft_revision_id, draft_digest),
    UNIQUE (
        draft_revision_id, pr_id, change_set_revision_id,
        head_sha, draft_digest
    ),
    FOREIGN KEY (pr_id) REFERENCES flow_runtime_pr (pr_id),
    FOREIGN KEY (change_set_revision_id)
        REFERENCES flow_runtime_change_set_revision (change_set_revision_id),
    FOREIGN KEY (created_by_run_id)
        REFERENCES flow_runtime_agent_run (run_id)
);

CREATE TABLE flow_runtime_pr_ready_policy_revision (
    ready_policy_revision_id TEXT PRIMARY KEY,
    pr_id TEXT NOT NULL,
    sequence INTEGER NOT NULL CHECK (sequence > 0),
    policy TEXT NOT NULL CHECK (
        policy IN ('KEEP_DRAFT', 'MARK_READY_ON_EXACT_GREEN')
    ),
    required_ci_policy_revision_id TEXT NOT NULL,
    authorization_id TEXT NOT NULL UNIQUE,
    operation_id TEXT NOT NULL UNIQUE,
    effect_plan_id TEXT NOT NULL UNIQUE,
    action_digest TEXT NOT NULL,
    proposed_head TEXT NOT NULL,
    publication_receipt_id TEXT NOT NULL UNIQUE,
    publication_receipt_digest TEXT NOT NULL,
    policy_digest TEXT NOT NULL UNIQUE,
    created_at INTEGER NOT NULL,
    UNIQUE (pr_id, sequence),
    FOREIGN KEY (pr_id) REFERENCES flow_runtime_pr (pr_id),
    FOREIGN KEY (
        authorization_id, effect_plan_id, operation_id, pr_id, action_digest
    ) REFERENCES flow_user_gate_authorization (
        authorization_id, effect_plan_ref, operation_id, pr_id, action_digest
    ),
    FOREIGN KEY (
        publication_receipt_id, operation_id, effect_plan_id,
        proposed_head, publication_receipt_digest
    ) REFERENCES flow_github_initial_publish_receipt (
        receipt_id, operation_id, plan_id, proposed_head, receipt_digest
    ),
    FOREIGN KEY (
        effect_plan_id, operation_id, authorization_id, pr_id,
        action_digest, proposed_head, required_ci_policy_revision_id, policy
    ) REFERENCES flow_github_initial_publish_plan (
        plan_id, operation_id, authorization_id, pr_id,
        action_digest, proposed_head, required_ci_policy_revision_id,
        ready_policy
    ),
    FOREIGN KEY (required_ci_policy_revision_id)
        REFERENCES flow_ci_policy_revision (policy_revision_id)
);

CREATE TABLE flow_runtime_operation (
    operation_id TEXT PRIMARY KEY,
    owner_kind TEXT NOT NULL,
    owner_id TEXT NOT NULL,
    task_id TEXT,
    kind TEXT NOT NULL CHECK (
        kind IN (
            'PROVISION_TASK',
            'RECONCILE_TASK',
            'RUN_TASK_TURN',
            'RUN_REVIEWER',
            'RUN_CI_FIXER',
            'RUN_CI_LEARNING',
            'OBSERVE_CI',
            'PUBLISH'
        )
    ),
    subject_digest TEXT NOT NULL,
    input_ref TEXT NOT NULL,
    work_watermark INTEGER,
    state TEXT NOT NULL CHECK (
        state IN (
            'READY',
            'CLAIMED',
            'WAITING',
            'SUCCEEDED',
            'RETRYABLE',
            'FAILED',
            'CANCELED'
        )
    ),
    attempt INTEGER NOT NULL DEFAULT 0,
    result_ref TEXT,
    created_at INTEGER NOT NULL,
    UNIQUE (owner_kind, owner_id, kind, subject_digest),
    UNIQUE (operation_id, task_id),
    FOREIGN KEY (task_id) REFERENCES flow_runtime_task (task_id)
);

CREATE UNIQUE INDEX flow_runtime_one_live_reconciliation
    ON flow_runtime_operation (task_id)
    WHERE kind = 'RECONCILE_TASK'
        AND state IN ('READY', 'CLAIMED', 'WAITING', 'RETRYABLE');

CREATE TABLE flow_runtime_dispatch_ticket (
    operation_id TEXT PRIMARY KEY,
    not_before INTEGER NOT NULL,
    claim_owner TEXT,
    claim_expires_at INTEGER,
    claim_generation INTEGER NOT NULL DEFAULT 0,
    claim_token TEXT,
    priority INTEGER NOT NULL,
    delivery_state TEXT NOT NULL CHECK (
        delivery_state IN ('AVAILABLE', 'CLAIMED', 'DONE')
    ),
    FOREIGN KEY (operation_id)
        REFERENCES flow_runtime_operation (operation_id)
);

CREATE TABLE flow_runtime_provision_subject (
    operation_id TEXT NOT NULL,
    task_id TEXT NOT NULL,
    launch_digest TEXT NOT NULL,
    base_sha TEXT NOT NULL,
    target_base_ref TEXT NOT NULL,
    mutation_digest TEXT NOT NULL,
    bound_at INTEGER NOT NULL,
    PRIMARY KEY (operation_id),
    FOREIGN KEY (operation_id, task_id)
        REFERENCES flow_runtime_operation (operation_id, task_id),
    FOREIGN KEY (task_id, launch_digest)
        REFERENCES flow_runtime_task (task_id, launch_digest)
);

CREATE INDEX flow_runtime_claimable_ticket
    ON flow_runtime_dispatch_ticket (
        delivery_state,
        not_before,
        priority DESC,
        operation_id
    );

CREATE TABLE flow_runtime_inbox (
    inbox_id TEXT PRIMARY KEY,
    task_id TEXT NOT NULL,
    pr_id TEXT,
    source TEXT NOT NULL,
    external_key TEXT NOT NULL,
    revision TEXT NOT NULL,
    kind TEXT NOT NULL CHECK (
        kind IN (
            'INITIAL_TASK', 'FINAL_RED', 'CI_FIX_READY', 'AGENT_RESULT_READY'
        )
    ),
    subject_head TEXT NOT NULL,
    payload_ref TEXT NOT NULL,
    agent_result_id TEXT,
    intended_gate_kind TEXT CHECK (
        intended_gate_kind IS NULL
        OR intended_gate_kind IN ('INITIAL_PUBLISH', 'CI_UPDATE')
    ),
    work_watermark INTEGER NOT NULL,
    observed_at INTEGER NOT NULL,
    selected_by_operation_id TEXT,
    handled_by_operation_id TEXT,
    terminal_reason TEXT CHECK (
        terminal_reason IN ('TASK_COMPLETED', 'TASK_CANCELED')
    ),
    CHECK (
        (kind IN ('CI_FIX_READY', 'AGENT_RESULT_READY')
            AND agent_result_id IS NOT NULL)
        OR (kind NOT IN ('CI_FIX_READY', 'AGENT_RESULT_READY')
            AND agent_result_id IS NULL)
    ),
    CHECK (
        (kind = 'FINAL_RED' AND intended_gate_kind IS NULL)
        OR (kind <> 'FINAL_RED' AND intended_gate_kind IS NOT NULL)
    ),
    UNIQUE (source, external_key, revision),
    UNIQUE (task_id, work_watermark),
    FOREIGN KEY (task_id) REFERENCES flow_runtime_task (task_id),
    FOREIGN KEY (pr_id) REFERENCES flow_runtime_pr (pr_id),
    FOREIGN KEY (agent_result_id)
        REFERENCES flow_runtime_agent_result (result_id),
    UNIQUE (agent_result_id),
    FOREIGN KEY (selected_by_operation_id)
        REFERENCES flow_runtime_operation (operation_id),
    FOREIGN KEY (handled_by_operation_id)
        REFERENCES flow_runtime_operation (operation_id)
);

CREATE INDEX flow_runtime_pending_inbox
    ON flow_runtime_inbox (
        task_id,
        handled_by_operation_id,
        selected_by_operation_id,
        work_watermark
    );

CREATE TABLE flow_runtime_writer_lease (
    task_id TEXT PRIMARY KEY,
    operation_id TEXT NOT NULL UNIQUE,
    task_epoch INTEGER NOT NULL,
    holder_kind TEXT NOT NULL CHECK (
        holder_kind IN ('TASK_AGENT', 'CI_FIXER')
    ),
    fencing_token INTEGER NOT NULL,
    claim_generation INTEGER NOT NULL,
    claim_token_digest TEXT NOT NULL,
    head_sha TEXT NOT NULL,
    tree_digest TEXT NOT NULL,
    snapshot_evidence_ref TEXT NOT NULL,
    expires_at INTEGER NOT NULL,
    FOREIGN KEY (task_id) REFERENCES flow_runtime_task (task_id),
    FOREIGN KEY (operation_id)
        REFERENCES flow_runtime_operation (operation_id)
);

CREATE TABLE flow_runtime_agent_session (
    session_id TEXT PRIMARY KEY,
    task_id TEXT NOT NULL,
    role TEXT NOT NULL CHECK (
        role IN (
            'TASK_AGENT', 'ADVERSARIAL_REVIEWER', 'CI_FIXER', 'CI_LEARNER'
        )
    ),
    state TEXT NOT NULL CHECK (
        state IN ('NEW', 'IDLE', 'RUNNING', 'PARKED_CHILD', 'CLOSED')
    ),
    last_run_id TEXT,
    close_reason TEXT,
    -- The vendor's own handle on this conversation, so a later turn continues
    -- it rather than starting over. This is the whole reason the session row
    -- outlives a run. TASK_AGENT and CI_FIXER are separate program sessions,
    -- but both resolve this as the Task's one writer-provider conversation;
    -- reviewer and learner rows remain isolated. Null until a CLI turn reports
    -- one; an API turn never has one, because the program replays its transcript.
    provider_session_id TEXT,
    -- What the session has cost so far, accumulated per turn. Held here rather
    -- than summed over attempts on demand because attempts are pruned and a
    -- budget that forgets spending is not a budget.
    total_tokens_in INTEGER NOT NULL DEFAULT 0 CHECK (total_tokens_in >= 0),
    total_tokens_out INTEGER NOT NULL DEFAULT 0 CHECK (total_tokens_out >= 0),
    total_cost_milli_usd INTEGER NOT NULL DEFAULT 0
        CHECK (total_cost_milli_usd >= 0),
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    FOREIGN KEY (task_id) REFERENCES flow_runtime_task (task_id)
);

CREATE UNIQUE INDEX flow_runtime_one_task_session
    ON flow_runtime_agent_session (task_id)
    WHERE role = 'TASK_AGENT';

CREATE UNIQUE INDEX flow_runtime_one_ci_session
    ON flow_runtime_agent_session (task_id)
    WHERE role = 'CI_FIXER';

CREATE TABLE flow_runtime_agent_run (
    run_id TEXT PRIMARY KEY,
    operation_id TEXT NOT NULL UNIQUE,
    session_id TEXT NOT NULL,
    role TEXT NOT NULL CHECK (
        role IN (
            'TASK_AGENT', 'ADVERSARIAL_REVIEWER', 'CI_FIXER', 'CI_LEARNER'
        )
    ),
    head_sha TEXT NOT NULL,
    prompt_manifest_ref TEXT NOT NULL,
    capability_set_ref TEXT NOT NULL,
    input_ref TEXT NOT NULL,
    input_change_set_revision_id TEXT,
    input_remote_head_sha TEXT,
    wake_kind TEXT CHECK (
        wake_kind IS NULL
        OR wake_kind IN ('INITIAL_TASK', 'CI_FIX_READY', 'AGENT_RESULT_READY')
    ),
    intended_gate_kind TEXT CHECK (
        intended_gate_kind IS NULL
        OR intended_gate_kind IN ('INITIAL_PUBLISH', 'CI_UPDATE')
    ),
    state TEXT NOT NULL CHECK (
        state IN ('QUEUED', 'RUNNING', 'COMPLETED', 'FAILED', 'CANCELED')
    ),
    failure_reason_code TEXT,
    created_at INTEGER NOT NULL,
    started_at INTEGER,
    completed_at INTEGER,
    CHECK (
        (role = 'TASK_AGENT' AND wake_kind IS NOT NULL
            AND intended_gate_kind IS NOT NULL
            AND (
                (intended_gate_kind = 'INITIAL_PUBLISH'
                    AND input_remote_head_sha IS NULL
                    AND (
                        (wake_kind = 'INITIAL_TASK'
                            AND input_change_set_revision_id IS NULL)
                        OR (wake_kind = 'AGENT_RESULT_READY'
                            AND input_change_set_revision_id IS NOT NULL)
                    ))
                OR (intended_gate_kind = 'CI_UPDATE'
                    AND input_change_set_revision_id IS NOT NULL
                    AND input_remote_head_sha IS NOT NULL)
            ))
        OR (role <> 'TASK_AGENT' AND wake_kind IS NULL
            AND intended_gate_kind IS NULL
            AND input_change_set_revision_id IS NULL
            AND input_remote_head_sha IS NULL)
    ),
    FOREIGN KEY (operation_id)
        REFERENCES flow_runtime_operation (operation_id),
    FOREIGN KEY (session_id)
        REFERENCES flow_runtime_agent_session (session_id),
    FOREIGN KEY (input_change_set_revision_id)
        REFERENCES flow_runtime_change_set_revision (change_set_revision_id)
);

CREATE TABLE flow_runtime_agent_result (
    result_id TEXT PRIMARY KEY,
    run_id TEXT NOT NULL UNIQUE,
    terminal_outcome TEXT NOT NULL CHECK (
        terminal_outcome IN ('COMPLETED', 'FAILED', 'CANCELED')
    ),
    final_content TEXT,
    error_ref TEXT,
    stop_proof_ref TEXT NOT NULL,
    stored_at INTEGER NOT NULL,
    FOREIGN KEY (run_id) REFERENCES flow_runtime_agent_run (run_id)
);

-- Set-once launch authority for the neutral TurnRunner. Secrets never enter
-- this database; credential_id/updated_at name the exact main-store revision
-- that must still exist immediately before the first provider request.
CREATE TABLE flow_runtime_agent_launch_binding (
    run_id TEXT PRIMARY KEY,
    provider_name TEXT NOT NULL,
    -- How the turn ran, which is not the same question as which wire dialect it
    -- spoke: a CLI turn speaks no wire at all.
    execution TEXT NOT NULL DEFAULT 'API' CHECK (execution IN ('API', 'CLI')),
    transport TEXT CHECK (
        transport IS NULL OR transport IN ('ANTHROPIC', 'OPENAI_COMPAT')
    ),
    -- An API turn is pinned by where it was called and which stored credential
    -- answered. A CLI turn has neither: it is a subprocess reading the user's own
    -- CLI login, which this program never sees and must not. That is the billing
    -- and privacy choice a user makes by choosing a CLI agent, so the binding
    -- records less for those runs rather than inventing an endpoint or a
    -- credential it does not have.
    endpoint TEXT,
    model TEXT NOT NULL,
    reasoning_effort TEXT,
    credential_id INTEGER,
    credential_name TEXT,
    credential_instance TEXT,
    credential_updated_at TEXT,
    prompt_revision TEXT NOT NULL,
    prompt_digest TEXT NOT NULL,
    tool_manifest_digest TEXT NOT NULL,
    -- Output and tool-iteration ceilings are properties of an API call. A
    -- subprocess is bounded by its cost cap and its process lifetime.
    max_output_tokens INTEGER CHECK (
        max_output_tokens IS NULL OR max_output_tokens > 0
    ),
    max_tool_iterations INTEGER CHECK (
        max_tool_iterations IS NULL OR max_tool_iterations > 0
    ),
    -- What a CLI run can be pinned to. Weaker than a credential identity, and
    -- recorded explicitly so the gap is visible rather than implied by absence.
    cli_binary TEXT,
    cli_version TEXT,
    binding_digest TEXT NOT NULL,
    bound_at INTEGER NOT NULL,
    -- The two shapes, kept apart. Neither can borrow the other's fields, so a row
    -- states its own execution kind before you read the column.
    CHECK (
        (execution = 'API'
            AND transport IS NOT NULL
            AND endpoint IS NOT NULL
            AND credential_id IS NOT NULL
            AND credential_name IS NOT NULL
            AND credential_instance IS NOT NULL
            AND credential_updated_at IS NOT NULL
            AND max_output_tokens IS NOT NULL
            AND max_tool_iterations IS NOT NULL
            AND cli_binary IS NULL
            AND cli_version IS NULL)
        OR (execution = 'CLI'
            AND transport IS NULL
            AND endpoint IS NULL
            AND credential_id IS NULL
            AND credential_name IS NULL
            AND credential_instance IS NULL
            AND credential_updated_at IS NULL
            AND max_output_tokens IS NULL
            AND max_tool_iterations IS NULL
            AND cli_binary IS NOT NULL)
    ),
    FOREIGN KEY (run_id) REFERENCES flow_runtime_agent_run (run_id)
);

CREATE TABLE flow_runtime_agent_process_attempt (
    process_attempt_id TEXT PRIMARY KEY,
    run_id TEXT NOT NULL,
    operation_id TEXT NOT NULL,
    claim_generation INTEGER NOT NULL,
    claim_token_digest TEXT NOT NULL,
    execution_id TEXT NOT NULL UNIQUE,
    capability_id TEXT NOT NULL UNIQUE,
    state TEXT NOT NULL CHECK (
        state IN ('RESERVED', 'ACTIVATED', 'STOPPED')
    ),
    jvm_pid INTEGER,
    jvm_started_at INTEGER,
    thread_id INTEGER,
    thread_name TEXT,
    -- A CLI turn's identity is its process group, recorded before the prompt is
    -- delivered so the crash that makes it matter cannot lose it. The owning Java
    -- thread still holds the fence, so the thread columns above stay populated
    -- too; these say which group that thread must bury before it may return.
    agent_pid INTEGER,
    agent_pgid INTEGER CHECK (agent_pgid IS NULL OR agent_pgid > 1),
    agent_started_at INTEGER,
    -- This attempt's own share of the session totals, snapshotted when the turn
    -- ends. The session row says what has been spent; these say which attempt
    -- spent it, which is the only way to read a runaway turn apart from a long
    -- but healthy one after the fact.
    attempt_provider_session_id TEXT,
    attempt_tokens_in INTEGER CHECK (
        attempt_tokens_in IS NULL OR attempt_tokens_in >= 0
    ),
    attempt_tokens_out INTEGER CHECK (
        attempt_tokens_out IS NULL OR attempt_tokens_out >= 0
    ),
    attempt_cost_milli_usd INTEGER CHECK (
        attempt_cost_milli_usd IS NULL OR attempt_cost_milli_usd >= 0
    ),
    reserved_at INTEGER NOT NULL,
    activated_at INTEGER,
    capability_revoked_at INTEGER,
    stop_type TEXT CHECK (
        stop_type IN ('NORMAL_RETURN', 'COOPERATIVE_CANCELLATION')
    ),
    stop_proof_ref TEXT,
    stopped_at INTEGER,
    completion_outcome TEXT CHECK (
        completion_outcome IN ('COMPLETED', 'FAILED', 'CANCELED')
    ),
    completion_content TEXT,
    completion_error_ref TEXT,
    completion_digest TEXT,
    quarantine_reason TEXT CHECK (
        quarantine_reason IN (
            'UNCOOPERATIVE_CANCELLATION',
            'IN_PROCESS_OWNER_UNAVAILABLE'
        )
    ),
    quarantined_at INTEGER,
    CHECK (
        (state = 'RESERVED'
            AND jvm_pid IS NULL
            AND jvm_started_at IS NULL
            AND thread_id IS NULL
            AND thread_name IS NULL
            AND activated_at IS NULL)
        OR (state IN ('ACTIVATED', 'STOPPED')
            AND jvm_pid IS NOT NULL
            AND jvm_started_at IS NOT NULL
            AND thread_id IS NOT NULL
            AND thread_name IS NOT NULL
            AND activated_at IS NOT NULL)
    ),
    CHECK (
        (state <> 'STOPPED'
            AND stop_type IS NULL
            AND stop_proof_ref IS NULL
            AND stopped_at IS NULL)
        OR (state = 'STOPPED'
            AND capability_revoked_at IS NOT NULL
            AND stop_type IS NOT NULL
            AND stop_proof_ref IS NOT NULL
            AND stopped_at IS NOT NULL
            AND completion_outcome IS NOT NULL
            AND completion_digest IS NOT NULL
            AND ((completion_outcome = 'COMPLETED')
                OR completion_error_ref IS NOT NULL))
    ),
    CHECK (
        (quarantine_reason IS NULL AND quarantined_at IS NULL)
        OR (quarantine_reason IS NOT NULL
            AND quarantined_at IS NOT NULL
            AND state = 'ACTIVATED'
            AND capability_revoked_at IS NOT NULL)
    ),
    UNIQUE (run_id, claim_generation),
    FOREIGN KEY (run_id) REFERENCES flow_runtime_agent_run (run_id),
    FOREIGN KEY (operation_id)
        REFERENCES flow_runtime_operation (operation_id)
);

CREATE TABLE flow_runtime_local_check_policy_revision (
    policy_revision_id TEXT PRIMARY KEY,
    repository_id TEXT NOT NULL,
    sequence INTEGER NOT NULL CHECK (sequence > 0),
    source_revision TEXT NOT NULL,
    source_digest TEXT NOT NULL,
    recorded_at INTEGER NOT NULL,
    UNIQUE (repository_id, sequence),
    UNIQUE (repository_id, source_revision)
);

CREATE TABLE flow_runtime_local_check_policy_current (
    repository_id TEXT PRIMARY KEY,
    policy_revision_id TEXT NOT NULL UNIQUE,
    FOREIGN KEY (policy_revision_id)
        REFERENCES flow_runtime_local_check_policy_revision (
            policy_revision_id
        )
);

CREATE TABLE flow_runtime_local_check_profile (
    profile_id TEXT PRIMARY KEY,
    policy_revision_id TEXT NOT NULL,
    name TEXT NOT NULL,
    command_json TEXT NOT NULL,
    working_directory TEXT NOT NULL,
    environment_allowlist_json TEXT NOT NULL,
    required_gate_kinds_json TEXT NOT NULL,
    timeout_seconds INTEGER NOT NULL CHECK (
        timeout_seconds > 0 AND timeout_seconds <= 7200
    ),
    UNIQUE (policy_revision_id, name),
    UNIQUE (policy_revision_id, profile_id),
    FOREIGN KEY (policy_revision_id)
        REFERENCES flow_runtime_local_check_policy_revision (
            policy_revision_id
        )
);

CREATE TABLE flow_runtime_local_check_run (
    check_run_id TEXT PRIMARY KEY,
    task_id TEXT NOT NULL,
    change_set_revision_id TEXT NOT NULL,
    policy_revision_id TEXT NOT NULL,
    profile_id TEXT NOT NULL,
    operation_id TEXT NOT NULL,
    agent_run_id TEXT NOT NULL,
    command_json TEXT NOT NULL,
    working_directory TEXT NOT NULL,
    attempt_sequence INTEGER NOT NULL CHECK (attempt_sequence > 0),
    observed_start_head TEXT NOT NULL,
    observed_end_head TEXT,
    started_at INTEGER NOT NULL,
    completed_at INTEGER NOT NULL,
    conclusion TEXT NOT NULL CHECK (
        conclusion IN ('PASSED', 'FAILED', 'UNAVAILABLE')
    ),
    exit_code INTEGER,
    unavailable_reason_code TEXT,
    output_ref TEXT NOT NULL UNIQUE,
    output_text TEXT NOT NULL,
    output_truncated INTEGER NOT NULL CHECK (output_truncated IN (0, 1)),
    tracked_tree_clean_before INTEGER NOT NULL CHECK (
        tracked_tree_clean_before IN (0, 1)
    ),
    tracked_tree_clean_after INTEGER NOT NULL CHECK (
        tracked_tree_clean_after IN (0, 1)
    ),
    CHECK (
        (conclusion = 'UNAVAILABLE'
            AND unavailable_reason_code IS NOT NULL)
        OR (conclusion <> 'UNAVAILABLE'
            AND unavailable_reason_code IS NULL)
    ),
    CHECK (
        observed_end_head IS NOT NULL
        OR conclusion = 'UNAVAILABLE'
    ),
    UNIQUE (
        task_id,
        change_set_revision_id,
        policy_revision_id,
        profile_id,
        attempt_sequence
    ),
    FOREIGN KEY (task_id) REFERENCES flow_runtime_task (task_id),
    FOREIGN KEY (change_set_revision_id)
        REFERENCES flow_runtime_change_set_revision (
            change_set_revision_id
        ),
    FOREIGN KEY (policy_revision_id, profile_id)
        REFERENCES flow_runtime_local_check_profile (
            policy_revision_id, profile_id
        ),
    FOREIGN KEY (operation_id)
        REFERENCES flow_runtime_operation (operation_id),
    FOREIGN KEY (agent_run_id)
        REFERENCES flow_runtime_agent_run (run_id)
);

CREATE TABLE flow_runtime_reviewer_request (
    request_id TEXT PRIMARY KEY,
    task_id TEXT NOT NULL,
    parent_operation_id TEXT NOT NULL UNIQUE,
    parent_run_id TEXT NOT NULL UNIQUE,
    reviewer_operation_id TEXT NOT NULL UNIQUE,
    repository_root TEXT NOT NULL,
    base_head_sha TEXT NOT NULL,
    reviewed_head_sha TEXT NOT NULL,
    remote_head_sha TEXT,
    origin_ci_fix_pending_id TEXT,
    origin_ci_fix_source_kind TEXT CHECK (
        origin_ci_fix_source_kind IN ('REPAIR_ATTEMPT', 'CLEANUP')
    ),
    origin_ci_fix_source_id TEXT,
    change_set_revision_id TEXT NOT NULL,
    local_check_policy_revision_id TEXT NOT NULL,
    head_tree_digest TEXT NOT NULL,
    diff_digest TEXT NOT NULL,
    intended_gate_kind TEXT NOT NULL CHECK (
        intended_gate_kind IN ('INITIAL_PUBLISH', 'CI_UPDATE')
    ),
    created_at INTEGER NOT NULL,
    CHECK (
        (intended_gate_kind = 'INITIAL_PUBLISH'
            AND remote_head_sha IS NULL
            AND origin_ci_fix_pending_id IS NULL
            AND origin_ci_fix_source_kind IS NULL
            AND origin_ci_fix_source_id IS NULL)
        OR (intended_gate_kind = 'CI_UPDATE'
            AND remote_head_sha IS NOT NULL
            AND origin_ci_fix_pending_id IS NOT NULL
            AND origin_ci_fix_source_kind IS NOT NULL
            AND origin_ci_fix_source_id IS NOT NULL)
    ),
    FOREIGN KEY (task_id) REFERENCES flow_runtime_task (task_id),
    FOREIGN KEY (parent_operation_id)
        REFERENCES flow_runtime_operation (operation_id),
    FOREIGN KEY (parent_run_id)
        REFERENCES flow_runtime_agent_run (run_id),
    FOREIGN KEY (reviewer_operation_id)
        REFERENCES flow_runtime_operation (operation_id),
    FOREIGN KEY (change_set_revision_id)
        REFERENCES flow_runtime_change_set_revision (change_set_revision_id),
    FOREIGN KEY (local_check_policy_revision_id)
        REFERENCES flow_runtime_local_check_policy_revision (
            policy_revision_id
        )
);

CREATE TABLE flow_runtime_task_terminal_request (
    run_id TEXT PRIMARY KEY,
    kind TEXT NOT NULL CHECK (
        kind IN ('REVIEWER', 'READY_FOR_REVIEW')
    ),
    request_id TEXT NOT NULL UNIQUE,
    created_at INTEGER NOT NULL,
    FOREIGN KEY (run_id) REFERENCES flow_runtime_agent_run (run_id)
);

CREATE TABLE flow_runtime_ready_for_review_request (
    request_id TEXT PRIMARY KEY,
    run_id TEXT NOT NULL UNIQUE,
    operation_id TEXT NOT NULL,
    task_id TEXT NOT NULL,
    pr_id TEXT NOT NULL,
    subject_ref TEXT NOT NULL UNIQUE,
    subject_digest TEXT NOT NULL,
    action_ref TEXT NOT NULL UNIQUE,
    action_digest TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    FOREIGN KEY (run_id) REFERENCES flow_runtime_agent_run (run_id),
    FOREIGN KEY (operation_id)
        REFERENCES flow_runtime_operation (operation_id),
    FOREIGN KEY (task_id) REFERENCES flow_runtime_task (task_id),
    FOREIGN KEY (pr_id) REFERENCES flow_runtime_pr (pr_id)
);

CREATE TABLE flow_runtime_reviewer_check_ref (
    request_id TEXT NOT NULL,
    ordinal INTEGER NOT NULL CHECK (ordinal >= 0),
    check_run_ref TEXT NOT NULL,
    PRIMARY KEY (request_id, ordinal),
    UNIQUE (request_id, check_run_ref),
    FOREIGN KEY (request_id)
        REFERENCES flow_runtime_reviewer_request (request_id),
    FOREIGN KEY (check_run_ref)
        REFERENCES flow_runtime_local_check_run (check_run_id)
);
