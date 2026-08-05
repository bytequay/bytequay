-- A conflicted pick is now repaired by an agent behind a per-commit compile
-- gate instead of being carried into the pull request unjudged.
--
-- The gate command itself is learned from the project's own CI config at
-- enqueue and kept on the row, so every attempt in a run compiles the way CI
-- does rather than the way this app guesses. (compile_script and ci_job_name
-- were added in V331 and never written to; this is what reclaims them.)

-- A compile command that cannot run at all — no toolchain, unreachable
-- repository, missing credentials — is not a red gate, and retrying it once
-- per commit would waste an entire range. The switch is therefore sticky, and
-- CI becomes the verdict for the rest of the run.
ALTER TABLE upstream_cherry_pick_job
    ADD COLUMN local_gate_unavailable INTEGER NOT NULL DEFAULT 0
    CHECK (local_gate_unavailable IN (0, 1));

-- Repair turns cost money against the run's budget. Tracking what was actually
-- spent is what lets a long unattended run be stopped at its cap, and what the
-- run view shows next to it.
ALTER TABLE upstream_cherry_pick_job
    ADD COLUMN spent_milli_usd INTEGER NOT NULL DEFAULT 0;
