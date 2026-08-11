CREATE TABLE flow_github_external_effect_plan (
    plan_id TEXT PRIMARY KEY,
    operation_id TEXT NOT NULL UNIQUE,
    authorization_id TEXT NOT NULL UNIQUE,
    pr_id TEXT NOT NULL,
    pr_sequence INTEGER NOT NULL CHECK (pr_sequence > 0),
    kind TEXT NOT NULL CHECK (kind = 'CI_UPDATE'),
    head_repository_external_id TEXT NOT NULL,
    head_repository_owner TEXT NOT NULL,
    head_repository_name TEXT NOT NULL,
    expected_remote_head TEXT NOT NULL,
    action_ref TEXT NOT NULL,
    action_digest TEXT NOT NULL,
    required_ci_policy_revision_id TEXT NOT NULL,
    plan_digest TEXT NOT NULL UNIQUE,
    created_at INTEGER NOT NULL,
    UNIQUE (pr_id, pr_sequence),
    UNIQUE (plan_id, operation_id),
    UNIQUE (plan_id, action_ref, action_digest),
    FOREIGN KEY (operation_id)
        REFERENCES flow_runtime_operation (operation_id),
    FOREIGN KEY (pr_id) REFERENCES flow_runtime_pr (pr_id),
    FOREIGN KEY (action_ref)
        REFERENCES flow_user_gate_ci_update_action (action_ref),
    FOREIGN KEY (required_ci_policy_revision_id)
        REFERENCES flow_ci_policy_revision (policy_revision_id),
    FOREIGN KEY (
        authorization_id, plan_id, operation_id, pr_id, action_digest
    ) REFERENCES flow_user_gate_authorization (
        authorization_id, effect_plan_ref, operation_id, pr_id, action_digest
    )
);

CREATE TABLE flow_github_external_effect_step (
    step_id TEXT PRIMARY KEY,
    plan_id TEXT NOT NULL,
    ordinal INTEGER NOT NULL CHECK (ordinal = 1),
    kind TEXT NOT NULL CHECK (kind = 'PUSH_EXACT'),
    head_repository_external_id TEXT NOT NULL,
    head_repository_owner TEXT NOT NULL,
    head_repository_name TEXT NOT NULL,
    branch_ref TEXT NOT NULL,
    expected_remote_head TEXT NOT NULL,
    proposed_head TEXT NOT NULL,
    force_push INTEGER NOT NULL CHECK (force_push = 0),
    action_ref TEXT NOT NULL,
    action_digest TEXT NOT NULL,
    precondition_digest TEXT NOT NULL,
    UNIQUE (plan_id, ordinal),
    UNIQUE (plan_id, action_ref, action_digest),
    UNIQUE (
        step_id, plan_id, head_repository_external_id,
        head_repository_owner, head_repository_name, branch_ref,
        expected_remote_head, proposed_head
    ),
    FOREIGN KEY (plan_id, action_ref, action_digest)
        REFERENCES flow_github_external_effect_plan (
            plan_id, action_ref, action_digest
        ),
    FOREIGN KEY (
        action_ref, head_repository_external_id, head_repository_owner,
        head_repository_name, branch_ref, expected_remote_head, proposed_head,
        force_push, action_digest
    ) REFERENCES flow_user_gate_ci_update_action (
        action_ref, head_repository_external_id, head_repository_owner,
        head_repository_name, branch_ref, expected_remote_head, proposed_head,
        force_push, action_digest
        )
);

CREATE TABLE flow_github_external_effect_attempt (
    attempt_id TEXT PRIMARY KEY,
    operation_id TEXT NOT NULL,
    plan_id TEXT NOT NULL,
    step_id TEXT NOT NULL,
    attempt_number INTEGER NOT NULL CHECK (attempt_number BETWEEN 1 AND 2),
    claim_generation INTEGER NOT NULL CHECK (claim_generation > 0),
    claim_token_digest TEXT NOT NULL,
    head_repository_external_id TEXT NOT NULL,
    head_repository_owner TEXT NOT NULL,
    head_repository_name TEXT NOT NULL,
    branch_ref TEXT NOT NULL,
    expected_remote_head TEXT NOT NULL,
    proposed_head TEXT NOT NULL,
    request_digest TEXT NOT NULL,
    execution_token_digest TEXT NOT NULL,
    activated_at INTEGER NOT NULL,
    UNIQUE (step_id, attempt_number),
    UNIQUE (operation_id, claim_generation),
    UNIQUE (attempt_id, operation_id, plan_id, step_id),
    FOREIGN KEY (plan_id, operation_id)
        REFERENCES flow_github_external_effect_plan (plan_id, operation_id),
    FOREIGN KEY (
        step_id, plan_id, head_repository_external_id,
        head_repository_owner, head_repository_name, branch_ref,
        expected_remote_head, proposed_head
    ) REFERENCES flow_github_external_effect_step (
        step_id, plan_id, head_repository_external_id,
        head_repository_owner, head_repository_name, branch_ref,
        expected_remote_head, proposed_head
    )
);

