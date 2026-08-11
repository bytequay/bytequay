CREATE TABLE flow_github_external_effect_plan (
    plan_id TEXT PRIMARY KEY,
    operation_id TEXT NOT NULL UNIQUE,
    authorization_id TEXT NOT NULL UNIQUE,
    pr_id TEXT NOT NULL,
    pr_sequence INTEGER NOT NULL CHECK (pr_sequence > 0),
    kind TEXT NOT NULL CHECK (kind = 'CI_UPDATE'),
    expected_remote_head TEXT NOT NULL,
    action_ref TEXT NOT NULL,
    action_digest TEXT NOT NULL,
    required_ci_policy_revision_id TEXT NOT NULL,
    plan_digest TEXT NOT NULL UNIQUE,
    created_at INTEGER NOT NULL,
    UNIQUE (pr_id, pr_sequence),
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
    branch_ref TEXT NOT NULL,
    expected_remote_head TEXT NOT NULL,
    proposed_head TEXT NOT NULL,
    force_push INTEGER NOT NULL CHECK (force_push = 0),
    action_ref TEXT NOT NULL,
    action_digest TEXT NOT NULL,
    precondition_digest TEXT NOT NULL,
    UNIQUE (plan_id, ordinal),
    UNIQUE (plan_id, action_ref, action_digest),
    FOREIGN KEY (plan_id, action_ref, action_digest)
        REFERENCES flow_github_external_effect_plan (
            plan_id, action_ref, action_digest
        ),
    FOREIGN KEY (
        action_ref, branch_ref, expected_remote_head, proposed_head,
        force_push, action_digest
    ) REFERENCES flow_user_gate_ci_update_action (
        action_ref, branch_ref, expected_remote_head, proposed_head,
        force_push, action_digest
        )
);
