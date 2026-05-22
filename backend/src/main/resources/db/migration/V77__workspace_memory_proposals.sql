-- Risk-gate for the workspace-memory distiller. The Haiku pass used
-- to call WorkspaceService.setMemory directly every 30 minutes,
-- silently replacing the markdown body even when the user had
-- hand-edited WORKSPACE.md in between. The design row at
-- "Risk-gated. Appending a new decision is auto; editing/deleting
-- existing memory needs confirm" and Phase 3 acceptance
-- ("distillation proposes (doesn't silently overwrite)") both
-- require a propose/confirm step.
--
-- This table holds at most one pending proposal per workspace.
-- workspace_id is the PK so a fresh distillation pass simply
-- upserts; the user resolves the proposal by applying it
-- (memory_md ← proposed_md) or discarding it (delete the row).
-- current_md is the memory_md as it was at proposal time, used by
-- the apply step to drift-check: if the live memory_md has since
-- changed (user hand-edit) the apply refuses with 409 so the
-- proposal can't clobber the edit.

CREATE TABLE workspace_memory_proposals (
    workspace_id        TEXT    PRIMARY KEY REFERENCES workspaces(id) ON DELETE CASCADE,
    current_md          TEXT    NOT NULL,
    proposed_md         TEXT    NOT NULL,
    summariser_model    TEXT    NOT NULL,
    prompt_tokens       INTEGER NOT NULL DEFAULT 0,
    completion_tokens   INTEGER NOT NULL DEFAULT 0,
    cost_usd_milli      INTEGER NOT NULL DEFAULT 0,
    created_at_ms       INTEGER NOT NULL
);
