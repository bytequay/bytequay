CREATE TABLE flow_github_effect_plan_envelope (
    plan_id TEXT PRIMARY KEY,
    operation_id TEXT NOT NULL UNIQUE,
    authorization_id TEXT NOT NULL UNIQUE,
    pr_id TEXT NOT NULL,
    pr_sequence INTEGER NOT NULL CHECK (pr_sequence > 0),
    kind TEXT NOT NULL CHECK (kind IN ('INITIAL_PUBLISH', 'CI_UPDATE')),
    action_ref TEXT NOT NULL,
    action_digest TEXT NOT NULL,
    plan_digest TEXT NOT NULL UNIQUE,
    created_at INTEGER NOT NULL,
    UNIQUE (pr_id, pr_sequence),
    UNIQUE (plan_id, operation_id),
    UNIQUE (plan_id, operation_id, kind),
    UNIQUE (plan_id, authorization_id, plan_digest),
    FOREIGN KEY (operation_id)
        REFERENCES flow_runtime_operation (operation_id),
    FOREIGN KEY (pr_id) REFERENCES flow_runtime_pr (pr_id),
    FOREIGN KEY (action_ref, kind, action_digest)
        REFERENCES flow_user_gate_action_manifest (
            action_ref, kind, action_digest
        ),
    FOREIGN KEY (
        authorization_id, plan_id, operation_id, pr_id, action_digest
    ) REFERENCES flow_user_gate_authorization (
        authorization_id, effect_plan_ref, operation_id, pr_id, action_digest
    )
);

CREATE TABLE flow_github_initial_publish_target_snapshot (
    target_snapshot_id TEXT PRIMARY KEY,
    task_id TEXT NOT NULL,
    pr_id TEXT NOT NULL,
    repository_id TEXT NOT NULL,
    launch_digest TEXT NOT NULL,
    base_repository_external_id TEXT NOT NULL,
    base_repository_owner TEXT NOT NULL,
    base_repository_name TEXT NOT NULL,
    head_repository_external_id TEXT NOT NULL,
    head_repository_owner TEXT NOT NULL,
    head_repository_name TEXT NOT NULL,
    head_branch_name TEXT NOT NULL,
    branch_ref TEXT NOT NULL,
    target_base_ref TEXT NOT NULL,
    expected_base_sha TEXT NOT NULL,
    proposed_head TEXT NOT NULL,
    required_ci_policy_revision_id TEXT NOT NULL,
    target_snapshot_digest TEXT NOT NULL UNIQUE,
    observed_at INTEGER NOT NULL,
    UNIQUE (target_snapshot_id, target_snapshot_digest),
    UNIQUE (target_snapshot_id, pr_id, target_snapshot_digest),
    UNIQUE (
        target_snapshot_id, pr_id, proposed_head,
        required_ci_policy_revision_id, target_snapshot_digest
    ),
    UNIQUE (
        target_snapshot_id, pr_id, launch_digest, proposed_head,
        required_ci_policy_revision_id, target_snapshot_digest
    ),
    UNIQUE (
        target_snapshot_id, pr_id, launch_digest, head_branch_name,
        expected_base_sha, proposed_head, required_ci_policy_revision_id,
        target_snapshot_digest
    ),
    CHECK (branch_ref = 'refs/heads/' || head_branch_name),
    FOREIGN KEY (task_id, launch_digest)
        REFERENCES flow_runtime_task (task_id, launch_digest),
    FOREIGN KEY (pr_id, task_id, repository_id, head_branch_name)
        REFERENCES flow_runtime_pr (
            pr_id, task_id, repository_id, branch_name
        ),
    FOREIGN KEY (required_ci_policy_revision_id)
        REFERENCES flow_ci_policy_revision (policy_revision_id)
);

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
    UNIQUE (
        plan_id, authorization_id, plan_digest,
        required_ci_policy_revision_id
    ),
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
    ),
    FOREIGN KEY (plan_id, operation_id, kind)
        REFERENCES flow_github_effect_plan_envelope (
            plan_id, operation_id, kind
        )
);

