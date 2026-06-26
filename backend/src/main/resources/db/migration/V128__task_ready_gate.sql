-- One-time sentinel: when a shipped draft PR's CI goes green, the lifecycle
-- driver parks a single "mark ready for review (+ request reviewers)" approval
-- gate. This stamp records that the gate was offered once, so a later sweep
-- (or a dismissed gate) never re-offers it. Distinct from the ready-to-merge
-- sentinel, which auto-resets; this one is set once and stays set.
ALTER TABLE tasks ADD COLUMN ready_gate_sent_at_ms INTEGER;
