-- Per-repo opt-in for the headless auto-fix runner. Off by default
-- (CLAUDE.md: "Auto-fix that pushes commits is opt-in per repo/PR,
-- off by default"). When 1, AutomationCoordinator will spawn a
-- headless CLI agent against the repo's free worktrees when their
-- linked PRs flip to failing CI; when 0 the coordinator only emits
-- a NEEDS_ATTENTION notification and waits for the human.

ALTER TABLE workspace_repos ADD COLUMN auto_fix_enabled INTEGER NOT NULL DEFAULT 0;
