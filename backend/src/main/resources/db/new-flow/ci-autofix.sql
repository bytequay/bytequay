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

CREATE TABLE flow_ci_round (
    round_id TEXT PRIMARY KEY,
    task_id TEXT NOT NULL,
    pr_id TEXT NOT NULL,
    remote_head TEXT NOT NULL,
    policy_revision_id TEXT NOT NULL,
    check_observation_ids_json TEXT NOT NULL,
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
    UNIQUE (pr_id, remote_head, policy_revision_id),
    FOREIGN KEY (policy_revision_id)
        REFERENCES flow_ci_policy_revision (policy_revision_id),
    FOREIGN KEY (superseded_by)
        REFERENCES flow_ci_round (round_id)
);

CREATE INDEX flow_ci_round_current_idx
    ON flow_ci_round (pr_id, remote_head, state);
