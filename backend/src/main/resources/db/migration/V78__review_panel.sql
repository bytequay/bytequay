-- Multi-agent review flow-type, Phase 1: schema only (single-reviewer
-- pass shipping first; the multi-reviewer state machine + UI land in
-- follow-up commits). Threads with flow='review' reference a PR and
-- own a review_pass; the pass holds the phase/round/cap state, the
-- panel of participants, the streamed conversation, and the
-- structured findings.
--
-- Per the workspace/thread/task design row "review threads reference
-- a PR, read-only, no worktree lease" — there's no task row here, just
-- the review_pass linked to the thread.

CREATE TABLE review_passes (
    id              TEXT    PRIMARY KEY,
    thread_id       TEXT    NOT NULL REFERENCES threads(id) ON DELETE CASCADE,
    repo_full_name  TEXT    NOT NULL,
    pr_number       INTEGER NOT NULL,
    -- Commit reviewed so a later run can detect "the PR has been
    -- updated since this review". Null while the kickoff fetch is
    -- still in flight.
    head_sha        TEXT,
    -- KICKOFF | INDEPENDENT | CROSS_REVIEW | CONSENSUS | DEBATE
    -- | TERMINATE | ARBITRATE | PUBLISHED
    phase           TEXT    NOT NULL,
    round           INTEGER NOT NULL DEFAULT 0,
    round_cap       INTEGER NOT NULL DEFAULT 3,
    cost_cap_milli  INTEGER NOT NULL DEFAULT 500,
    cost_usd_milli  INTEGER NOT NULL DEFAULT 0,
    -- approve | request_changes | comment; null until the user confirms
    -- a suggested verdict via the publish gate.
    verdict         TEXT,
    created_at_ms   INTEGER NOT NULL,
    ended_at_ms     INTEGER
);

CREATE INDEX idx_review_passes_thread     ON review_passes(thread_id);
CREATE INDEX idx_review_passes_repo_pr    ON review_passes(repo_full_name, pr_number);

CREATE TABLE review_participants (
    id              TEXT    PRIMARY KEY,
    review_pass_id  TEXT    NOT NULL REFERENCES review_passes(id) ON DELETE CASCADE,
    -- moderator | reviewer | human
    kind            TEXT    NOT NULL,
    -- FK into the AI credentials store (review-role credential).
    -- Null for the moderator + human participants.
    credential_id   TEXT,
    -- Display label: "GPT-5", "Claude", "You", "Moderator".
    persona_label   TEXT    NOT NULL,
    model           TEXT,
    -- Optional hex colour for the persona bubble (panel UI). Null
    -- when the renderer should pick a default for this position.
    color           TEXT,
    created_at_ms   INTEGER NOT NULL
);

CREATE INDEX idx_review_participants_pass ON review_participants(review_pass_id);

CREATE TABLE review_messages (
    id              TEXT    PRIMARY KEY,
    review_pass_id  TEXT    NOT NULL REFERENCES review_passes(id) ON DELETE CASCADE,
    participant_id  TEXT    NOT NULL REFERENCES review_participants(id),
    -- Same phase token as review_passes.phase; the message snapshots
    -- which phase produced it so a later read can group them.
    phase           TEXT    NOT NULL,
    round           INTEGER NOT NULL,
    body            TEXT    NOT NULL,
    -- JSON array of participant ids the message targets (@mention).
    mentions        TEXT,
    -- JSON array of review_messages ids the message quotes (#ref).
    refs            TEXT,
    cost_usd_milli  INTEGER NOT NULL DEFAULT 0,
    created_at_ms   INTEGER NOT NULL
);

CREATE INDEX idx_review_messages_pass     ON review_messages(review_pass_id, created_at_ms);

CREATE TABLE review_findings (
    id                  TEXT    PRIMARY KEY,
    review_pass_id      TEXT    NOT NULL REFERENCES review_passes(id) ON DELETE CASCADE,
    -- File path the finding is anchored to. Null for whole-PR
    -- findings (architectural notes, missing-test summaries).
    path                TEXT,
    -- Line number the finding is anchored to. Null when path is
    -- whole-file or when the finding is whole-PR.
    line                INTEGER,
    -- blocker | major | nit | question
    severity            TEXT    NOT NULL,
    -- agreed | disputed | resolved | arbitrated | dropped | posted
    status              TEXT    NOT NULL,
    body                TEXT    NOT NULL,
    -- How a disputed finding was settled (human pick / consensus
    -- statement). Null until the finding goes from disputed →
    -- resolved/arbitrated.
    resolution          TEXT,
    -- GitHub comment id once the finding is posted to the PR.
    posted_comment_id   TEXT,
    created_at_ms       INTEGER NOT NULL
);

CREATE INDEX idx_review_findings_pass     ON review_findings(review_pass_id);