CREATE TABLE flow_github_initial_publish_plan (
    plan_id TEXT PRIMARY KEY,
    operation_id TEXT NOT NULL UNIQUE,
    kind TEXT NOT NULL CHECK (kind = 'INITIAL_PUBLISH'),
    authorization_id TEXT NOT NULL UNIQUE,
    pr_id TEXT NOT NULL,
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
    change_set_revision_id TEXT NOT NULL,
    draft_revision_id TEXT NOT NULL,
    draft_digest TEXT NOT NULL,
    required_ci_policy_revision_id TEXT NOT NULL,
    ready_policy TEXT NOT NULL CHECK (
        ready_policy IN ('KEEP_DRAFT', 'MARK_READY_ON_EXACT_GREEN')
    ),
    target_snapshot_id TEXT NOT NULL,
    target_snapshot_digest TEXT NOT NULL,
    action_ref TEXT NOT NULL,
    action_digest TEXT NOT NULL,
    plan_digest TEXT NOT NULL UNIQUE,
    created_at INTEGER NOT NULL,
    UNIQUE (plan_id, operation_id),
    UNIQUE (plan_id, operation_id, proposed_head),
    UNIQUE (
        plan_id, operation_id, proposed_head, expected_base_sha
    ),
    UNIQUE (plan_id, action_ref, action_digest),
    UNIQUE (
        plan_id, operation_id, authorization_id, pr_id,
        action_digest, proposed_head, required_ci_policy_revision_id,
        ready_policy
    ),
    UNIQUE (
        plan_id, pr_id, change_set_revision_id, proposed_head,
        draft_revision_id, draft_digest, action_ref, action_digest
    ),
    FOREIGN KEY (plan_id, operation_id, kind)
        REFERENCES flow_github_effect_plan_envelope (
            plan_id, operation_id, kind
        ),
    FOREIGN KEY (
        action_ref, action_digest, pr_id, change_set_revision_id,
        base_repository_external_id, base_repository_owner,
        base_repository_name, head_repository_external_id,
        head_repository_owner, head_repository_name, branch_ref,
        target_base_ref, expected_base_sha, proposed_head,
        draft_revision_id, draft_digest, required_ci_policy_revision_id,
        ready_policy, target_snapshot_id, target_snapshot_digest
    )
        REFERENCES flow_user_gate_initial_publish_action (
            action_ref, action_digest, pr_id, change_set_revision_id,
            base_repository_external_id, base_repository_owner,
            base_repository_name, head_repository_external_id,
            head_repository_owner, head_repository_name, branch_ref,
            target_base_ref, expected_base_sha, proposed_head,
            draft_revision_id, draft_digest, required_ci_policy_revision_id,
            ready_policy, target_snapshot_id, target_snapshot_digest
        ),
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
        target_snapshot_id, pr_id, proposed_head,
        required_ci_policy_revision_id, target_snapshot_digest
    )
        REFERENCES flow_github_initial_publish_target_snapshot (
            target_snapshot_id, pr_id, proposed_head,
            required_ci_policy_revision_id, target_snapshot_digest
        )
);

CREATE TABLE flow_github_initial_publish_step (
    step_id TEXT PRIMARY KEY,
    plan_id TEXT NOT NULL,
    ordinal INTEGER NOT NULL CHECK (ordinal IN (1, 2)),
    kind TEXT NOT NULL CHECK (
        kind IN ('CREATE_REF_EXACT', 'CREATE_DRAFT_PR')
    ),
    step_digest TEXT NOT NULL UNIQUE,
    UNIQUE (plan_id, ordinal),
    UNIQUE (step_id, plan_id),
    UNIQUE (step_id, plan_id, ordinal, kind),
    UNIQUE (step_id, plan_id, ordinal, kind, step_digest),
    CHECK (
        (ordinal = 1 AND kind = 'CREATE_REF_EXACT')
        OR (ordinal = 2 AND kind = 'CREATE_DRAFT_PR')
    ),
    FOREIGN KEY (plan_id)
        REFERENCES flow_github_initial_publish_plan (plan_id)
);

