-- Cache GitHub's per-check output.title / output.summary so the merge
-- bar's expandable failure cards can render the actual error message
-- without a fresh GitHub call. Both nullable: not every runner publishes
-- an output block.
ALTER TABLE pr_check_runs ADD COLUMN output_title TEXT;
ALTER TABLE pr_check_runs ADD COLUMN output_summary TEXT;
