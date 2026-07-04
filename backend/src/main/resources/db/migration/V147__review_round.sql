-- ReviewRound: one reviewer batch + the agent's entire response (triage,
-- fix commits, drafted replies, nested ci_fix), gated behind a single
-- posting approval (plan-rail-runs.md R11-R13). run_id is the round's own
-- agent_run(kind=review_round) row, set once the run opens.
CREATE TABLE review_round (
    id             TEXT    NOT NULL PRIMARY KEY,
    task_id        TEXT    NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    idx            INTEGER NOT NULL,                 -- round 1, 2, 3...
    reviewers_json TEXT,                             -- jsonb array of "@handle"
    status         TEXT    NOT NULL,                 -- triaging | addressing | awaiting_gate | posted | closed
    stats_json     TEXT,                             -- jsonb: fixed, replied, pushedBack, open
    run_id         TEXT,                             -- the round's own agent_run row
    opened_at_ms   INTEGER NOT NULL,
    gated_at_ms    INTEGER,                           -- when drafts became ready
    posted_at_ms   INTEGER                            -- when the user approved the gate
);
CREATE INDEX idx_review_round_task ON review_round(task_id, opened_at_ms);

-- review_comment gets a round assignment (null until grouped into a round)
-- and a drafted-reply slot: the review_round agent drafts a reply body
-- locally via record_round_reply; nothing posts until the round's gate
-- approval. remote_comment_id is the raw GitHub id needed to actually post
-- the reply on approval — remote_link (V116) is a human-readable dedup key
-- built from the same id but not parseable back out reliably.
ALTER TABLE review_comment ADD COLUMN round_id TEXT REFERENCES review_round(id);
ALTER TABLE review_comment ADD COLUMN remote_comment_id INTEGER;
ALTER TABLE review_comment ADD COLUMN draft_reply_body TEXT;
ALTER TABLE review_comment ADD COLUMN draft_reply_created_at_ms INTEGER;
CREATE INDEX idx_review_comment_round ON review_comment(round_id);
