-- Indexes for the cross-domain UI's active-review lookups (V108 hosting):
--   - the PR dashboard's "is a standalone (THREAD-hosted) review open on
--     this PR right now?" keyed on (repo_full_name, pr_number, host_kind,
--     phase);
--   - the task page's "active TASK_PHASE review pass for this task" keyed
--     on (host_kind, host_id, phase).
-- review_passes already carries repo_full_name + pr_number, so no
-- denormalised pr_ref column is needed.
CREATE INDEX review_pass_pr_phase_idx
    ON review_passes(repo_full_name, pr_number, host_kind, phase);
CREATE INDEX review_pass_host_phase_idx
    ON review_passes(host_kind, host_id, phase);