CREATE TABLE flow_github_external_effect_probe (
    probe_id TEXT PRIMARY KEY,
    operation_id TEXT NOT NULL,
    plan_id TEXT NOT NULL,
    step_id TEXT NOT NULL,
    attempt_id TEXT,
    claim_generation INTEGER NOT NULL CHECK (claim_generation > 0),
    probe_number INTEGER NOT NULL CHECK (probe_number > 0),
    head_repository_external_id TEXT NOT NULL,
    head_repository_owner TEXT NOT NULL,
    head_repository_name TEXT NOT NULL,
    branch_ref TEXT NOT NULL,
    expected_remote_head TEXT NOT NULL,
    proposed_head TEXT NOT NULL,
    outcome TEXT NOT NULL CHECK (
        outcome IN ('APPLIED', 'ABSENT', 'DIVERGED', 'UNKNOWN')
    ),
    observed_head TEXT,
    probe_digest TEXT NOT NULL UNIQUE,
    observed_at INTEGER NOT NULL,
    UNIQUE (operation_id, claim_generation, probe_number),
    UNIQUE (
        probe_id, operation_id, plan_id, step_id, attempt_id,
        outcome, observed_head
    ),
    UNIQUE (
        probe_id, operation_id, plan_id, step_id, outcome, observed_head,
        head_repository_external_id, head_repository_owner,
        head_repository_name, branch_ref, expected_remote_head, proposed_head
    ),
    FOREIGN KEY (plan_id, operation_id)
        REFERENCES flow_github_external_effect_plan (plan_id, operation_id),
    FOREIGN KEY (
        step_id, plan_id, head_repository_external_id,
        head_repository_owner, head_repository_name, branch_ref,
        expected_remote_head, proposed_head
    ) REFERENCES flow_github_external_effect_step (
        step_id, plan_id, head_repository_external_id,
        head_repository_owner, head_repository_name, branch_ref,
        expected_remote_head, proposed_head
    ),
    FOREIGN KEY (attempt_id, operation_id, plan_id, step_id)
        REFERENCES flow_github_external_effect_attempt (
            attempt_id, operation_id, plan_id, step_id
        ),
    CHECK (outcome <> 'UNKNOWN' OR observed_head IS NULL),
    CHECK (outcome NOT IN ('APPLIED', 'ABSENT') OR observed_head IS NOT NULL)
);

CREATE TABLE flow_github_external_effect_receipt (
    receipt_id TEXT PRIMARY KEY,
    operation_id TEXT NOT NULL UNIQUE,
    plan_id TEXT NOT NULL,
    step_id TEXT NOT NULL UNIQUE,
    attempt_id TEXT,
    probe_id TEXT NOT NULL UNIQUE,
    probe_outcome TEXT NOT NULL CHECK (probe_outcome = 'APPLIED'),
    observed_head TEXT NOT NULL,
    head_repository_external_id TEXT NOT NULL,
    head_repository_owner TEXT NOT NULL,
    head_repository_name TEXT NOT NULL,
    branch_ref TEXT NOT NULL,
    expected_remote_head TEXT NOT NULL,
    proposed_head TEXT NOT NULL,
    receipt_digest TEXT NOT NULL UNIQUE,
    recorded_at INTEGER NOT NULL,
    FOREIGN KEY (plan_id, operation_id)
        REFERENCES flow_github_external_effect_plan (plan_id, operation_id),
    FOREIGN KEY (
        step_id, plan_id, head_repository_external_id,
        head_repository_owner, head_repository_name, branch_ref,
        expected_remote_head, proposed_head
    ) REFERENCES flow_github_external_effect_step (
        step_id, plan_id, head_repository_external_id,
        head_repository_owner, head_repository_name, branch_ref,
        expected_remote_head, proposed_head
    ),
    FOREIGN KEY (attempt_id, operation_id, plan_id, step_id)
        REFERENCES flow_github_external_effect_attempt (
            attempt_id, operation_id, plan_id, step_id
        ),
    FOREIGN KEY (
        probe_id, operation_id, plan_id, step_id,
        probe_outcome, observed_head, head_repository_external_id,
        head_repository_owner, head_repository_name, branch_ref,
        expected_remote_head, proposed_head
    ) REFERENCES flow_github_external_effect_probe (
        probe_id, operation_id, plan_id, step_id,
        outcome, observed_head, head_repository_external_id,
        head_repository_owner, head_repository_name, branch_ref,
        expected_remote_head, proposed_head
    ),
    CHECK (observed_head = proposed_head)
);
