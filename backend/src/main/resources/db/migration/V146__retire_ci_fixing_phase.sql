-- TaskPhase.CI_FIXING is retired: a red check no longer moves the task's
-- phase at all — a ci_fix AgentRun (see V145) fixes and re-pushes beside
-- whatever phase the task is already on. Rewrite every persisted CI_FIXING
-- value to PUSHED_AWAITING_CI (the phase it always held immediately before
-- CI went red) before the enum constant is deleted, so TaskPhase.valueOf()
-- never sees a value it can't parse.
UPDATE tasks SET phase = 'PUSHED_AWAITING_CI' WHERE phase = 'CI_FIXING';
UPDATE task_phase_event SET to_phase = 'PUSHED_AWAITING_CI' WHERE to_phase = 'CI_FIXING';
UPDATE task_phase_event SET from_phase = 'PUSHED_AWAITING_CI' WHERE from_phase = 'CI_FIXING';