CREATE TABLE flow_github_initial_publish_attempt (
    attempt_id TEXT PRIMARY KEY,
    operation_id TEXT NOT NULL,
    plan_id TEXT NOT NULL,
    step_id TEXT NOT NULL,
    step_ordinal INTEGER NOT NULL,
    step_kind TEXT NOT NULL,
    attempt_number INTEGER NOT NULL CHECK (attempt_number BETWEEN 1 AND 2),
    claim_generation INTEGER NOT NULL CHECK (claim_generation > 0),
    claim_token_digest TEXT NOT NULL,
    request_digest TEXT NOT NULL,
    execution_token_digest TEXT NOT NULL,
    activated_at INTEGER NOT NULL,
    UNIQUE (step_id, attempt_number),
    UNIQUE (operation_id, claim_generation),
    UNIQUE (attempt_id, operation_id, plan_id, step_id),
    FOREIGN KEY (plan_id, operation_id)
        REFERENCES flow_github_initial_publish_plan (plan_id, operation_id),
    FOREIGN KEY (step_id, plan_id, step_ordinal, step_kind)
        REFERENCES flow_github_initial_publish_step (
            step_id, plan_id, ordinal, kind
        )
);

CREATE TABLE flow_github_initial_publish_probe (
    probe_id TEXT PRIMARY KEY,
    operation_id TEXT NOT NULL,
    plan_id TEXT NOT NULL,
    step_id TEXT NOT NULL,
    attempt_id TEXT,
    claim_generation INTEGER NOT NULL CHECK (claim_generation > 0),
    claim_token_digest TEXT NOT NULL,
    probe_number INTEGER NOT NULL CHECK (probe_number > 0),
    step_ordinal INTEGER NOT NULL CHECK (step_ordinal IN (1, 2)),
    step_kind TEXT NOT NULL CHECK (
        step_kind IN ('CREATE_REF_EXACT', 'CREATE_DRAFT_PR')
    ),
    outcome TEXT NOT NULL CHECK (
        outcome IN ('APPLIED', 'ABSENT', 'DIVERGED', 'UNKNOWN')
    ),
    observed_head TEXT,
    observation_digest TEXT NOT NULL UNIQUE,
    observed_at INTEGER NOT NULL,
    UNIQUE (operation_id, claim_generation, probe_number),
    UNIQUE (probe_id, operation_id, plan_id, step_id, attempt_id),
    UNIQUE (
        probe_id, operation_id, plan_id, step_id, attempt_id,
        step_ordinal, step_kind, outcome, observed_head, observation_digest
    ),
    UNIQUE (
        probe_id, operation_id, plan_id, step_id,
        step_ordinal, step_kind, outcome, observed_head, observation_digest
    ),
    CHECK (outcome NOT IN ('APPLIED', 'DIVERGED')
        OR observed_head IS NOT NULL),
    CHECK (outcome NOT IN ('ABSENT', 'UNKNOWN') OR observed_head IS NULL),
    FOREIGN KEY (plan_id, operation_id)
        REFERENCES flow_github_initial_publish_plan (plan_id, operation_id),
    FOREIGN KEY (step_id, plan_id, step_ordinal, step_kind)
        REFERENCES flow_github_initial_publish_step (
            step_id, plan_id, ordinal, kind
        ),
    FOREIGN KEY (attempt_id, operation_id, plan_id, step_id)
        REFERENCES flow_github_initial_publish_attempt (
            attempt_id, operation_id, plan_id, step_id
        )
);

