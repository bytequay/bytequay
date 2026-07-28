-- Workspace session and Task/Trunk activity reads poll these exact scopes.
-- Keep their AgentTurn ticket lookup bounded before correlated accounting.
CREATE INDEX idx_dispatch_ticket_agent_turn_workspace_v267
    ON dispatch_ticket(workspace_id, created_at_ms DESC, id DESC)
    WHERE async_family = 'AGENT_TURN';

CREATE INDEX idx_dispatch_ticket_agent_turn_task_v267
    ON dispatch_ticket(task_id, created_at_ms DESC, id DESC)
    WHERE async_family = 'AGENT_TURN';

CREATE INDEX idx_dispatch_ticket_agent_turn_trunk_v267
    ON dispatch_ticket(trunk_id, created_at_ms DESC, id DESC)
    WHERE async_family = 'AGENT_TURN';
