CREATE TABLE flow_upstream_sync_request (
    request_id TEXT PRIMARY KEY,
    request_key TEXT NOT NULL UNIQUE,
    repository_id TEXT NOT NULL,
    goal_text TEXT NOT NULL,
    source_remote TEXT NOT NULL,
    source_from_ref TEXT NOT NULL,
    source_to_ref TEXT NOT NULL,
    target_ref TEXT NOT NULL,
    selected_upstream_shas_json TEXT NOT NULL,
    -- Display only, aligned by position with the shas above: the run surface
    -- names the range by its endpoint subjects, and a waiting pick has no
    -- commit of its own to read one from. The shas stay the contract.
    selected_subjects_json TEXT NOT NULL DEFAULT '[]',
    state TEXT NOT NULL CHECK (
        state IN ('REQUESTED', 'STARTED', 'CANCELED', 'NEEDS_ATTENTION')
    ),
    requested_by_user_id TEXT,
    created_at INTEGER NOT NULL
);

CREATE TABLE flow_upstream_sync_run (
    run_id TEXT PRIMARY KEY,
    request_id TEXT NOT NULL UNIQUE,
    task_id TEXT NOT NULL UNIQUE,
    -- Program-resolved per Task. A sync branch is a reviewable series, so a
    -- repair belongs behind the pick it fixes; an ordinary Task keeps TIP and
    -- never reads this table.
    repair_placement TEXT NOT NULL CHECK (
        repair_placement IN ('TIP', 'ATTRIBUTED_FIXUP')
    ),
    state TEXT NOT NULL CHECK (
        state IN (
            'READY',
            'PICKING',
            'WAITING_CONFLICT_REPAIR',
            'WAITING_USER',
            'FINAL_REVIEW',
            'WAITING_INITIAL_PUBLISH',
            'HANDED_OFF',
            'CANCELED',
            'NEEDS_ATTENTION'
        )
    ),
    -- The whole of phase 1's bound. There is deliberately no round or pick
    -- ceiling: a large range legitimately needs many repairs.
    remaining_repair_turns INTEGER NOT NULL CHECK (remaining_repair_turns >= 0),
    current_index INTEGER NOT NULL DEFAULT 0 CHECK (current_index >= 0),
    current_head TEXT,
    park_reason TEXT,
    verification_ref TEXT,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    FOREIGN KEY (request_id)
        REFERENCES flow_upstream_sync_request (request_id),
    FOREIGN KEY (task_id) REFERENCES flow_runtime_task (task_id)
);

CREATE TABLE flow_upstream_pick (
    pick_id TEXT PRIMARY KEY,
    run_id TEXT NOT NULL,
    ordinal INTEGER NOT NULL CHECK (ordinal >= 0),
    upstream_sha TEXT NOT NULL,
    pre_head TEXT NOT NULL,
    result_head TEXT,
    result_commit_sha TEXT,
    state TEXT NOT NULL CHECK (
        state IN (
            'CLEAN',
            'CONFLICTED',
            'RESOLVED',
            'SKIPPED_EMPTY',
            'NEEDS_ATTENTION'
        )
    ),
    conflicted_paths_json TEXT NOT NULL DEFAULT '[]',
    provenance_verified INTEGER NOT NULL DEFAULT 0
        CHECK (provenance_verified IN (0, 1)),
    change_set_revision_id TEXT,
    recorded_at INTEGER NOT NULL,
    -- An empty pick records no commit; every other outcome must.
    CHECK (
        (state = 'SKIPPED_EMPTY'
            AND result_commit_sha IS NULL AND result_head IS NULL)
        OR (state <> 'SKIPPED_EMPTY'
            AND result_commit_sha IS NOT NULL AND result_head IS NOT NULL)
    ),
    UNIQUE (run_id, ordinal),
    UNIQUE (run_id, upstream_sha),
    FOREIGN KEY (run_id) REFERENCES flow_upstream_sync_run (run_id),
    FOREIGN KEY (change_set_revision_id)
        REFERENCES flow_runtime_change_set_revision (change_set_revision_id)
);

CREATE TABLE flow_upstream_fixup (
    fixup_id TEXT PRIMARY KEY,
    run_id TEXT NOT NULL,
    -- One row per owner, so a pick can never carry two fixups. A later repair
    -- amends this row's commit rather than appending a second one.
    pick_id TEXT NOT NULL UNIQUE,
    owner_upstream_sha TEXT NOT NULL,
    kind TEXT NOT NULL CHECK (kind IN ('ADJACENT_FIXUP', 'STANDALONE')),
    current_commit_sha TEXT NOT NULL,
    changed_paths_json TEXT NOT NULL DEFAULT '[]',
    created_by_run_id TEXT,
    amend_count INTEGER NOT NULL DEFAULT 0 CHECK (amend_count >= 0),
    change_set_revision_id TEXT,
    recorded_at INTEGER NOT NULL,
    FOREIGN KEY (run_id) REFERENCES flow_upstream_sync_run (run_id),
    FOREIGN KEY (pick_id) REFERENCES flow_upstream_pick (pick_id),
    FOREIGN KEY (created_by_run_id)
        REFERENCES flow_runtime_agent_run (run_id),
    FOREIGN KEY (change_set_revision_id)
        REFERENCES flow_runtime_change_set_revision (change_set_revision_id)
);

CREATE INDEX flow_upstream_pick_by_run
    ON flow_upstream_pick (run_id, ordinal);