CREATE TABLE flow_github_initial_pr_observation (
    probe_id TEXT PRIMARY KEY,
    operation_id TEXT NOT NULL,
    plan_id TEXT NOT NULL,
    step_id TEXT NOT NULL,
    attempt_id TEXT,
    step_ordinal INTEGER NOT NULL CHECK (step_ordinal = 2),
    step_kind TEXT NOT NULL CHECK (step_kind = 'CREATE_DRAFT_PR'),
    outcome TEXT NOT NULL CHECK (outcome IN ('APPLIED', 'DIVERGED')),
    observed_head TEXT NOT NULL,
    state TEXT NOT NULL,
    draft INTEGER NOT NULL CHECK (draft IN (0, 1)),
    base_repository_external_id TEXT NOT NULL,
    base_repository_owner TEXT NOT NULL,
    base_repository_name TEXT NOT NULL,
    head_repository_external_id TEXT NOT NULL,
    head_repository_owner TEXT NOT NULL,
    head_repository_name TEXT NOT NULL,
    head_branch_ref TEXT NOT NULL,
    target_base_ref TEXT NOT NULL,
    pr_number INTEGER NOT NULL CHECK (pr_number > 0),
    pr_node_id TEXT NOT NULL,
    html_url TEXT NOT NULL,
    observed_base_sha TEXT NOT NULL,
    observed_title_digest TEXT NOT NULL,
    observed_body_digest TEXT NOT NULL,
    first_pass_digest TEXT NOT NULL,
    second_pass_digest TEXT NOT NULL,
    observation_digest TEXT NOT NULL UNIQUE,
    CHECK (first_pass_digest = second_pass_digest),
    UNIQUE (
        probe_id, operation_id, plan_id, step_id, attempt_id,
        step_ordinal, step_kind, outcome, observed_head,
        state, draft,
        base_repository_external_id, base_repository_owner,
        base_repository_name, head_repository_external_id,
        head_repository_owner, head_repository_name,
        head_branch_ref, target_base_ref,
        pr_number, pr_node_id, html_url,
        observed_base_sha, observed_title_digest,
        observed_body_digest, first_pass_digest, second_pass_digest,
        observation_digest
    ),
    CHECK (outcome <> 'APPLIED' OR attempt_id IS NOT NULL),
    FOREIGN KEY (
        probe_id, operation_id, plan_id, step_id,
        step_ordinal, step_kind, outcome, observed_head, observation_digest
    )
        REFERENCES flow_github_initial_publish_probe (
            probe_id, operation_id, plan_id, step_id,
            step_ordinal, step_kind, outcome, observed_head, observation_digest
        ),
    FOREIGN KEY (attempt_id, operation_id, plan_id, step_id)
        REFERENCES flow_github_initial_publish_attempt (
            attempt_id, operation_id, plan_id, step_id
        )
);

CREATE TABLE flow_github_initial_publish_step_receipt (
    receipt_id TEXT PRIMARY KEY,
    operation_id TEXT NOT NULL,
    plan_id TEXT NOT NULL,
    step_id TEXT NOT NULL UNIQUE,
    attempt_id TEXT NOT NULL UNIQUE,
    probe_id TEXT NOT NULL UNIQUE,
    step_ordinal INTEGER NOT NULL,
    step_kind TEXT NOT NULL,
    probe_outcome TEXT NOT NULL CHECK (probe_outcome = 'APPLIED'),
    proposed_head TEXT NOT NULL,
    observation_digest TEXT NOT NULL,
    receipt_digest TEXT NOT NULL UNIQUE,
    recorded_at INTEGER NOT NULL,
    UNIQUE (plan_id, step_ordinal),
    UNIQUE (receipt_id, operation_id, plan_id),
    UNIQUE (
        receipt_id, operation_id, plan_id, step_ordinal, step_kind
    ),
    UNIQUE (
        receipt_id, operation_id, plan_id, step_ordinal, step_kind,
        proposed_head
    ),
    UNIQUE (receipt_id, operation_id, plan_id, step_id),
    UNIQUE (
        receipt_id, operation_id, plan_id, step_id, attempt_id, probe_id,
        step_ordinal, step_kind, probe_outcome, proposed_head,
        observation_digest
    ),
    FOREIGN KEY (plan_id, operation_id)
        REFERENCES flow_github_initial_publish_plan (plan_id, operation_id),
    FOREIGN KEY (plan_id, operation_id, proposed_head)
        REFERENCES flow_github_initial_publish_plan (
            plan_id, operation_id, proposed_head
        ),
    FOREIGN KEY (step_id, plan_id, step_ordinal, step_kind)
        REFERENCES flow_github_initial_publish_step (
            step_id, plan_id, ordinal, kind
        ),
    FOREIGN KEY (attempt_id, operation_id, plan_id, step_id)
        REFERENCES flow_github_initial_publish_attempt (
            attempt_id, operation_id, plan_id, step_id
        ),
    FOREIGN KEY (
        probe_id, operation_id, plan_id, step_id,
        attempt_id, step_ordinal, step_kind, probe_outcome,
        proposed_head, observation_digest
    ) REFERENCES flow_github_initial_publish_probe (
        probe_id, operation_id, plan_id, step_id,
        attempt_id, step_ordinal, step_kind, outcome,
        observed_head, observation_digest
    )
);

CREATE TABLE flow_github_initial_pr_receipt_detail (
    receipt_id TEXT PRIMARY KEY,
    operation_id TEXT NOT NULL,
    plan_id TEXT NOT NULL,
    step_id TEXT NOT NULL,
    attempt_id TEXT NOT NULL,
    probe_id TEXT NOT NULL,
    step_ordinal INTEGER NOT NULL CHECK (step_ordinal = 2),
    step_kind TEXT NOT NULL CHECK (step_kind = 'CREATE_DRAFT_PR'),
    outcome TEXT NOT NULL CHECK (outcome = 'APPLIED'),
    proposed_head TEXT NOT NULL,
    state TEXT NOT NULL CHECK (state = 'OPEN'),
    draft INTEGER NOT NULL CHECK (draft = 1),
    base_repository_external_id TEXT NOT NULL,
    base_repository_owner TEXT NOT NULL,
    base_repository_name TEXT NOT NULL,
    head_repository_external_id TEXT NOT NULL,
    head_repository_owner TEXT NOT NULL,
    head_repository_name TEXT NOT NULL,
    head_branch_ref TEXT NOT NULL,
    target_base_ref TEXT NOT NULL,
    pr_number INTEGER NOT NULL CHECK (pr_number > 0),
    pr_node_id TEXT NOT NULL,
    html_url TEXT NOT NULL,
    observed_base_sha TEXT NOT NULL,
    observed_title_digest TEXT NOT NULL,
    observed_body_digest TEXT NOT NULL,
    first_pass_digest TEXT NOT NULL,
    second_pass_digest TEXT NOT NULL,
    observation_digest TEXT NOT NULL,
    CHECK (first_pass_digest = second_pass_digest),
    UNIQUE (receipt_id, operation_id, plan_id),
    UNIQUE (
        receipt_id, operation_id, plan_id, proposed_head,
        pr_number, pr_node_id, html_url, observed_base_sha
    ),
    FOREIGN KEY (
        receipt_id, operation_id, plan_id, step_id, attempt_id, probe_id,
        step_ordinal, step_kind, outcome, proposed_head,
        observation_digest
    )
        REFERENCES flow_github_initial_publish_step_receipt (
            receipt_id, operation_id, plan_id, step_id, attempt_id, probe_id,
            step_ordinal, step_kind, probe_outcome, proposed_head,
            observation_digest
        ),
    FOREIGN KEY (
        probe_id, operation_id, plan_id, step_id, attempt_id,
        step_ordinal, step_kind, outcome, proposed_head,
        state, draft,
        base_repository_external_id, base_repository_owner,
        base_repository_name, head_repository_external_id,
        head_repository_owner, head_repository_name,
        head_branch_ref, target_base_ref,
        pr_number, pr_node_id, html_url, observed_base_sha,
        observed_title_digest, observed_body_digest,
        first_pass_digest, second_pass_digest, observation_digest
    ) REFERENCES flow_github_initial_pr_observation (
        probe_id, operation_id, plan_id, step_id, attempt_id,
        step_ordinal, step_kind, outcome, observed_head,
        state, draft,
        base_repository_external_id, base_repository_owner,
        base_repository_name, head_repository_external_id,
        head_repository_owner, head_repository_name,
        head_branch_ref, target_base_ref,
        pr_number, pr_node_id, html_url, observed_base_sha,
        observed_title_digest, observed_body_digest,
        first_pass_digest, second_pass_digest, observation_digest
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

CREATE TABLE flow_github_effect_receipt_envelope (
    receipt_id TEXT PRIMARY KEY,
    operation_id TEXT NOT NULL UNIQUE,
    plan_id TEXT NOT NULL,
    kind TEXT NOT NULL CHECK (kind IN ('INITIAL_PUBLISH', 'CI_UPDATE')),
    proposed_head TEXT NOT NULL,
    receipt_digest TEXT NOT NULL UNIQUE,
    recorded_at INTEGER NOT NULL,
    UNIQUE (receipt_id, plan_id, receipt_digest, proposed_head),
    FOREIGN KEY (plan_id, operation_id, kind)
        REFERENCES flow_github_effect_plan_envelope (
            plan_id, operation_id, kind
        )
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
    UNIQUE (receipt_id, plan_id, receipt_digest, proposed_head),
    UNIQUE (
        receipt_id, operation_id, plan_id, receipt_digest,
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
    CHECK (observed_head = proposed_head),
    FOREIGN KEY (receipt_id, plan_id, receipt_digest, proposed_head)
        REFERENCES flow_github_effect_receipt_envelope (
            receipt_id, plan_id, receipt_digest, proposed_head
        )
);

CREATE TABLE flow_github_initial_publish_receipt (
    receipt_id TEXT PRIMARY KEY,
    operation_id TEXT NOT NULL UNIQUE,
    plan_id TEXT NOT NULL,
    branch_receipt_id TEXT NOT NULL UNIQUE,
    branch_step_ordinal INTEGER NOT NULL CHECK (branch_step_ordinal = 1),
    branch_step_kind TEXT NOT NULL CHECK (
        branch_step_kind = 'CREATE_REF_EXACT'
    ),
    pr_step_receipt_id TEXT NOT NULL UNIQUE,
    pr_step_ordinal INTEGER NOT NULL CHECK (pr_step_ordinal = 2),
    pr_step_kind TEXT NOT NULL CHECK (
        pr_step_kind = 'CREATE_DRAFT_PR'
    ),
    proposed_head TEXT NOT NULL,
    pr_number INTEGER NOT NULL CHECK (pr_number > 0),
    pr_node_id TEXT NOT NULL,
    html_url TEXT NOT NULL,
    observed_base_sha TEXT NOT NULL,
    receipt_digest TEXT NOT NULL UNIQUE,
    recorded_at INTEGER NOT NULL,
    UNIQUE (receipt_id, plan_id, receipt_digest, proposed_head),
    UNIQUE (
        receipt_id, operation_id, plan_id, proposed_head,
        receipt_digest
    ),
    FOREIGN KEY (receipt_id, plan_id, receipt_digest, proposed_head)
        REFERENCES flow_github_effect_receipt_envelope (
            receipt_id, plan_id, receipt_digest, proposed_head
        ),
    FOREIGN KEY (plan_id, operation_id)
        REFERENCES flow_github_initial_publish_plan (plan_id, operation_id),
    FOREIGN KEY (
        plan_id, operation_id, proposed_head, observed_base_sha
    ) REFERENCES flow_github_initial_publish_plan (
        plan_id, operation_id, proposed_head, expected_base_sha
    ),
    FOREIGN KEY (
        branch_receipt_id, operation_id, plan_id,
        branch_step_ordinal, branch_step_kind, proposed_head
    )
        REFERENCES flow_github_initial_publish_step_receipt (
            receipt_id, operation_id, plan_id, step_ordinal, step_kind,
            proposed_head
        ),
    FOREIGN KEY (
        pr_step_receipt_id, operation_id, plan_id,
        pr_step_ordinal, pr_step_kind, proposed_head
    )
        REFERENCES flow_github_initial_publish_step_receipt (
            receipt_id, operation_id, plan_id, step_ordinal, step_kind,
            proposed_head
        ),
    FOREIGN KEY (
        pr_step_receipt_id, operation_id, plan_id, proposed_head,
        pr_number, pr_node_id, html_url, observed_base_sha
    )
        REFERENCES flow_github_initial_pr_receipt_detail (
            receipt_id, operation_id, plan_id, proposed_head,
            pr_number, pr_node_id, html_url, observed_base_sha
        )
);

CREATE TABLE flow_github_initial_base_preflight (
    preflight_id TEXT PRIMARY KEY,
    operation_id TEXT NOT NULL,
    plan_id TEXT NOT NULL,
    step_id TEXT NOT NULL,
    claim_generation INTEGER NOT NULL CHECK (claim_generation > 0),
    claim_token_digest TEXT NOT NULL,
    expected_base_sha TEXT NOT NULL,
    observed_base_sha TEXT NOT NULL,
    preflight_digest TEXT NOT NULL UNIQUE,
    observed_at INTEGER NOT NULL,
    UNIQUE (operation_id, claim_generation, step_id),
    UNIQUE (
        preflight_id, operation_id, plan_id, step_id,
        claim_generation, claim_token_digest, expected_base_sha,
        observed_base_sha, preflight_digest
    ),
    FOREIGN KEY (plan_id, operation_id)
        REFERENCES flow_github_initial_publish_plan (plan_id, operation_id),
    FOREIGN KEY (step_id, plan_id)
        REFERENCES flow_github_initial_publish_step (step_id, plan_id),
    CHECK (observed_base_sha <> expected_base_sha)
);

CREATE TABLE flow_github_initial_publish_partial_receipt (
    partial_receipt_id TEXT PRIMARY KEY,
    operation_id TEXT NOT NULL UNIQUE,
    plan_id TEXT NOT NULL,
    kind TEXT NOT NULL CHECK (
        kind IN (
            'BRANCH_ONLY_BASE_DRIFT', 'BRANCH_ONLY_STALE',
            'CREATED_PR_STALE'
        )
    ),
    reason_code TEXT NOT NULL CHECK (
        reason_code IN (
            'REMOTE_BASE_DRIFT', 'TASK_HEAD_DRIFT',
            'TASK_BASE_DRIFT', 'REQUIRED_CI_POLICY_DRIFT',
            'PR_OWNER_DRIFT', 'LOCAL_AUTHORITY_DRIFT',
            'REMOTE_STEP_INVALID'
        )
    ),
    attention_detail TEXT NOT NULL,
    branch_receipt_id TEXT NOT NULL UNIQUE,
    branch_step_ordinal INTEGER NOT NULL CHECK (branch_step_ordinal = 1),
    branch_step_kind TEXT NOT NULL CHECK (
        branch_step_kind = 'CREATE_REF_EXACT'
    ),
    pr_step_receipt_id TEXT UNIQUE,
    pr_step_ordinal INTEGER CHECK (pr_step_ordinal = 2),
    pr_step_kind TEXT CHECK (pr_step_kind = 'CREATE_DRAFT_PR'),
    base_preflight_id TEXT UNIQUE,
    base_preflight_step_id TEXT,
    base_preflight_claim_generation INTEGER,
    base_preflight_claim_token_digest TEXT,
    base_preflight_digest TEXT,
    proposed_head TEXT NOT NULL,
    expected_base_sha TEXT NOT NULL,
    observed_base_sha TEXT NOT NULL,
    pr_number INTEGER,
    pr_node_id TEXT,
    html_url TEXT,
    partial_digest TEXT NOT NULL UNIQUE,
    recorded_at INTEGER NOT NULL,
    UNIQUE (partial_receipt_id, operation_id, plan_id, kind, partial_digest),
    FOREIGN KEY (plan_id, operation_id)
        REFERENCES flow_github_initial_publish_plan (plan_id, operation_id),
    FOREIGN KEY (
        branch_receipt_id, operation_id, plan_id,
        branch_step_ordinal, branch_step_kind, proposed_head
    ) REFERENCES flow_github_initial_publish_step_receipt (
        receipt_id, operation_id, plan_id, step_ordinal, step_kind,
        proposed_head
    ),
    FOREIGN KEY (
        pr_step_receipt_id, operation_id, plan_id,
        pr_step_ordinal, pr_step_kind, proposed_head
    ) REFERENCES flow_github_initial_publish_step_receipt (
        receipt_id, operation_id, plan_id, step_ordinal, step_kind,
        proposed_head
    ),
    FOREIGN KEY (
        base_preflight_id, operation_id, plan_id, base_preflight_step_id,
        base_preflight_claim_generation, base_preflight_claim_token_digest,
        expected_base_sha, observed_base_sha, base_preflight_digest
    ) REFERENCES flow_github_initial_base_preflight (
        preflight_id, operation_id, plan_id, step_id,
        claim_generation, claim_token_digest, expected_base_sha,
        observed_base_sha, preflight_digest
    ),
    FOREIGN KEY (
        pr_step_receipt_id, operation_id, plan_id, proposed_head,
        pr_number, pr_node_id, html_url, observed_base_sha
    ) REFERENCES flow_github_initial_pr_receipt_detail (
        receipt_id, operation_id, plan_id, proposed_head,
        pr_number, pr_node_id, html_url, observed_base_sha
    ),
    FOREIGN KEY (plan_id, operation_id, proposed_head, expected_base_sha)
        REFERENCES flow_github_initial_publish_plan (
            plan_id, operation_id, proposed_head, expected_base_sha
        ),
    CHECK (
        (kind = 'BRANCH_ONLY_BASE_DRIFT'
            AND reason_code = 'REMOTE_BASE_DRIFT'
            AND pr_step_receipt_id IS NULL
            AND pr_step_ordinal IS NULL AND pr_step_kind IS NULL
            AND base_preflight_id IS NOT NULL
            AND base_preflight_step_id IS NOT NULL
            AND base_preflight_claim_generation IS NOT NULL
            AND base_preflight_claim_token_digest IS NOT NULL
            AND base_preflight_digest IS NOT NULL
            AND pr_number IS NULL AND pr_node_id IS NULL AND html_url IS NULL
            AND observed_base_sha <> expected_base_sha)
        OR (kind = 'BRANCH_ONLY_STALE'
            AND reason_code IN (
                'LOCAL_AUTHORITY_DRIFT', 'REMOTE_STEP_INVALID'
            )
            AND pr_step_receipt_id IS NULL
            AND pr_step_ordinal IS NULL AND pr_step_kind IS NULL
            AND base_preflight_id IS NULL
            AND base_preflight_step_id IS NULL
            AND base_preflight_claim_generation IS NULL
            AND base_preflight_claim_token_digest IS NULL
            AND base_preflight_digest IS NULL
            AND pr_number IS NULL AND pr_node_id IS NULL AND html_url IS NULL
            AND observed_base_sha = expected_base_sha)
        OR (kind = 'CREATED_PR_STALE'
            AND pr_step_receipt_id IS NOT NULL
            AND pr_step_ordinal = 2
            AND pr_step_kind = 'CREATE_DRAFT_PR'
            AND base_preflight_id IS NULL
            AND base_preflight_step_id IS NULL
            AND base_preflight_claim_generation IS NULL
            AND base_preflight_claim_token_digest IS NULL
            AND base_preflight_digest IS NULL
            AND pr_number IS NOT NULL AND pr_node_id IS NOT NULL
            AND html_url IS NOT NULL
            AND (reason_code <> 'REMOTE_BASE_DRIFT'
                OR observed_base_sha <> expected_base_sha))
    )
);
